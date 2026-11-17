package dev.bugobliterator.itermbuddy.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.sessions.TreeState
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import dev.bugobliterator.itermbuddy.ui.bannerOffersReconnect
import dev.bugobliterator.itermbuddy.ui.connectionBanner
import dev.bugobliterator.itermbuddy.ui.connectionLabel
import dev.bugobliterator.itermbuddy.ui.keepsScreenOn
import dev.bugobliterator.itermbuddy.ui.sessions.SessionListScreen
import dev.bugobliterator.itermbuddy.ui.settings.SettingsScreen

/** What the home can ask for. Session open and Back go to the app-scoped navigator, never composition. */
interface HomeActions {
    fun openSession(session: dev.bugobliterator.itermbuddy.core.sessions.SessionSummary, title: String)

    fun openSettings()

    fun back()

    fun refresh()

    fun reconnect()

    fun unpair()
}

/** Everything the home shows, collected by the activity from app-scoped flows. */
data class HomeModel(
    val state: ClientState,
    val destination: HomeDestination,
    val tree: TreeState,
    val live: LiveViewState,
    val unpairing: Boolean,
    val unpairFailed: Boolean,
    val appVersion: String,
    val relayOrigin: String?,
)

/**
 * The paired home: a [Scaffold] for [HomeTab][dev.bugobliterator.itermbuddy.ui.HomeTab]
 * `SESSIONS` (no bottom bar while it is the only tab), rendering [HomeModel.destination].
 * System back on any destination but the list calls [HomeActions.back]. The screen is kept on
 * only while a session view shows ([keepsScreenOn]); that flag is the only thing composition
 * changes, and it never touches the stream.
 */
@Composable
fun HomeScaffold(model: HomeModel, actions: HomeActions, modifier: Modifier = Modifier) {
    val destination = model.destination
    BackHandler(enabled = destination != HomeDestination.SessionList) { actions.back() }
    val view = LocalView.current
    val keepOn = keepsScreenOn(destination)
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    when (destination) {
        HomeDestination.SessionList -> SessionsHome(model, actions, modifier)
        HomeDestination.Settings -> SettingsScreen(
            state = model.state,
            unpairing = model.unpairing,
            unpairFailed = model.unpairFailed,
            appVersion = model.appVersion,
            relayOrigin = model.relayOrigin,
            onBack = actions::back,
            onUnpair = actions::unpair,
            modifier = modifier,
        )
        is HomeDestination.LiveSession -> SessionPlaceholder(destination.title, model.live, actions, modifier)
        is HomeDestination.BrowserSession -> SessionPlaceholder(destination.title, model.live, actions, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsHome(model: HomeModel, actions: HomeActions, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                actions = {
                    Text(
                        text = "● ${connectionLabel(model.state)}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "More options" },
                        ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    menuOpen = false
                                    actions.openSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            connectionBanner(model.state)?.let { text ->
                ConnectionBannerRow(text, bannerOffersReconnect(model.state), actions::reconnect)
            }
            SessionListScreen(
                state = model.tree,
                onOpen = actions::openSession,
                onRefresh = actions::refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ConnectionBannerRow(text: String, reconnect: Boolean, onReconnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (reconnect) TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

/**
 * Stands in for the live view until the Live View task: the session's name, the stream's state
 * and Back. Back is the navigator's [HomeActions.back], which closes the stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPlaceholder(title: String, live: LiveViewState, actions: HomeActions, modifier: Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = actions::back,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = liveStatusText(live),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The placeholder's one line for the stream's state. */
internal fun liveStatusText(live: LiveViewState): String = when (live) {
    LiveViewState.Closed -> "Not watching."
    is LiveViewState.Starting -> "Starting…"
    is LiveViewState.Live -> if (live.stalled) "Picture stalled" else "Live (${live.config.columns}×${live.config.rows})"
    is LiveViewState.Reconnecting -> "Reconnecting…"
    is LiveViewState.Paused -> "Paused"
    is LiveViewState.Ended -> "Stream ended."
    is LiveViewState.Failed -> "Couldn't show this session."
}
