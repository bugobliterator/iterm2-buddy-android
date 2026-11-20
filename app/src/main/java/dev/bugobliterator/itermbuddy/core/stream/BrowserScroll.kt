package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
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
 * - A fling coasts: on each [flush] its velocity adds `velocity × elapsed`, then decays by
 *   `0.998^elapsedMs` (UIScrollView's normal deceleration), and stops below 30 image px/s. Each
 *   flush counts at most 100 ms, as iOS caps a display-link step (2337-2350).
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
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastFlushMillis = 0L

    /** Whether a fling is still coasting. */
    val isFlinging: Boolean get() = velocityX != 0.0 || velocityY != 0.0

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
     * view px/s, scaled to image px/s as [drag] scales distance, from [nowMillis]; a velocity
     * already below 30 image px/s does not coast. It scrolls the page under the last drag's point.
     */
    fun fling(velocityXViewPxPerSec: Float, velocityYViewPxPerSec: Float, imagePxPerViewPx: Float, zoom: Float, nowMillis: Long) {
        val scale = scale(imagePxPerViewPx, zoom) ?: return
        if (!velocityXViewPxPerSec.isFinite() || !velocityYViewPxPerSec.isFinite()) return
        velocityX = velocityXViewPxPerSec * scale
        velocityY = velocityYViewPxPerSec * scale
        lastFlushMillis = nowMillis
        if (hypot(velocityX, velocityY) < MINIMUM_VELOCITY) stopFling()
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
        val elapsedMillis = (nowMillis - lastFlushMillis).coerceIn(0, MAX_STEP_MILLIS)
        lastFlushMillis = nowMillis
        if (isFlinging) {
            pendingX += velocityX * elapsedMillis / 1000.0
            pendingY += velocityY * elapsedMillis / 1000.0
            val decay = DECELERATION.pow(elapsedMillis.toDouble())
            velocityX *= decay
            velocityY *= decay
            if (hypot(velocityX, velocityY) < MINIMUM_VELOCITY) stopFling()
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
        velocityX = 0.0
        velocityY = 0.0
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
        /** UIScrollView's normal deceleration: the velocity kept per millisecond. */
        const val DECELERATION = 0.998

        /** A fling below this many image px/s stops. */
        const val MINIMUM_VELOCITY = 30.0

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
 * the keys go up). [next] hands over at most one key: Page Up/Down once at least half a page
 * ([pagePx]) is pending, else an arrow once at least half a line ([linePx]) is; each takes its
 * size off the pending travel but never past zero, so rounding never sends a key back the other
 * way. The backlog is capped at [MAX_BACKLOG_PAGES] pages, so a long fling does not keep paging
 * long after the finger has gone. Not thread-safe; the caller serialises calls.
 */
class KeyScrollAccumulator {
    private var pending = 0.0

    /** Adds [deltaPx] encoded px of vertical travel; [pagePx] bounds the backlog. */
    fun add(deltaPx: Double, pagePx: Double) {
        if (!deltaPx.isFinite()) return
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
            size >= pagePx / 2 -> (if (up) ScrollKey.PAGE_UP else ScrollKey.PAGE_DOWN) to pagePx
            size >= linePx / 2 -> (if (up) ScrollKey.LINE_UP else ScrollKey.LINE_DOWN) to linePx
            else -> return null
        }
        val left = maxOf(0.0, size - step)
        pending = if (up) left else -left
        return key
    }

    /** Drops the pending travel. */
    fun cancel() {
        pending = 0.0
    }

    private companion object {
        const val MAX_BACKLOG_PAGES = 2.0
    }
}
