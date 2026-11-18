package dev.bugobliterator.itermbuddy.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.TreeState
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.wire.MacStatus
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import dev.bugobliterator.itermbuddy.ui.bannerOffersReconnect
import dev.bugobliterator.itermbuddy.ui.connectionBanner
import dev.bugobliterator.itermbuddy.ui.connectionLabel
import dev.bugobliterator.itermbuddy.ui.keepsScreenOn
import dev.bugobliterator.itermbuddy.ui.live.LiveSessionActions
import dev.bugobliterator.itermbuddy.ui.live.LiveSessionScreen
import dev.bugobliterator.itermbuddy.ui.live.LiveVideoSource
import dev.bugobliterator.itermbuddy.ui.live.NoPersonalizedLearning
import dev.bugobliterator.itermbuddy.ui.sessions.SessionListScreen
import dev.bugobliterator.itermbuddy.ui.settings.SettingsScreen
import dev.bugobliterator.itermbuddy.ui.settings.decoderRowText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the home can ask for. Session open and Back go to the app-scoped navigator, never
 * composition; the live view's calls are [LiveSessionActions].
 */
interface HomeActions : LiveSessionActions {
    fun openSession(session: SessionSummary, title: String)

    fun openSettings()

    fun refresh()

    fun reconnect()

    fun unpair()

    /** The session list's search; app-scoped, so opening a session and coming back keeps it. */
    fun setSearchQuery(query: String)
}

/**
 * Everything the home shows, collected by the activity from app-scoped flows. [video] is read by
 * the live view and Settings themselves (its stats change with every frame); [activeStream] is
 * the controller's adopted stream (its config stays for Fit while a window is too large).
 */
data class HomeModel(
    val state: ClientState,
    val destination: HomeDestination,
    val tree: TreeState,
    val live: LiveViewState,
    val unpairing: Boolean,
    val unpairFailed: Boolean,
    val appVersion: String,
    val relayOrigin: String?,
    val activeStream: ActiveStream?,
    val video: LiveVideoSource,
    val debugBuild: Boolean,
    val videoStatsShown: Boolean,
    val query: String = "",
    val macStatus: MacStatus? = null,
)

/**
 * The Mac alert banner's text for [status] (`CompanionClient.macStatus`): shown on Sessions and
 * over the live view while the Mac reports its main thread blocked or any alert showing; null
 * otherwise, and while there is no status (not connected, or a Mac older than revision 14). This
 * app does not answer alerts: the owner answers at the Mac.
 */
fun macAlertBanner(status: MacStatus?): String? =
    if (status?.showsAlert == true) "iTerm2 is showing an alert on the Mac." else null

/**
 * The paired home: a [Scaffold] for [HomeTab][dev.bugobliterator.itermbuddy.ui.HomeTab]
 * `SESSIONS` (no bottom bar while it is the only tab), rendering [HomeModel.destination].
 * System back on any destination but the list calls [HomeActions.back]. The screen is kept on
 * only while a session view shows ([keepsScreenOn]), whatever the stream's state, and the flag is
 * cleared on leaving it. Session destinations render [LiveSessionScreen], which attaches and
 * detaches the decoder's surface; nothing in composition opens or closes a stream.
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
        HomeDestination.Settings -> {
            val source = model.video
            val decoder by remember(source) { source.output.stats.map(::decoderRowText).distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = source.decoderRowNow())
            SettingsScreen(
                state = model.state,
                unpairing = model.unpairing,
                unpairFailed = model.unpairFailed,
                appVersion = model.appVersion,
                relayOrigin = model.relayOrigin,
                decoder = decoder,
                debugBuild = model.debugBuild,
                videoStatsShown = model.videoStatsShown,
                onVideoStatsShownChange = actions::setVideoStatsShown,
                onBack = actions::back,
                onUnpair = actions::unpair,
                modifier = modifier,
            )
        }
        is HomeDestination.LiveSession -> LiveSession(destination.session, destination.title, model, actions, modifier)
        is HomeDestination.BrowserSession -> LiveSession(destination.session, destination.title, model, actions, modifier)
    }
}

/**
 * The live view for [session]; a browser session shows its video with the browser note until
 * Browser View. The Mac alert banner ([macAlertBanner]) sits just below the live view's top bar.
 */
@Composable
private fun LiveSession(session: SessionSummary, title: String, model: HomeModel, actions: HomeActions, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        LiveSessionScreen(
            title = title,
            session = session,
            live = model.live,
            activeStream = model.activeStream,
            macRevision = (model.state as? ClientState.Connected)?.macRevision,
            source = model.video,
            debugBuild = model.debugBuild,
            videoStatsShown = model.videoStatsShown,
            actions = actions,
        )
        macAlertBanner(model.macStatus)?.let { text ->
            MacAlertBannerRow(
                text = text,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = LIVE_TOP_BAR_HEIGHT),
            )
        }
    }
}

/** The live view's top bar row height (`LiveSessionScreen`'s `LiveTopBar`), which the banner sits below. */
private val LIVE_TOP_BAR_HEIGHT = 48.dp

@Composable
private fun MacAlertBannerRow(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsHome(model: HomeModel, actions: HomeActions, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    // The query is app-scoped (HomeModel.query); only the field's focus is local.
    val query = model.query
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Back while searching clears the search first; the next Back does what it always did.
    BackHandler(enabled = query.isNotEmpty() || searchFocused) {
        actions.setSearchQuery("")
        focusManager.clearFocus()
    }
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
        // The IME padding keeps the list's last rows above the keyboard while searching.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            connectionBanner(model.state)?.let { text ->
                ConnectionBannerRow(text, bannerOffersReconnect(model.state), actions::reconnect)
            }
            macAlertBanner(model.macStatus)?.let { text -> MacAlertBannerRow(text) }
            SessionSearchField(
                query = query,
                onQueryChange = actions::setSearchQuery,
                onFocusChange = { searchFocused = it },
                onDone = { focusManager.clearFocus() },
            )
            SessionListScreen(
                state = model.tree,
                onOpen = actions::openSession,
                onRefresh = actions::refresh,
                modifier = Modifier.fillMaxSize(),
                query = query,
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

/** The search field at the top of the session list, with ✕ to clear it; no autocorrect or learning. */
@Composable
private fun SessionSearchField(query: String, onQueryChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit, onDone: () -> Unit) = NoPersonalizedLearning {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onFocusChanged { onFocusChange(it.isFocused) },
        placeholder = { Text("Search sessions") },
        singleLine = true,
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.semantics { contentDescription = "Clear search" }) {
                    Text("✕")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
    )
}

