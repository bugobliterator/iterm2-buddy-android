package dev.bugobliterator.itermbuddy

import android.app.Application
import android.util.Log
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.store.KeystorePairingStore
import dev.bugobliterator.itermbuddy.store.PairingStoreUnavailableException
import dev.bugobliterator.itermbuddy.ui.ScanNotice
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the process's single [CompanionClient], so it outlives activity recreation (rotation,
 * dark mode) and keeps its connection across it. The client runs in an app-lifetime scope; the
 * Notifications plan moves its ownership to a foreground service.
 *
 * The client is built here but started by [startOnce], which [MainActivity] calls. Starting in
 * [onCreate] would also connect whenever the process starts without the UI, such as for
 * instrumented tests, which would then race the client for the pairing store.
 */
class CompanionApp : Application() {
    /** App-lifetime work: one failure never cancels the rest. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var client: CompanionClient
        private set

    private val started = AtomicBoolean(false)

    private val scanNoticeFlow = MutableStateFlow<ScanNotice?>(null)

    /** The latest scanner report, kept here so it survives activity recreation. */
    val scanNotice: StateFlow<ScanNotice?> = scanNoticeFlow.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        client = CompanionClient(KeystorePairingStore(this), OkHttpClient(), scope)
    }

    /** Calls [CompanionClient.start] once per process, however often the activity is recreated. */
    fun startOnce() {
        if (started.compareAndSet(false, true)) client.start()
    }

    fun clearScanNotice() {
        scanNoticeFlow.value = null
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

    /** [CompanionClient.unpair] in the app scope, so leaving the screen does not cut it short. */
    fun unpair() {
        scope.launch {
            try {
                client.unpair()
            } catch (e: PairingStoreUnavailableException) {
                // The client is Idle with the pairing still stored; the screen offers Unpair again.
                Log.w(TAG, "unpair could not clear the store: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "CompanionApp"
    }
}
