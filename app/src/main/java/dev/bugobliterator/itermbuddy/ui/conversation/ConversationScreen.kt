package dev.bugobliterator.itermbuddy.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatListSnapshot
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.NotSendableReason
import dev.bugobliterator.itermbuddy.core.chat.StableSessionId
import dev.bugobliterator.itermbuddy.core.chat.warnsOfPendingRequest
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.sessions.SessionRow
import dev.bugobliterator.itermbuddy.ui.sessions.sessionRows
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** iOS's soft banner over a cached transcript (`IOS/Screens/ConversationView.swift:107-123`). */
const val STALE_CONVERSATION_TEXT = "Couldn’t refresh. Showing the last loaded messages."

/** A chat whose history is over the raw chat cap, or ended a link by itself (owner decision). */
const val TOO_LARGE_CONVERSATION_TEXT = "This chat is too large to open on this phone."

const val FAILED_CONVERSATION_TEXT = "Couldn't load this chat."

/** "Go to session": the session view to open and its title. [toString] shows no names. */
data class GoToSession(val session: SessionSummary, val title: String) {
    override fun toString(): String = "GoToSession(session=$session, title(len=${title.length}))"
}

/** One workgroup an orchestrator chat claims, for its "Workgroups" menu. [toString] shows the ID only. */
data class WorkgroupLink(val workgroupID: String, val label: String) {
    override fun toString(): String = "WorkgroupLink(workgroupID=$workgroupID, label(len=${label.length}))"
}

/**
 * Whether [chat] offers "Go to session", and what it opens. Only a session-bound chat
 * (`terminalSessionGuid` set) does (`IOS/Screens/ConversationView.swift:20-38`). The tree's `guid`
 * is a stable ID (`sources/Companion/CompanionSessionLister.swift:23-31`): a chat bound to a session
 * in [tree] shows it at once, opening that session titled as its row. An older or Mac-created chat
 * can hold a raw guid the tree does not list (nor buried and peer sessions the flat list misses,
 * `AM:300-308`): that shows the button only once the Mac confirmed the session ([live] true,
 * [dev.bugobliterator.itermbuddy.core.chat.BoundSessionProbe]), as iOS gates it, and opens a view
 * whose stream reference is the chat's guid itself, which the Mac resolves either way
 * (`HB:1208` → `HB:2007`), titled with the chat's title.
 */
fun goToSession(chat: ChatSummary?, tree: SessionTree?, live: Boolean?): GoToSession? {
    val guid = chat?.terminalSessionGuid ?: return null
    val wanted = sessionKey(guid)
    if (tree != null) {
        for (row in sessionRows(tree)) {
            if (row is SessionRow.Session && sessionKey(row.summary.guid) == wanted) return GoToSession(row.summary, row.title)
        }
    }
    if (live != true) return null
    return GoToSession(SessionSummary(guid, chat.title, "", isBrowser = false), chat.title)
}

/** Stable IDs compare in canonical form, raw guids without case. */
private fun sessionKey(guid: String): String = StableSessionId.canonical(guid) ?: guid.uppercase()

/** What the conversation's body shows. */
sealed interface ConversationBody {
    data object Loading : ConversationBody

    /** [TOO_LARGE_CONVERSATION_TEXT] with Try again. */
    data object TooLarge : ConversationBody

    /** [FAILED_CONVERSATION_TEXT], with Try again when [retryable] (not while AI is off). */
    data class Failed(val retryable: Boolean) : ConversationBody

    /** The rows, with [banners] above the composer (stale, deleted). Items compare by identity. */
    data class Shown(val items: List<TranscriptItem>, val banners: List<String>) : ConversationBody {
        override fun toString(): String = "Shown(items=${items.size}, banners=${banners.size})"
    }
}

/**
 * The conversation screen's model. [title] is the chat's (iOS falls back to "Chat",
 * `IOS/Screens/ConversationView.swift:16-18`); [showsMute] from Mac revision 5 (`AM:2670-2672`);
 * [goToSession] per [goToSession]; [workgroups] the workgroups an orchestrator chat claims;
 * [sendable] and [whyNotSendable] from the repository, and [warnsOfPendingRequest]
 * ([dev.bugobliterator.itermbuddy.core.chat.warnsOfPendingRequest]), for the composer. [toString]
 * shows no content.
 */
data class ConversationContent(
    val chatID: String,
    val title: String,
    val body: ConversationBody,
    val muted: Boolean,
    val showsMute: Boolean,
    val goToSession: GoToSession?,
    val workgroups: List<WorkgroupLink>,
    val sendable: Boolean,
    val whyNotSendable: NotSendableReason? = if (sendable) null else NotSendableReason.NOT_LOADED,
    val warnsOfPendingRequest: Boolean = false,
) {
    override fun toString(): String =
        "ConversationContent(chatID=$chatID, title(len=${title.length}), body=$body, muted=$muted, showsMute=$showsMute, " +
            "goToSession=$goToSession, workgroups=${workgroups.size}, sendable=$sendable, whyNotSendable=$whyNotSendable, " +
            "warnsOfPendingRequest=$warnsOfPendingRequest)"
}

