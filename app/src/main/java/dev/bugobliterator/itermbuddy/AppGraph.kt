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
import dev.bugobliterator.itermbuddy.core.chat.DeleteFromOutcome
import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.MentionResolver
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupWatch
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.sessions.TreeState
import dev.bugobliterator.itermbuddy.core.sessions.clearOnUnpair
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.ui.HomeNavigator
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.chats.ChatListActions
import dev.bugobliterator.itermbuddy.ui.chats.ChatListContent
import dev.bugobliterator.itermbuddy.ui.chats.ChatListPresenter
import dev.bugobliterator.itermbuddy.ui.chats.NewChatCreator
import dev.bugobliterator.itermbuddy.ui.chats.chatListContents
import dev.bugobliterator.itermbuddy.ui.chats.ChatSearch
import dev.bugobliterator.itermbuddy.ui.chats.ImageBytesDecoder
import dev.bugobliterator.itermbuddy.ui.chats.noteSnippetMentions
import dev.bugobliterator.itermbuddy.ui.chatsNeedAttention
import dev.bugobliterator.itermbuddy.ui.conversation.ChatDrafts
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationActions
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationMentions
import dev.bugobliterator.itermbuddy.ui.conversation.GoToSession
import dev.bugobliterator.itermbuddy.ui.conversation.LinkTarget
import dev.bugobliterator.itermbuddy.ui.conversation.WorkgroupLink
import dev.bugobliterator.itermbuddy.ui.conversation.conversationContents
import dev.bugobliterator.itermbuddy.ui.conversation.mentionSession
import dev.bugobliterator.itermbuddy.ui.home.ChatsHome
import dev.bugobliterator.itermbuddy.ui.live.ComposerDrafts
import dev.bugobliterator.itermbuddy.ui.live.LiveInputMemories
import dev.bugobliterator.itermbuddy.ui.sessions.SessionSearch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
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
 * - [navigator] (two tabs, a back stack each) alone opens and closes the stream
 *   ([controller]'s `open`/`close`), the conversations ([openConversation], [closeConversation]),
 *   the visible chat ([ChatRepository.setVisible]) and the workgroup watches
 *   ([workgroupWatch]); it reads the Mac's revision from [client]'s state to choose the browser
 *   view, takes its default tab from the first chat link up and leaves the Chats root when AI
 *   turns off, from one ordered feed ([ChatRepository.availability]); pushes stop once the
 *   pairing is gone, and its reset drops the shared chat contents under its lock ([clearShared]);
 * - [mentions] resolves @-mentions through the client; the chat list's snippets are noted with
 *   it on each list change, and again after its retry gate reopens on each chat link up
 *   ([ChatRepository.linkGeneration]) and when AI comes back on ([noteSnippetMentions]);
 * - [chatList] is the Chats root's content ([chatListContents], computed off the main thread
 *   while collected, and for [sharingStopMillis] after its last collector leaves, its last value
 *   kept for the next visit, never across an unpair); [chatIcons]
 *   holds its decoded icons; [chatListActions] are its calls; [chatsAttention] is the Chats tab's dot;
 * - [conversationState] is an open conversation's content (`conversationContents`, off the main
 *   thread), computed from its open to its close and shared by every collector; [openConversation]
 *   and [closeConversation] are what the navigator calls: they open and close the chat in [chats],
 *   reopen the mention retry gate, and watch the chat's bound session with [boundSessions], which
 *   probes it again on each chat link up and when the chat's binding changes in the list, and
 *   ask [mentions] for the names of the workgroups it claims (its Workgroups menu);
 *   [conversationActions] are a conversation's calls; [inlineImages] holds decoded inline images (48 MiB);
 * - [newChat] creates chats and opens each in place of the Chats stack;
 * - [search], [chatSearch], [drafts], [chatDrafts] and [inputMemories] hold what the owner typed in the UI, app-scoped;
 * - [watchWorkgroup] makes a [WorkgroupWatch], fed [foreground] (set by the process lifecycle
 *   through [setForeground]) and whether [client] is connected; the navigator keeps one per
 *   workgroup on its stacks ([workgroupWatch]), so a member's live view and Back do not reload it;
 * - on every unpair (local, Mac-sent, or a re-scan), [navigator] is reset (closing the stream,
 *   the conversations and the workgroup watches), [repository] and [chats] cleared (the chat
 *   repository clears its seen marks itself, on its consumer), the shared chat contents reset
 *   ([clearShared]), the mentions, bound-session verdicts, icons and images forgotten, and the
 *   searches, drafts and input memories emptied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeGraph(
    private val scope: CoroutineScope,
    val client: CompanionClient,
    val controller: StreamController,
    val repository: SessionTreeRepository,
    val chats: ChatRepository,
    val search: SessionSearch,
    val drafts: ComposerDrafts,
    val inputMemories: LiveInputMemories,
    val mentions: MentionResolver,
    val chatSearch: ChatSearch,
    val chatIcons: ChatIconCache<ImageBitmap>,
    val boundSessions: BoundSessionProbe,
    val inlineImages: ChatIconCache<ImageBitmap>,
    val chatDrafts: ChatDrafts,
    private val contentContext: CoroutineContext,
    private val sharingStopMillis: Long = LIST_SHARING_STOP_MILLIS,
    private val workgroupLog: (String) -> Unit = {},
) {
    private val foregroundFlow = MutableStateFlow(true)

    /** Whether the app is in the foreground (the process lifecycle's `ON_START`/`ON_STOP`). */
    val foreground: StateFlow<Boolean> = foregroundFlow.asStateFlow()

    /** The workgroup watches of the workgroup views on the navigator's stacks, by workgroup ID. */
    private val workgroups = ConcurrentHashMap<String, StackedWorkgroup>()

    /** The conversations on the navigator's stacks. Initialised before [navigator], whose calls use it. */
    private val openChats: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The open conversations' shared contents, by chat ID, each computed for as long as it is open. */
    private val conversationStates = ConcurrentHashMap<String, OpenConversation>()

    /** Bumped on unpair: the shared list's value from before it is never shown again. */
    private val chatListResets = MutableStateFlow(0L)

    /** The list's presenter for the epoch it was made in: kept across collectors, so rows keep their models. */
    @Volatile private var listPresenter: Pair<Long, ChatListPresenter>? = null

    val navigator = HomeNavigator(
        openStream = controller::open,
        closeStream = controller::close,
        openChat = ::openConversation,
        closeChat = ::closeConversation,
        setVisibleChat = chats::setVisible,
        startWorkgroup = ::startWorkgroup,
        stopWorkgroup = ::stopWorkgroup,
        macRevision = { macRevisionOf(client.state.value) },
        // Nothing is pushed once the pairing is gone, and the shared contents go under the
        // navigator's lock right after its reset: an open racing the unpair leaves nothing behind.
        accepting = { client.paired.value == true },
        onReset = ::clearShared,
    )

    /**
     * The list's content tagged with the reset epoch it was computed in. It stops computing
     * [sharingStopMillis] after its last collector leaves (a rotation's gap keeps it running) but
     * keeps its last value, so coming back shows it at once and then follows the repository.
     */
    private val sharedChatList: StateFlow<Pair<Long, ChatListContent>> = chatListResets
        .flatMapLatest { epoch ->
            chatListContents(chats.listSnapshot, chats.macRevision, chatSearch.query, mentions.resolutions, contentContext, presenter = presenterFor(epoch))
                .map { epoch to it }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(sharingStopMillis), 0L to ChatListContent.Loading)

    /** The Chats root's content: the last one computed, unless an unpair came since (then Loading). */
    val chatList: Flow<ChatListContent> = combine(sharedChatList, chatListResets) { tagged, epoch -> current(tagged, epoch) }
        .distinctUntilChanged()

    /** [chatList]'s value now, for a screen's first frame. */
    fun chatListNow(): ChatListContent = current(sharedChatList.value, chatListResets.value)

    private fun current(tagged: Pair<Long, ChatListContent>, epoch: Long): ChatListContent =
        if (tagged.first == epoch) tagged.second else ChatListContent.Loading

    private fun presenterFor(epoch: Long): ChatListPresenter = synchronized(this) {
        listPresenter?.takeIf { it.first == epoch }?.second ?: ChatListPresenter().also { listPresenter = epoch to it }
    }

    /** Whether the Chats tab shows its dot ([chatsNeedAttention]). */
    val chatsAttention: StateFlow<Boolean> = chats.rows.map(::chatsNeedAttention).distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * New Chat's creator: a created chat opens in place of the Chats stack (iOS
     * `openConversation(replacingPath: true)`, `AM:2594`); cleared on unpair, so a create still out
     * then opens nothing.
     */
    val newChat = NewChatCreator(scope, chats::create) { chatID -> navigator.openConversation(chatID, replacingStack = true) }

    /** The Chats root's calls. */
    val chatListActions: ChatListActions = object : ChatListActions {
        override fun openChat(chatID: String) = navigator.openConversation(chatID)

        override fun refreshChats() = chats.refresh()

        override fun newChat() {
            newChat.dismissFailure()
            navigator.openNewChat()
        }

        override fun setChatMuted(chatID: String, muted: Boolean) = chats.setMuted(chatID, muted)

        override fun deleteChat(chatID: String) = chats.delete(chatID)

        override fun setChatQuery(query: String) = chatSearch.set(query)
    }

    /** What the home's chat destinations read and call. */
    val chatsHome: ChatsHome = object : ChatsHome {
        override val chatList get() = this@HomeGraph.chatList

        override fun chatListNow() = this@HomeGraph.chatListNow()
        override val chatQuery get() = chatSearch.query
        override val listActions get() = chatListActions
        override val aiAvailable get() = chats.aiAvailable
        override val newChatStatus get() = newChat.status

        override fun createChat(mode: NewChatMode) {
            newChat.create(mode)
        }

        override fun retryCreateChat() = newChat.retry()

        override fun dismissCreateChatFailure() = newChat.dismissFailure()

        override val icons get() = chatIcons
        override val images get() = inlineImages
        override val drafts get() = chatDrafts

        override fun conversation(chatID: String) = conversationState(chatID)

        override fun conversationActions(chatID: String) = this@HomeGraph.conversationActions(chatID)

        override suspend fun send(chatID: String, draft: MentionDraft) = this@HomeGraph.send(chatID, draft)

        override fun workgroupWatch(workgroupID: String) = this@HomeGraph.workgroupWatch(workgroupID)

        override fun currentTree(): SessionTree? = (repository.state.value as? TreeState.Loaded)?.tree
    }

    /** The app came to the foreground, or left it: tells the chats and every workgroup watch. */
    fun setForeground(foreground: Boolean) {
        foregroundFlow.value = foreground
        chats.setForeground(foreground)
    }

    /**
     * A [WorkgroupWatch] for [workgroupID]'s view, polling every [pollInterval]. For as long as
     * [scope] (the view's) lives, its foreground gate follows [foreground], its connection gate
     * whether [client] is connected, and each chat link up ([ChatRepository.linkGeneration]) is a
     * [WorkgroupWatch.linkUp]. The caller starts and stops it.
     */
    fun watchWorkgroup(scope: CoroutineScope, workgroupID: String, pollInterval: Duration = 5.seconds): WorkgroupWatch {
        val watch = WorkgroupWatch(client, scope, workgroupID, pollInterval = pollInterval, log = workgroupLog)
        watch.setForeground(foreground.value)
        watch.setConnected(client.state.value is ClientState.Connected)
        scope.launch { foreground.collect(watch::setForeground) }
        scope.launch { client.state.map { it is ClientState.Connected }.distinctUntilChanged().collect(watch::setConnected) }
        // The generation only grows, so a reconnect too fast to see as a state change is still a link up.
        val generationAtStart = chats.linkGeneration.value
        scope.launch { chats.linkGeneration.filter { it > generationAtStart }.collect { watch.linkUp() } }
        return watch
    }

    /**
     * The watch of [workgroupID]'s view while one is on the navigator's stacks, else null. The
     * view starts it while shown and stops it when it leaves ([dev.bugobliterator.itermbuddy.ui.workgroup.WorkgroupRoute]);
     * it lives until the view is popped.
     */
    fun workgroupWatch(workgroupID: String): WorkgroupWatch? = workgroups[workgroupID]?.watch

    private fun startWorkgroup(workgroupID: String) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val watch = watchWorkgroup(CoroutineScope(scope.coroutineContext + job), workgroupID)
        workgroups.put(workgroupID, StackedWorkgroup(job, watch))?.end()
    }

    private fun stopWorkgroup(workgroupID: String) {
        workgroups.remove(workgroupID)?.end()
    }

    /**
     * Opens [chatID]'s conversation (a navigator push): subscribed, gate reopened, bound session
     * probed, and its claimed workgroups' names asked for (the Workgroups menu's labels).
     */
    fun openConversation(chatID: String) {
        openChats += chatID
        // Computed from now until it closes, seen or not: coming back to it shows it at once.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val state = conversationContent(chatID).stateIn(CoroutineScope(scope.coroutineContext + job), SharingStarted.Eagerly, null)
        conversationStates.put(chatID, OpenConversation(job, state))?.job?.cancel()
        chats.open(chatID)
        mentions.reopenRetryGate()
        boundSessions.watch(chatID, boundGuid(chatID))
        noteWorkgroupScopes(chats.listSnapshot.value.list)
    }

    /**
     * Asks the Mac to name the workgroups the open conversations claim, as mentions (`@wg-…`):
     * on each open, and as the list changes (a list that arrives after the open). The resolver
     * asks only for names it does not have.
     */
    internal fun noteWorkgroupScopes(list: ChatListState) {
        val entries = (list as? ChatListState.Loaded)?.entries ?: return
        val scopes = entries.filter { it.chat.id in openChats && it.chat.orchestrationEnabled }.flatMap { it.chat.workgroupScopes }
        if (scopes.isNotEmpty()) mentions.note(scopes.map { "@$it" })
    }

    /** Closes [chatID]'s conversation (a navigator pop); its shared content goes with it. */
    fun closeConversation(chatID: String) {
        openChats -= chatID
        chats.close(chatID)
        boundSessions.unwatch(chatID)
        conversationStates.remove(chatID)?.job?.cancel()
    }

    /**
     * [chatID]'s conversation content: while the conversation is on the navigator's stacks, the
     * one shared value computed since it opened (null until the first). For a chat not open (a
     * screen outliving its pop), an unshared flow that computes only while collected and is never
     * kept.
     */
    fun conversationState(chatID: String): StateFlow<ConversationContent?> =
        conversationStates[chatID]?.state ?: conversationContent(chatID).stateIn(scope, SharingStarted.WhileSubscribed(), null)

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

    /** [chatID]'s conversation calls: navigation through [navigator], the rest through [chats]. */
    fun conversationActions(chatID: String): ConversationActions = object : ConversationActions {
        override fun back() = navigator.back()

        override fun retry() = chats.retry(chatID)

        override fun setMuted(muted: Boolean) = chats.setMuted(chatID, muted)

        override fun goToSession(target: GoToSession) = navigator.openSession(target.session, target.title)

        override fun openMention(target: LinkTarget) {
            when (target) {
                is LinkTarget.Session -> {
                    val tree = (repository.state.value as? TreeState.Loaded)?.tree
                    val shown = mentionSession(target.guid, target.name, tree)
                    navigator.openSession(shown.session, shown.title)
                }
                is LinkTarget.Workgroup -> navigator.openWorkgroup(target.workgroupID, target.name)
                // The text itself opens a URL (a Compose link annotation); nothing reaches here.
                is LinkTarget.Url -> Unit
            }
        }

        override fun openWorkgroup(workgroup: WorkgroupLink) = navigator.openWorkgroup(workgroup.workgroupID, workgroup.label)

        override fun retrySend(uniqueID: String) {
            // App-scoped: leaving the screen does not cut the send short. Its outcome shows in the transcript.
            scope.launch { chats.retrySend(chatID, uniqueID) }
        }

        override fun deleteUnsent(uniqueID: String) = chats.discardUnsent(chatID, uniqueID)

        override suspend fun deleteFromHere(uniqueID: String): DeleteFromOutcome = chats.deleteFrom(chatID, uniqueID)
    }

    /** Sends [draft] to [chatID] ([ChatRepository.send]): the composer's Send. */
    suspend fun send(chatID: String, draft: MentionDraft): SendOutcome = chats.send(chatID, draft)

    /**
     * Unpair, under the navigator's lock at the end of [HomeNavigator.reset]: [chatList] shows
     * Loading until the next pairing's list, and every shared conversation content and any
     * workgroup watch left are dropped.
     */
    fun clearShared() {
        chatListResets.value++
        listPresenter = null
        for (chatID in conversationStates.keys.toList()) conversationStates.remove(chatID)?.job?.cancel()
        openChats.clear()
        for (workgroupID in workgroups.keys.toList()) stopWorkgroup(workgroupID)
    }

    private fun boundGuid(chatID: String): String? = boundGuid(chats.listSnapshot.value.list, chatID)

    override fun toString(): String = "HomeGraph(navigator=$navigator, workgroups=${workgroups.size}, conversations=${conversationStates.size})"
}

