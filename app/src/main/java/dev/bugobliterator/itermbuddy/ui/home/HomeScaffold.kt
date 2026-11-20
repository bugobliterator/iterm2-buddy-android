package dev.bugobliterator.itermbuddy.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.bugobliterator.itermbuddy.ui.HomeTab
import dev.bugobliterator.itermbuddy.ui.bannerOffersReconnect
import dev.bugobliterator.itermbuddy.ui.browser.BrowserSessionScreen
import dev.bugobliterator.itermbuddy.ui.chats.ChatListScreen
import dev.bugobliterator.itermbuddy.ui.chats.NewChatScreen
import dev.bugobliterator.itermbuddy.ui.connectionBanner
import dev.bugobliterator.itermbuddy.ui.connectionLabel
import dev.bugobliterator.itermbuddy.ui.conversation.ChatComposer
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationBody
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationScreen
import dev.bugobliterator.itermbuddy.ui.keepsScreenOn
import dev.bugobliterator.itermbuddy.ui.live.LiveSessionActions
import dev.bugobliterator.itermbuddy.ui.live.LiveSessionScreen
import dev.bugobliterator.itermbuddy.ui.live.LiveVideoSource
import dev.bugobliterator.itermbuddy.ui.live.NoPersonalizedLearning
import dev.bugobliterator.itermbuddy.ui.sessions.ClaudeStateTipRow
import dev.bugobliterator.itermbuddy.ui.sessions.SessionListScreen
import dev.bugobliterator.itermbuddy.ui.settings.SettingsScreen
import dev.bugobliterator.itermbuddy.ui.settings.decoderRowText
import dev.bugobliterator.itermbuddy.ui.showsBottomBar
import dev.bugobliterator.itermbuddy.ui.workgroup.WorkgroupFallback
import dev.bugobliterator.itermbuddy.ui.workgroup.WorkgroupRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the home can ask for. Session open and Back go to the app-scoped navigator, never
 * composition; the live view's calls are [LiveSessionActions].
 */
interface HomeActions : LiveSessionActions {
    fun openSession(session: SessionSummary, title: String)

    /** The bottom bar: the owner selected [tab] ([dev.bugobliterator.itermbuddy.ui.HomeNavigator.selectTab]). */
    fun selectTab(tab: HomeTab)

    fun openSettings()

    fun refresh()

    fun reconnect()

    fun unpair()

    /** The session list's search; app-scoped, so opening a session and coming back keeps it. */
    fun setSearchQuery(query: String)

    /** The owner dismissed the Sessions list's `\(status)` tip ([HomeModel.claudeTip]). */
    fun dismissClaudeTip() {}

    /** The owner flipped Settings' "Send / * + = as paste" switch ([HomeModel.pasteKeys]). */
    fun setPasteKeys(enabled: Boolean) {}

    /** The owner flipped Settings' "Scroll web pages with keys" switch ([HomeModel.scrollKeys]). */
    fun setScrollKeys(enabled: Boolean) {}

    /** Settings › Share log: the log goes to Android's share sheet. */
    fun shareLog() {}

    /** Settings › Clear log, after the owner confirmed it. */
    fun clearLog() {}

    /** The owner flipped Settings' "Include typed-key trace" switch ([HomeModel.keyTrace]). */
    fun setKeyTrace(enabled: Boolean) {}
}

/**
 * Everything the home shows, collected by the activity from app-scoped flows. [video] is read by
 * the live view and Settings themselves (its stats change with every frame); [activeStream] is
 * the controller's adopted stream (its config stays for Fit while a window is too large).
 * [destination], [tab], [navKey] and [stackKeys] come from one
 * [NavState][dev.bugobliterator.itermbuddy.ui.NavState], so they always agree; [chatsAttention]
 * is the Chats item's dot; [claudeTip] shows the Sessions list's `\(status)` tip; [pasteKeys] is
 * Settings' "Send / * + = as paste" switch, [scrollKeys] its "Scroll web pages with keys" and
 * [keyTrace] its "Include typed-key trace". The chat
 * destinations collect their own flows from [ChatsHome].
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
    val tab: HomeTab = HomeTab.SESSIONS,
    val chatsAttention: Boolean = false,
    val navKey: String = "",
    val stackKeys: Set<String> = emptySet(),
    val claudeTip: Boolean = false,
    val pasteKeys: Boolean = true,
    val scrollKeys: Boolean = true,
    val keyTrace: Boolean = false,
)

/**
 * The Mac alert banner's text for [status] (`CompanionClient.macStatus`), shown on Sessions and
 * under the live view's top bar; null when the Mac reports neither, and while there is no status
 * (not connected, or a Mac older than revision 14). iOS's wording: with any alert showing, its
 * pill's "Your Mac needs a response" (`IOS/App/iTerm2CompanionApp.swift:286`); blocked with
 * none, its banner's text (`:296`). This app does not answer alerts: the owner answers at the Mac.
 */
