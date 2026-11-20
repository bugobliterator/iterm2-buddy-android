package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.cellAt
import dev.bugobliterator.itermbuddy.core.stream.historyCellAt
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlin.math.abs
import kotlin.math.hypot

/*
 * The live canvas's zoom state and per-event decisions, pure so they are tested on the JVM;
 * [VideoCanvas] keeps a [KeptZoom] in snapshot state and calls these.
 */

/**
 * What the gesture detectors read at each event: the current fit, view size, maximum scale, insets
 * and history height, and the base view a browser page rests at ([base]: [browserFillZoom]; null
 * for a terminal, whose base is fit).
 */
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
    val base: ZoomState? = null,
) {
    /** The content zoom and pan work on: the band with the history above it ([documentFit]). */
    val docFit: FitRect = documentFit(fit, history)
}

/**
 * A zoom as the owner left it, with the bottom inset (px) it was made for (null: the resting one)
 * and the history height (px) it was made for (null: whatever it is now). [atBase]: it is at the
 * geometry's base view ([GestureGeometry.base]), so when the base changes (a rotation, the bars
 * measured, another canvas) it follows the new base instead of keeping its scale ([followBase]).
 */
internal data class KeptZoom(val state: ZoomState, val inset: Float?, val history: Float? = null, val atBase: Boolean = false)

/**
 * The browser page's base view ("fill the screen"): the page scaled to cover the visible area
 * inside the insets (the resting bottom one, the keyboard hidden), so no band is left: a page
 * relatively wider than that area fills its height and is centred across, its sides cropped; a
 * relatively taller one fills its width, top-aligned. Null for an empty fit or view. A page of the
 * area's own shape gives [ZoomState.FIT].
 */
internal fun browserFillZoom(
    fit: FitRect,
    viewW: Float,
    viewH: Float,
    topInset: Float,
    bottomInset: Float,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): ZoomState? {
    if (fit.isEmpty) return null
    val left = nonNegative(leftInset)
    val top = nonNegative(topInset)
    val visibleW = viewW - left - nonNegative(rightInset)
    val visibleH = viewH - top - nonNegative(bottomInset)
    if (!(visibleW > 0f && visibleH > 0f) || !visibleW.isFinite() || !visibleH.isFinite()) return null
    val scale = maxOf(1f, visibleW / fit.width, visibleH / fit.height)
    if (!scale.isFinite()) return null
    val offsetX = left + (visibleW - scale * fit.width) / 2 - scale * fit.left
    val offsetY = top - scale * fit.top
    return clampZoom(ZoomState(scale, offsetX, offsetY), fit, viewW, viewH, bottomInset, topInset, leftInset, rightInset)
}

/** Within this fraction of the base scale a zoom counts as at the base (as [BROWSER_FIT_SCALE] for fit). */
private const val BASE_TOLERANCE = 0.001f

/** Whether [scale] is the base view's [baseScale]. */
internal fun isAtBase(scale: Float, baseScale: Float): Boolean = abs(scale - baseScale) <= baseScale * BASE_TOLERANCE

/** A kept zoom made from [state] after a gesture: at the base when [geometry] has one and [state] is at its scale. */
internal fun keptAfter(state: ZoomState, inset: Float, geometry: GestureGeometry): KeptZoom =
    KeptZoom(state, inset, geometry.history, atBase = geometry.base?.let { isAtBase(state.scale, it.scale) } ?: false)

/**
 * [kept], or the geometry's base view (made for the resting inset) when [kept] is at a base of
 * another scale: the first view of a page, or one left at its base before the canvas changed.
 * A zoom the owner chose (fit, or zoomed in) is kept, as a terminal's is.
 */
internal fun followBase(kept: KeptZoom, geometry: GestureGeometry): KeptZoom {
    val base = geometry.base ?: return kept
    if (!kept.atBase || isAtBase(kept.state.scale, base.scale)) return kept
    return KeptZoom(base, null, geometry.history, atBase = true)
}

/**
 * What is shown: [kept] at its base ([followBase]), moved with the document from its history height
 * to the current one ([followDocument]; normally [rebaseKept] has done that already), then lifted
 * from its inset to [inset] ([liftForKeyboard]), clamped for [geometry].
 */
