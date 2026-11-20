package dev.bugobliterator.itermbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.applog.KEY_TRACE_CLEAR_NOW
import dev.bugobliterator.itermbuddy.applog.KEY_TRACE_KEEP
import dev.bugobliterator.itermbuddy.applog.KEY_TRACE_OFF_PROMPT
import dev.bugobliterator.itermbuddy.applog.KEY_TRACE_SUMMARY
import dev.bugobliterator.itermbuddy.applog.KEY_TRACE_TITLE
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.input.PASTE_KEYS_SUMMARY
import dev.bugobliterator.itermbuddy.core.input.PASTE_KEYS_TITLE
import dev.bugobliterator.itermbuddy.core.input.SCROLL_KEYS_SUMMARY
import dev.bugobliterator.itermbuddy.core.input.SCROLL_KEYS_TITLE
import dev.bugobliterator.itermbuddy.ui.UNPAIR_FAILED
import dev.bugobliterator.itermbuddy.ui.connectionBanner
import dev.bugobliterator.itermbuddy.video.VideoStats

internal const val UNPAIR_CONFIRM = "Unpair this phone? You'll need to scan a new code on the Mac."

internal const val CLEAR_LOG_CONFIRM = "Clear the app's log? Problems that already happened will no longer be in a shared log."

/** The Mac row: what the pairing screen used to show once connected, with the Mac's revision. */
internal fun macStatusText(state: ClientState): String = when (state) {
    is ClientState.Connected -> "Connected to iTerm2 (revision ${state.macRevision})"
    else -> connectionBanner(state) ?: "Not connected."
}

/**
 * The Decoder row: the HEVC decoder in use, else the last one used, from the live view's
 * [VideoStats]. A codec name only, never content.
 */
internal fun decoderRowText(stats: VideoStats): String = stats.decoderName ?: stats.lastDecoderName ?: "Not used yet"

/** Debug builds: the Settings switch for [dev.bugobliterator.itermbuddy.video.HevcVideoOutput.setLowLatencyKeys]. */
internal const val DECODER_LOW_LATENCY_TITLE = "Decoder low-latency keys"

internal const val DECODER_LOW_LATENCY_SUMMARY =
    "Ask the decoder for low latency with every key known for it. Off rebuilds it with the earlier format, " +
        "to compare the video stats' decoder latency."

/**
 * Settings, reached from the home's overflow menu: the connection, the relay origin, Unpair
 * (confirmed first), the app version and the HEVC decoder ([decoder], [decoderRowText]). The
 * pairing screen's Connected content (status and Unpair) lives here now. The "Send / * + = as
 * paste" switch ([pasteKeys], [PASTE_KEYS_TITLE]) sets how the live view sends those four keys;
 * "Scroll web pages with keys" ([scrollKeys], [SCROLL_KEYS_TITLE]) how a browser page scrolls.
 * Debug builds ([debugBuild]) also get the "Video stats" toggle for the live view's stats overlay
 * and "Decoder low-latency keys" ([decoderLowLatency], [DECODER_LOW_LATENCY_TITLE]): off rebuilds
 * the decoder with the earlier minimal format, to compare the overlay's decoder latency.
 * Diagnostics: Share log ([onShareLog], Android's share sheet), Clear log ([onClearLog],
 * confirmed first) and the "Include typed-key trace" switch ([keyTrace], [KEY_TRACE_TITLE]);
 * turning it off offers to clear the log, which keeps the traced keys until cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ClientState,
    unpairing: Boolean,
    unpairFailed: Boolean,
    appVersion: String,
    relayOrigin: String?,
    decoder: String,
    debugBuild: Boolean,
    videoStatsShown: Boolean,
    onVideoStatsShownChange: (Boolean) -> Unit,
    pasteKeys: Boolean,
    onPasteKeysChange: (Boolean) -> Unit,
    scrollKeys: Boolean,
    onScrollKeysChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    keyTrace: Boolean = false,
    onKeyTraceChange: (Boolean) -> Unit = {},
    onShareLog: () -> Unit = {},
    onClearLog: () -> Unit = {},
    decoderLowLatency: Boolean = true,
    onDecoderLowLatencyChange: (Boolean) -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    var offeringTraceClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Entry("Mac", macStatusText(state))
            Entry("Relay", relayOrigin ?: "—")
            if (unpairing) {
                Text("Unpairing…", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                if (unpairFailed) Text(UNPAIR_FAILED, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { confirming = true }) { Text("Unpair this phone") }
            }
            Entry("App version", appVersion)
            Entry("Decoder", decoder)
            SwitchRow(PASTE_KEYS_TITLE, PASTE_KEYS_SUMMARY, pasteKeys, onPasteKeysChange)
            SwitchRow(SCROLL_KEYS_TITLE, SCROLL_KEYS_SUMMARY, scrollKeys, onScrollKeysChange)
            if (debugBuild) {
                SwitchRow("Video stats", summary = null, videoStatsShown, onVideoStatsShownChange)
                SwitchRow(DECODER_LOW_LATENCY_TITLE, DECODER_LOW_LATENCY_SUMMARY, decoderLowLatency, onDecoderLowLatencyChange)
            }
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onShareLog) { Text("Share log") }
                OutlinedButton(onClick = { confirmingClear = true }) { Text("Clear log") }
            }
            SwitchRow(KEY_TRACE_TITLE, KEY_TRACE_SUMMARY, keyTrace) { on ->
                onKeyTraceChange(on)
                if (!on) offeringTraceClear = true
            }
        }
    }
    if (offeringTraceClear) {
        AlertDialog(
            onDismissRequest = { offeringTraceClear = false },
            text = { Text(KEY_TRACE_OFF_PROMPT) },
            confirmButton = {
                TextButton(onClick = {
                    offeringTraceClear = false
                    onClearLog()
                }) { Text(KEY_TRACE_CLEAR_NOW) }
            },
            dismissButton = { TextButton(onClick = { offeringTraceClear = false }) { Text(KEY_TRACE_KEEP) } },
        )
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            text = { Text(CLEAR_LOG_CONFIRM) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClearLog()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(UNPAIR_CONFIRM) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onUnpair()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** A whole-row switch: [title], an optional [summary] under it, and the switch. */
@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Entry(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.65f))
    }
}
