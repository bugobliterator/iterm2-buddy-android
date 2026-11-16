package dev.bugobliterator.itermbuddy.core.stream

/**
 * Upstream `CompanionCellGeometry`, in encoded pixels:
 * `column = floor((x - leftMargin) / cellWidth)`, `row = floor((y - topMargin) / cellHeight)`.
 */
data class CellGeometry(val cellWidth: Double, val cellHeight: Double, val leftMargin: Double, val topMargin: Double)

/**
 * Upstream `CompanionStreamConfig`: decoder setup and pixel geometry for one generation
 * of a live stream (`sources/Companion/Shared/CompanionMessages.swift`).
 *
 * [streamID] and [generationId] are upstream `UInt32`, [firstAbsLine] `Int64`.
 * [codecExtradata] is the parameter-set container (`u16 count`, then `u32 length || bytes`
 * per set), not hvcC; it is not copied, so callers must not mutate it.
 * Absent fields decode as upstream's custom `init(from:)` does: [cellGeometry] and
 * [canResize] null, [firstAbsLine] and [totalLines] 0, [altScreen],
 * [scrollWheelReporting] and [isBrowser] false (older Macs). A browser config has
 * [columns] and [rows] 0 and no [cellGeometry].
 *
 * Equality compares [codecExtradata] by content.
 */
class StreamConfig(
    val streamID: Long,
    val generationId: Long,
    val codecExtradata: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val scale: Double,
    val columns: Int,
    val rows: Int,
    val cellGeometry: CellGeometry?,
    val firstAbsLine: Long,
    val totalLines: Int,
    val canResize: Boolean?,
    val altScreen: Boolean,
    val scrollWheelReporting: Boolean,
    val isBrowser: Boolean,
) {
    private fun fields(): List<Any?> = listOf(
        streamID, generationId, pixelWidth, pixelHeight, scale, columns, rows, cellGeometry,
        firstAbsLine, totalLines, canResize, altScreen, scrollWheelReporting, isBrowser,
    )

    override fun equals(other: Any?): Boolean =
        other is StreamConfig && other.fields() == fields() && other.codecExtradata.contentEquals(codecExtradata)

    override fun hashCode(): Int = 31 * fields().hashCode() + codecExtradata.contentHashCode()

    override fun toString(): String =
        "StreamConfig(streamID=$streamID, generationId=$generationId, codecExtradata=${codecExtradata.size} bytes, " +
            "pixelWidth=$pixelWidth, pixelHeight=$pixelHeight, scale=$scale, columns=$columns, rows=$rows, " +
            "cellGeometry=$cellGeometry, firstAbsLine=$firstAbsLine, totalLines=$totalLines, canResize=$canResize, " +
            "altScreen=$altScreen, scrollWheelReporting=$scrollWheelReporting, isBrowser=$isBrowser)"
}
