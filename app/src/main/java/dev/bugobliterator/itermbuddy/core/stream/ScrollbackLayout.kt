package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.floor

/**
 * History lines above the live band, as iOS sizes the document (`IOS/Screens/SessionView.swift:2046-2089`):
 * `max(liveTop − firstAbsLine, totalLines − rows)`, never negative, and 0 on the alternate screen.
 * The extent ([totalLines]) is the floor, so a stale live top cannot shrink the history.
 */
fun historyLineCount(firstAbsLine: Long, liveTop: Long, totalLines: Int, rows: Int, altScreen: Boolean): Long =
    if (altScreen) 0 else maxOf(0L, liveTop - firstAbsLine, (totalLines - rows).toLong())

/**
 * The scrollable document of a live terminal session, as iOS lays it out
 * (`IOS/Screens/SessionView.swift:2041-2095`): the history lines [firstAbsLine] ..< [liveTop]
 * above, then the live band of [rows] lines (the video), each line [lineHeightPx] tall, with
 * [historyLines] from [historyLineCount] (the same rule as `HistoryTileStore`). On the alternate
 * screen there is no history: the document is the live band alone.
 *
 * Document y is 0 at the top of the history (or of the live band on the alternate screen).
 */
data class ScrollbackLayout(
    val firstAbsLine: Long,
    val liveTop: Long,
    val totalLines: Int,
    val rows: Int,
    val lineHeightPx: Float,
    val altScreen: Boolean,
) {
    /** History lines above the live band: 0 on the alternate screen. */
    val historyLines: Long = historyLineCount(firstAbsLine, liveTop, totalLines, rows, altScreen)

    /** The absolute line at document y 0: the origin, or the live top on the alternate screen. */
    private val topAbsLine: Long = if (altScreen) liveTop else firstAbsLine

    val documentHeightPx: Float = (historyLines + rows) * lineHeightPx

    /** Where the live band (the video) starts. */
    val liveBandTopPx: Float = historyLines * lineHeightPx

    /** The top of a history tile whose first line is [tileFirstAbsLine]. */
    fun tileTopPx(tileFirstAbsLine: Long): Float = (tileFirstAbsLine - topAbsLine) * lineHeightPx

    /**
     * The bottom of a tile of [lineCount] lines from [tileFirstAbsLine]: the top of the line after
     * it, computed from the line number (not top + height), so a tile ending at the live top ends
     * exactly at [liveBandTopPx], with no seam however the line height rounds.
     */
    fun tileBottomPx(tileFirstAbsLine: Long, lineCount: Int): Float = tileTopPx(tileFirstAbsLine + lineCount)

    /** The absolute line at document y [documentYPx], clamped to the document. */
    fun absLineAt(documentYPx: Float): Long {
        if (lineHeightPx <= 0f) return topAbsLine
        val offset = floor(documentYPx / lineHeightPx).toLong()
        val lastLine = historyLines + rows - 1
        return topAbsLine + offset.coerceIn(0, maxOf(0, lastLine))
    }
}
