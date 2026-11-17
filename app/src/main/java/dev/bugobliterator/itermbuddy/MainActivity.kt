package dev.bugobliterator.itermbuddy

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
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.ui.AppRoot
import dev.bugobliterator.itermbuddy.ui.PairingActions
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import dev.bugobliterator.itermbuddy.ui.home.HomeActions
import dev.bugobliterator.itermbuddy.ui.home.HomeModel
import dev.bugobliterator.itermbuddy.ui.scanFailureOutcome
import dev.bugobliterator.itermbuddy.ui.scanOutcome

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
            MaterialTheme {
                AppRoot(
                    paired = paired,
                    home = HomeModel(state, destination, tree, live, unpairing, unpairFailed, version, relayOrigin),
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
