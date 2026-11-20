package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.abs

/**
 * The wheel step: [lineHeightPx] (the video's line height as displayed at fit) when it is a usable
 * size, else [fallbackPx] (24 dp), as iOS falls back to 24 pt before any layout
 * (`IOS/Screens/SessionView.swift:2442-2444`).
 */
fun wheelStepPx(lineHeightPx: Float, fallbackPx: Float): Float =
    if (lineHeightPx.isFinite() && lineHeightPx > 0.5f) lineHeightPx else fallbackPx

/**
 * Turns vertical finger travel past the scroll edge into terminal mouse-wheel notches, one per
 * [pointsPerLine] px, for a stream on the alternate screen with mouse-wheel reporting on
 * (`IOS/Screens/SessionView.swift:2422-2448`). Finger moving down (positive delta) reveals older
 * content: a notch `true` (up). The remainder short of a whole notch is carried to the next drag.
 *
 * Android-only: a drag in the other direction drops the remainder first, so a reversal answers
 * within one notch of travel (iOS nets the two). Call [reset] when a gesture starts, as iOS zeroes
 * its accumulator then (`SessionView.swift:2406-2416`). Android-only: a release that flicks keeps
 * the remainder for the coast that follows (the canvas feeds the coast's travel through [drag]
 * too, [WheelCoastBudget] bounding it), so no travel is lost between the drag and the coast; a
 * release that does not coast resets it.
 *
 * Not thread-safe: the gesture handler's thread only. Holds no terminal content.
 */
class ScrollWheelAccumulator(private val pointsPerLine: Float) {
    private var remainder = 0f

    /**
     * Adds [deltaYPx] of past-edge travel; one entry per whole notch reached, in order, `true` for
     * up (older content). Empty for a non-finite delta or an unusable [pointsPerLine].
     */
    fun drag(deltaYPx: Float): List<Boolean> {
        if (!deltaYPx.isFinite() || deltaYPx == 0f || !pointsPerLine.isFinite() || pointsPerLine <= 0f) return emptyList()
        if (remainder != 0f && (remainder > 0f) != (deltaYPx > 0f)) remainder = 0f
        remainder += deltaYPx
        val notches = (abs(remainder) / pointsPerLine).toInt()
        if (notches == 0) return emptyList()
        val up = remainder > 0f
        remainder -= (if (up) 1 else -1) * notches * pointsPerLine
        return List(notches) { up }
    }

    /** Drops any partial notch. */
    fun reset() {
        remainder = 0f
    }
}

/** While a flick coasts in wheel mode, at most this many notches go out per frame (one `reportScrollWheel`). */
const val WHEEL_COAST_NOTCHES_PER_FRAME = 3

/** A flick in wheel mode coasts at most this many notches in all. */
const val WHEEL_COAST_MAX_NOTCHES = 120

/**
 * How many notches a coasting flick may still send ([WHEEL_COAST_NOTCHES_PER_FRAME] per frame,
 * [WHEEL_COAST_MAX_NOTCHES] in all), so a hard flick in vim or less scrolls a few screens rather
 * than hundreds of lines, and never floods the link. Notches over the frame's share are dropped,
 * not owed. Not thread-safe.
 */
class WheelCoastBudget(private val perFrame: Int = WHEEL_COAST_NOTCHES_PER_FRAME, private val total: Int = WHEEL_COAST_MAX_NOTCHES) {
    /** Notches let through so far. */
    var sent: Int = 0
        private set

    /** Whether the coast has sent all it may. */
    val exhausted: Boolean get() = sent >= total

    /** Of [notches] reached this frame, how many to send (0 or more); counts them as sent. */
    fun take(notches: Int): Int {
        val allowed = minOf(notches, perFrame, total - sent).coerceAtLeast(0)
        sent += allowed
        return allowed
    }
}
