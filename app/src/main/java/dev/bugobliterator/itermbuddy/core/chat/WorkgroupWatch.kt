package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why the workgroup view shows no members. The screen shows canned text for each, never the Mac's. */
enum class WorkgroupFailure {
    /** The Mac answered `unknownSession`: the workgroup is gone (`HB:2165-2170`). */
    GONE,

    /** No answer: a timeout, a lost or absent link, another error or an unexpected reply. */
    UNREACHABLE,
}

/** What the workgroup view shows. Every [toString] shows IDs, flags and counts only. */
sealed interface WorkgroupState {
    data object Loading : WorkgroupState

    /**
     * The members as last read. [refreshing] while an owner's refresh is out; [stale] when the
     * latest request failed and [info] is from an earlier answer.
     */
    data class Shown(val info: WorkgroupInfo, val refreshing: Boolean, val stale: Boolean) : WorkgroupState

    /** Nothing to show: [retryable] offers Try again ([WorkgroupWatch.refresh]); a [WorkgroupFailure.GONE] does not. */
    data class Failed(val reason: WorkgroupFailure, val retryable: Boolean) : WorkgroupState
}

/**
 * One workgroup's member list, kept fresh while its view is on screen.
 *
 * iOS loads the list when the view appears and on pull to refresh (`IOS/Screens/WorkgroupView.swift:42-46,
 * 59-61, 135-145`); the Mac has no workgroup push. Android adds polling (owner decision): while
 * [start]ed, in the foreground ([setForeground]) and connected ([setConnected]), it asks with
 * `fetchWorkgroupInfo` (`HB:2164-2200`; not AI-gated, `HB:2312`) every [pollInterval], counted from
 * the end of the previous request. Backgrounded or disconnected, polling pauses; it resumes with one
 * request at once. A link coming back ([setConnected] true after false) asks at once as well; if a
 * request from the old link is still out, it asks again as soon as that one ends, so the answer shown
 * is the new link's. [start] and [refresh] ask whatever the gates say (a refresh while disconnected
 * fails fast and shows as such).
 *
 * At most one request is out at a time; anything that wants one while it is out waits for it (only a
 * link up asks again afterwards). A failure keeps the last [WorkgroupState.Shown] marked `stale`, or
 * shows [WorkgroupFailure.UNREACHABLE] when nothing was ever read; polling goes on. The Mac's
 * `unknownSession` error ("That workgroup no longer exists.") shows [WorkgroupFailure.GONE] and ends
 * every request until the next [start] (a new appearance of the view).
 *
 * Requests run in their own coroutines in [scope] (never on the chat repository's consumer), each
 * bounded by [timeout] (iOS's 15 s, `AM:4287`). Thread-safe. [log] gets the workgroup ID and outcome
 * kinds only, never names, statuses or the Mac's text.
 */
