package dev.bugobliterator.itermbuddy

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.sessions.clearOnUnpair
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.ui.SessionNavigator
import dev.bugobliterator.itermbuddy.ui.live.ComposerDrafts
import dev.bugobliterator.itermbuddy.ui.live.LiveInputMemories
import dev.bugobliterator.itermbuddy.ui.sessions.SessionSearch
import kotlinx.coroutines.CoroutineScope

/**
 * The app-scoped sessions objects, as [wireSessions] connects them:
 *
 * - [client] sends media and stream-scoped control to [controller] (its stream sink), and the
 *   controller sends through the client (its [StreamLink]);
 * - [repository] reads the client's state, events and requests;
 * - [navigator] drives [controller]'s `open`/`close`, and reads the Mac's revision from [client]'s
 *   state to choose the browser view;
 * - [search], [drafts] and [inputMemories] hold what the owner typed in the UI, app-scoped;
 * - on every unpair (local, Mac-sent, or a re-scan), [navigator] is reset (closing the stream),
 *   [repository] cleared, and the search, drafts and input memories emptied.
 */
class SessionsGraph(
    val client: CompanionClient,
    val controller: StreamController,
    val repository: SessionTreeRepository,
    val navigator: SessionNavigator,
    val search: SessionSearch,
    val drafts: ComposerDrafts,
    val inputMemories: LiveInputMemories,
)

/**
 * Builds the sessions graph: `CompanionApp` with production parts, tests with the fake relay,
 * fake Mac and a fake video output, so both run the same wiring.
 *
 * The client and the controller need each other; the client is built with a sink that forwards
 * to the controller, set before this returns. Nothing reaches the sink before the client's
 * `start` or `pair`, which callers make only after this returns.
 *
 * @param log for a failed unpair cleanup.
 */
fun wireSessions(
    scope: CoroutineScope,
    newClient: (StreamSink) -> CompanionClient,
    newController: (StreamLink) -> StreamController,
    newRepository: (CompanionClient) -> SessionTreeRepository,
    log: (String) -> Unit = {},
): SessionsGraph {
    val sink = ForwardingStreamSink()
    val client = newClient(sink)
    val controller = newController(client)
    sink.target = controller
    val repository = newRepository(client)
    val navigator = SessionNavigator(open = controller::open, close = controller::close, macRevision = { macRevisionOf(client.state.value) })
    val search = SessionSearch()
    val drafts = ComposerDrafts()
    val inputMemories = LiveInputMemories()
    clearOnUnpair(
        client.paired,
        scope,
        listOf(navigator::reset, repository::clear, search::clear, drafts::clear, inputMemories::clear),
        log,
    )
    return SessionsGraph(client, controller, repository, navigator, search, drafts, inputMemories)
}

/** The client's stream sink before the controller exists; forwards every input once it does. */
private class ForwardingStreamSink : StreamSink {
    @Volatile var target: StreamSink? = null

    override suspend fun accept(input: StreamInput) {
        // Set by wireSessions before anything can start the client: a null here is a wiring bug.
        checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
    }
}

/** The Mac's hello revision in [state]; 0 when not connected. */
fun macRevisionOf(state: ClientState): Int = (state as? ClientState.Connected)?.macRevision ?: 0
