package dev.bugobliterator.itermbuddy.ui.chats

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.ChatListSnapshot
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.ui.live.NoPersonalizedLearning
import java.nio.ByteBuffer
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal const val EMPTY_CHATS_TITLE = "No chats yet"
internal const val EMPTY_CHATS_BODY = "Tap New Chat to start a conversation with a session or the orchestrator."
internal const val CHATS_FAILED = "Couldn't load chats."
internal const val CHATS_REFRESH_FAILED = "Couldn't refresh. Showing the last loaded chats."

/** What the Chats tab's root shows, from the repository's flows ([chatListContent]). */
sealed interface ChatListContent {
    /** No list yet (connecting, or the first fetch is out). */
    data object Loading : ChatListContent

    /** No list could be read; [retryable] offers Try again. */
    data class Failed(val retryable: Boolean) : ChatListContent

    /** AI is off on the Mac: [AiUnavailablePanel]. */
    data object AiOff : ChatListContent

    /** The Mac has no chats at all, whatever the query (`IOS/Screens/HomeView.swift:75-88`). */
    data class Empty(val refreshing: Boolean, val refreshFailed: Boolean) : ChatListContent

    /** Chats exist but none matches [query] (trimmed). [toString] shows its length only. */
    data class NoMatch(val query: String, val refreshing: Boolean, val refreshFailed: Boolean) : ChatListContent {
        val text: String get() = "No chats match \"$query\"."

        override fun toString(): String = "NoMatch(query(len=${query.length}), refreshing=$refreshing, refreshFailed=$refreshFailed)"
    }

    /** The rows to show, in the Mac's order (most recent first). */
    data class Rows(val rows: List<ChatRowModel>, val refreshing: Boolean, val refreshFailed: Boolean) : ChatListContent {
        override fun toString(): String = "Rows(rows=${rows.size}, refreshing=$refreshing, refreshFailed=$refreshFailed)"
    }
}

/**
 * The Chats root for the repository's [list], [rows] and [aiAvailable] (one
 * [ChatListSnapshot]), the Mac's [macRevision], the search [query] and the mention
 * [resolutions], with no earlier models to reuse. See [ChatListPresenter.present].
 */
fun chatListContent(
    list: ChatListState,
    rows: List<ChatRow>,
    aiAvailable: Boolean,
    macRevision: Int?,
    query: String,
    resolutions: Map<String, MentionResolution>,
): ChatListContent = ChatListPresenter().present(list, rows, aiAvailable, macRevision, query, resolutions)

/**
 * Builds what the Chats root shows, keeping the previous row models: a row whose [ChatRow],
 * rendered snippet and actions are unchanged keeps its [ChatRowModel] instance (so Compose skips
 * it), and an icon whose bytes are unchanged keeps its [ChatIconRef] (hashed once). Single-threaded:
 * one presenter per collector ([chatListContents]), off the main thread.
 */
class ChatListPresenter(private val iconRef: (ByteBuffer?) -> ChatIconRef? = ::chatIconRef) {
    private class Shown(val row: ChatRow, val model: ChatRowModel)

    private var previous: Map<String, Shown> = emptyMap()

    /**
     * AI off comes first, whatever list is kept (iOS checks it first,
     * `IOS/Screens/HomeView.swift:16-19`); then the list's state. Empty is decided from the
     * list's entries; while [rows] do not cover them (never, from one snapshot) it shows Loading.
     * Then the rows [filterChatRows] keeps, in the Mac's order.
     */
    fun present(
        list: ChatListState,
        rows: List<ChatRow>,
        aiAvailable: Boolean,
        macRevision: Int?,
        query: String,
        resolutions: Map<String, MentionResolution>,
    ): ChatListContent {
        if (!aiAvailable) return ChatListContent.AiOff
        return when (list) {
            ChatListState.Loading -> ChatListContent.Loading
            is ChatListState.Failed -> ChatListContent.Failed(list.retryable)
            ChatListState.AiUnavailable -> ChatListContent.AiOff
            is ChatListState.Loaded -> when {
                list.entries.isEmpty() -> ChatListContent.Empty(list.refreshing, list.refreshFailed)
                rows.map { it.entry.chat.id } != list.entries.map { it.chat.id } -> ChatListContent.Loading
                else -> {
                    val shown = rows.map { Shown(it, model(it, macRevision, resolutions)) }
                    previous = shown.associateBy { it.model.chatID }
                    val terms = searchTerms(query)
                    val matching = shown.map { it.model }.filter { terms.isEmpty() || matches(terms, it.title, it.snippet) }
                    if (matching.isEmpty()) {
                        ChatListContent.NoMatch(query.trim(), list.refreshing, list.refreshFailed)
                    } else {
                        ChatListContent.Rows(matching, list.refreshing, list.refreshFailed)
                    }
                }
            }
        }
    }