internal fun shownZoom(keptZoom: KeptZoom, geometry: GestureGeometry, inset: Float): ZoomState {
    val kept = followBase(keptZoom, geometry)
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
    return kept.copy(state = followed, history = geometry.history)
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

/**
 * One event of a drag or pinch on a browser page: the new [kept] zoom, the page scroll it makes in
 * screen px ([scrollX], [scrollY]: positive is right and down, the content following the finger),
 * whether the gesture has had a pinch ([sawPinch]) and whether this event is its first pinch
 * ([pinchStarted]: the caller cancels the page scroll then).
 */
internal class BrowserGestureStep(val kept: KeptZoom, val scrollX: Float, val scrollY: Float, val sawPinch: Boolean, val pinchStarted: Boolean)

/** At or below this zoom the whole page fits: a drag scrolls it and a release may fling (iOS `zoomScale <= 1.001`). */
internal const val BROWSER_FIT_SCALE = 1.001f

/**
 * One event of a pan or pinch on a browser page, as iOS's `handleBrowserPan` and
 * `accrueBrowserScroll` (`IOS/Screens/SessionView.swift:2256-2328`):
 *
 * - With two fingers down, or once the gesture has had them ([sawPinch]), it zooms and pans the
 *   view locally ([applyGesture]) and never scrolls the page, until it ends.
 * - At fit ([BROWSER_FIT_SCALE]) a one-finger drag scrolls the page by all of it.
 * - Zoomed in, it pans the zoomed view ([wheelDrag]): horizontal travel only pans, and vertical
 *   travel scrolls the page only past the top or bottom pan edge. At the fill view
 *   ([browserFillZoom]) of a page wider than the screen the height fits exactly, so a horizontal
 *   drag pans to the cropped sides and all vertical travel scrolls the page.
 */
internal fun browserGestureStep(
    kept: KeptZoom,
    geometry: GestureGeometry,
    inset: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    pointers: Int,
    sawPinch: Boolean,
): BrowserGestureStep {
    val g = geometry
    val shown = shownZoom(kept, g, inset)
    if (sawPinch || pointers > 1) {
        val next = applyGesture(shown, centroidX, centroidY, zoom, panX, panY, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
        return BrowserGestureStep(keptAfter(next, inset, g), 0f, 0f, sawPinch = true, pinchStarted = !sawPinch)
    }
    if (shown.scale <= BROWSER_FIT_SCALE) {
        return BrowserGestureStep(kept, finiteOrZero(panX), finiteOrZero(panY), sawPinch = false, pinchStarted = false)
    }
    val drag = wheelDrag(shown, panX, panY, g.docFit, g.viewW, g.viewH, inset, g.top, g.left, g.right)
    return BrowserGestureStep(keptAfter(drag.state, inset, g), 0f, drag.pastEdgeY, sawPinch = false, pinchStarted = false)
}

/**
 * Whether a released drag coasts: at the page's resting view and with no pinch in the gesture.
 * iOS coasts only at zoom 1, its base (`SessionView.swift:2283-2290`); here that is the whole page
 * (fit) or the fill view ([baseScale], [browserFillZoom]; 1 without one).
 */
internal fun browserFlings(scale: Float, sawPinch: Boolean, baseScale: Float = 1f): Boolean =
    !sawPinch && (scale <= BROWSER_FIT_SCALE || isAtBase(scale, baseScale))

/** Encoded-image pixels per view pixel at fit (`imageW / fit.width`); null without an image or a fit. */
internal fun browserImagePxPerViewPx(imageW: Int, fit: FitRect): Float? {
    if (imageW <= 0 || fit.isEmpty) return null
    return (imageW / fit.width).takeIf { it.isFinite() && it > 0f }
}

/**
 * The page pixel a tap at screen point ([x], [y]) clicks, for the zoom as shown; null off the page
 * (the black area below a short page), so a click never lands outside the web view on the Mac.
 */
internal fun browserTapPoint(x: Float, y: Float, shown: ZoomState, fit: FitRect, imageW: Int, imageH: Int): Pair<Float, Float>? =
    viewToImage(x, y, shown, fit, imageW, imageH)

/**
 * The page pixel a drag starting at screen point ([x], [y]) scrolls at (iOS fixes it when the pan
 * begins, `SessionView.swift:2262-2268`), clamped to the page so a drag that starts below a short
 * page still scrolls it; null without an image or a fit.
 */
internal fun browserScrollPoint(x: Float, y: Float, shown: ZoomState, fit: FitRect, imageW: Int, imageH: Int): Pair<Float, Float>? {
    if (!(shown.scale > 0f) || !shown.scale.isFinite() || fit.isEmpty || imageW <= 0 || imageH <= 0) return null
    val imageX = ((x - shown.offsetX) / shown.scale - fit.left) * imageW / fit.width
    val imageY = ((y - shown.offsetY) / shown.scale - fit.top) * imageH / fit.height
    if (!imageX.isFinite() || !imageY.isFinite()) return null
    return imageX.coerceIn(0f, (imageW - 1).toFloat()) to imageY.coerceIn(0f, (imageH - 1).toFloat())
}

private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

private fun nonNegative(value: Float): Float = if (value.isFinite() && value > 0f) value else 0f

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
 * - With [doubleTapMillis] 0 (a browser page, which has no double-tap zoom) a tap is given at its
 *   lift, never deferred, and two quick taps are two taps.
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
            } else if (doubleTapMillis <= 0) {
                // No double tap (a browser page): the tap is not deferred.
                state = Idle
                fired + PressAction.Tap(x, y)
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
 * Whether a lift of the primary finger is a cancel rather than an up: Compose delivers
 * `ACTION_CANCEL` (the system pilfered the press, such as a back gesture) as an unpressed change
 * already consumed (`SuspendingPointerInputModifierNodeImpl.onCancelPointerInput`); a real lift,
 * read in the initial pass, is not consumed yet. While a selection is under way ([wasSelecting])
 * either one ends it where it is, so the lift is taken as an up.
 */
internal fun isCancelledLift(consumed: Boolean, wasSelecting: Boolean): Boolean = consumed && !wasSelecting

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
