package dev.bugobliterator.itermbuddy.core.sessions

import android.util.Log
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the session list shows. [toString] never carries names: [SessionTree] redacts them. */
sealed interface TreeState {
    /** No tree yet (or it was dropped by [SessionTreeRepository.clear]). */
    data object Loading : TreeState

    /** [tree] is the newest tree received; [refreshing] while a fetch for a newer one is out. */
    data class Loaded(val tree: SessionTree, val refreshing: Boolean) : TreeState

    /**
     * The fetch failed with no tree to show. [retryable] when the link was up as it failed (a
     * timeout or an error reply), so "Try again" can help; false when not connected, where the
     * next connect fetches by itself and the home banner says why.
     */
    data class Failed(val retryable: Boolean) : TreeState
}

/**
 * The Mac's session tree for the home list (iOS `AppModel.refreshSessionBrowser` and the
 * `sessionTree` push handling, `AM:2409-2414`, `AM:4456-4466`).
 *
 * It fetches (`fetchSessionTree`, bounded by [fetchTimeout], 15 s as iOS `AM:1384`) on the first
 * [ClientState.Connected], again on each later one (a reconnect), and on [refresh]; and it
 * replaces the tree with every unsolicited `sessionTree` from [events], with no refetch. A fetch
 * supersedes one still out. A failed fetch keeps a tree already shown.
 *
 * **Order.** A push (through [events]) and a fetch reply (through the request) reach this class
 * on different coroutines and can be applied in either order, while the Mac drops repeats of the
 * tree it last pushed, so a stale tree applied last could stay up indefinitely. Every tree
 * carries its [MacMessage.SessionTreeMessage.arrival], stamped in wire order by the session's
 * receive loop; a tree no newer than the newest one applied is ignored.
 *
 * **Clear.** [clear] (on unpair) drops the tree, discards a fetch still out, and ignores every
 * tree received before it, so nothing of the old pairing shows again.
 *
 * Thread-safe: every state change is made under one lock.
 *
 * @param events where pushes come from: the client's `events`; tests slow it down to apply a
 *   push after a reply.
 * @param log diagnostics: counts and outcomes, never names or titles.
 */
class SessionTreeRepository(
    private val client: CompanionClient,
    private val scope: CoroutineScope,
    private val fetchTimeout: Duration = DEFAULT_FETCH_TIMEOUT,
    events: Flow<MacMessage> = client.events,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<TreeState>(TreeState.Loading)

    val state: StateFlow<TreeState> = stateFlow.asStateFlow()

    // Guarded by lock.
    /** The arrival of the newest tree applied, or the floor [clear] set; older trees are ignored. */
    private var newestArrival = 0L
    private var fetchJob: Job? = null

    /** Numbers fetches so a superseded one's outcome is told apart from the current one's. */
    private var fetchCount = 0L
    private var currentFetch: Long? = null

    /** Fetches numbered up to this were out when [clear] ran: their replies are discarded. */
    private var clearedFetches = 0L

    init {
        scope.launch {
            events.collect { message -> if (message is MacMessage.SessionTreeMessage) apply(message, fetch = null) }
        }
        scope.launch {
            client.state.map { it is ClientState.Connected }.distinctUntilChanged().collect { connected -> if (connected) fetch() }
        }
    }

    /** Fetches the tree again (pull to refresh, Try again), keeping a tree already shown. */
    fun refresh() = fetch()

    /** Back to [TreeState.Loading]: drops the tree and a fetch still out, and ignores every tree received so far. */
    fun clear() {
        synchronized(lock) {
            fetchJob?.cancel()
            fetchJob = null
            currentFetch = null
            clearedFetches = fetchCount
            newestArrival = maxOf(newestArrival, client.newestArrival)
            stateFlow.value = TreeState.Loading
        }
    }

    private fun fetch() {
        synchronized(lock) {
            fetchJob?.cancel()
            val id = ++fetchCount
            currentFetch = id
            stateFlow.value = when (val now = stateFlow.value) {
                is TreeState.Loaded -> now.copy(refreshing = true)
                else -> TreeState.Loading
            }
            fetchJob = scope.launch { runFetch(id) }
        }
    }

    private suspend fun runFetch(id: Long) {
        val reply = try {
            client.request(PhoneMessage.FetchSessionTree, timeout = fetchTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("fetching the session tree failed: ${e.javaClass.simpleName}")
            null
        }
        if (reply is MacMessage.SessionTreeMessage) {
            apply(reply, fetch = id)
            return
        }
        if (reply != null) log("fetchSessionTree was answered with ${reply.javaClass.simpleName}")
        failed(id)
    }

    /** Applies [message] if it is newer than everything applied; [fetch] is the fetch it answers, if any. */
    private fun apply(message: MacMessage.SessionTreeMessage, fetch: Long?) {
        synchronized(lock) {
            if (fetch != null && fetch <= clearedFetches) return
            val answersCurrent = fetch != null && fetch == currentFetch
            if (answersCurrent) currentFetch = null
            if (message.arrival <= newestArrival) {
                log("ignored a session tree older than the one shown (${message.arrival} <= $newestArrival)")
                // The current fetch is over even though its tree was stale: stop the spinner.
                val now = stateFlow.value
                if (answersCurrent && now is TreeState.Loaded) stateFlow.value = now.copy(refreshing = false)
                return
            }
            newestArrival = message.arrival
            stateFlow.value = TreeState.Loaded(message.tree, refreshing = currentFetch != null)
        }
    }

    private fun failed(fetch: Long) {
        synchronized(lock) {
            if (fetch != currentFetch) return
            currentFetch = null
            stateFlow.value = when (val now = stateFlow.value) {
                is TreeState.Loaded -> now.copy(refreshing = false)
                else -> TreeState.Failed(retryable = client.state.value is ClientState.Connected)
            }
        }
    }

    private companion object {
        const val TAG = "SessionTreeRepository"
        val DEFAULT_FETCH_TIMEOUT = 15.seconds
    }
}
