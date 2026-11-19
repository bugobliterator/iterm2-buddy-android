package dev.bugobliterator.itermbuddy

import androidx.compose.ui.graphics.ImageBitmap
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.chat.BoundSessionProbe
import dev.bugobliterator.itermbuddy.core.chat.ChatInput
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.ChatSink
import dev.bugobliterator.itermbuddy.core.chat.MentionResolver
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupWatch
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.sessions.TreeState
import dev.bugobliterator.itermbuddy.core.sessions.clearOnUnpair
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.ui.SessionNavigator
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.chats.ChatListContent
import dev.bugobliterator.itermbuddy.ui.chats.chatListContents
import dev.bugobliterator.itermbuddy.ui.chats.ChatSearch
import dev.bugobliterator.itermbuddy.ui.chats.ImageBytesDecoder
import dev.bugobliterator.itermbuddy.ui.chats.noteSnippetMentions
import dev.bugobliterator.itermbuddy.ui.conversation.ChatDrafts
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationMentions
import dev.bugobliterator.itermbuddy.ui.conversation.conversationContents
import dev.bugobliterator.itermbuddy.ui.live.ComposerDrafts
import dev.bugobliterator.itermbuddy.ui.live.LiveInputMemories
import dev.bugobliterator.itermbuddy.ui.sessions.SessionSearch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app-scoped home objects (sessions and chats), as [wireHome] connects them:
 *
 * - [client] sends media and stream-scoped control to [controller] (its stream sink), and the
 *   controller sends through the client (its [StreamLink]);
 * - [client] sends chat-scoped messages to [chats] (its chat sink, the repository's inbox), and the
 *   repository sends through the client;
 * - [repository] reads the client's state, events and requests;
 * - [navigator] drives [controller]'s `open`/`close`, and reads the Mac's revision from [client]'s
 *   state to choose the browser view (at open, and again as the revision changes);
 * - [mentions] resolves @-mentions through the client; the chat list's snippets are noted with
 *   it on each list change, and again after its retry gate reopens on each chat link up
 *   ([ChatRepository.linkGeneration]) and when AI comes back on ([noteSnippetMentions]);
 * - [chatList] is the Chats root's content ([chatListContents], computed off the main thread
 *   while collected); [chatIcons] holds its decoded icons;
 * - [conversationContent] is a conversation's content (`conversationContents`, off the main
 *   thread); [openConversation] and [closeConversation] are what the navigator calls: they open
 *   and close the chat in [chats], reopen the mention retry gate, and watch the chat's bound
 *   session with [boundSessions], which probes it again on each chat link up and when the chat's
 *   binding changes in the list; [inlineImages] holds decoded inline images (48 MiB);
 * - [search], [chatSearch], [drafts], [chatDrafts] and [inputMemories] hold what the owner typed in the UI, app-scoped;
 * - [watchWorkgroup] makes a workgroup view's [WorkgroupWatch], fed [foreground] (set by the
 *   process lifecycle through [setForeground]) and whether [client] is connected;
 * - on every unpair (local, Mac-sent, or a re-scan), [navigator] is reset (closing the stream),
 *   [repository] and [chats] cleared (the chat repository clears its seen marks itself, on its
 *   consumer), the mentions, bound-session verdicts, icons and images forgotten, and the searches,
 *   drafts and input memories emptied.
 */
