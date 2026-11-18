package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.cellAt
import dev.bugobliterator.itermbuddy.core.stream.historyCellAt
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlin.math.hypot

/*
 * The live canvas's zoom state and per-event decisions, pure so they are tested on the JVM;
 * [VideoCanvas] keeps a [KeptZoom] in snapshot state and calls these.
 */

/** What the gesture detectors read at each event: the current fit, view size, maximum scale, insets and history height. */
internal class GestureGeometry(
    val fit: FitRect,
    val viewW: Float,
    val viewH: Float,
    val max: Float,
    val top: Float,
    val restBottom: Float,
    val left: Float,
    val right: Float,
    val history: Float,
) {
    /** The content zoom and pan work on: the band with the history above it ([documentFit]). */
    val docFit: FitRect = documentFit(fit, history)
}

/**
 * A zoom as the owner left it, with the bottom inset (px) it was made for (null: the resting one)
 * and the history height (px) it was made for (null: whatever it is now).
 */
internal data class KeptZoom(val state: ZoomState, val inset: Float?, val history: Float? = null)

/**
 * What is shown: [kept] moved with the document from its history height to the current one
 * ([followDocument]; normally [rebaseKept] has done that already), then lifted from its inset to
 * [inset] ([liftForKeyboard]), clamped for [geometry].
 */
internal fun shownZoom(kept: KeptZoom, geometry: GestureGeometry, inset: Float): ZoomState {
    val keptInset = kept.inset ?: geometry.restBottom
    val keptHistory = kept.history ?: geometry.history
    val followed = if (keptHistory == geometry.history) {
        kept.state
    } else {
        followDocument(kept.state, geometry.fit, geometry.viewW, geometry.viewH, keptHistory, geometry.history, keptInset, geometry.top, geometry.left, geometry.right)
    }
    return liftForKeyboard(followed, geometry.docFit, geometry.viewW, geometry.viewH, keptInset, inset, geometry.top, geometry.left, geometry.right)
}

/**
 * [kept] rebased onto [geometry]'s history height, on every change of it: whether the live band
 * was at the bottom is judged at each change, as iOS judges `wasAtBottom` at every layout
 * (`IOS/Screens/SessionView.swift:2060-2061, 2101-2111`), so a document that shrank (a clear, the
 * alternate screen) and reads as pinned follows the live band when it grows again.
 */
internal fun rebaseKept(kept: KeptZoom, geometry: GestureGeometry): KeptZoom {
    val from = kept.history ?: return kept.copy(history = geometry.history)
    if (from == geometry.history) return kept
    val inset = kept.inset ?: geometry.restBottom
    val followed = followDocument(kept.state, geometry.fit, geometry.viewW, geometry.viewH, from, geometry.history, inset, geometry.top, geometry.left, geometry.right)
    return KeptZoom(followed, kept.inset, geometry.history)
}

/** The outcome of one pan or pinch event: the new [kept] zoom, the wheel notches to send, and the gesture's two-finger latch. */
internal class GestureStep(val kept: KeptZoom, val notches: List<Boolean>, val multiTouch: Boolean)

/**
 * One event of a pan or pinch ([centroidX], [centroidY], [panX], [panY], [zoom]) with [pointers]
 * down, the gesture so far having had two fingers if [multiTouch]. With a [wheel] (wheel mode) and
 * only ever one finger, the drag pans first and the travel past the pan edge becomes notches
 * ([wheelDrag]); otherwise it zooms and pans ([applyGesture]). A gesture latches to zoom and pan
 * once two fingers are down, until it ends.
 */
