package dev.bugobliterator.itermbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.ui.UNPAIR_FAILED
import dev.bugobliterator.itermbuddy.ui.connectionBanner

internal const val UNPAIR_CONFIRM = "Unpair this phone? You'll need to scan a new code on the Mac."

/** The Mac row: what the pairing screen used to show once connected, with the Mac's revision. */
internal fun macStatusText(state: ClientState): String = when (state) {
    is ClientState.Connected -> "Connected to iTerm2 (revision ${state.macRevision})"
    else -> connectionBanner(state) ?: "Not connected."
}

/**
 * Settings, reached from the home's overflow menu: the connection, Unpair (confirmed first) and
 * the app version. The pairing screen's Connected content (status and Unpair) lives here now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ClientState,
    unpairing: Boolean,
    unpairFailed: Boolean,
    appVersion: String,
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
            if (unpairing) {
                Text("Unpairing…", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                if (unpairFailed) Text(UNPAIR_FAILED, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { confirming = true }) { Text("Unpair this phone") }
            }
            Entry("App version", appVersion)
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
