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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.ui.UNPAIR_FAILED
import dev.bugobliterator.itermbuddy.ui.connectionBanner
import dev.bugobliterator.itermbuddy.video.VideoStats

internal const val UNPAIR_CONFIRM = "Unpair this phone? You'll need to scan a new code on the Mac."

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

/**
 * Settings, reached from the home's overflow menu: the connection, the relay origin, Unpair
 * (confirmed first), the app version and the HEVC decoder ([decoder], [decoderRowText]). The
 * pairing screen's Connected content (status and Unpair) lives here now. Debug builds
 * ([debugBuild]) also get the "Video stats" toggle for the live view's stats overlay.
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
    onBack: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
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
            if (debugBuild) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = videoStatsShown,
                        role = Role.Switch,
                        onValueChange = onVideoStatsShownChange,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Video stats", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = videoStatsShown, onCheckedChange = null)
                }
            }
        }
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

@Composable
private fun Entry(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.65f))
    }
}