class HomeGraph(
    val client: CompanionClient,
    val controller: StreamController,
    val repository: SessionTreeRepository,
    val chats: ChatRepository,
    val navigator: SessionNavigator,
    val search: SessionSearch,
    val drafts: ComposerDrafts,
    val inputMemories: LiveInputMemories,
    val mentions: MentionResolver,
    val chatSearch: ChatSearch,
    val chatIcons: ChatIconCache<ImageBitmap>,
    val chatList: StateFlow<ChatListContent>,
    val boundSessions: BoundSessionProbe,
    val inlineImages: ChatIconCache<ImageBitmap>,
    val chatDrafts: ChatDrafts,
    private val contentContext: CoroutineContext,
    private val workgroupLog: (String) -> Unit = {},
) {
    private val foregroundFlow = MutableStateFlow(true)

    /** Whether the app is in the foreground (the process lifecycle's `ON_START`/`ON_STOP`). */
    val foreground: StateFlow<Boolean> = foregroundFlow.asStateFlow()

    /** The app came to the foreground, or left it: tells the chats and every workgroup watch. */
    fun setForeground(foreground: Boolean) {
        foregroundFlow.value = foreground
        chats.setForeground(foreground)
    }

    /**
     * A [WorkgroupWatch] for [workgroupID]'s view, polling every [pollInterval]. Its foreground and
     * connection gates follow [foreground] and [client]'s state for as long as [scope] (the view's)
     * lives; each change to connected is a new link, asked at once. The caller starts and stops it.
     */
    fun watchWorkgroup(scope: CoroutineScope, workgroupID: String, pollInterval: Duration = 5.seconds): WorkgroupWatch {
        val watch = WorkgroupWatch(client, scope, workgroupID, pollInterval = pollInterval, log = workgroupLog)
        watch.setForeground(foreground.value)
        watch.setConnected(client.state.value is ClientState.Connected)
        scope.launch { foreground.collect(watch::setForeground) }
        scope.launch { client.state.map { it is ClientState.Connected }.distinctUntilChanged().collect(watch::setConnected) }
        return watch
    }

    /** Opens [chatID]'s conversation (a navigator push): subscribed, gate reopened, bound session probed. */
    fun openConversation(chatID: String) {
        chats.open(chatID)
        mentions.reopenRetryGate()
        boundSessions.watch(chatID, boundGuid(chatID))
    }

    /** Closes [chatID]'s conversation (a navigator pop). */
    fun closeConversation(chatID: String) {
        chats.close(chatID)
        boundSessions.unwatch(chatID)
    }

    /** [chatID]'s conversation screen content, computed off the main thread while collected. */
    fun conversationContent(chatID: String): Flow<ConversationContent> = conversationContents(
        chatID,
        chats.conversation(chatID),
        chats.listSnapshot,
        chats.activity,
        chats.macRevision,
        repository.state.map { (it as? TreeState.Loaded)?.tree },
        boundSessions.live,
        mentions.resolutions,
        reopenings = merge(chats.linkGeneration.drop(1).map { }, chats.aiAvailable.drop(1).filter { it }.map { }),
        mentions = object : ConversationMentions {
            override fun reopen() = mentions.reopenRetryGate()

            override fun noteMessages(messages: List<ChatMessage>) = mentions.noteMessages(messages)
        },
        context = contentContext,
    )

    private fun boundGuid(chatID: String): String? = boundGuid(chats.listSnapshot.value.list, chatID)
}

/** [chatID]'s bound session in [list], or null (unbound, or not listed yet). */
private fun boundGuid(list: ChatListState, chatID: String): String? =
    (list as? ChatListState.Loaded)?.entries?.firstOrNull { it.chat.id == chatID }?.chat?.terminalSessionGuid

/**
 * Builds the home graph: `CompanionApp` with production parts, tests with the fake relay, fake
 * Mac and a fake video output, so both run the same wiring.
 *
 * The client needs its sinks and the controller and the chat repository need the client; the
 * client is built with sinks that forward to them, set before this returns. Nothing reaches a
 * sink before the client's `start` or `pair`, which callers make only after this returns.
 *
 * @param iconDecoder decodes chat icons (the app passes `BitmapImageDecoder`; tests decode nothing).
 * @param imageDecoder decodes inline images in conversations (likewise).
 * @param log for a failed unpair cleanup.
 * @param chatLog the mention resolver's, bound-session probe's and workgroup watches' diagnostics
 *   (IDs, counts and failure kinds only).
 * @param listContext where [HomeGraph.chatList] and conversations' content are computed.
 */
