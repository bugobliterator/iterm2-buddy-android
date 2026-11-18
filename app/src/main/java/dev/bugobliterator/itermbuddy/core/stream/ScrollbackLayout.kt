package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.floor

/**
 * The scrollable document of a live terminal session, as iOS lays it out
 * (`IOS/Screens/SessionView.swift:2041-2095`): the history lines [firstAbsLine] ..< [liveTop]
 * above, then the live band of [rows] lines (the video), each line [lineHeightPx] tall. On the
 * alternate screen there is no history: the document is the live band alone.
 *
 * Document y is 0 at the top of the history (or of the live band on the alternate screen).
 */
data class ScrollbackLayout(
    val firstAbsLine: Long,
    val liveTop: Long,
    val rows: Int,
    val lineHeightPx: Float,
    val altScreen: Boolean,
) {
    /** History lines above the live band: 0 on the alternate screen. */
    val historyLines: Long = if (altScreen) 0 else maxOf(0, liveTop - firstAbsLine)

    /** The absolute line at document y 0. */
    private val topAbsLine: Long = liveTop - historyLines

    val documentHeightPx: Float = (historyLines + rows) * lineHeightPx

    /** Where the live band (the video) starts. */
    val liveBandTopPx: Float = historyLines * lineHeightPx

    /** The top of a history tile whose first line is [tileFirstAbsLine]. */
    fun tileTopPx(tileFirstAbsLine: Long): Float = (tileFirstAbsLine - topAbsLine) * lineHeightPx

    /** The absolute line at document y [documentYPx], clamped to the document. */
    fun absLineAt(documentYPx: Float): Long {
        if (lineHeightPx <= 0f) return topAbsLine
        val offset = floor(documentYPx / lineHeightPx).toLong()
        val lastLine = historyLines + rows - 1
        return topAbsLine + offset.coerceIn(0, maxOf(0, lastLine))
    }
}
