package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlin.math.floor

/** A terminal size in cells, as `resizeSession` sends it. */
data class Grid(val columns: Int, val rows: Int)

/** Fit to phone's column count in portrait (viewport not wider than tall), as iOS. */
const val FIT_PORTRAIT_COLUMNS = 60

/** Fit to phone's column count in landscape, as iOS. */
const val FIT_LANDSCAPE_COLUMNS = 120

/**
 * The grid **Fit to phone** asks the Mac for: iOS `resizeActiveSessionForLegibility`
 * (`IOS/App/AppModel.swift:5122-5153`), plus the decoder caps.
 *
 * Null for a browser config, unless [StreamConfig.canResize] is true (false **or absent** gives
 * null), for an empty viewport, and when no cell size can be read: the cell is
 * [StreamConfig.cellGeometry] when it is positive, else the image over the grid
 * (`pixelWidth / columns`, `pixelHeight / rows`, no margins), which needs a non-zero grid.
 *
 * Columns are 120 when the viewport is wider than tall, else 60. With [decoderMax] (the decoder's
 * width to height), columns are first capped so `columns × cellWidth + 2 × leftMargin ≤ maxW`;
 * **then** the rows are those that fill the viewport at the capped columns' cell size,
 * `floor(viewportH / (viewportW / columns × cellH / cellW))`, capped so
 * `rows × cellHeight + 2 × topMargin ≤ maxH`. Both are at least 1.
 *
 * The viewport is the live canvas measured with the keyboard hidden, in any one unit (only its
 * aspect and the cell aspect matter).
 */
fun fitGrid(viewportWidthPx: Int, viewportHeightPx: Int, config: StreamConfig, decoderMax: Pair<Int, Int>?): Grid? {
    if (config.isBrowser || config.canResize != true) return null
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
    val cell = effectiveCellGeometry(config) ?: return null
    var columns = if (viewportWidthPx > viewportHeightPx) FIT_LANDSCAPE_COLUMNS else FIT_PORTRAIT_COLUMNS
    if (decoderMax != null) {
        columns = minOf(columns, floorAtLeastOne((decoderMax.first - 2 * cell.leftMargin) / cell.cellWidth))
    }
    val cellViewHeight = viewportWidthPx.toDouble() / columns * cell.cellHeight / cell.cellWidth
    var rows = floorAtLeastOne(viewportHeightPx / cellViewHeight)
    if (decoderMax != null) {
        rows = minOf(rows, floorAtLeastOne((decoderMax.second - 2 * cell.topMargin) / cell.cellHeight))
    }
    return Grid(columns, rows)
}

/**
 * Fit to phone: sends `resizeSession` with [fitGrid] for [stream]'s session on [stream]'s link
 * only (a link that has ended sends nothing and throws, as [StreamLink.send]). Returns the grid
 * sent, or null when there is none (no config yet, a browser, or a window that cannot resize).
 * Only an explicit tap calls this; nothing ever undoes it.
 *
 * @throws dev.bugobliterator.itermbuddy.core.CompanionRequestException as [StreamLink.send].
 */
suspend fun fitToPhone(
    link: StreamLink,
    stream: ActiveStream,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    decoderMax: Pair<Int, Int>?,
): Grid? {
    val config = stream.config ?: return null
    val grid = fitGrid(viewportWidthPx, viewportHeightPx, config, decoderMax) ?: return null
    link.send(PhoneMessage.ResizeSession(stream.sessionGuid, grid.columns, grid.rows), onLink = stream.linkId)
    return grid
}

/**
 * The cell in encoded pixels: the reported [StreamConfig.cellGeometry] when its size is positive,
 * else the image over the grid with no margins (iOS order); null when neither is usable (a browser
 * config's 0×0 grid with no geometry).
 */
internal fun effectiveCellGeometry(config: StreamConfig): CellGeometry? {
    val reported = config.cellGeometry
    if (reported != null && usable(reported.cellWidth) && usable(reported.cellHeight)) {
        return reported.copy(
            leftMargin = reported.leftMargin.takeIf { it.isFinite() } ?: 0.0,
            topMargin = reported.topMargin.takeIf { it.isFinite() } ?: 0.0,
        )
    }
    if (config.columns <= 0 || config.rows <= 0 || config.pixelWidth <= 0 || config.pixelHeight <= 0) return null
    return CellGeometry(config.pixelWidth.toDouble() / config.columns, config.pixelHeight.toDouble() / config.rows, 0.0, 0.0)
}

private fun usable(value: Double): Boolean = value.isFinite() && value > 0

private fun floorAtLeastOne(value: Double): Int =
    if (value.isNaN()) 1 else floor(value).coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
