package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.truncate

/**
 * One `browserScroll` to send: the page under encoded-image pixel ([x], [y]) moves by whole
 * encoded-image pixels [deltaX], [deltaY] (positive is right and down: the content follows the
 * finger). Holds no page content.
 */
data class ScrollStep(val x: Double, val y: Double, val deltaX: Int, val deltaY: Int)

/**
 * A browser stream's drag and fling, in encoded-image pixels, as iOS accumulates them
 * (`IOS/Screens/SessionView.swift:1397-1411, 2256-2362`): travel adds up between flushes and each
 * [flush] hands over its whole pixels, keeping the fraction, so a fast drag sends one scroll per
 * flush and a slow one still adds up.
 *
 * - View pixels become image pixels by `× imagePxPerViewPx / zoom` (iOS
 *   `imagePixelsPerScrollPoint`, 2246-2250), for drags and fling velocities alike, where
 *   `imagePxPerViewPx` is the image's density at zoom 1 (Fit) and `zoom` the current zoom, divided
 *   separately.
 * - A fling coasts along the app's one fling decay ([FlingCurve], Android's spline fling, the
 *   feel of any Android list; iOS uses UIScrollView's 0.998/ms), computed in screen px at the
 *   screen's density and scaled to image px: each [flush] adds the curve's travel since the last
 *   one, so steps shrink smoothly as it slows and stop at the curve's end. Each flush counts at
 *   most 100 ms, as iOS caps a display-link step (2337-2350). A release slower than
 *   [MIN_FLING_DP_PER_SEC] does not coast.
 * - A new drag stops a fling (iOS zeroes the velocity when a pan begins); [stopFling] stops it and
 *   keeps the travel not yet sent (a tap, 2193); [cancel] drops both (a pinch, `stopBrowserScrolling`).
 *
 * The caller decides when to fling: iOS coasts only at zoom 1, and not after a pinch (2283-2290).
 * Not thread-safe; the caller serialises calls.
 */
class BrowserScrollAccumulator {
    private var pendingX = 0.0
    private var pendingY = 0.0
    private var pointX = 0.0
    private var pointY = 0.0
    private var fling: FlingStepper? = null
    private var flingX = 0.0
    private var flingY = 0.0

    /** Whether a fling is still coasting. */
    val isFlinging: Boolean get() = fling != null

    /**
     * Adds a drag of ([dxViewPx], [dyViewPx]) view pixels, at the image's density at zoom 1 (Fit)
     * [imagePxPerViewPx] and the current [zoom], over the encoded-image pixel ([atX], [atY]); ignored when any
     * of them is not finite or the density or zoom is not positive.
     */
    fun drag(dxViewPx: Float, dyViewPx: Float, imagePxPerViewPx: Float, zoom: Float, atX: Float, atY: Float) {
        val scale = scale(imagePxPerViewPx, zoom) ?: return
        if (!dxViewPx.isFinite() || !dyViewPx.isFinite() || !atX.isFinite() || !atY.isFinite()) return
        stopFling()
        pendingX += dxViewPx * scale
        pendingY += dyViewPx * scale
        pointX = atX.toDouble()
        pointY = atY.toDouble()
    }

    /**
     * Starts coasting at a release velocity of ([velocityXViewPxPerSec], [velocityYViewPxPerSec])
     * view (screen) px/s on a screen of [densityPxPerDp] px per dp, from [nowMillis], along a
     * [FlingCurve] whose travel is scaled to image px as [drag] scales distance; a release below
     * [MIN_FLING_DP_PER_SEC] does not coast. It scrolls the page under the last drag's point.
     */
    fun fling(
        velocityXViewPxPerSec: Float,
        velocityYViewPxPerSec: Float,
        imagePxPerViewPx: Float,
        zoom: Float,
        nowMillis: Long,
        densityPxPerDp: Float = 1f,
    ) {
        val scale = scale(imagePxPerViewPx, zoom) ?: return
        if (!velocityXViewPxPerSec.isFinite() || !velocityYViewPxPerSec.isFinite()) return
        stopFling()
        val speed = hypot(velocityXViewPxPerSec, velocityYViewPxPerSec)
        val curve = FlingCurve.orNull(speed, densityPxPerDp) ?: return
        flingX = velocityXViewPxPerSec / speed * scale
        flingY = velocityYViewPxPerSec / speed * scale
        fling = FlingStepper(curve, MAX_STEP_MILLIS).also { it.start(nowMillis) }
    }

    /** Sets the image point a fling scrolls at, for a fling with no drag before it. */
    fun at(atX: Float, atY: Float) {
        if (!atX.isFinite() || !atY.isFinite()) return
        pointX = atX.toDouble()
        pointY = atY.toDouble()
    }

