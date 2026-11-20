package dev.bugobliterator.itermbuddy

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.bugobliterator.itermbuddy.applog.AppLog
import dev.bugobliterator.itermbuddy.applog.LogShare
import dev.bugobliterator.itermbuddy.applog.logSummary
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.FitViewport
import dev.bugobliterator.itermbuddy.core.stream.pingActiveLink
import dev.bugobliterator.itermbuddy.ui.AppRoot
import dev.bugobliterator.itermbuddy.ui.AppTheme
import dev.bugobliterator.itermbuddy.ui.HomeTab
import dev.bugobliterator.itermbuddy.ui.PairingActions
import dev.bugobliterator.itermbuddy.ui.ScanOutcome
import dev.bugobliterator.itermbuddy.ui.home.HomeActions
import dev.bugobliterator.itermbuddy.ui.home.HomeModel
import dev.bugobliterator.itermbuddy.ui.live.LiveVideoSource
import dev.bugobliterator.itermbuddy.ui.scanFailureOutcome
import dev.bugobliterator.itermbuddy.ui.scanOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * The home's calls: session open, tab selection and Back go to the app-scoped navigator, which
     * alone opens and closes streams and conversations, so this activity being recreated changes
     * nothing.
     */
    private val homeActions = object : HomeActions {
        override fun openSession(session: SessionSummary, title: String) = app.home.navigator.openSession(session, title)

        override fun selectTab(tab: HomeTab) = app.home.navigator.selectTab(tab)

        override fun openSettings() = app.home.navigator.openSettings()

        override fun back() = app.home.navigator.back()

        override fun retry() = app.home.controller.retry()

        override fun setFitToPhone(enabled: Boolean) = app.setFitToPhone(enabled)

        override fun fitNow(sessionGuid: String) = app.fitNow(sessionGuid)

        override fun fitViewport(viewport: FitViewport) = app.autoFit.setViewport(viewport)

        override fun clearFitViewport(sessionGuid: String) = app.autoFit.clearViewport(sessionGuid)

        override fun setVideoStatsShown(shown: Boolean) = app.setVideoStatsShown(shown)

        override fun key(target: InputTarget, event: KeyEvent) = app.input.key(target, event)

        override fun browserKey(target: InputTarget, event: KeyEvent) = app.input.key(target, event, pasteKeys = false)

        override fun paste(target: InputTarget, text: String) = app.input.paste(target, text)

        override fun typeThenReturn(target: InputTarget, text: String) = app.input.typeThenReturn(target, text)

        override fun scrollWheel(target: InputTarget, streamID: Long, up: Boolean, lines: Int) =
            app.input.scrollWheel(target, streamID, up, lines)

        override fun composerDraft(sessionGuid: String): String = app.drafts.get(sessionGuid)

        override fun setComposerDraft(sessionGuid: String, draft: String) = app.drafts.set(sessionGuid, draft)

        override fun inputMemory(sessionGuid: String) = app.inputMemories.of(sessionGuid)

        override fun openLink(url: String) = openInPhoneBrowser(url)

        override fun linkBrowserSession(): SessionSummary? = app.linkBrowserSession()

        override fun openLinkInBrowserSession(session: SessionSummary, url: String) = app.openLinkInBrowserSession(session, url)

        override fun setSearchQuery(query: String) = app.home.search.set(query)

        override fun refresh() = app.home.repository.refresh()

        override fun dismissClaudeTip() = app.home.claudeTip.dismiss()

        override fun setPasteKeys(enabled: Boolean) = app.home.pasteKeys.set(enabled)

        override fun setScrollKeys(enabled: Boolean) = app.home.scrollKeys.set(enabled)

        override fun shareLog() = shareAppLog()

        override fun clearLog() = app.clearLog()

        override fun setKeyTrace(enabled: Boolean) = app.keyTrace.set(enabled)

        override fun setDecoderLowLatency(enabled: Boolean) = app.setDecoderLowLatency(enabled)

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
        val sessions = app.home
        val video = LiveVideoSource(
            app.video,
            sessions.controller.state,
            sessions.controller.historyTiles,
            app.selection,
            app.browser,
            latency = app.latency,
            ping = { pingActiveLink(sessions.client, sessions.controller.activeStream.value) },
            fitNotices = app.autoFit.notices,
            links = app.links,
        )
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // The live view reads the stream's session and config; liveTop moves with the output, so
        // it is dropped here rather than recompose the whole home on every scroll.
        val activeForView = sessions.controller.activeStream.map { it?.copy(liveTop = null) }.distinctUntilChanged()
        // Read now, so a recreated activity's first frame has the stream (not null, which would
        // drop the config and the Fit menu for a frame).
        val activeNow = sessions.controller.activeStream.value?.copy(liveTop = null)
        // A Mac-local link dropped before it reached its Browser session: a brief notice.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.browserLinks.drops.collect { Toast.makeText(this@MainActivity, it.text, Toast.LENGTH_SHORT).show() }
            }
        }
        setContent {
            val state by app.client.state.collectAsStateWithLifecycle()
            val paired by app.client.paired.collectAsStateWithLifecycle()
            val notice by app.scanNotice.collectAsStateWithLifecycle()
            val unpairing by app.unpairing.collectAsStateWithLifecycle()
            val unpairFailed by app.unpairFailed.collectAsStateWithLifecycle()
            // Tab and destination are one value: they never disagree for a frame.
            val nav by sessions.navigator.state.collectAsStateWithLifecycle()
            val chatsAttention by sessions.chatsAttention.collectAsStateWithLifecycle()
            val tree by sessions.repository.state.collectAsStateWithLifecycle()
            val live by sessions.controller.state.collectAsStateWithLifecycle()
            val relayOrigin by app.client.relayOrigin.collectAsStateWithLifecycle()
            val activeStream by activeForView.collectAsStateWithLifecycle(initialValue = activeNow)
            val videoStatsShown by app.videoStatsShown.collectAsStateWithLifecycle()
            val query by sessions.search.query.collectAsStateWithLifecycle()
            val macStatus by app.client.macStatus.collectAsStateWithLifecycle()
            val claudeTip by sessions.claudeTip.shown.collectAsStateWithLifecycle()
            val pasteKeys by sessions.pasteKeys.enabled.collectAsStateWithLifecycle()
            val scrollKeys by sessions.scrollKeys.enabled.collectAsStateWithLifecycle()
            val fitToPhone by sessions.fitToPhone.enabled.collectAsStateWithLifecycle()
            val keyTrace by app.keyTrace.enabled.collectAsStateWithLifecycle()
            val decoderLowLatency by app.decoderLowLatency.collectAsStateWithLifecycle()
            AppTheme {
                AppRoot(
                    paired = paired,
                    home = HomeModel(
                        state, nav.destination, tree, live, unpairing, unpairFailed, version, relayOrigin,
                        activeStream, video, debugBuild, videoStatsShown, query, macStatus, nav.tab, chatsAttention,
                        nav.key, nav.stackKeys, claudeTip, pasteKeys, scrollKeys, keyTrace,
                        decoderLowLatency, fitToPhone,
                    ),
                    homeActions = homeActions,
                    chats = sessions.chatsHome,
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
                AppLog.i(TAG, "scan failed: code ${(error as? MlKitException)?.errorCode}")
                app.onScanOutcome(scanFailureOutcome(error))
            }
    }

    /**
     * Settings › Share log: the app writes the share file off the main thread, then Android's
     * share sheet offers it as `text/plain` through the FileProvider (`<applicationId>.logs`,
     * serving only the cache's [LogShare.SHARE_DIR]), with read access granted to the chosen app.
     * Any failure (the file not written whole, no provider) shows its message and shares nothing.
     */
    private fun shareAppLog() {
        val app = app
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { app.shareLogFile() }
                val uri = FileProvider.getUriForFile(this@MainActivity, packageName + LogShare.AUTHORITY_SUFFIX, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                AppLog.i(TAG, "sharing the log: ${file.length()} bytes")
                startActivity(Intent.createChooser(send, "Share log"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A share file that could not be written whole is never offered.
                AppLog.w(TAG, "could not share the log: ${logSummary(e)}")
                Toast.makeText(this@MainActivity, "Could not share the log: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * A tapped terminal link in the phone's browser (`ACTION_VIEW`, a browsable app). With no app
     * that can open it, a brief notice; the URL is never logged.
     */
    private fun openInPhoneBrowser(url: String) {
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            AppLog.i(TAG, "no app opens a link of ${url.length} chars (${logSummary(e)})")
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
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
