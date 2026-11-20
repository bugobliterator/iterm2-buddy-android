package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.FlingCurve
import dev.bugobliterator.itermbuddy.core.stream.FlingStepper
import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.WheelCoastBudget
import kotlin.math.abs
import kotlin.math.hypot

/*
 * The terminal canvas's momentum, pure so it is tested on the JVM; [VideoCanvas] runs it one
 * frame at a time (withFrameMillis) after a one-finger release and stops it on the next touch.
 */

/** Within this many px of the wanted step a coast counts as still moving on that axis (rounding at the clamp). */
private const val EDGE_SLACK_PX = 0.5f

/** One frame of a [CanvasCoast]: the new kept zoom, the wheel notches to send ([notches] toward [up]), and whether the coast is over. */
internal class CoastFrame(val kept: KeptZoom, val notches: Int, val up: Boolean, val finished: Boolean)

/**
 * A released one-finger drag on a terminal coasting with Android's fling decay ([FlingCurve]) in
 * the direction it was released, frame by frame ([step]):
 *
 * - **Scrollback and zoomed pans** ([wheel] null): each frame pans the document by the curve's
 *   travel ([applyGesture], clamped as a drag is), so it stops at the history's top and at the
 *   live bottom with no bounce: an axis that stops moving at the clamp is done, and the coast is
 *   over once both are (or the curve ends).
 * - **Wheel mode** ([wheel], the alternate screen with mouse-wheel reporting): each frame's travel
 *   goes through [wheelDrag] as a drag's does (a zoomed picture pans first) and the vertical
 *   travel past the edge through the same [ScrollWheelAccumulator], so the drag's remainder
 *   carries into the coast; the notches are bounded by a [WheelCoastBudget] (a few per frame, a
 *   cap in all), sent as one count per frame.
 *
 * [target] is where a scrollback coast will come to rest, so the tiles it will reach are fetched
 * ahead. Not thread-safe; the UI thread only.
 */
internal class CanvasCoast private constructor(
    private val stepper: FlingStepper,
    private val unitX: Float,
    private val unitY: Float,
    private val wheel: ScrollWheelAccumulator?,
) {
    private var blockedX = unitX == 0f
    private var blockedY = unitY == 0f
    private val budget = WheelCoastBudget()

    /** Starts the coast's clock at the frame time [nowMillis]. */
    fun start(nowMillis: Long) = stepper.start(nowMillis)

    /** The frame at [nowMillis]: [kept] moved by the curve's travel since the last frame (see the class comment). */
    fun step(kept: KeptZoom, geometry: GestureGeometry, inset: Float, nowMillis: Long): CoastFrame {
        val g = geometry
        val travel = stepper.advance(nowMillis)
        val dx = if (blockedX) 0f else travel * unitX
        val dy = if (blockedY) 0f else travel * unitY
        val shown = shownZoom(kept, g, inset)
        if (wheel != null) {
            val drag = wheelDrag(shown, dx, dy, g.docFit, g.viewW, g.viewH, inset, g.top, g.left, g.right)
            if (stalled(dx, drag.state.offsetX - shown.offsetX)) blockedX = true
            val reached = wheel.drag(drag.pastEdgeY)
            val notches = budget.take(reached.size)
            val finished = stepper.isFinished || budget.exhausted || (blockedX && blockedY)
            return CoastFrame(keptAfter(drag.state, inset, g), notches, reached.firstOrNull() ?: false, finished)
        }
        val next = applyGesture(shown, 0f, 0f, 1f, dx, dy, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
        if (stalled(dx, next.offsetX - shown.offsetX)) blockedX = true
        if (stalled(dy, next.offsetY - shown.offsetY)) blockedY = true
        return CoastFrame(keptAfter(next, inset, g), 0, false, stepper.isFinished || (blockedX && blockedY))
    }

    /**
     * Where a scrollback coast from [kept] would rest if nothing stopped it (the whole curve,
     * clamped), as the document span shown then; null in wheel mode (no history to fetch).
     */
    fun target(kept: KeptZoom, geometry: GestureGeometry, inset: Float): DocumentView? {
        if (wheel != null) return null
        val g = geometry
        val shown = shownZoom(kept, g, inset)
        val total = stepper.curve.distancePx
        val rest = applyGesture(shown, 0f, 0f, 1f, total * unitX, total * unitY, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
        return documentView(rest, g.fit, g.history, g.viewH, inset)
    }

    /** Whether an axis asked to move [wanted] px moved visibly less ([moved]): it met the clamp. */
    private fun stalled(wanted: Float, moved: Float): Boolean = wanted != 0f && abs(moved) < abs(wanted) - EDGE_SLACK_PX

    companion object {
        /**
         * The coast of a release at ([velocityX], [velocityY]) px/s (the finger's: positive moves
         * the content right and down), its speed capped at [maxVelocity] (the platform's maximum
         * fling velocity), on a screen of [densityPxPerDp]; null when it is too slow to coast
         * ([FlingCurve.orNull]). [wheel] is the gesture's accumulator in wheel mode, else null.
         */
        fun of(velocityX: Float, velocityY: Float, densityPxPerDp: Float, maxVelocity: Float, wheel: ScrollWheelAccumulator?): CanvasCoast? {
            if (!velocityX.isFinite() || !velocityY.isFinite()) return null
            val speed = hypot(velocityX, velocityY)
            if (!(speed > 0f)) return null
            val capped = if (maxVelocity.isFinite() && maxVelocity > 0f) minOf(speed, maxVelocity) else speed
            val curve = FlingCurve.orNull(capped, densityPxPerDp) ?: return null
            return CanvasCoast(FlingStepper(curve), velocityX / speed, velocityY / speed, wheel)
        }
    }
}
