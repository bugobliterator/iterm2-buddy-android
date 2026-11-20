package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long the viewport must hold still before [AutoFitter] fits to it: one resize per rotation. */
val FIT_SETTLE_DELAY: Duration = 400.milliseconds

/** How long [AutoFitter] waits for the Mac's config to show a resize before it says the Mac did not resize. */
val FIT_RESULT_TIMEOUT: Duration = 4.seconds

/** Shown when the toggle is on and the Mac reports the session cannot resize (`canResize` false or absent). */
const val FIT_NOTICE_CANNOT_RESIZE =
    "This session can't be resized from the phone: its Mac window is full screen, maximised, attached to a screen edge or locked to its width."

/** Shown when a fit was sent and the Mac's grid did not change within [FIT_RESULT_TIMEOUT]. */
const val FIT_NOTICE_NO_CHANGE =
    "The Mac didn't resize this session. Its window may be full screen, locked to its size, or unable to grow on the Mac's screen."

/** The live canvas a session's view measured for Fit to phone ([fitGrid]'s viewport, keyboard hidden). */
data class FitViewport(val sessionGuid: String, val widthPx: Int, val heightPx: Int)

/** A brief message for the view of [sessionGuid]. */
data class FitNotice(val sessionGuid: String, val text: String)

/** The fewest columns iTerm2 sets (`safelySetSessionSize:rows:columns:` raises narrower widths to 20). */
const val MAC_MIN_COLUMNS = 20

/** At most this many fits are sent for one viewport on one stream (each may add one corrective step). */
const val MAX_FITS_PER_VIEWPORT = 2

/**
 * **Fit to phone** while its toggle ([enabled], [FitToPhoneSetting]) is on: keeps the live
 * terminal session's grid fitted to the phone ([fitGrid]), sending `resizeSession` on [link] for
 * the active stream's link only. Turning the toggle off never undoes a resize.
 *
 * **When it fits.** While on, a live view open ([setViewport]: the view of the active stream's
 * session has measured its canvas) and the active stream's config known, it computes [fitGrid] and
 * sends it when the Mac's grid (the config's columns and rows) differs. It does so as the view opens
 * or the stream goes live (a new stream, a reconnect, another session), when the toggle turns on,
 * on [refit], and when the viewport changes enough to change the grid (a rotation, split screen, a
 * foldable). A viewport change counts only once the viewport has held still for [settleDelay], so a
 * rotation's layout passes send one resize. The keyboard never resizes: the view measures its
 * canvas with the keyboard hidden (and without the Mac alert banner).
 *
 * **Bounded.** The target comes from the viewport, the decoder's limits and the cell size frozen at
 * the stream's first evaluation: configs that arrive later (during a fit, or after a font size
 * change on the Mac) never move the target, so the Mac's own changes are not followed. For one
 * stream it acts on a target once (a later Mac-side resize is not fought), and it sends at most
 * [MAX_FITS_PER_VIEWPORT] fits per viewport per stream, each with at most one corrective step.
 *
 * **Never.** Not for a browser config; not while `canResize` is not true (a full-screen,
 * maximised or width-locked window, or a Mac too old to say): then [notices] gets
 * [FIT_NOTICE_CANNOT_RESIZE] once per session while on (not for the session shown when the toggle
 * turns on: the toggle's own hint says it). Not when the Mac's grid already equals the target, or
 * equals what an earlier fit of this session to the same target ended at (no loop with a Mac that
 * caps the grid). Those remembered outcomes are forgotten whenever the toggle turns on or off
 * (unpair turns it off) and, for its session, on [refit].
 *
 * **Results.** After a send it watches the configs. A config is the fit's result only when its
 * columns are the requested ones (at least [MAC_MIN_COLUMNS]: iTerm2 takes columns as asked and caps
 * only rows) and the grid has moved since the send; anything else (an earlier fit's late config, an
 * intermediate grid, a resize from the Mac or another phone) is not, and it keeps waiting. On a
 * result it logs the requested and resulting sizes (also when that config says `canResize` false)
 * and, after a first fit whose rows came back more than [FIT_CORRECTION_MIN_SHORTFALL] short, sends
 * one corrective resize ([correctedFitGrid]: fewer columns, the same rows, so the session fills the
 * phone with bigger text). Without a result within [resultTimeout] it logs the grid it ended at and
 * keeps that; when the grid never moved, [notices] gets [FIT_NOTICE_NO_CHANGE] (after a first step
 * only). The stream ending, another session or link, or the view leaving drops a waiting fit and
 * its timer.
 *
 * **Threading.** Every input goes through one channel to one consumer coroutine in [scope]; all
 * state is touched only there. [setViewport], [clearViewport] and [refit] are safe from any thread.
 * [log] gets session GUIDs and grid sizes only.
 */