internal fun gestureStep(
    kept: KeptZoom,
    geometry: GestureGeometry,
    inset: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    pointers: Int,
    multiTouch: Boolean,
    wheel: ScrollWheelAccumulator?,
): GestureStep {
    val g = geometry
    val shown = shownZoom(kept, g, inset)
    val latched = multiTouch || pointers > 1
    if (wheel != null && !latched) {
        val drag = wheelDrag(shown, panX, panY, g.docFit, g.viewW, g.viewH, inset, g.top, g.left, g.right)
        return GestureStep(KeptZoom(drag.state, inset, g.history), wheel.drag(drag.pastEdgeY), latched)
    }
    val next = applyGesture(shown, centroidX, centroidY, zoom, panX, panY, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
    return GestureStep(KeptZoom(next, inset, g.history), emptyList(), latched)
}

/** How long a still finger is held before a selection begins: iOS's 0.3 s (`IOS/Screens/SessionView.swift:1928-1933`). */
internal const val SELECTION_HOLD_MILLIS = 300L

/** What [PressArbiter] decides, at canvas point ([x], [y]). */
internal sealed interface PressAction {
    val x: Float
    val y: Float

    /** A single tap, once the double-tap window has passed with no second press. */
    data class Tap(override val x: Float, override val y: Float) : PressAction

    data class DoubleTap(override val x: Float, override val y: Float) : PressAction

    data class SelectBegin(override val x: Float, override val y: Float) : PressAction

    data class SelectMove(override val x: Float, override val y: Float) : PressAction

    data class SelectEnd(override val x: Float, override val y: Float) : PressAction
}

/**
 * One decision for taps, double taps and the selection hold, so they cannot overlap (Compose's
 * `detectTapGestures` defers a first tap until the double-tap window passes and then delivers it
 * when a second press comes, which would clear a selection that second press starts). Fed the
 * primary finger's events in event time; [deadline] is when [tick] must be called if nothing
 * else happens first. Pans and pinches are [gestureStep]'s: a press that moves past [slop] or
 * gets a second finger before the hold is left to them and never selects.
 *
 * - A press held still for [holdMillis] (when it was [down] as selectable) begins a selection
 *   ([PressAction.SelectBegin]); moves extend it and the lift ends it. A first tap still waiting
 *   for its double-tap window is dropped: the press that follows it is a selection, not a tap.
 * - A press lifted before the hold is a tap, deferred [doubleTapMillis] for a second one. A second
 *   press lifted in time is a [PressAction.DoubleTap] (no tap); one that pans fires the waiting tap.
 * - An unselectable press held long and lifted is a tap, as Compose's (no long-press handler).
 *
 * Holds no state but positions and times; tested on the JVM.
 */
internal class PressArbiter(private val holdMillis: Long, private val doubleTapMillis: Long, private val slop: Float) {
    private sealed interface State

    private data object Idle : State

    /** A tap lifted at ([x], [y]) at [upTime], waiting for a second press. */
    private class TapPending(val x: Float, val y: Float, val upTime: Long) : State

    /** A finger down at ([x], [y]) at [time]; [firstTap] is the tap it may be the second of. */
    private class Pressed(val x: Float, val y: Float, val time: Long, val selectable: Boolean, val firstTap: TapPending?) : State {
        var lastX = x
        var lastY = y
    }

    /** The press is a pan or pinch: nothing more until it lifts. */
    private data object Ignoring : State

    private class Selecting(var x: Float, var y: Float) : State

    private var state: State = Idle

    /** Whether a selection owns the gesture: its events should be consumed. */
    val selecting: Boolean get() = state is Selecting

    /** When [tick] is due, or null when no timer runs. */
    val deadline: Long?
        get() = when (val now = state) {
            is TapPending -> now.upTime + doubleTapMillis
            is Pressed -> if (now.selectable) now.time + holdMillis else null
            else -> null
        }

    /** The primary finger goes down; [selectable] whether a hold may select now. */
    fun down(x: Float, y: Float, time: Long, selectable: Boolean): List<PressAction> {
        val fired = tick(time)
        val pending = state as? TapPending
        state = Pressed(x, y, time, selectable, pending)
        return fired
    }

    /** The primary finger at ([x], [y]) with [pointers] fingers down. */
    fun move(x: Float, y: Float, pointers: Int, time: Long): List<PressAction> {
        val fired = tick(time)
        when (val now = state) {
            is Pressed -> {
                now.lastX = x
                now.lastY = y
                val travel = hypot(x - now.x, y - now.y)
                if (pointers > 1 || !(travel <= slop)) {
                    state = Ignoring
                    return fired + listOfNotNull(now.firstTap?.let { PressAction.Tap(it.x, it.y) })
                }
            }
            is Selecting -> if (x != now.x || y != now.y) {
                now.x = x
                now.y = y
                return fired + PressAction.SelectMove(x, y)
            }
            else -> Unit
        }
        return fired
    }

    /** The primary finger lifts at ([x], [y]). */
    fun up(x: Float, y: Float, time: Long): List<PressAction> {
        val fired = tick(time)
        return when (val now = state) {
            is Pressed -> if (now.firstTap != null) {
                state = Idle
                fired + PressAction.DoubleTap(x, y)
            } else {
                state = TapPending(x, y, time)
                fired
            }
            is Selecting -> {
                state = Idle
                fired + PressAction.SelectEnd(x, y)
            }
            else -> {
                state = Idle
                fired
            }
        }
    }

    /** Time passed to [time]: a hold begins, or a waiting tap fires. */
    fun tick(time: Long): List<PressAction> {
        val due = deadline ?: return emptyList()
        if (time < due) return emptyList()
        return when (val now = state) {
            is TapPending -> {
                state = Idle
                listOf(PressAction.Tap(now.x, now.y))
            }
            is Pressed -> {
                state = Selecting(now.lastX, now.lastY)
                listOf(PressAction.SelectBegin(now.lastX, now.lastY))
            }
            else -> emptyList()
        }
    }

    /** The gesture was cut short: a selection ends where it was; anything else is forgotten. */
    fun cancel(): List<PressAction> {
        val now = state
        state = Idle
        return if (now is Selecting) listOf(PressAction.SelectEnd(now.x, now.y)) else emptyList()
    }
}

/**
 * The selection point under screen point ([x], [y]) of a terminal shown with zoom [shown], the
 * live band at [fit] and the document [layout] above it ([ScrollbackLayout.liveBandTopPx] of
 * history): over the band, the video's cell ([cellAt], margins included, from the layout's live
 * top); over history, the tile's cell on that line ([historyCellAt], tile space, no margins).
 * Clamped to the document and the grid, as iOS (`IOS/Screens/SessionView.swift:2710-2717`), so
 * a drag past an edge selects up to it. Null without a usable zoom, fit or image, or for a config
 * that cannot select (a browser, no cell geometry).
 */
internal fun selectionPointAt(x: Float, y: Float, shown: ZoomState, fit: FitRect, layout: ScrollbackLayout, config: StreamConfig): SelectionPoint? {
    if (fit.isEmpty || config.pixelWidth <= 0 || config.pixelHeight <= 0) return null
    val history = layout.liveBandTopPx
    val (docX, docY) = screenToDocument(x, y, shown, fit, history) ?: return null
    val imageX = (docX - fit.left) * config.pixelWidth / fit.width
    return if (docY >= history) {
        cellAt(imageX, (docY - history) * config.pixelHeight / fit.height, config, layout.liveTop)
    } else {
        historyCellAt(layout.absLineAt(docY), imageX, config)
    }
}