    private fun model(row: ChatRow, macRevision: Int?, resolutions: Map<String, MentionResolution>): ChatRowModel {
        val chat = row.entry.chat
        val snippet = renderedSnippet(row.entry.snippet, resolutions)
        val actions = chatRowActions(row.entry, macRevision)
        val prior = previous[chat.id]
        if (prior != null && prior.row == row && prior.model.snippet == snippet && prior.model.actions == actions) return prior.model
        val icon = if (prior != null && prior.row.entry.chat.iconView() == chat.iconView()) prior.model.icon else iconRef(chat.iconView())
        return ChatRowModel(
            chatID = chat.id,
            title = chat.title,
            lastModifiedMillis = chat.lastModifiedMillis,
            snippet = snippet,
            muted = row.entry.muted == true,
            state = row.state,
            unread = row.unread,
            actions = actions,
            icon = icon,
        )
    }
}

/**
 * The Chats root's content from the repository's [snapshot] and [macRevision], the search
 * [query] and the mention [resolutions], computed on [context] (Default: never the main thread)
 * by one [ChatListPresenter], and only when it changes.
 */
fun chatListContents(
    snapshot: Flow<ChatListSnapshot>,
    macRevision: Flow<Int?>,
    query: Flow<String>,
    resolutions: Flow<Map<String, MentionResolution>>,
    context: CoroutineContext = Dispatchers.Default,
    iconRef: (ByteBuffer?) -> ChatIconRef? = ::chatIconRef,
): Flow<ChatListContent> = flow {
    val presenter = ChatListPresenter(iconRef)
    emitAll(
        combine(snapshot, macRevision, query, resolutions) { shot, revision, text, names ->
            presenter.present(shot.list, shot.rows, shot.aiAvailable, revision, text, names)
        },
    )
}.distinctUntilChanged().flowOn(context)

/** How long until the next whole [intervalMillis] after [nowMillis] (the stamps refresh on the minute). */
fun nextStampRefreshDelay(nowMillis: Long, intervalMillis: Long): Long = intervalMillis - Math.floorMod(nowMillis, intervalMillis)

/** The Chats root's callbacks. Opening and the actions go to app-scoped owners, never composition state. */
interface ChatListActions {
    fun openChat(chatID: String)

    fun refreshChats()

    fun newChat()

    fun setChatMuted(chatID: String, muted: Boolean)

    fun deleteChat(chatID: String)

    /** The chat search; app-scoped ([ChatSearch]). */
    fun setChatQuery(query: String)
}

