package dev.bugobliterator.itermbuddy

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.ui.AppRoot
import dev.bugobliterator.itermbuddy.ui.PairingActions
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import dev.bugobliterator.itermbuddy.ui.home.HomeActions
import dev.bugobliterator.itermbuddy.ui.home.HomeModel
import dev.bugobliterator.itermbuddy.ui.live.LiveVideoSource
import dev.bugobliterator.itermbuddy.ui.scanFailureOutcome
import dev.bugobliterator.itermbuddy.ui.scanOutcome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val app: CompanionApp get() = application as CompanionApp

    private val actions = object : PairingActions {
        override fun scan() = startScan()

        override fun start() {
            app.clearNotices()
            app.client.start()
        }

        override fun cancel() {
            app.clearNotices()
            app.client.cancel()
        }

        override fun unpair() {
            app.clearNotices()
            app.unpair()
        }
    }

    /**
     * The home's calls: session open and Back go to the app-scoped navigator, which alone opens
     * and closes the stream, so this activity being recreated changes nothing.
     */
    private val homeActions = object : HomeActions {
        override fun openSession(session: SessionSummary, title: String) = app.sessions.navigator.openSession(session, title)

        override fun openSettings() = app.sessions.navigator.openSettings()

        override fun back() = app.sessions.navigator.back()

        override fun retry() = app.sessions.controller.retry()

        override fun fitToPhone(sessionGuid: String, viewportWidthPx: Int, viewportHeightPx: Int) =
            app.fitToPhone(sessionGuid, viewportWidthPx, viewportHeightPx)

        override fun setVideoStatsShown(shown: Boolean) = app.setVideoStatsShown(shown)

        override fun key(target: InputTarget, event: KeyEvent) = app.input.key(target, event)

        override fun paste(target: InputTarget, text: String) = app.input.paste(target, text)

        override fun pasteThenReturn(target: InputTarget, text: String) = app.input.pasteThenReturn(target, text)

        override fun composerDraft(sessionGuid: String): String = app.drafts.get(sessionGuid)

        override fun setComposerDraft(sessionGuid: String, draft: String) = app.drafts.set(sessionGuid, draft)

        override fun refresh() = app.sessions.repository.refresh()

        override fun reconnect() {
            app.clearNotices()
            app.client.start()
        }

        override fun unpair() {
            app.clearNotices()
            app.unpair()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started on the first launch of the UI in this process; a recreated activity finds it running.
        app.startOnce()
        enableEdgeToEdge()
        val version = appVersion()
        val sessions = app.sessions
        val video = LiveVideoSource(app.video, sessions.controller.state)
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // The live view reads the stream's session and config; liveTop moves with the output, so
        // it is dropped here rather than recompose the whole home on every scroll.
        val activeForView = sessions.controller.activeStream.map { it?.copy(liveTop = null) }.distinctUntilChanged()
        // Read now, so a recreated activity's first frame has the stream (not null, which would
        // drop the config and the Fit menu for a frame).
        val activeNow = sessions.controller.activeStream.value?.copy(liveTop = null)
        setContent {
            val state by app.client.state.collectAsStateWithLifecycle()
            val paired by app.client.paired.collectAsStateWithLifecycle()
            val notice by app.scanNotice.collectAsStateWithLifecycle()
            val unpairing by app.unpairing.collectAsStateWithLifecycle()
            val unpairFailed by app.unpairFailed.collectAsStateWithLifecycle()
            val destination by sessions.navigator.destination.collectAsStateWithLifecycle()
            val tree by sessions.repository.state.collectAsStateWithLifecycle()
            val live by sessions.controller.state.collectAsStateWithLifecycle()
            val relayOrigin by app.client.relayOrigin.collectAsStateWithLifecycle()
            val activeStream by activeForView.collectAsStateWithLifecycle(initialValue = activeNow)
            val videoStatsShown by app.videoStatsShown.collectAsStateWithLifecycle()
            MaterialTheme {
                AppRoot(
                    paired = paired,
                    home = HomeModel(
                        state, destination, tree, live, unpairing, unpairFailed, version, relayOrigin,
                        activeStream, video, debugBuild, videoStatsShown,
                    ),
                    homeActions = homeActions,
                    pairingActions = actions,
                    scanNotice = notice,
                )
            }
        }
    }

    /**
     * Google's code scanner: Play services shows the camera UI, so this app holds no camera
     * permission. Results go to [CompanionApp], which outlives this activity.
     */
    private fun startScan() {
        val app = app
        app.clearNotices()
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            // The QR text is never logged: it carries the Mac's key and pairing ID.
            .addOnSuccessListener { barcode -> app.onScanOutcome(scanOutcome(barcode.rawValue)) }
            .addOnCanceledListener { app.onScanOutcome(ScanOutcome.Quiet) }
            .addOnFailureListener { error ->
                Log.i(TAG, "scan failed: code ${(error as? MlKitException)?.errorCode}")
                app.onScanOutcome(scanFailureOutcome(error))
            }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