/**
 * Builds [ConversationContent], keeping rows through one [TranscriptItemCache]. Single-threaded:
 * one presenter per collector ([conversationContents]), off the main thread.
 */
class ConversationPresenter {
    private val cache = TranscriptItemCache()

    fun present(
        chatID: String,
        state: ConversationState,
        entry: ChatListEntry?,
        activity: AgentState?,
        macRevision: Int?,
        tree: SessionTree?,
        live: Boolean?,
        resolutions: Map<String, MentionResolution>,
    ): ConversationContent {
        val chat = entry?.chat
        val body = when (state) {
            is ConversationState.Loading -> ConversationBody.Loading
            ConversationState.TooLarge -> ConversationBody.TooLarge
            is ConversationState.Failed -> ConversationBody.Failed(state.retryable)
            is ConversationState.Shown -> ConversationBody.Shown(
                cache.items(state.messages, showsTyping(activity), resolutions, state.echoes),
                listOfNotNull(
                    STALE_CONVERSATION_TEXT.takeIf { state.stale },
                    ChatRepository.DELETED_CHAT_NOTICE.takeIf { state.deleted },
                ),
            )
        }
        val workgroups = if (chat?.orchestrationEnabled == true) {
            chat.workgroupScopes.map { WorkgroupLink(it, resolutions[it]?.displayName ?: it) }
        } else {
            emptyList()
        }
        return ConversationContent(
            chatID = chatID,
            title = chat?.title ?: "Chat",
            body = body,
            muted = entry?.muted == true,
            showsMute = (macRevision ?: 0) >= MUTE_REVISION,
            goToSession = goToSession(chat, tree, live),
            workgroups = workgroups,
            sendable = (state as? ConversationState.Shown)?.sendable == true,
            whyNotSendable = state.whyNotSendable,
            warnsOfPendingRequest = state is ConversationState.Shown && warnsOfPendingRequest(activity, state.messages),
        )
    }

    /** The messages rebuilt as new since the last call ([TranscriptItemCache.takeBuilt]). */
    fun takeBuilt(): List<ChatMessage> = cache.takeBuilt()
}

/** The Mac persists muted chats from revision 5 (`AM:2670-2672`). */
private const val MUTE_REVISION = 5

/** The mention resolver's calls a conversation makes. */
interface ConversationMentions {
    /** Reopens the retry gate (`MentionResolver.reopenRetryGate`). */
    fun reopen()

    /** Asks for the mentions in [messages] not yet resolved (`MentionResolver.noteMessages`). */
    fun noteMessages(messages: List<ChatMessage>)
}

/**
 * [chatID]'s conversation screen content from the repository's [conversation], [listSnapshot],
 * [activity] and [macRevision], the session [tree], the bound-session verdicts [live] and the
 * mention [resolutions], computed on [context] (Default: never the main thread) by one
 * [ConversationPresenter]: markdown and mentions are rendered there, and only for messages that
 * changed. Each message rendered as new is noted with [mentions]; on each of [reopenings] (a link
 * up, AI back on) the retry gate reopens and every message is noted again (`AM:2349-2351`).
 */
fun conversationContents(
    chatID: String,
    conversation: Flow<ConversationState>,
    listSnapshot: Flow<ChatListSnapshot>,
    activity: Flow<Map<String, AgentState>>,
    macRevision: Flow<Int?>,
    tree: Flow<SessionTree?>,
    live: Flow<Map<String, Boolean>>,
    resolutions: Flow<Map<String, MentionResolution>>,
    reopenings: Flow<Unit>,
    mentions: ConversationMentions,
    context: CoroutineContext = Dispatchers.Default,
): Flow<ConversationContent> = channelFlow {
    val presenter = ConversationPresenter()
    val current = AtomicReference<List<ChatMessage>>(emptyList())
    launch {
        reopenings.collect {
            mentions.reopen()
            mentions.noteMessages(current.get())
        }
    }
    val entry = listSnapshot.map { shot -> (shot.list as? ChatListState.Loaded)?.entries?.firstOrNull { it.chat.id == chatID } }
        .distinctUntilChanged()
    val state = activity.map { it[chatID] }.distinctUntilChanged()
    val session = combine(macRevision, tree, live.map { it[chatID] }.distinctUntilChanged()) { revision, sessions, verdict ->
        SessionInputs(revision, sessions, verdict)
    }
    combine(conversation, entry, state, session, resolutions) { shown, listed, agent, inputs, names ->
        val content = presenter.present(chatID, shown, listed, agent, inputs.macRevision, inputs.tree, inputs.live, names)
        if (shown is ConversationState.Shown) current.set(shown.messages)
        val built = presenter.takeBuilt()
        if (built.isNotEmpty()) mentions.noteMessages(built)
        content
    }.distinctUntilChanged().collect { send(it) }
}.flowOn(context)