fun macAlertBanner(status: MacStatus?): String? = when {
    status == null -> null
    status.alertCount > 0 -> "Your Mac needs a response"
    status.mainBlocked -> "Your Mac is busy or showing a dialog that can\u2019t be answered from here"
    else -> null
}

/**
 * The paired home: two tabs, Chats and Sessions (`IOS/App/iTerm2CompanionApp.swift:155-176`),
 * rendering [HomeModel.destination], the top of the selected tab's stack. The bottom bar (the
 * Chats item with a dot when a chat is unread or waiting) shows on the tab roots only
 * ([showsBottomBar]), and not while the keyboard is up. System back on any other destination
 * calls [HomeActions.back]. The screen is kept on only while a session view shows
 * ([keepsScreenOn]), whatever the stream's state, and the flag is cleared on leaving it. Session
 * destinations render [LiveSessionScreen] or [BrowserSessionScreen], which attach and detach the
 * decoder's surface; nothing in composition opens or closes a stream or a conversation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScaffold(model: HomeModel, actions: HomeActions, chats: ChatsHome, modifier: Modifier = Modifier) {
    val destination = model.destination
    val root = showsBottomBar(destination)
    BackHandler(enabled = !root) { actions.back() }
    val view = LocalView.current
    val keepOn = keepsScreenOn(destination)
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    // Each stacked destination keeps its saved UI state (scroll positions) while it is covered or
    // its tab is not shown, and loses it once popped.
    val saved = rememberSaveableStateHolder()
    // The keys stacked last time (saved too, so a recreated activity still forgets what was popped
    // before it); after each change, outside composition, the keys that left are dropped.
    var stackedBefore by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(model.stackKeys) {
        for (gone in stackedBefore) if (gone !in model.stackKeys) saved.removeState(gone)
        stackedBefore = model.stackKeys.toList()
    }
    val key = model.navKey
    if (!root) {
        saved.SaveableStateProvider(key) { HomeDestinationView(destination, model, actions, chats, modifier) }
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        // The bar below takes the navigation bar's inset; the root's own scaffold must not too.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            saved.SaveableStateProvider(key) { HomeDestinationView(destination, model, actions, chats, Modifier) }
        }
        if (!WindowInsets.isImeVisible) HomeBottomBar(model.tab, model.chatsAttention, actions::selectTab)
    }
}

@Composable
private fun HomeDestinationView(destination: HomeDestination, model: HomeModel, actions: HomeActions, chats: ChatsHome, modifier: Modifier) {
    when (destination) {
        HomeDestination.ChatList -> ChatsRoot(model, actions, chats, modifier)
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
                pasteKeys = model.pasteKeys,
                onPasteKeysChange = actions::setPasteKeys,
                scrollKeys = model.scrollKeys,
                onScrollKeysChange = actions::setScrollKeys,
                keyTrace = model.keyTrace,
                onKeyTraceChange = actions::setKeyTrace,
                onShareLog = actions::shareLog,
                onClearLog = actions::clearLog,
                onBack = actions::back,
                onUnpair = actions::unpair,
                modifier = modifier,
            )
        }
        HomeDestination.NewChat -> NewChat(model, actions, chats, modifier)
        is HomeDestination.Conversation -> Conversation(destination.chatID, model, chats, modifier)
        is HomeDestination.Workgroup -> {
            val watch = chats.workgroupWatch(destination.workgroupID)
            if (watch == null) {
                WorkgroupFallback(destination.title, actions::back, modifier)
            } else {
                WorkgroupRoute(
                    watch = watch,
                    fallbackTitle = destination.title,
                    tree = chats::currentTree,
                    onBack = actions::back,
                    onOpenSession = actions::openSession,
                    modifier = modifier,
                )
            }
        }
        is HomeDestination.LiveSession -> LiveSession(destination.session, destination.title, model, actions, modifier)
        is HomeDestination.BrowserSession -> BrowserSession(destination.session, destination.title, model, actions, modifier)
    }
}

/** Chats and Sessions; the Chats item shows a dot when [chatsAttention]. */
@Composable
private fun HomeBottomBar(tab: HomeTab, chatsAttention: Boolean, onSelect: (HomeTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = tab == HomeTab.CHATS,
            onClick = { onSelect(HomeTab.CHATS) },
            icon = {
                BadgedBox(badge = { if (chatsAttention) Badge() }) {
                    Text("💬", modifier = Modifier.clearAndSetSemantics { })
                }
            },
            label = { Text("Chats") },
            modifier = Modifier.semantics { if (chatsAttention) stateDescription = "Needs attention" },
        )
        NavigationBarItem(
            selected = tab == HomeTab.SESSIONS,
            onClick = { onSelect(HomeTab.SESSIONS) },
            icon = { Text(">_", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { }) },
            label = { Text("Sessions") },
        )
    }
}