fun wireHome(
    scope: CoroutineScope,
    newClient: (StreamSink, ChatSink) -> CompanionClient,
    newController: (StreamLink) -> StreamController,
    newRepository: (CompanionClient) -> SessionTreeRepository,
    newChats: (StreamLink) -> ChatRepository,
    iconDecoder: ImageBytesDecoder<ImageBitmap> = ImageBytesDecoder { null },
    imageDecoder: ImageBytesDecoder<ImageBitmap> = ImageBytesDecoder { null },
    log: (String) -> Unit = {},
    chatLog: (String) -> Unit = {},
    listContext: CoroutineContext = Dispatchers.Default,
): HomeGraph {
    val sink = ForwardingStreamSink()
    val chatSink = ForwardingChatSink()
    val client = newClient(sink, chatSink)
    val controller = newController(client)
    sink.target = controller
    val chats = newChats(client)
    chatSink.target = chats.sink
    val repository = newRepository(client)
    val navigator = SessionNavigator(open = controller::open, close = controller::close, macRevision = { macRevisionOf(client.state.value) })
    scope.launch { client.state.map(::macRevisionOf).distinctUntilChanged().collect(navigator::macRevisionChanged) }
    val search = SessionSearch()
    val drafts = ComposerDrafts()
    val inputMemories = LiveInputMemories()
    val mentions = MentionResolver(client, scope, chatLog)
    val chatSearch = ChatSearch()
    val chatIcons = ChatIconCache(iconDecoder)
    // Each chat link up or AI coming back on reopens the resolver's retry gate (AM:2349-2351).
    // The generation only grows, so a reconnect too fast to see in between is still a change.
    val linkUps = chats.linkGeneration.drop(1).map { }
    val aiBackOn = chats.aiAvailable.drop(1).filter { it }.map { }
    scope.launch {
        noteSnippetMentions(chats.rows, merge(linkUps, aiBackOn), mentions::reopenRetryGate) { mentions.note(it) }
    }
    val boundSessions = BoundSessionProbe(client, scope, log = chatLog)
    val inlineImages = ChatIconCache(imageDecoder, INLINE_IMAGE_BUDGET_BYTES)
    val chatDrafts = ChatDrafts()
    // A new link probes the open conversations' bound sessions again; a binding that changes in
    // the list (or first appears there) is probed once.
    scope.launch { chats.linkGeneration.drop(1).collect { boundSessions.linkUp() } }
    scope.launch {
        chats.listSnapshot.map { it.list }.distinctUntilChanged().collect { list ->
            boundSessions.refreshBindings { chatID -> boundGuid(list, chatID) }
        }
    }
    val chatList = chatListContents(chats.listSnapshot, chats.macRevision, chatSearch.query, mentions.resolutions, listContext)
        .stateIn(scope, SharingStarted.WhileSubscribed(LIST_SHARING_STOP_MILLIS), ChatListContent.Loading)
    clearOnUnpair(
        client.paired,
        scope,
        // Not the chat seen store: ChatRepository.clear() clears it on its consumer, after its
        // barrier against inputs from earlier links, so no mark still queued lands after the wipe.
        listOf(
            navigator::reset, repository::clear, chats::clear, mentions::clear, boundSessions::clear, chatIcons::clear,
            inlineImages::clear, search::clear, chatSearch::clear, drafts::clear, chatDrafts::clear, inputMemories::clear,
        ),
        log,
    )
    return HomeGraph(
        client, controller, repository, chats, navigator, search, drafts, inputMemories, mentions, chatSearch, chatIcons, chatList,
        boundSessions, inlineImages, chatDrafts, listContext, chatLog,
    )
}

/** The decoded inline images' shared bitmap budget (*Global Constraints*). */
private const val INLINE_IMAGE_BUDGET_BYTES = 48 * 1024 * 1024

/** How long [HomeGraph.chatList] keeps computing after its last collector (rotation) leaves. */
private const val LIST_SHARING_STOP_MILLIS = 5_000L

/** The client's stream sink before the controller exists; forwards every input once it does. */
private class ForwardingStreamSink : StreamSink {
    @Volatile var target: StreamSink? = null

    override suspend fun accept(input: StreamInput) {
        // Set by wireHome before anything can start the client: a null here is a wiring bug.
        checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
    }
}

/** The client's chat sink before the chat repository exists; forwards every input once it does. */
private class ForwardingChatSink : ChatSink {
    @Volatile var target: ChatSink? = null

    override suspend fun accept(input: ChatInput) {
        // Set by wireHome before anything can start the client: a null here is a wiring bug.
        checkNotNull(target) { "the chat repository is not wired yet" }.accept(input)
    }
}

/** The Mac's hello revision in [state]; 0 when not connected. */
fun macRevisionOf(state: ClientState): Int = (state as? ClientState.Connected)?.macRevision ?: 0