@OptIn(FlowPreview::class)
class AutoFitter(
    private val link: StreamLink,
    enabled: StateFlow<Boolean>,
    active: StateFlow<ActiveStream?>,
    private val decoderMax: () -> Pair<Int, Int>?,
    private val sizeSupported: ((Int, Int) -> Boolean)?,
    private val scope: CoroutineScope,
    settleDelay: Duration = FIT_SETTLE_DELAY,
    private val resultTimeout: Duration = FIT_RESULT_TIMEOUT,
    private val log: (String) -> Unit = {},
) {
    private val viewportFlow = MutableStateFlow<FitViewport?>(null)

    private val noticesFlow = MutableSharedFlow<FitNotice>(extraBufferCapacity = NOTICE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Brief messages for the live view (a snackbar); lost when no view collects them. */
    val notices: SharedFlow<FitNotice> = noticesFlow.asSharedFlow()

    private sealed interface Event {
        data class Inputs(val enabled: Boolean, val stream: StreamView?, val viewport: FitViewport?) : Event

        data class Timeout(val pendingId: Long) : Event

        data class Refit(val sessionGuid: String) : Event
    }

    /** What of [ActiveStream] matters here (not the live top, which moves with every frame). */
    private data class StreamView(val linkId: Long, val streamID: Long, val sessionGuid: String, val config: StreamConfig?)

    private data class StreamKey(val linkId: Long, val streamID: Long)

    /** A resize sent, waiting for the Mac's config to show it. [moved]: a config with another grid than [before] came since. */
    private data class Pending(
        val id: Long,
        val linkId: Long,
        val sessionGuid: String,
        val target: Grid,
        val requested: Grid,
        val before: Grid,
        val viewport: FitViewport,
        val cell: CellGeometry,
        val corrective: Boolean,
        val moved: Boolean = false,
    )

    /** The last fit of a session: to [target], it ended at [result]. */
    private data class Outcome(val target: Grid, val result: Grid)

    private data class Handled(val key: StreamKey, val target: Grid)

    private val events = Channel<Event>(Channel.UNLIMITED)

    // Consumer-only state.
    private var enabledNow = false
    private var stream: StreamView? = null
    private var viewport: FitViewport? = null
    private var handled: Handled? = null
    private var pending: Pending? = null
    private var pendingTimer: Job? = null
    private var nextPendingId = 0L
    private val outcomes = HashMap<String, Outcome>()
    private val noticedCannotResize = HashSet<String>()

    /** The stream the frozen cell and the send counts belong to. */
    private var streamKey: StreamKey? = null
    private var frozenCell: CellGeometry? = null
    private val fitsPerViewport = HashMap<Pair<Int, Int>, Int>()

    init {
        val streams = active.map { it?.let { s -> StreamView(s.linkId, s.streamID, s.sessionGuid, s.config) } }.distinctUntilChanged()
        // A cleared viewport (the view left) applies at once; a measured one once it holds still.
        val settled = viewportFlow.debounce { if (it == null) Duration.ZERO else settleDelay }.distinctUntilChanged()
        scope.launch {
            combine(enabled, streams, settled) { on, s, v -> Event.Inputs(on, s, v) }.collect { events.send(it) }
        }
        scope.launch {
            for (event in events) {
                when (event) {
                    is Event.Inputs -> onInputs(event)
                    is Event.Timeout -> onTimeout(event.pendingId)
                    is Event.Refit -> onRefit(event.sessionGuid)
                }
            }
        }
    }

    /** The view of [FitViewport.sessionGuid] measured its canvas (keyboard hidden); call on every change. */
    fun setViewport(viewport: FitViewport) {
        viewportFlow.value = viewport
    }

    /** The view of [sessionGuid] left; a newer view's viewport is kept. */
    fun clearViewport(sessionGuid: String) {
        viewportFlow.compareAndSet(viewportFlow.value?.takeIf { it.sessionGuid == sessionGuid } ?: return, null)
    }

    /**
     * Fit [sessionGuid] again now (the too-large overlay's Fit): forgets its last outcome, the
     * target acted on and the send counts, then fits as if its view had just opened. Does nothing
     * while the toggle is off (turning it on fits anyway).
     */
    fun refit(sessionGuid: String) {
        events.trySend(Event.Refit(sessionGuid))
    }

    private suspend fun onInputs(inputs: Event.Inputs) {
        val turnedOn = inputs.enabled && !enabledNow
        val turnedOff = !inputs.enabled && enabledNow
        enabledNow = inputs.enabled
        stream = inputs.stream
        viewport = inputs.viewport
        // Turning on retries everything; unpair turns it off.
        if (turnedOn || turnedOff) outcomes.clear()
        if (!enabledNow) {
            // Off never undoes anything; it only stops watching.
            dropPending()
            return
        }
        if (turnedOn) {
            handled = null
            fitsPerViewport.clear()
            noticedCannotResize.clear()
            // The toggle's own hint explains a session that cannot resize; no second message.
            inputs.stream?.let { noticedCannotResize += it.sessionGuid }
        }
        evaluate()
    }

    private suspend fun onRefit(sessionGuid: String) {
        outcomes.remove(sessionGuid)
        if (!enabledNow || stream?.sessionGuid != sessionGuid) return
        log("fit to phone for $sessionGuid: refit asked")
        dropPending()
        handled = null
        fitsPerViewport.clear()
        evaluate()
    }

    private suspend fun evaluate() {
        val s = stream
        val v = viewport
        pending?.let { waiting ->
            val gone = s == null || s.linkId != waiting.linkId || s.sessionGuid != waiting.sessionGuid || v?.sessionGuid != waiting.sessionGuid
            if (gone) {
                log("fit to phone for ${waiting.sessionGuid}: dropped the waiting ${size(waiting.requested)} (the stream or view went)")
                dropPending()
            }
        }
        if (s == null || v == null || s.sessionGuid != v.sessionGuid) return
        val config = s.config ?: return
        val key = StreamKey(s.linkId, s.streamID)
        if (key != streamKey) {
            streamKey = key
            frozenCell = null
            fitsPerViewport.clear()
        }
        val current = Grid(config.columns, config.rows)
        pending?.let { waiting ->
            if (current != waiting.before && !waiting.moved) pending = waiting.copy(moved = true)
            val updated = pending!!
            if (isResult(updated, current)) {
                resolve(updated, current, config)
                return
            }
        }
        if (config.isBrowser) return
        if (config.canResize != true) {
            dropPending()
            if (noticedCannotResize.add(s.sessionGuid)) {
                log("fit to phone for ${s.sessionGuid}: the Mac cannot resize this session (canResize ${config.canResize})")
                noticesFlow.tryEmit(FitNotice(s.sessionGuid, FIT_NOTICE_CANNOT_RESIZE))
            }
            return
        }
        val cell = frozenCell ?: effectiveCellGeometry(config)?.also { frozenCell = it } ?: return
        val target = fitGrid(v.widthPx, v.heightPx, config.copy(cellGeometry = cell), decoderMax(), sizeSupported)
        pending?.let { waiting ->
            if (target == waiting.target) return // still waiting for this fit's result; the timer runs
            log("fit to phone for ${waiting.sessionGuid}: the viewport changed while ${size(waiting.requested)} was waiting")
            dropPending()
        }
        if (target == null) return
        val handledNow = Handled(key, target)
        if (handled == handledNow) return
        handled = handledNow
        if (current == target) {
            log("fit to phone for ${s.sessionGuid}: already at ${size(target)}")
            outcomes[s.sessionGuid] = Outcome(target, current)
            return
        }
        if (outcomes[s.sessionGuid] == Outcome(target, current)) {
            log("fit to phone for ${s.sessionGuid}: already at ${size(current)}, where the last fit to ${size(target)} ended")
            return
        }
        val viewportSize = v.widthPx to v.heightPx
        val sent = fitsPerViewport[viewportSize] ?: 0
        if (sent >= MAX_FITS_PER_VIEWPORT) {
            log("fit to phone for ${s.sessionGuid}: not sending ${size(target)}: $sent fits already sent for viewport ${v.widthPx}x${v.heightPx} on this stream")
            return
        }
        fitsPerViewport[viewportSize] = sent + 1
        send(s.linkId, s.sessionGuid, target, target, current, v, cell, corrective = false)
    }

    /** Whether [current] is [waiting]'s result: the requested columns (as iTerm2 sets them), after the grid moved. */
    private fun isResult(waiting: Pending, current: Grid): Boolean =
        waiting.moved && current.columns == maxOf(waiting.requested.columns, MAC_MIN_COLUMNS)

    private suspend fun resolve(waiting: Pending, achieved: Grid, config: StreamConfig) {
        val step = if (waiting.corrective) "corrective" else "fit"
        log(
            "fit to phone for ${waiting.sessionGuid}: $step requested ${size(waiting.requested)}, got ${size(achieved)} " +
                "(was ${size(waiting.before)})" + if (config.canResize == true) "" else " (canResize ${config.canResize})",
        )
        dropPending()
        if (!waiting.corrective && config.canResize == true) {
            val withCell = config.copy(cellGeometry = waiting.cell)
            val corrected = correctedFitGrid(waiting.requested, achieved, waiting.viewport.widthPx, waiting.viewport.heightPx, withCell)
            if (corrected != null) {
                send(waiting.linkId, waiting.sessionGuid, waiting.target, corrected, achieved, waiting.viewport, waiting.cell, corrective = true)
                return
            }
        }
        outcomes[waiting.sessionGuid] = Outcome(waiting.target, achieved)
    }

    private fun onTimeout(pendingId: Long) {
        val waiting = pending?.takeIf { it.id == pendingId } ?: return
        val step = if (waiting.corrective) "corrective" else "fit"
        val now = stream?.config?.let { Grid(it.columns, it.rows) } ?: waiting.before
        dropPending()
        outcomes[waiting.sessionGuid] = Outcome(waiting.target, now)
        if (!waiting.moved) {
            log("fit to phone for ${waiting.sessionGuid}: $step requested ${size(waiting.requested)}, the grid stayed ${size(waiting.before)} for $resultTimeout")
            if (!waiting.corrective) noticesFlow.tryEmit(FitNotice(waiting.sessionGuid, FIT_NOTICE_NO_CHANGE))
        } else {
            log("fit to phone for ${waiting.sessionGuid}: $step requested ${size(waiting.requested)}, no matching result in $resultTimeout; the grid is ${size(now)}")
        }
    }

    private suspend fun send(
        linkId: Long,
        guid: String,
        target: Grid,
        grid: Grid,
        before: Grid,
        viewport: FitViewport,
        cell: CellGeometry,
        corrective: Boolean,
    ) {
        val step = if (corrective) "corrective" else "fit"
        try {
            link.send(PhoneMessage.ResizeSession(guid, grid.columns, grid.rows), onLink = linkId)
        } catch (e: CompanionRequestException) {
            log("fit to phone for $guid: $step ${size(grid)} not sent: ${e.javaClass.simpleName}")
            return
        }
        log("fit to phone for $guid: $step sent ${size(grid)} (was ${size(before)}, viewport ${viewport.widthPx}x${viewport.heightPx})")
        val id = ++nextPendingId
        pending = Pending(id, linkId, guid, target, grid, before, viewport, cell, corrective)
        pendingTimer = scope.launch {
            delay(resultTimeout)
            events.send(Event.Timeout(id))
        }
    }

    private fun dropPending() {
        pending = null
        pendingTimer?.cancel()
        pendingTimer = null
    }

    private fun size(grid: Grid) = "${grid.columns}x${grid.rows}"

    private companion object {
        const val NOTICE_BUFFER = 4
    }
}
