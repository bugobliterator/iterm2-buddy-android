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
 * per set), not hvcC. The config keeps its own copy, taken on construction, and hands out a
 * fresh copy on every read, so [equals] and [hashCode] never change.
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
    codecExtradata: ByteArray,
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
    private val extradata = codecExtradata.copyOf()

    /** A copy of the parameter-set container. */
    val codecExtradata: ByteArray get() = extradata.copyOf()

    /** This config with the named fields replaced, as a data class `copy` would. */
    fun copy(
        streamID: Long = this.streamID,
        generationId: Long = this.generationId,
        codecExtradata: ByteArray = this.extradata,
        pixelWidth: Int = this.pixelWidth,
        pixelHeight: Int = this.pixelHeight,
        scale: Double = this.scale,
        columns: Int = this.columns,
        rows: Int = this.rows,
        cellGeometry: CellGeometry? = this.cellGeometry,
        firstAbsLine: Long = this.firstAbsLine,
        totalLines: Int = this.totalLines,
        canResize: Boolean? = this.canResize,
        altScreen: Boolean = this.altScreen,
        scrollWheelReporting: Boolean = this.scrollWheelReporting,
        isBrowser: Boolean = this.isBrowser,
    ): StreamConfig = StreamConfig(
        streamID, generationId, codecExtradata, pixelWidth, pixelHeight, scale, columns, rows, cellGeometry,
        firstAbsLine, totalLines, canResize, altScreen, scrollWheelReporting, isBrowser,
    )

    private fun fields(): List<Any?> = listOf(
        streamID, generationId, pixelWidth, pixelHeight, scale, columns, rows, cellGeometry,
        firstAbsLine, totalLines, canResize, altScreen, scrollWheelReporting, isBrowser,
    )

    override fun equals(other: Any?): Boolean =
        other is StreamConfig && other.fields() == fields() && other.extradata.contentEquals(extradata)

    override fun hashCode(): Int = 31 * fields().hashCode() + extradata.contentHashCode()

    override fun toString(): String =
        "StreamConfig(streamID=$streamID, generationId=$generationId, codecExtradata=${extradata.size} bytes, " +
            "pixelWidth=$pixelWidth, pixelHeight=$pixelHeight, scale=$scale, columns=$columns, rows=$rows, " +
            "cellGeometry=$cellGeometry, firstAbsLine=$firstAbsLine, totalLines=$totalLines, canResize=$canResize, " +
            "altScreen=$altScreen, scrollWheelReporting=$scrollWheelReporting, isBrowser=$isBrowser)"
}