/**
 * The Chats tab's root: the "Chats" top bar with New Chat (✎, disabled while AI is off) after
 * [topBarActions], then [banners], the search field (hidden while AI is off) and [content] under
 * pull to refresh: a spinner, "Couldn't load chats." with Try again, the AI-off panel, the empty
 * state, the no-match text, or the rows ([ChatRowView]), with a soft banner when a refresh
 * failed. A long press offers Mute/Unmute (Mac revision 5 and later) and Delete, which asks
 * first. Back while searching clears the search.
 *
 * [icons] decodes each chat icon once per content, off the main thread. The stamps read
 * [nowMillis] and refresh on each whole [stampRefreshMillis].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    content: ChatListContent,
    query: String,
    actions: ChatListActions,
    icons: ChatIconCache<ImageBitmap>,
    modifier: Modifier = Modifier,
    topBarActions: @Composable RowScope.() -> Unit = {},
    banners: @Composable ColumnScope.() -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
    stampRefreshMillis: Long = STAMP_REFRESH_MILLIS,
) {
    val aiOff = content == ChatListContent.AiOff
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = !aiOff && (query.isNotEmpty() || searchFocused)) {
        actions.setChatQuery("")
        focusManager.clearFocus()
    }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    topBarActions()
                    IconButton(
                        onClick = actions::newChat,
                        enabled = !aiOff,
                        modifier = Modifier.semantics { contentDescription = "New Chat" },
                    ) { Text("✎", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            banners()
            if (!aiOff) {
                ChatSearchField(
                    query = query,
                    onQueryChange = actions::setChatQuery,
                    onFocusChange = { searchFocused = it },
                    onDone = { focusManager.clearFocus() },
                )
            }
            ChatListBody(content, actions, icons, nowMillis, stampRefreshMillis, onDelete = { deleting = it })
        }
    }
    val pending = deleting
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("It is deleted on your Mac too.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.deleteChat(pending)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListBody(
    content: ChatListContent,
    actions: ChatListActions,
    icons: ChatIconCache<ImageBitmap>,
    nowMillis: () -> Long,
    stampRefreshMillis: Long,
    onDelete: (String) -> Unit,
) {
    val refreshing = when (content) {
        is ChatListContent.Rows -> content.refreshing
        is ChatListContent.Empty -> content.refreshing
        is ChatListContent.NoMatch -> content.refreshing
        else -> false
    }
    val refreshFailed = when (content) {
        is ChatListContent.Rows -> content.refreshFailed
        is ChatListContent.Empty -> content.refreshFailed
        is ChatListContent.NoMatch -> content.refreshFailed
        else -> false
    }
    if (content == ChatListContent.AiOff) {
        AiUnavailablePanel()
        return
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions::refreshChats, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (refreshFailed) RefreshFailedBanner()
            when (content) {
                ChatListContent.Loading -> Centered { CircularProgressIndicator() }
                is ChatListContent.Failed -> Centered {
                    Text(CHATS_FAILED, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (content.retryable) Button(onClick = actions::refreshChats) { Text("Try again") }
                }
                is ChatListContent.Empty -> Centered {
                    Text("💬", style = MaterialTheme.typography.displayMedium, modifier = Modifier.clearAndSetSemantics { })
                    Text(EMPTY_CHATS_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        EMPTY_CHATS_BODY,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is ChatListContent.NoMatch -> Centered {
                    Text(content.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                is ChatListContent.Rows -> ChatRows(content.rows, actions, icons, nowMillis, stampRefreshMillis, onDelete)
                ChatListContent.AiOff -> Unit
            }
        }
    }
}

@Composable
private fun ChatRows(
    rows: List<ChatRowModel>,
    actions: ChatListActions,
    icons: ChatIconCache<ImageBitmap>,
    nowMillis: () -> Long,
    stampRefreshMillis: Long,
    onDelete: (String) -> Unit,
) {
    val now by produceState(nowMillis(), nowMillis, stampRefreshMillis) {
        // The stamps move from time of day to weekday as time passes: refresh them on each minute.
        while (true) {
            delay(nextStampRefreshDelay(nowMillis(), stampRefreshMillis))
            value = nowMillis()
        }
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(context)
    val zone = remember(now) { ZoneId.systemDefault() }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.chatID }) { row ->
            // Keyed by the chat (the item key) and the icon's content hash; decoded off the main thread.
            val ref = row.icon
            val icon by produceState(ref?.let { icons.cached(it.key) }, ref) { value = ref?.let { icons.load(it) } }
            ChatRowView(
                row = row,
                timestamp = chatTimestamp(row.lastModifiedMillis, now, zone, locale, use24Hour),
                icon = icon,
                onOpen = { actions.openChat(row.chatID) },
                onAction = { action ->
                    when (action) {
                        ChatRowAction.MUTE -> actions.setChatMuted(row.chatID, true)
                        ChatRowAction.UNMUTE -> actions.setChatMuted(row.chatID, false)
                        ChatRowAction.DELETE -> onDelete(row.chatID)
                    }
                },
            )
        }
    }
}

@Composable
private fun RefreshFailedBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            CHATS_REFRESH_FAILED,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A scrollable centred column, so pull to refresh works on the empty and failure states too. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** The search field above the chats, with ✕ to clear it; no autocorrect or learning. */
@Composable
private fun ChatSearchField(query: String, onQueryChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit, onDone: () -> Unit) = NoPersonalizedLearning {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onFocusChanged { onFocusChange(it.isFocused) },
        placeholder = { Text("Search chats") },
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

private const val STAMP_REFRESH_MILLIS = 60_000L
