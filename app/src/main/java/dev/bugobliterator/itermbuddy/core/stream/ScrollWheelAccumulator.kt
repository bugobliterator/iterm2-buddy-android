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
 * within one notch of travel (iOS nets the two). Call [reset] when a gesture starts and ends, as
 * iOS zeroes its accumulator then (`SessionView.swift:2406-2416`).
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
