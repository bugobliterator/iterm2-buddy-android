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

/**
 * **Fit to phone** while its toggle ([enabled], [FitToPhoneSetting]) is on: keeps the live
 * terminal session's grid fitted to the phone ([fitGrid]), sending `resizeSession` on [link] for
 * the active stream's link only. Turning the toggle off never undoes a resize.
 *
 * **When it fits.** While on, a live view open ([setViewport]: the view of the active stream's
 * session has measured its canvas) and the active stream's config known, it computes [fitGrid] and
 * sends it when the Mac's grid (the config's columns and rows) differs. It does so as the view opens
 * or the stream goes live (a new stream, a reconnect, another session), when the toggle turns on,
 * and when the viewport changes enough to change the grid (a rotation, split screen, a foldable). A
 * viewport change counts only once the viewport has held still for [settleDelay], so a rotation's
 * layout passes send one resize. The keyboard never resizes: the view measures its canvas with the
 * keyboard hidden. For one stream it acts on a target grid once: a config the Mac sends later (the
 * owner resizing the window on the Mac, say) is not fought.
 *
 * **Never.** Not for a browser config; not while `canResize` is not true (a full-screen,
 * maximised or width-locked window, or a Mac too old to say): then [notices] gets
 * [FIT_NOTICE_CANNOT_RESIZE] once per session while on (not for the session shown when the toggle
 * turns on: the toggle's own hint says it). Not when the Mac's grid already equals the target, or
 * equals what an earlier fit of this session to the same target ended at (no loop with a Mac that
 * caps the grid).
 *
 * **Capped fits.** After a send it watches the config: when the Mac's grid changes it logs the
 * requested and resulting sizes and, after a first fit whose rows came back fewer and so wider than
 * the viewport, sends one corrective resize ([correctedFitGrid]: fewer columns, the same rows, so
 * the session fills the phone with bigger text). At most one corrective step per fit; its result is
 * logged and kept. When the grid does not change within [resultTimeout], it logs that and
 * [notices] gets [FIT_NOTICE_NO_CHANGE] (after a first step only).
 *
 * **Threading.** Every input goes through one channel to one consumer coroutine in [scope]; all
 * state is touched only there. [setViewport] and [clearViewport] are safe from any thread.
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
    }

    /** What of [ActiveStream] matters here (not the live top, which moves with every frame). */
    private data class StreamView(val linkId: Long, val streamID: Long, val sessionGuid: String, val config: StreamConfig?)

    /** A resize sent, waiting for the Mac's config to show it. */
    private data class Pending(
        val id: Long,
        val linkId: Long,
        val sessionGuid: String,
        val target: Grid,
        val requested: Grid,
        val before: Grid,
        val viewport: FitViewport,
        val corrective: Boolean,
    )

    /** The last fit of a session: to [target], it ended at [result]. */
    private data class Outcome(val target: Grid, val result: Grid)

    private data class Handled(val linkId: Long, val streamID: Long, val target: Grid)

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

    private suspend fun onInputs(inputs: Event.Inputs) {
        val turnedOn = inputs.enabled && !enabledNow
        enabledNow = inputs.enabled
        stream = inputs.stream
        viewport = inputs.viewport
        if (!enabledNow) {
            // Off never undoes anything; it only stops watching.
            dropPending()
            return
        }
        if (turnedOn) {
            handled = null
            noticedCannotResize.clear()
            // The toggle's own hint explains a session that cannot resize; no second message.
            inputs.stream?.let { noticedCannotResize += it.sessionGuid }
        }
        evaluate()
    }

    private suspend fun evaluate() {
        val s = stream
        val v = viewport
        if (s == null || v == null || s.sessionGuid != v.sessionGuid) return
        pending?.let { if (it.linkId != s.linkId || it.sessionGuid != s.sessionGuid) dropPending() }
        val config = s.config ?: return
        if (config.isBrowser) return
        if (config.canResize != true) {
            dropPending()
            if (noticedCannotResize.add(s.sessionGuid)) {
                log("fit to phone for ${s.sessionGuid}: the Mac cannot resize this session (canResize ${config.canResize})")
                noticesFlow.tryEmit(FitNotice(s.sessionGuid, FIT_NOTICE_CANNOT_RESIZE))
            }
            return
        }
        val current = Grid(config.columns, config.rows)
        val target = fitGrid(v.widthPx, v.heightPx, config, decoderMax(), sizeSupported)
        val waiting = pending
        if (waiting != null) {
            if (target != waiting.target) {
                dropPending() // the viewport changed: a new fit below
            } else if (current == waiting.before) {
                return // the Mac has not applied it yet; the timer is running
            } else {
                resolve(waiting, current, config)
                return
            }
        }
        if (target == null) return
        val key = Handled(s.linkId, s.streamID, target)
        if (handled == key) return
        handled = key
        if (current == target) {
            log("fit to phone for ${s.sessionGuid}: already at ${size(target)}")
            outcomes[s.sessionGuid] = Outcome(target, current)
            return
        }
        if (outcomes[s.sessionGuid] == Outcome(target, current)) {
            log("fit to phone for ${s.sessionGuid}: already at ${size(current)}, where the last fit to ${size(target)} ended")
            return
        }
        send(s.linkId, s.sessionGuid, target, target, current, v, corrective = false)
    }

    private suspend fun resolve(waiting: Pending, achieved: Grid, config: StreamConfig) {
        val step = if (waiting.corrective) "corrective" else "fit"
        log("fit to phone for ${waiting.sessionGuid}: $step requested ${size(waiting.requested)}, got ${size(achieved)} (was ${size(waiting.before)})")
        dropPending()
        if (!waiting.corrective) {
            val corrected = correctedFitGrid(waiting.requested, achieved, waiting.viewport.widthPx, waiting.viewport.heightPx, config)
            if (corrected != null) {
                send(waiting.linkId, waiting.sessionGuid, waiting.target, corrected, achieved, waiting.viewport, corrective = true)
                return
            }
        }
        outcomes[waiting.sessionGuid] = Outcome(waiting.target, achieved)
    }

    private fun onTimeout(pendingId: Long) {
        val waiting = pending?.takeIf { it.id == pendingId } ?: return
        val step = if (waiting.corrective) "corrective" else "fit"
        log("fit to phone for ${waiting.sessionGuid}: $step requested ${size(waiting.requested)}, the grid stayed ${size(waiting.before)} for $resultTimeout")
        pending = null
        pendingTimer = null
        outcomes[waiting.sessionGuid] = Outcome(waiting.target, waiting.before)
        if (!waiting.corrective) noticesFlow.tryEmit(FitNotice(waiting.sessionGuid, FIT_NOTICE_NO_CHANGE))
    }

    private suspend fun send(linkId: Long, guid: String, target: Grid, grid: Grid, before: Grid, viewport: FitViewport, corrective: Boolean) {
        val step = if (corrective) "corrective" else "fit"
        try {
            link.send(PhoneMessage.ResizeSession(guid, grid.columns, grid.rows), onLink = linkId)
        } catch (e: CompanionRequestException) {
            log("fit to phone for $guid: $step ${size(grid)} not sent: ${e.javaClass.simpleName}")
            return
        }
        log("fit to phone for $guid: $step sent ${size(grid)} (was ${size(before)}, viewport ${viewport.widthPx}x${viewport.heightPx})")
        val id = ++nextPendingId
        pending = Pending(id, linkId, guid, target, grid, before, viewport, corrective)
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
