package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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
 * the end of the previous request. Backgrounded, polling pauses and resumes with one request at
 * once. [setConnected] is only a gate: down, polling pauses; up again, the poll timer restarts. The
 * new link itself is [linkUp] (the chat link's generation), which asks at once: if the gates are shut
 * it asks as soon as they open, and if a request from the old link is still out it asks again as
 * soon as that one ends, so the answer shown is the new link's. [start] and [refresh] ask whatever
 * the gates say (a refresh while disconnected fails fast and shows as such).
 *
 * At most one request is out at a time; anything that wants one while it is out waits for it (only a
 * link up asks again afterwards; a refresh still shows the list refreshing). A failure keeps the last [WorkgroupState.Shown] marked `stale`, or
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

    /** A link came up that no request has been sent on yet: ask when the gates and [inFlight] allow. */
    private var linkUpPending = false

    /** Test seam: runs in the poll timer after its wait, before it takes the lock. */
    internal var beforeTimerLock: () -> Unit = {}

    /** Bumped by [stop], so an answer from before it is dropped. */
    private var generation = 0L

    /** The view appeared: asks at once and polls from then on. Again after [stop], a fresh start. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            gone = false
            askLocked()
        }
    }

    /** The view left: no more requests, and an answer still out is dropped. The last state is kept. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            generation++
            linkUpPending = false
            pollWait?.cancel()
            pollWait = null
            inFlight?.cancel()
            inFlight = null
            val shown = stateFlow.value
            if (shown is WorkgroupState.Shown && shown.refreshing) stateFlow.value = shown.copy(refreshing = false)
        }
    }

    /**
     * Pull to refresh, or Try again: asks now. With a request already out, no second one is sent,
     * but a shown list is marked refreshing until that answer lands.
     */
    fun refresh() {
        synchronized(lock) {
            if (!started || gone) return
            val now = stateFlow.value
            if (inFlight != null) {
                if (now is WorkgroupState.Shown) stateFlow.value = now.copy(refreshing = true)
                return
            }
            stateFlow.value = when (now) {
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
     * Whether the client is connected: a gate only. Polling pauses while it is down; when it is up
     * again, a [linkUp] that came meanwhile asks at once, else the poll timer restarts.
     */
    fun setConnected(connected: Boolean) {
        synchronized(lock) {
            val reopened = connected && !this.connected
            this.connected = connected
            when {
                !connected -> cancelPollLocked()
                !reopened || !pollingLocked() || inFlight != null -> Unit
                linkUpPending -> askLocked()
                else -> schedulePollLocked()
            }
        }
    }

    /**
     * A new link is up: asks at once, or as soon as the gates open, or right after a request still
     * out on the old link ends.
     */
    fun linkUp() {
        synchronized(lock) {
            if (!started || gone) return
            linkUpPending = true
            if (pollingLocked() && inFlight == null) askLocked()
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
        linkUpPending = false
        // A failure on screen gives way to Loading while it is asked again.
        if (stateFlow.value is WorkgroupState.Failed) stateFlow.value = WorkgroupState.Loading
        val sentIn = generation
        // Started lazily so it is registered before it can finish.
        val job = scope.launch(start = CoroutineStart.LAZY) { ask(sentIn) }
        inFlight = job
        // A job that never runs (the scope is already cancelled) or is cancelled must not stay "in flight".
        job.invokeOnCompletion { synchronized(lock) { if (inFlight === job) inFlight = null } }
        job.start()
    }

    private fun schedulePollLocked() {
        cancelPollLocked()
        if (!pollingLocked()) return
        val sentIn = generation
        // Started lazily so it is registered before it can run.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(pollInterval)
            beforeTimerLock()
            synchronized(lock) {
                // Cancelled or replaced while it waited for the lock (backgrounded, disconnected, stopped,
                // or another timer started): it must neither ask nor start a second loop.
                if (!isActive || pollWait !== coroutineContext.job || generation != sentIn || !pollingLocked()) return@launch
                pollWait = null
                askLocked()
            }
        }
        pollWait = job
        job.start()
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
                    linkUpPending = false
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
            if (linkUpPending && pollingLocked()) {
                askLocked()
                return
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