/** An open conversation's shared content and the job computing it. */
private class OpenConversation(val job: Job, val state: StateFlow<ConversationContent?>)

/** A workgroup view's watch and the job its coroutines run under. */
private class StackedWorkgroup(val job: Job, val watch: WorkgroupWatch) {
    fun end() {
        watch.stop()
        job.cancel()
    }
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
 * @param listSharingStopMillis how long the chat list and a conversation's content keep computing
 *   after their last collector leaves (a rotation's gap).
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
    listSharingStopMillis: Long = LIST_SHARING_STOP_MILLIS,
): HomeGraph {
    val sink = ForwardingStreamSink()
    val chatSink = ForwardingChatSink()
    val client = newClient(sink, chatSink)
    val controller = newController(client)
    sink.target = controller
    val chats = newChats(client)
    chatSink.target = chats.sink
    val repository = newRepository(client)
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
    // A new link probes the open conversations' bound sessions again.
    scope.launch { chats.linkGeneration.drop(1).collect { boundSessions.linkUp() } }
    val graph = HomeGraph(
        scope, client, controller, repository, chats, search, drafts, inputMemories, mentions, chatSearch, chatIcons,
        boundSessions, inlineImages, chatDrafts, listContext, listSharingStopMillis, chatLog,
    )
    val navigator = graph.navigator
    // A binding that changes in the list (or first appears there) is probed once; the open
    // conversations' claimed workgroups are named once the list carries them.
    scope.launch {
        chats.listSnapshot.map { it.list }.distinctUntilChanged().collect { list ->
            boundSessions.refreshBindings { chatID -> boundGuid(list, chatID) }
            graph.noteWorkgroupScopes(list)
        }
    }
    scope.launch { client.state.map(::macRevisionOf).distinctUntilChanged().collect(navigator::macRevisionChanged) }
    // One ordered feed: the default tab from the first link's hello, and AI turning off leaves
    // the Chats root (counted, so conflated flips are not lost).
    scope.launch { chats.availability.collect(navigator::availabilityChanged) }
    clearOnUnpair(
        client.paired,
        scope,
        // Not the chat seen store: ChatRepository.clear() clears it on its consumer, after its
        // barrier against inputs from earlier links, so no mark still queued lands after the wipe.
        listOf(
            // The navigator's reset also clears the graph's shared chat contents, under its lock.
            navigator::reset, graph.newChat::clear, repository::clear, chats::clear, mentions::clear, boundSessions::clear,
            chatIcons::clear, inlineImages::clear, search::clear, chatSearch::clear, drafts::clear, chatDrafts::clear,
            inputMemories::clear,
        ),
        log,
    )
    return graph
}

/** The decoded inline images' shared bitmap budget (*Global Constraints*). */
private const val INLINE_IMAGE_BUDGET_BYTES = 48 * 1024 * 1024

/** How long [HomeGraph.chatList] keeps computing after its last collector (rotation) leaves. */
const val LIST_SHARING_STOP_MILLIS = 5_000L

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