private class SessionInputs(val macRevision: Int?, val tree: SessionTree?, val live: Boolean?)

/** The conversation's callbacks. Navigation and actions go to app-scoped owners, never composition state. */
interface ConversationActions {
    fun back()

    /** Try again after a failed or too-large load ([ChatRepository.retry]). */
    fun retry()

    fun setMuted(muted: Boolean)

    fun goToSession(target: GoToSession)

    /** A mention was tapped: a session opens its view, a workgroup its member list. */
    fun openMention(target: LinkTarget)

    fun openWorkgroup(workgroup: WorkgroupLink)

    /** Sends again a message of the owner's that was not delivered ([ChatRepository.retrySend]). */
    fun retrySend(uniqueID: String)

    /** Drops a message of the owner's that was not delivered ([ChatRepository.discardUnsent]). */
    fun deleteUnsent(uniqueID: String)
}

/**
 * The conversation: a top bar with ←, the title, "Go to session" (▣), the "Workgroups" menu and
 * the mute bell; the transcript ([MessageBubble] rows keyed by message, typing dots while the
 * agent works) pinned to the bottom while it is there, else a "New messages ↓" pill when more
 * arrives; the banners; and [composer] below. Loading, too large and failed states fill the body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    content: ConversationContent,
    actions: ConversationActions,
    images: ChatIconCache<ImageBitmap>,
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = actions::back, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("←") }
                },
                title = { Text(content.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { ConversationTopActions(content, actions) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val body = content.body) {
                    ConversationBody.Loading -> Centered { CircularProgressIndicator() }
                    ConversationBody.TooLarge -> Centered {
                        Text(TOO_LARGE_CONVERSATION_TEXT, textAlign = TextAlign.Center)
                        Button(onClick = actions::retry) { Text("Try again") }
                    }
                    is ConversationBody.Failed -> Centered {
                        Text(FAILED_CONVERSATION_TEXT, textAlign = TextAlign.Center)
                        if (body.retryable) Button(onClick = actions::retry) { Text("Try again") }
                    }
                    is ConversationBody.Shown -> Transcript(body.items, images, actions::openMention) { action, uniqueID ->
                        when (action) {
                            UnsentAction.RETRY -> actions.retrySend(uniqueID)
                            UnsentAction.DELETE -> actions.deleteUnsent(uniqueID)
                        }
                    }
                }
            }
            (content.body as? ConversationBody.Shown)?.banners?.forEach { Banner(it) }
            composer()
        }
    }
}

@Composable
private fun ConversationTopActions(content: ConversationContent, actions: ConversationActions) {
    content.goToSession?.let { target ->
        IconButton(onClick = { actions.goToSession(target) }, modifier = Modifier.semantics { contentDescription = "Go to session" }) {
            Text(">_", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (content.workgroups.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("Workgroups") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (workgroup in content.workgroups) {
                    DropdownMenuItem(
                        text = { Text(workgroup.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            actions.openWorkgroup(workgroup)
                        },
                    )
                }
            }
        }
    }
    if (content.showsMute) {
        val label = if (content.muted) "Unmute this chat" else "Mute this chat"
        IconButton(onClick = { actions.setMuted(!content.muted) }, modifier = Modifier.semantics { contentDescription = label }) {
            Text(if (content.muted) "🔕" else "🔔")
        }
    }
}

/**
 * The rows, newest at the bottom. The list is laid out from the bottom (reversed); [followAction]
 * decides, from whether it was at the bottom before the rows changed, whether to keep the newest
 * row in view (a row inserted at the bottom is otherwise kept off-screen by key anchoring) or to
 * show the "New messages ↓" pill.
 */
@Composable
private fun Transcript(
    items: List<TranscriptItem>,
    images: ChatIconCache<ImageBitmap>,
    onMention: (LinkTarget) -> Unit,
    onUnsent: (UnsentAction, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    var unseen by remember { mutableStateOf(false) }
    val tail = remember(items) { transcriptTail(items) }
    val previous = remember { arrayOfNulls<TranscriptTail>(1) }
    // Read in composition, before the new rows are laid out: where the list was before the change.
    val wasAtBottom = atBottom
    val action = remember(tail) {
        followAction(wasAtBottom, previous[0], tail).also { previous[0] = tail }
    }
    // Applied as the rows apply, before they are laid out, so the newest row never lands off-screen.
    DisposableEffect(tail) {
        when (action) {
            FollowAction.PIN -> {
                unseen = false
                listState.requestScrollToItem(0)
            }
            FollowAction.PILL -> unseen = true
            FollowAction.NONE -> Unit
        }
        onDispose { }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            items(items.asReversed(), key = { it.key }) { item -> MessageBubble(item, images, onMention, onUnsent = onUnsent) }
        }
        if (unseen && !atBottom) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) { Text("New messages ↓", modifier = Modifier.padding(horizontal = 12.dp)) }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            "⚠ $text",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
