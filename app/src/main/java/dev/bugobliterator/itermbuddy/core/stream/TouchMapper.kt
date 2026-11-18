package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlin.math.floor

/*
 * Image pixels to selection points, as upstream `CompanionTouchMapper.selectionPoint`
 * (`sources/Companion/Shared/CompanionTouchMapper.swift:30-49`): clamped to the grid, never null
 * for a point off the image, so a drag past an edge selects up to it. Columns are visual, as the
 * Mac expects them (`CompanionHostBridge.swift:984-988`).
 */

/**
 * The cell under the live video's image pixel ([imageX], [imageY]):
 * `column = floor((x − leftMargin) / cellWidth)`, `row = floor((y − topMargin) / cellHeight)`,
 * both clamped to [config]'s grid, and `absLine = liveTop + row`. Null for a browser, without
 * `cellGeometry` (selection needs it, as iOS's `sessionSelectionSupported`, `AppModel.swift:1082-1084`),
 * for a cell size that is not positive, or for a coordinate that is not a number.
 */
fun cellAt(imageX: Float, imageY: Float, config: StreamConfig, liveTop: Long): SelectionPoint? {
    val cell = selectableGeometry(config) ?: return null
    if (!imageX.isFinite() || !imageY.isFinite()) return null
    val column = clampIndex(floor((imageX - cell.leftMargin) / cell.cellWidth), config.columns)
    val row = clampIndex(floor((imageY - cell.topMargin) / cell.cellHeight), config.rows)
    return SelectionPoint(liveTop + row, column)
}

/**
 * The cell on history line [absLine] under image x [imageX], where [imageX] is in the live video's
 * image space across the band's span (`documentXToImageX`). History tiles are rendered without
 * margins (`includeMargins: false`, `CompanionHostBridge.swift:2145-2149`) and drawn across the
 * band's whole width, so the column is taken in tile space, `floor(imageX / pixelWidth × columns)`,
 * not through the video's margins; clamped to the grid. Null as for [cellAt].
 *
 * iOS maps history through the video's margins (`IOS/Screens/SessionView.swift:2710-2717`), which
 * is off by up to a margin's width over a tile; this follows the tile as drawn.
 */
fun historyCellAt(absLine: Long, imageX: Float, config: StreamConfig): SelectionPoint? {
    selectableGeometry(config) ?: return null
    if (!imageX.isFinite() || config.pixelWidth <= 0) return null
    val column = clampIndex(floor(imageX.toDouble() / config.pixelWidth * config.columns), config.columns)
    return SelectionPoint(absLine, column)
}

/** [config]'s cell geometry when a selection can be mapped on it. */
private fun selectableGeometry(config: StreamConfig): CellGeometry? {
    val cell = config.cellGeometry ?: return null
    if (config.isBrowser || config.columns <= 0 || config.rows <= 0) return null
    if (!(cell.cellWidth > 0.0) || !(cell.cellHeight > 0.0) || !cell.leftMargin.isFinite() || !cell.topMargin.isFinite()) return null
    return cell
}

private fun clampIndex(value: Double, count: Int): Int = value.coerceIn(0.0, (count - 1).toDouble()).toInt()
