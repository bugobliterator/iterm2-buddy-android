package dev.bugobliterator.itermbuddy

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bugobliterator.itermbuddy.applog.AndroidLogcat
import dev.bugobliterator.itermbuddy.applog.AppLog
import dev.bugobliterator.itermbuddy.applog.AppLogger
import dev.bugobliterator.itermbuddy.applog.KeyTraceSwitch
import dev.bugobliterator.itermbuddy.applog.LogFileSink
import dev.bugobliterator.itermbuddy.applog.LogHeader
import dev.bugobliterator.itermbuddy.applog.LogShare
import dev.bugobliterator.itermbuddy.applog.UncaughtLogging
import dev.bugobliterator.itermbuddy.applog.keyTraceGate
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.FileChatSeenStore
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTrace
import dev.bugobliterator.itermbuddy.core.input.SharedPrefsPasteKeysStore
import dev.bugobliterator.itermbuddy.core.input.SharedPrefsScrollKeysStore
import dev.bugobliterator.itermbuddy.core.input.isInputTarget
import dev.bugobliterator.itermbuddy.core.relay.relayHttpClient
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.LatencyMeter
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.sessions.SharedPrefsClaudeTipStore
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.fitToPhone
import dev.bugobliterator.itermbuddy.core.wire.ProtocolRevision
import dev.bugobliterator.itermbuddy.store.KeystorePairingStore
import dev.bugobliterator.itermbuddy.ui.chats.BitmapImageDecoder
import dev.bugobliterator.itermbuddy.ui.chats.iconDecodeSidePx
import dev.bugobliterator.itermbuddy.ui.live.ComposerDrafts
import dev.bugobliterator.itermbuddy.ui.live.LiveInputMemories
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import dev.bugobliterator.itermbuddy.video.MediaCodecCatalog
import dev.bugobliterator.itermbuddy.ui.ScanNotice
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the process's single [CompanionClient], so it outlives activity recreation (rotation,
 * dark mode) and keeps its connection across it. The client runs in an app-lifetime scope; the
 * Notifications plan moves its ownership to a foreground service.
 *
 * It also owns the rest of the app-scoped home graph, built by [wireHome] (the same function the
 * tests use): the [StreamController] (the client's stream sink, sending through the client), the
 * [HevcVideoOutput] it decodes into, the [SessionTreeRepository], the [ChatRepository] (the
 * client's chat sink, with its seen marks in [FileChatSeenStore]), and the
 * [HomeNavigator][dev.bugobliterator.itermbuddy.ui.HomeNavigator] (the Chats and Sessions tabs)
 * that alone opens and closes streams, conversations and workgroup watches. Every unpair resets
 * the navigator (stopping the stream) and clears the tree and the chats. [ProcessLifecycleOwner] drives the controller, the chats and the workgroup watches
 * ([HomeGraph.setForeground]): `ON_STOP` (the last
 * activity stopped: lock, home, app switch) pauses the controller and makes no conversation
 * visible, and `ON_START` resumes both and kicks a reconnect ([CompanionClient.reconnectNow]); it
 * is debounced over configuration changes, so rotation neither pauses nor resumes.
 *
 * [input] is the one ordered path for keys and pastes: app-scoped, so a key typed just before the
 * activity is recreated is still sent, and it sends only while its target is the controller's
 * active stream (same link, watch epoch and session; [isInputTarget]).
 *
 * [selection] drives the live view's terminal selection and copy on the controller's active
 * stream, app-scoped like [input] so a drag's end is still sent across activity recreation.
 *
 * [video] is never closed: it lives as long as the process. An Application has no reliable end
 * (`onTerminate` never runs on a device), and process death takes the codec and the decoder
 * thread with it; the decoder itself exists only while the live view attaches a surface.
 *
 * Logging: [onCreate] first installs [AppLog]'s file-backed logger (logcat as before, plus the
 * rolling log in `filesDir/logs`, [logFile]) and the uncaught-exception handler that logs a crash
 * before the platform's handler runs ([UncaughtLogging]). [keyTrace] is Settings' "Include
 * typed-key trace" (not persisted). [shareLogFile] and [clearLog] back Settings' Diagnostics.
 * Unpair leaves the log alone: it holds no secrets.
 *
 * The client is built here but started by [startOnce], which [MainActivity] calls. Starting in
 * [onCreate] would also connect whenever the process starts without the UI, such as for
 * instrumented tests, which would then race the client for the pairing store.
 */
class CompanionApp : Application() {
    /**
     * App-lifetime work: one failure never cancels the rest, and an exception nothing caught is
     * logged rather than crashing the process.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> AppLog.e(TAG, "uncaught in app scope", e) },
    )

    /** The home graph (sessions and chats); see the class comment. */
    lateinit var home: HomeGraph
        private set

    /** The live view's decoder output; process-lifetime, never closed (see the class comment). */
    lateinit var video: HevcVideoOutput
        private set

    val client: CompanionClient get() = home.client

    /** The composer's per-session drafts, in memory only; emptied on unpair by the graph. */
    val drafts: ComposerDrafts get() = home.drafts

    /** Each session's sticky modifiers, tray and keyboard-restore flag; emptied on unpair by the graph. */
    val inputMemories: LiveInputMemories get() = home.inputMemories

    /** Keys and pastes for the live session; see the class comment. */
    lateinit var input: InputSender
        private set

    /**
     * The debug overlay's latency numbers: the stream controller and the input sender report to it,
     * and it records only while the overlay is shown.
     */
    val latency = LatencyMeter()

    /** Selection gestures, Select All, Clear and Copy for the live session; see the class comment. */
    lateinit var selection: SelectionDriver
        private set

    /** Taps, scrolls and the browser controls for a browser session; see the class comment. */
    lateinit var browser: BrowserDriver
        private set

    /** The rolling on-device log (`filesDir/logs`); see [LogFileSink]. */
    lateinit var logFile: LogFileSink
        private set

    /** Settings' "Include typed-key trace": off at every process start; see [KeyTraceSwitch]. */
    val keyTrace = KeyTraceSwitch { on ->
        // Take effect at the next traced line, not within a second.
        InputTrace.current.recheck()
        AppLog.i(TAG, "typed-key trace turned ${if (on) "on" else "off"} in Settings")
    }

    private val started = AtomicBoolean(false)

    private val scanNoticeFlow = MutableStateFlow<ScanNotice?>(null)

    /** The latest scanner report, kept here so it survives activity recreation. */
    val scanNotice: StateFlow<ScanNotice?> = scanNoticeFlow.asStateFlow()

    private val unpairingFlow = MutableStateFlow(false)

    /** True while an [unpair] runs. */
    val unpairing: StateFlow<Boolean> = unpairingFlow.asStateFlow()

    private val unpairFailedFlow = MutableStateFlow(false)

    /** True for a few seconds after an [unpair] failed, or until the next button press. */
    val unpairFailed: StateFlow<Boolean> = unpairFailedFlow.asStateFlow()

    private var unpairFailedTimer: Job? = null

    private val videoStatsShownFlow = MutableStateFlow(false)

    /** Debug builds: whether the live view shows the decoder's stats overlay (Settings toggles it; not persisted). */
    val videoStatsShown: StateFlow<Boolean> = videoStatsShownFlow.asStateFlow()

    fun setVideoStatsShown(shown: Boolean) {
        videoStatsShownFlow.value = shown
    }

    private val decoderLowLatencyFlow = MutableStateFlow(true)

    /**
     * Debug builds: Settings' "Decoder low-latency keys" (on at every process start, not
     * persisted). Off configures the decoder with the earlier minimal format, for comparing the
     * overlay's decoder latency; release builds never turn it off.
     */
    val decoderLowLatency: StateFlow<Boolean> = decoderLowLatencyFlow.asStateFlow()

    fun setDecoderLowLatency(enabled: Boolean) {
        decoderLowLatencyFlow.value = enabled
        video.setLowLatencyKeys(enabled)
    }

    /**
     * Fit to phone for [sessionGuid], from an explicit tap only: sends `resizeSession` with
     * [fitGrid][dev.bugobliterator.itermbuddy.core.stream.fitGrid] on the active stream's link
     * ([fitToPhone]), in the app scope so leaving the view does not cut it short. Nothing is sent
     * unless the active stream is that session's; nothing ever undoes it (owner decision). Logs the
     * GUID and the grid only.
     */
    fun fitToPhone(sessionGuid: String, viewportWidthPx: Int, viewportHeightPx: Int) {
        val stream = home.controller.activeStream.value
        if (stream == null || stream.sessionGuid != sessionGuid) {
            AppLog.i(STREAM_TAG, "fit to phone for $sessionGuid skipped: not the active stream")
            return
        }
        scope.launch {
            try {
                val grid = fitToPhone(client, stream, viewportWidthPx, viewportHeightPx, video.maxSize(), video::sizeSupported)
                AppLog.i(STREAM_TAG, "fit to phone for $sessionGuid: ${grid?.let { "${it.columns}x${it.rows}" } ?: "no grid"}")
            } catch (e: CompanionRequestException) {
                AppLog.w(STREAM_TAG, "fit to phone for $sessionGuid failed: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // First, so everything after it reaches the log file too. The writer is one coroutine on IO.
        logFile = LogFileSink(File(filesDir, LOG_DIR))
        logFile.start(scope, Dispatchers.IO)
        AppLog.logger = AppLogger(AndroidLogcat, logFile, System::currentTimeMillis)
        UncaughtLogging.install()
        AppLog.i(TAG, "process started: app ${appVersionName()}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        // The opt-in input trace: off unless Settings' "Include typed-key trace" is on or
        // `adb shell setprop log.tag.IB.Input VERBOSE`.
        InputTrace.current = InputTrace(
            gate = keyTraceGate(switch = { keyTrace.enabled.value }, adbVerbose = { Log.isLoggable(InputTrace.TAG, Log.VERBOSE) }),
            nowMillis = SystemClock::uptimeMillis,
            sink = { AppLog.v(InputTrace.TAG, it) },
        )
        video = HevcVideoOutput(MediaCodecCatalog())
        home = wireHome(
            scope,
            newClient = { sink, chatSink ->
                CompanionClient(KeystorePairingStore(this), relayHttpClient(), scope, streamSink = sink, chatSink = chatSink)
            },
            newController = { link ->
                StreamController(link, video, scope, nowMillis = SystemClock::uptimeMillis, log = { AppLog.i(STREAM_TAG, it) }, latency = latency)
            },
            newRepository = { client -> SessionTreeRepository(client, scope) },
            newChats = { link ->
                // The chat consumer reads and writes the seen marks: on the app scope's Default
                // dispatcher, never the main thread.
                ChatRepository(
                    link,
                    scope,
                    // Written on IO: the chat consumer never waits on a sync.
                    FileChatSeenStore.inNoBackupDir(this, Dispatchers.IO.asExecutor()) { AppLog.w(CHAT_TAG, it) },
                    nowMillis = SystemClock::uptimeMillis,
                    log = { AppLog.i(CHAT_TAG, it) },
                )
            },
            // The row shows icons at 44 dp: decode them to that size in pixels, at most 128.
            iconDecoder = BitmapImageDecoder(iconDecodeSidePx(resources.displayMetrics.density)),
            // Inline images show at most the screen's width: decode them no larger.
            imageDecoder = BitmapImageDecoder(resources.displayMetrics.widthPixels),
            log = { AppLog.w(TAG, it) },
            chatLog = { AppLog.i(CHAT_TAG, it) },
            claudeTipStore = SharedPrefsClaudeTipStore(this),
            pasteKeysStore = SharedPrefsPasteKeysStore(this),
            scrollKeysStore = SharedPrefsScrollKeysStore(this),
        )
        input = InputSender(
            link = home.client,
            scope = scope,
            isCurrent = { target -> isInputTarget(home.controller.activeStream.value, target) },
            log = { AppLog.i(INPUT_TAG, it) },
            // Read as each key is sent: the Settings switch "Send / * + = as paste".
            pasteKeys = { home.pasteKeys.enabled.value },
            latency = latency,
        )
        selection = SelectionDriver(
            link = home.client,
            active = home.controller.activeStream,
            selection = home.controller.selection,
            scope = scope,
            log = { AppLog.i(SELECTION_TAG, it) },
        )
        browser = BrowserDriver(
            input = input,
            active = home.controller.activeStream,
            editing = home.controller.browserEditing,
            navigation = home.controller.browserNavigation,
            macRevision = home.client.state.map(::macRevisionOf).stateIn(scope, SharingStarted.Eagerly, macRevisionOf(home.client.state.value)),
            scope = scope,
            nowMillis = SystemClock::uptimeMillis,
            // Read at each flush: Settings' "Scroll web pages with keys".
            scrollKeys = { home.scrollKeys.enabled.value },
            log = { AppLog.i(BROWSER_TAG, it) },
        )
        // Application.onCreate runs on the main thread, as addObserver requires.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Back in the app: do not wait out a grown backoff (up to 30 s) to reconnect.
                    home.client.reconnectNow()
                    home.controller.resume()
                    home.setForeground(true)
                }

                override fun onStop(owner: LifecycleOwner) {
                    home.controller.pause()
                    // Locked or backgrounded: no conversation is visible (a turn ending now is
                    // Done) and the workgroup watches stop polling.
                    home.setForeground(false)
                    AppLog.flush()
                }
            },
        )
    }

    /** Calls [CompanionClient.start] once per process, however often the activity is recreated. */
    fun startOnce() {
        if (started.compareAndSet(false, true)) client.start()
    }

    /** Clears the transient notices; every button press calls this first. */
    fun clearNotices() {
        scanNoticeFlow.value = null
        unpairFailedFlow.value = false
    }

    /** Acts on a finished scan: pairs, shows a notice, or does nothing. Never logs the QR text. */
    fun onScanOutcome(outcome: ScanOutcome) {
        when (outcome) {
            is ScanOutcome.Scanned -> {
                scanNoticeFlow.value = null
                client.pair(outcome.code)
            }
            is ScanOutcome.Notice -> scanNoticeFlow.value = outcome.notice
            ScanOutcome.Quiet -> scanNoticeFlow.value = null
        }
    }

    /**
     * [CompanionClient.unpair] in the app scope, so leaving the screen does not cut it short.
     * Undispatched, so the client's control chain has the unpair queued before this returns and a
     * button pressed straight after runs after it.
     */
    fun unpair() {
        unpairingFlow.value = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                client.unpair()
            } catch (e: CancellationException) {
                // Superseded by a later pair() or start(), or the scope is going away.
                throw e
            } catch (e: Exception) {
                // An IOException leaves the client Idle with the pairing still stored.
                AppLog.w(TAG, "unpair failed: $e")
                showUnpairFailed()
            } finally {
                unpairingFlow.value = false
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.flush()
    }

    /**
     * Settings › Share log: writes the header ([logHeader]) and the log to a file in the cache's
     * [LogShare.SHARE_DIR] and returns it, for the activity to share through the FileProvider.
     * Disk I/O: call it off the main thread.
     */
    fun shareLogFile(): File {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return LogShare.assemble(logFile, logHeader(now, zone), cacheDir, now, zone)
    }

    /** Settings › Clear log (confirmed): deletes the log, off the main thread. */
    fun clearLog() {
        scope.launch(Dispatchers.IO) {
            logFile.clear()
            AppLog.i(TAG, "log cleared in Settings")
        }
    }

    /** The share's header: versions, device, revisions, whether paired, and the switches. Nothing secret. */
    private fun logHeader(nowMillis: Long, zone: ZoneId): LogHeader {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return LogHeader(
            createdAt = LogShare.headerTime(nowMillis, zone),
            appVersionName = info?.versionName ?: "?",
            appVersionCode = info?.longVersionCode ?: -1L,
            debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            protocolRevision = ProtocolRevision.REVISION,
            minimumPeer = ProtocolRevision.MINIMUM_PEER,
            macRevision = (client.state.value as? ClientState.Connected)?.macRevision,
            paired = client.paired.value,
            pasteKeys = home.pasteKeys.enabled.value,
            scrollKeys = home.scrollKeys.enabled.value,
            keyTrace = keyTrace.enabled.value,
        )
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun showUnpairFailed() {
        unpairFailedFlow.value = true
        unpairFailedTimer?.cancel()
        unpairFailedTimer = scope.launch {
            delay(UNPAIR_FAILED_SHOWN)
            unpairFailedFlow.value = false
        }
    }

    private companion object {
        const val TAG = "CompanionApp"
        const val STREAM_TAG = "StreamController"
        const val CHAT_TAG = "ChatRepository"
        const val INPUT_TAG = "InputSender"
        const val SELECTION_TAG = "SelectionDriver"
        const val BROWSER_TAG = "BrowserDriver"
        const val LOG_DIR = "logs"
        val UNPAIR_FAILED_SHOWN = 5.seconds
    }
}