    /**
     * Advances a fling to [nowMillis] and hands over the whole pixels travelled since the last
     * flush (toward zero), keeping the fraction; null when both are 0.
     */
    fun flush(nowMillis: Long): ScrollStep? {
        fling?.let { coast ->
            val travel = coast.advance(nowMillis).toDouble()
            pendingX += travel * flingX
            pendingY += travel * flingY
            if (coast.isFinished) stopFling()
        }
        val sendX = truncate(pendingX)
        val sendY = truncate(pendingY)
        if (sendX == 0.0 && sendY == 0.0) return null
        pendingX -= sendX
        pendingY -= sendY
        return ScrollStep(pointX, pointY, sendX.toInt(), sendY.toInt())
    }

    /** Stops a fling; travel not yet flushed stays. */
    fun stopFling() {
        fling = null
    }

    /** Drops the travel not yet flushed and any fling. */
    fun cancel() {
        stopFling()
        pendingX = 0.0
        pendingY = 0.0
    }

    private fun scale(imagePxPerViewPx: Float, zoom: Float): Double? {
        if (!imagePxPerViewPx.isFinite() || !zoom.isFinite() || imagePxPerViewPx <= 0f || zoom <= 0f) return null
        return imagePxPerViewPx.toDouble() / zoom.toDouble()
    }

    private companion object {
        /** The longest step one flush counts, as iOS caps its display-link step at 0.1 s. */
        const val MAX_STEP_MILLIS = 100L
    }
}

/** A key that scrolls a browser page: an arrow (one line) or Page Up/Down (one viewport). */
enum class ScrollKey { LINE_UP, LINE_DOWN, PAGE_UP, PAGE_DOWN }

/**
 * Vertical page travel in encoded-image pixels, handed over as scrolling keys, for iTerm2's
 * misplaced wheel events (`docs/reports/iterm2-companion-browser-scroll-offset.md`): the key path
 * scrolls whatever has focus on the page, wherever the window is.
 *
 * Travel uses the [ScrollStep] sign (positive moves the content down: the page scrolls up, so
 * the keys go up). [next] hands over at most one key, and keeps the page within half a line of
 * the finger:
 *
 * - **Page** Up/Down once at least a page ([pagePx]) less half a line ([linePx]) is pending;
 * - else an **arrow** once at least half a line is.
 *
 * Each key takes its whole size off the pending travel, signed, so a key that overshoots (an
 * arrow for 0.6 of a line) is paid back by the travel that follows: a slow drag scrolls as far as
 * the finger went, within half a line, never twice as far. What is left is clamped to just inside
 * half a line either way, so rounding never fires a key back the other way. Travel against keys
 * still owed (a drag reversing a fling's backlog) drops them first ([add]). The backlog is capped
 * at [MAX_BACKLOG_PAGES] pages, so a long fling does not keep paging long after the finger has
 * gone. Not thread-safe; the caller serialises calls.
 */
class KeyScrollAccumulator {
    private var pending = 0.0

    /** The travel not yet handed over as keys, encoded px (positive: content down). */
    val pendingPx: Double get() = pending

    /**
     * Adds [deltaPx] encoded px of vertical travel for lines of [linePx]; [pagePx] bounds the
     * backlog. Travel against a key still owed ([hasWork]) drops the backlog first.
     */
    fun add(deltaPx: Double, linePx: Double, pagePx: Double) {
        if (!deltaPx.isFinite() || deltaPx == 0.0) return
        if (pending != 0.0 && (deltaPx > 0) != (pending > 0) && hasWork(linePx)) pending = 0.0
        val cap = if (pagePx.isFinite() && pagePx > 0) pagePx * MAX_BACKLOG_PAGES else Double.MAX_VALUE
        pending = (pending + deltaPx).coerceIn(-cap, cap)
    }

    /** Whether a key is still owed for lines of [linePx]. */
    fun hasWork(linePx: Double): Boolean = linePx > 0 && abs(pending) >= linePx / 2

    /** The next key for lines of [linePx] and pages of [pagePx], or null when less than half a line is pending. */
    fun next(linePx: Double, pagePx: Double): ScrollKey? {
        if (!(linePx > 0) || !(pagePx > 0)) return null
        val up = pending > 0
        val size = abs(pending)
        val (key, step) = when {
            pagePx > linePx && size >= pagePx - linePx / 2 -> (if (up) ScrollKey.PAGE_UP else ScrollKey.PAGE_DOWN) to pagePx
            size >= linePx / 2 -> (if (up) ScrollKey.LINE_UP else ScrollKey.LINE_DOWN) to linePx
            else -> return null
        }
        // Just inside half a line the other way, so the overshoot never fires a key back.
        val left = maxOf(size - step, -(linePx / 2) * INSIDE)
        pending = if (up) left else -left
        return key
    }

    /** Drops the pending travel. */
    fun cancel() {
        pending = 0.0
    }

    private companion object {
        const val MAX_BACKLOG_PAGES = 2.0

        /** "Just inside" half a line: a remainder of exactly half a line would fire. */
        const val INSIDE = 1 - 1e-6
    }
}
