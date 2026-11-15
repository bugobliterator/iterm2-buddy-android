package dev.bugobliterator.itermbuddy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.bugobliterator.itermbuddy.ui.PairingActions
import dev.bugobliterator.itermbuddy.ui.PairingScreen
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import dev.bugobliterator.itermbuddy.ui.scanFailureOutcome
import dev.bugobliterator.itermbuddy.ui.scanOutcome

class MainActivity : ComponentActivity() {
    private val app: CompanionApp get() = application as CompanionApp

    private val actions = object : PairingActions {
        override fun scan() = startScan()

        override fun start() = app.client.start()

        override fun cancel() = app.client.cancel()

        override fun unpair() = app.unpair()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started on the first launch of the UI in this process; a recreated activity finds it running.
        app.startOnce()
        enableEdgeToEdge()
        setContent {
            val state by app.client.state.collectAsState()
            val notice by app.scanNotice.collectAsState()
            MaterialTheme {
                PairingScreen(state, actions, scanNotice = notice)
            }
        }
    }

    /**
     * Google's code scanner: Play services shows the camera UI, so this app holds no camera
     * permission. Results go to [CompanionApp], which outlives this activity.
     */
    private fun startScan() {
        val app = app
        app.clearScanNotice()
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

    private companion object {
        const val TAG = "MainActivity"
    }
}