/** The Chats tab's root: [ChatListScreen] with the home's status, menu and banners. */
@Composable
private fun ChatsRoot(model: HomeModel, actions: HomeActions, chats: ChatsHome, modifier: Modifier) {
    // The first frame shows the list as last computed (a visit away keeps it), never a blank Loading.
    val content by chats.chatList.collectAsStateWithLifecycle(initialValue = chats.chatListNow())
    val query by chats.chatQuery.collectAsStateWithLifecycle()
    ChatListScreen(
        content = content,
        query = query,
        actions = chats.listActions,
        icons = chats.icons,
        modifier = modifier,
        topBarActions = { HomeTopBarActions(model, actions) },
        banners = { HomeBanners(model, actions) },
    )
}

/** New Chat: a session from the tree, or the orchestrator; the created chat opens in its place. */
@Composable
private fun NewChat(model: HomeModel, actions: HomeActions, chats: ChatsHome, modifier: Modifier) {
    val aiAvailable by chats.aiAvailable.collectAsStateWithLifecycle()
    val status by chats.newChatStatus.collectAsStateWithLifecycle()
    NewChatScreen(
        aiAvailable = aiAvailable,
        tree = (model.tree as? TreeState.Loaded)?.tree,
        status = status,
        onBack = actions::back,
        onCreate = chats::createChat,
        onRetry = chats::retryCreateChat,
        onDismissFailure = chats::dismissCreateChatFailure,
        modifier = modifier,
    )
}

/**
 * [chatID]'s conversation, from its shared content (a placeholder while the first is computed),
 * with [ChatComposer] below the transcript.
 */
@Composable
private fun Conversation(chatID: String, model: HomeModel, chats: ChatsHome, modifier: Modifier) {
    val state = remember(chatID) { chats.conversation(chatID) }
    val shown by state.collectAsStateWithLifecycle()
    val content = shown ?: remember(chatID) { loadingConversation(chatID) }
    val conversationActions = remember(chatID) { chats.conversationActions(chatID) }
    ConversationScreen(
        content = content,
        actions = conversationActions,
        images = chats.images,
        modifier = modifier,
        composer = {
            ChatComposer(
                chatID = chatID,
                drafts = chats.drafts,
                whyNotSendable = content.whyNotSendable,
                warnsOfPendingRequest = content.warnsOfPendingRequest,
                tree = (model.tree as? TreeState.Loaded)?.tree,
                send = { chats.sendDraft(chatID) },
            )
        },
    )
}

/** What a conversation shows before its first content: iOS's fallback title and a spinner. */
private fun loadingConversation(chatID: String) = ConversationContent(
    chatID = chatID,
    title = "Chat",
    body = ConversationBody.Loading,
    muted = false,
    showsMute = false,
    goToSession = null,
    workgroups = emptyList(),
    sendable = false,
)

/** The connection status and the ⋮ menu (Settings) on each tab root's top bar. */
@Composable
private fun HomeTopBarActions(model: HomeModel, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
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
}

/** The connection banner and the Mac alert banner, under each tab root's top bar. */
@Composable
private fun HomeBanners(model: HomeModel, actions: HomeActions) {
    connectionBanner(model.state)?.let { text ->
        ConnectionBannerRow(text, bannerOffersReconnect(model.state), actions::reconnect)
    }
    macAlertBanner(model.macStatus)?.let { text -> MacAlertBannerRow(text) }
}

/**
 * The browser view for [session], driven by the app's browser driver
 * ([LiveVideoSource.browser]); without one (never in the app) the live view shows the page.
 */
@Composable
private fun BrowserSession(session: SessionSummary, title: String, model: HomeModel, actions: HomeActions, modifier: Modifier) {
    val browser = model.video.browser
    if (browser == null) {
        LiveSession(session, title, model, actions, modifier)
        return
    }
    BrowserSessionScreen(
        title = title,
        session = session,
        live = model.live,
        activeStream = model.activeStream,
        source = model.video,
        browser = browser,
        debugBuild = model.debugBuild,
        videoStatsShown = model.videoStatsShown,
        actions = actions,
        modifier = modifier,
        macAlert = macAlertBanner(model.macStatus),
    )
}

/**
 * The live view for [session]; a browser session here (a Mac below revision 15) shows its video
 * with the browser note. The Mac alert banner ([macAlertBanner]) is a row of the live view's top bar.
 */
@Composable
private fun LiveSession(session: SessionSummary, title: String, model: HomeModel, actions: HomeActions, modifier: Modifier) {
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
        modifier = modifier,
        macAlert = macAlertBanner(model.macStatus),
    )
}

/** The Mac alert banner on Sessions, under the connection banner. */
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
                actions = { HomeTopBarActions(model, actions) },
            )
        },
    ) { padding ->
        // The IME padding keeps the list's last rows above the keyboard while searching.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            HomeBanners(model, actions)
            if (model.claudeTip) ClaudeStateTipRow(onDismiss = actions::dismissClaudeTip)
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

