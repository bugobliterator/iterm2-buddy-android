package dev.bugobliterator.itermbuddy

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.store.KeystorePairingStore
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
 * It also owns the rest of the app-scoped sessions graph, built by [wireSessions] (the same
 * function the tests use): the [StreamController] (the client's stream sink, sending through the
 * client), the [HevcVideoOutput] it decodes into, the [SessionTreeRepository], and the
 * [SessionNavigator][dev.bugobliterator.itermbuddy.ui.SessionNavigator] that alone opens and
 * closes streams. Every unpair resets the navigator (stopping the stream) and clears the tree.
 * [ProcessLifecycleOwner] drives the controller: `ON_STOP` (the last activity stopped: lock,
 * home, app switch) pauses it and `ON_START` resumes it; it is debounced over configuration
 * changes, so rotation neither pauses nor resumes.
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

    /** The sessions graph; see the class comment. */
    lateinit var sessions: SessionsGraph
        private set

    /** The live view's decoder output; process-lifetime, never closed (see the class comment). */
    lateinit var video: HevcVideoOutput
        private set

    val client: CompanionClient get() = sessions.client

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

    override fun onCreate() {
        super.onCreate()
        video = HevcVideoOutput(MediaCodecCatalog())
        sessions = wireSessions(
            scope,
            newClient = { sink -> CompanionClient(KeystorePairingStore(this), OkHttpClient(), scope, streamSink = sink) },
            newController = { link ->
                StreamController(link, video, scope, nowMillis = SystemClock::uptimeMillis, log = { Log.i(STREAM_TAG, it) })
            },
            newRepository = { client -> SessionTreeRepository(client, scope) },
            log = { Log.w(TAG, it) },
        )
        // Application.onCreate runs on the main thread, as addObserver requires.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = sessions.controller.resume()

                override fun onStop(owner: LifecycleOwner) = sessions.controller.pause()
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
        val UNPAIR_FAILED_SHOWN = 5.seconds
    }
}
