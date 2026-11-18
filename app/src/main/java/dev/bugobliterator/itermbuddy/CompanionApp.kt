package dev.bugobliterator.itermbuddy

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.FileChatSeenStore
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTrace
import dev.bugobliterator.itermbuddy.core.input.isInputTarget
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.fitToPhone
import dev.bugobliterator.itermbuddy.store.KeystorePairingStore
import dev.bugobliterator.itermbuddy.ui.chats.BitmapImageDecoder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
 * [SessionNavigator][dev.bugobliterator.itermbuddy.ui.SessionNavigator] that alone opens and
 * closes streams. Every unpair resets the navigator (stopping the stream) and clears the tree and
 * the chats. [ProcessLifecycleOwner] drives the controller and the chats: `ON_STOP` (the last
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
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in app scope", e) },
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

    /** Selection gestures, Select All, Clear and Copy for the live session; see the class comment. */
    lateinit var selection: SelectionDriver
        private set

    /** Taps, scrolls and the browser controls for a browser session; see the class comment. */
    lateinit var browser: BrowserDriver
        private set

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
            Log.i(STREAM_TAG, "fit to phone for $sessionGuid skipped: not the active stream")
            return
        }
        scope.launch {
            try {
                val grid = fitToPhone(client, stream, viewportWidthPx, viewportHeightPx, video.maxSize(), video::sizeSupported)
                Log.i(STREAM_TAG, "fit to phone for $sessionGuid: ${grid?.let { "${it.columns}x${it.rows}" } ?: "no grid"}")
            } catch (e: CompanionRequestException) {
                Log.w(STREAM_TAG, "fit to phone for $sessionGuid failed: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The opt-in input trace: off unless `adb shell setprop log.tag.IB.Input VERBOSE`.
        InputTrace.current = InputTrace(
            gate = { Log.isLoggable(InputTrace.TAG, Log.VERBOSE) },
            nowMillis = SystemClock::uptimeMillis,
            sink = { Log.v(InputTrace.TAG, it) },
        )
        video = HevcVideoOutput(MediaCodecCatalog())
        home = wireHome(
            scope,
            newClient = { sink, chatSink ->
                CompanionClient(KeystorePairingStore(this), OkHttpClient(), scope, streamSink = sink, chatSink = chatSink)
            },
            newController = { link ->
                StreamController(link, video, scope, nowMillis = SystemClock::uptimeMillis, log = { Log.i(STREAM_TAG, it) })
            },
            newRepository = { client -> SessionTreeRepository(client, scope) },
            newChats = { link ->
                // The chat consumer reads and writes the seen marks: on the app scope's Default
                // dispatcher, never the main thread.
                ChatRepository(
                    link,
                    scope,
                    FileChatSeenStore.inNoBackupDir(this) { Log.w(CHAT_TAG, it) },
                    nowMillis = SystemClock::uptimeMillis,
                    log = { Log.i(CHAT_TAG, it) },
                )
            },
            iconDecoder = BitmapImageDecoder(),
            log = { Log.w(TAG, it) },
            chatLog = { Log.i(CHAT_TAG, it) },
        )
        input = InputSender(
            link = home.client,
            scope = scope,
            isCurrent = { target -> isInputTarget(home.controller.activeStream.value, target) },
            log = { Log.i(INPUT_TAG, it) },
        )
        selection = SelectionDriver(
            link = home.client,
            active = home.controller.activeStream,
            selection = home.controller.selection,
            scope = scope,
            log = { Log.i(SELECTION_TAG, it) },
        )
        browser = BrowserDriver(
            input = input,
            active = home.controller.activeStream,
            editing = home.controller.browserEditing,
            navigation = home.controller.browserNavigation,
            macRevision = home.client.state.map(::macRevisionOf).stateIn(scope, SharingStarted.Eagerly, macRevisionOf(home.client.state.value)),
            scope = scope,
            nowMillis = SystemClock::uptimeMillis,
            log = { Log.i(BROWSER_TAG, it) },
        )
        // Application.onCreate runs on the main thread, as addObserver requires.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Back in the app: do not wait out a grown backoff (up to 30 s) to reconnect.
                    home.client.reconnectNow()
                    home.controller.resume()
                    home.chats.setForeground(true)
                }

                override fun onStop(owner: LifecycleOwner) {
                    home.controller.pause()
                    // Locked or backgrounded: no conversation is visible, so a turn ending now is Done.
                    home.chats.setForeground(false)
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
                Log.w(TAG, "unpair failed: $e")
                showUnpairFailed()
            } finally {
                unpairingFlow.value = false
            }
        }
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
        val UNPAIR_FAILED_SHOWN = 5.seconds
    }
}
