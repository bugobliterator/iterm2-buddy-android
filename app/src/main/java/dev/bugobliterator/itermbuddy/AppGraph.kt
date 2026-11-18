package dev.bugobliterator.itermbuddy

import androidx.compose.ui.graphics.ImageBitmap
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.chat.ChatInput
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.ChatSink
import dev.bugobliterator.itermbuddy.core.chat.MentionResolver
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.sessions.clearOnUnpair
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.ui.SessionNavigator
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.chats.ChatSearch
import dev.bugobliterator.itermbuddy.ui.chats.ImageBytesDecoder
import dev.bugobliterator.itermbuddy.ui.chats.noteSnippetMentions
import dev.bugobliterator.itermbuddy.ui.live.ComposerDrafts
import dev.bugobliterator.itermbuddy.ui.live.LiveInputMemories
import dev.bugobliterator.itermbuddy.ui.sessions.SessionSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

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
 *   it on each list change, and again after its retry gate reopens on each link up and when AI
 *   comes back on ([noteSnippetMentions]);
 * - [chatIcons] holds the chat list's decoded icons;
 * - [search], [chatSearch], [drafts] and [inputMemories] hold what the owner typed in the UI, app-scoped;
 * - on every unpair (local, Mac-sent, or a re-scan), [navigator] is reset (closing the stream),
 *   [repository] and [chats] cleared (the chat repository clears its seen marks itself, on its
 *   consumer), the mentions and icons forgotten, and the searches, drafts and input memories emptied.
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
)

/**
 * Builds the home graph: `CompanionApp` with production parts, tests with the fake relay, fake
 * Mac and a fake video output, so both run the same wiring.
 *
 * The client needs its sinks and the controller and the chat repository need the client; the
 * client is built with sinks that forward to them, set before this returns. Nothing reaches a
 * sink before the client's `start` or `pair`, which callers make only after this returns.
 *
 * @param iconDecoder decodes chat icons (the app passes `BitmapImageDecoder`; tests decode nothing).
 * @param log for a failed unpair cleanup.
 * @param chatLog the mention resolver's diagnostics (counts and failure kinds only).
 */
fun wireHome(
    scope: CoroutineScope,
    newClient: (StreamSink, ChatSink) -> CompanionClient,
    newController: (StreamLink) -> StreamController,
    newRepository: (CompanionClient) -> SessionTreeRepository,
    newChats: (StreamLink) -> ChatRepository,
    iconDecoder: ImageBytesDecoder<ImageBitmap> = ImageBytesDecoder { null },
    log: (String) -> Unit = {},
    chatLog: (String) -> Unit = {},
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
    // A link up (the client turning Connected) or AI coming back on reopens the resolver's retry
    // gate (AM:2349-2351). A reconnect too fast for the state flow to show may be missed: its
    // misses then stay raw until the next trigger, never wrong.
    val linkUps = client.state.map { it is ClientState.Connected }.distinctUntilChanged().filter { it }
    val aiBackOn = chats.aiAvailable.drop(1).filter { it }
    scope.launch {
        noteSnippetMentions(chats.rows, merge(linkUps, aiBackOn).map { }, mentions::reopenRetryGate) { mentions.note(it) }
    }
    clearOnUnpair(
        client.paired,
        scope,
        // Not the chat seen store: ChatRepository.clear() clears it on its consumer, after its
        // barrier against inputs from earlier links, so no mark still queued lands after the wipe.
        listOf(
            navigator::reset, repository::clear, chats::clear, mentions::clear, chatIcons::clear, search::clear,
            chatSearch::clear, drafts::clear, inputMemories::clear,
        ),
        log,
    )
    return HomeGraph(client, controller, repository, chats, navigator, search, drafts, inputMemories, mentions, chatSearch, chatIcons)
}

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