class WorkgroupWatch(
    private val link: StreamLink,
    private val scope: CoroutineScope,
    val workgroupID: String,
    private val pollInterval: Duration = 5.seconds,
    private val timeout: Duration = 15.seconds,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<WorkgroupState>(WorkgroupState.Loading)

    val state: StateFlow<WorkgroupState> = stateFlow.asStateFlow()

    private var started = false
    private var foreground = true
    private var connected = true
    private var gone = false

    /** The request out, if any. */
    private var inFlight: Job? = null

    /** The wait for the next poll, if any. */
    private var pollWait: Job? = null

    /** A link came up while [inFlight] was out on the old one: ask again when it ends. */
    private var askAgain = false

    /** Bumped by [stop], so an answer from before it is dropped. */
    private var generation = 0L

    /** The view appeared: asks at once and polls from then on. Again after [stop], a fresh start. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            gone = false
            if (stateFlow.value is WorkgroupState.Failed) stateFlow.value = WorkgroupState.Loading
            askLocked()
        }
    }

    /** The view left: no more requests, and an answer still out is dropped. The last state is kept. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            generation++
            askAgain = false
            pollWait?.cancel()
            pollWait = null
            inFlight?.cancel()
            inFlight = null
            val shown = stateFlow.value
            if (shown is WorkgroupState.Shown && shown.refreshing) stateFlow.value = shown.copy(refreshing = false)
        }
    }

    /** Pull to refresh, or Try again: asks now unless a request is already out. */
    fun refresh() {
        synchronized(lock) {
            if (!started || gone || inFlight != null) return
            stateFlow.value = when (val now = stateFlow.value) {
                is WorkgroupState.Shown -> now.copy(refreshing = true)
                else -> WorkgroupState.Loading
            }
            askLocked()
        }
    }

    /** Whether the app is in the foreground: polling pauses in the background and resumes with one request. */
    fun setForeground(foreground: Boolean) {
        synchronized(lock) {
            val resumed = foreground && !this.foreground
            this.foreground = foreground
            if (!foreground) cancelPollLocked() else if (resumed && pollingLocked()) askLocked()
        }
    }

    /**
     * Whether the link to the Mac is up. Polling pauses while it is down; a change to up is a new
     * link, asked at once (or right after a request still out on the old one).
     */
    fun setConnected(connected: Boolean) {
        synchronized(lock) {
            val linkUp = connected && !this.connected
            this.connected = connected
            if (!connected) {
                cancelPollLocked()
            } else if (linkUp && pollingLocked()) {
                if (inFlight != null) askAgain = true else askLocked()
            }
        }
    }

    private fun pollingLocked(): Boolean = started && foreground && connected && !gone

    private fun cancelPollLocked() {
        pollWait?.cancel()
        pollWait = null
    }

    private fun askLocked() {
        if (!started || gone || inFlight != null) return
        cancelPollLocked()
        val sentIn = generation
        // Started lazily so it is registered before it can finish.
        val job = scope.launch(start = CoroutineStart.LAZY) { ask(sentIn) }
        inFlight = job
        job.start()
    }

    private fun schedulePollLocked() {
        cancelPollLocked()
        if (!pollingLocked()) return
        val sentIn = generation
        pollWait = scope.launch {
            delay(pollInterval)
            synchronized(lock) {
                if (generation != sentIn) return@launch
                pollWait = null
                askLocked()
            }
        }
    }

    private suspend fun ask(sentIn: Long) {
        val outcome: Outcome = try {
            when (val reply = link.request(PhoneMessage.FetchWorkgroupInfo(workgroupID), timeout = timeout)) {
                is MacMessage.WorkgroupInfoMessage -> Outcome.Read(reply.info)
                is MacMessage.Error -> if (reply.code == UNKNOWN_SESSION_CODE) Outcome.Gone else Outcome.Failed("error ${reply.code}")
                else -> Outcome.Failed("reply ${reply::class.simpleName}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(e::class.simpleName ?: "exception")
        }
        synchronized(lock) {
            if (generation != sentIn) return
            inFlight = null
            when (outcome) {
                is Outcome.Read -> {
                    log("Workgroup $workgroupID: ${outcome.info.members.size} member(s)")
                    stateFlow.value = WorkgroupState.Shown(outcome.info, refreshing = false, stale = false)
                }
                Outcome.Gone -> {
                    log("Workgroup $workgroupID is gone")
                    gone = true
                    askAgain = false
                    cancelPollLocked()
                    stateFlow.value = WorkgroupState.Failed(WorkgroupFailure.GONE, retryable = false)
                    return
                }
                is Outcome.Failed -> {
                    log("Workgroup $workgroupID info failed: ${outcome.kind}")
                    stateFlow.value = when (val now = stateFlow.value) {
                        is WorkgroupState.Shown -> now.copy(refreshing = false, stale = true)
                        else -> WorkgroupState.Failed(WorkgroupFailure.UNREACHABLE, retryable = true)
                    }
                }
            }
            if (askAgain) {
                askAgain = false
                if (pollingLocked()) {
                    askLocked()
                    return
                }
            }
            schedulePollLocked()
        }
    }

    override fun toString(): String = synchronized(lock) {
        "WorkgroupWatch(workgroupID=$workgroupID, started=$started, foreground=$foreground, connected=$connected, " +
            "gone=$gone, asking=${inFlight != null}, state=${stateFlow.value})"
    }

    private sealed interface Outcome {
        class Read(val info: WorkgroupInfo) : Outcome

        data object Gone : Outcome

        /** [kind] names the failure: an error code, a reply case or an exception class, never text. */
        class Failed(val kind: String) : Outcome
    }
}

/** The Mac's error code for a workgroup it cannot find (`HB:2167`, `CompanionError.Code.unknownSession`). */
private const val UNKNOWN_SESSION_CODE = "unknownSession"
