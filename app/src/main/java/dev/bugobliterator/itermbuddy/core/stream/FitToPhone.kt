package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.ceil
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
 * `rows × cellHeight + 2 × topMargin ≤ maxH`. Both are at least 1. Then, with [sizeSupported]
 * (the decoder's own check, which can also limit area or alignment: [VideoOutput.sizeSupported]),
 * the rows are stepped down, and then the columns, until the grid's frame
 * (`ceil(columns × cellWidth + 2 × leftMargin)` by `ceil(rows × cellHeight + 2 × topMargin)`) is
 * supported; null when even 1×1 is not.
 *
 * The viewport is the live canvas measured with the keyboard hidden, in any one unit (only its
 * aspect and the cell aspect matter).
 */
fun fitGrid(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    config: StreamConfig,
    decoderMax: Pair<Int, Int>?,
    sizeSupported: ((Int, Int) -> Boolean)? = null,
): Grid? {
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
    if (sizeSupported == null) return Grid(columns, rows)
    val supported = { c: Int, r: Int ->
        sizeSupported(framePixels(c, cell.cellWidth, cell.leftMargin), framePixels(r, cell.cellHeight, cell.topMargin))
    }
    var ok = supported(columns, rows)
    while (!ok && rows > 1) ok = supported(columns, --rows)
    while (!ok && columns > 1) ok = supported(--columns, rows)
    return if (ok) Grid(columns, rows) else null
}

/** The frame side for [cells] cells of [size] with [margin] on both sides, rounded up. */
private fun framePixels(cells: Int, size: Double, margin: Double): Int =
    ceil(cells * size + 2 * margin).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()

/** The fewest columns [correctedFitGrid] asks for: narrower than this, a shell line is too short to use. */
const val FIT_MIN_CORRECTED_COLUMNS = 40

/** [correctedFitGrid] leaves a fit alone unless the Mac capped its rows by more than this share (a row or two is not worth a resize). */
const val FIT_CORRECTION_MIN_SHORTFALL = 0.05

/**
 * The one corrective step after a fit the Mac capped. iTerm2 grows a window's rows only as far as
 * its screens allow (`PseudoTerminal.m` `safelySetSessionSize:rows:columns:`, "Cap growth if it
 * exceeds the maximum", rows only; the columns are taken as asked), and a split pane shares the
 * window's height with its siblings, so a tall phone's [requested] rows can come back as fewer
 * [achieved] rows with all the columns: a grid wider than the phone, drawn with black below it.
 *
 * Returns the grid that fills the viewport at the [achieved] rows: those rows and
 * `floor(rows × viewportW / viewportH × cellHeight / cellWidth)` columns (the cell from [config],
 * the config that reported [achieved], as [fitGrid] reads it), at least [FIT_MIN_CORRECTED_COLUMNS].
 * Null when no correction applies: the rows were not capped by more than
 * [FIT_CORRECTION_MIN_SHORTFALL] of [requested]'s, the result would not be narrower than
 * [achieved], the viewport is empty or the cell unreadable.
 */
fun correctedFitGrid(
    requested: Grid,
    achieved: Grid,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    config: StreamConfig,
): Grid? {
    if (achieved.rows <= 0 || achieved.rows >= requested.rows * (1 - FIT_CORRECTION_MIN_SHORTFALL)) return null
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
    val cell = effectiveCellGeometry(config) ?: return null
    val fitting = achieved.rows.toDouble() * viewportWidthPx / viewportHeightPx * cell.cellHeight / cell.cellWidth
    val columns = maxOf(FIT_MIN_CORRECTED_COLUMNS, floorAtLeastOne(fitting))
    return if (columns < achieved.columns) Grid(columns, achieved.rows) else null
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
