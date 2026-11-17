package dev.bugobliterator.itermbuddy.core.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Types and codecs for history tiles, scroll-wheel reports and selection
 * (upstream `sources/Companion/Shared/CompanionMessages.swift`). Encoding writes what Swift's
 * synthesized `Codable` writes; decoding follows `Envelope.kt`'s rules: unknown keys are
 * ignored, optional fields read as `decodeIfPresent`, and a known case with a bad body throws
 * WireDecodeException.
 */

/** Upstream `CompanionSelectionPhase`. */
enum class SelectionPhase(val raw: String) {
    BEGIN("begin"),
    MOVE("move"),
    END("end"),
}

/**
 * Upstream `CompanionSelectionMode`: `character` is exact cells; `word`, `line` and `smart`
 * snap like the Mac's double, triple and smart click. Send the begin's mode on move and end
 * too: the Mac picks the character or snapping path from `mode` on every phase
 * (`CompanionHostBridge.swift:818`), whatever upstream's doc comment says.
 */
enum class SelectionMode(val raw: String) {
    CHARACTER("character"),
    WORD("word"),
    LINE("line"),
    SMART("smart"),
}

/**
 * Upstream `CompanionSelectionPoint`: [absLine] is the absolute (overflow-adjusted) line, Swift
 * `Int64`; [column] is a 0-based VISUAL column (the Mac converts to logical for bidi lines).
 */
data class SelectionPoint(val absLine: Long, val column: Int)

/** Upstream `CompanionSelectionRange`: [start] is the earlier point, [end] the last selected cell (inclusive). */
data class SelectionSpan(val start: SelectionPoint, val end: SelectionPoint)

/**
 * Upstream `CompanionHistoryTile`: the scrollback lines [firstAbsLine] ..< [firstAbsLine] +
 * [lineCount] actually rendered (clamped to what the Mac still has), plus the window available
 * now ([windowFirstAbsLine], [windowLineCount]). An entirely evicted request comes back with
 * [lineCount] 0 and empty [pngData]. [streamID] and [generationId] echo the request (`UInt32`).
 *
 * [pngData] is rendered terminal content: the tile keeps its own copy, taken on construction,
 * hands out a fresh copy on every read, and [toString] shows only its size.
 */
class HistoryTile(
    val streamID: Long,
    val generationId: Long,
    val firstAbsLine: Long,
    val lineCount: Int,
    val windowFirstAbsLine: Long,
    val windowLineCount: Int,
    pngData: ByteArray,
) {
    private val png = pngData.copyOf()

    /** A copy of the PNG bytes. */
    val pngData: ByteArray get() = png.copyOf()

    /** The PNG's size in bytes, for byte budgets, without a copy. */
    val pngSize: Int get() = png.size

    /**
     * Runs [block] on the tile's own PNG bytes, `(bytes, offset, length)`, without a copy: for a
     * decoder such as `BitmapFactory.decodeByteArray`. [block] must not modify or keep the array.
     */
    fun <T> withPng(block: (bytes: ByteArray, offset: Int, length: Int) -> T): T = block(png, 0, png.size)

    private fun fields(): List<Any> =
        listOf(streamID, generationId, firstAbsLine, lineCount, windowFirstAbsLine, windowLineCount)

    override fun equals(other: Any?): Boolean =
        other is HistoryTile && other.fields() == fields() && other.png.contentEquals(png)

    override fun hashCode(): Int = 31 * fields().hashCode() + png.contentHashCode()

    override fun toString(): String =
        "HistoryTile(streamID=$streamID, generationId=$generationId, firstAbsLine=$firstAbsLine, " +
            "lineCount=$lineCount, windowFirstAbsLine=$windowFirstAbsLine, windowLineCount=$windowLineCount, " +
            "pngData=${png.size} bytes)"
}

private fun requireU32(name: String, value: Long) {
    require(value in 0..U32_MAX) { "$name must be a UInt32" }
}

private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))

/** The Mac renders at most this many lines per tile (`CompanionHostBridge.swift:2044`). */
const val MAX_HISTORY_TILE_LINES = 200

internal fun encodeFetchHistoryTile(message: PhoneMessage.FetchHistoryTile): JsonObject {
    requireU32("streamID", message.streamID)
    require(message.lineCount in 1..MAX_HISTORY_TILE_LINES) { "lineCount must be in 1..$MAX_HISTORY_TILE_LINES" }
    requireU32("generationId", message.generationId)
    return case(
        "fetchHistoryTile",
        obj(
            "streamID" to JsonPrimitive(message.streamID),
            "firstAbsLine" to JsonPrimitive(message.firstAbsLine),
            "lineCount" to JsonPrimitive(message.lineCount),
            "generationId" to JsonPrimitive(message.generationId),
        ),
    )
}

internal fun encodeReportScrollWheel(message: PhoneMessage.ReportScrollWheel): JsonObject {
    requireU32("streamID", message.streamID)
    // iOS never sends a zero notch count (AppModel.swift:4658).
    require(message.lines > 0) { "lines must be positive" }
    return case(
        "reportScrollWheel",
        obj(
            "streamID" to JsonPrimitive(message.streamID),
            "up" to JsonPrimitive(message.up),
            "lines" to JsonPrimitive(message.lines),
        ),
    )
}

internal fun encodeSelectionGesture(message: PhoneMessage.SelectionGesture): JsonObject {
    requireU32("streamID", message.streamID)
    return case(
        "selectionGesture",
        obj(
            "streamID" to JsonPrimitive(message.streamID),
            "phase" to JsonPrimitive(message.phase.raw),
            "mode" to JsonPrimitive(message.mode.raw),
            "point" to encodePoint(message.point),
        ),
    )
}

private fun encodePoint(point: SelectionPoint): JsonObject =
    obj("absLine" to JsonPrimitive(point.absLine), "column" to JsonPrimitive(point.column))

/** `clearSelection(streamID:)` and `selectAllInStream(streamID:)`. */
internal fun encodeStreamOnlyCase(name: String, streamID: Long): JsonObject {
    requireU32("streamID", streamID)
    return case(name, obj("streamID" to JsonPrimitive(streamID)))
}

internal fun encodeCopySelection(message: PhoneMessage.CopySelection): JsonObject =
    case("copySelection", obj("sessionGuid" to JsonPrimitive(message.sessionGuid)))

internal fun decodeHistoryTile(body: JsonElement): MacMessage {
    val case = "historyTile"
    val tile = body.asObject(case).obj(case, UNLABELLED)
    return MacMessage.HistoryTileMessage(
        HistoryTile(
            streamID = tile.u32(case, "streamID"),
            generationId = tile.u32(case, "generationId"),
            firstAbsLine = tile.long(case, "firstAbsLine"),
            lineCount = tile.int(case, "lineCount"),
            windowFirstAbsLine = tile.long(case, "windowFirstAbsLine"),
            windowLineCount = tile.int(case, "windowLineCount"),
            pngData = tile.base64(case, "pngData"),
        ),
    )
}

internal fun decodeStreamExtent(body: JsonElement): MacMessage {
    val case = "streamExtent"
    val extent = body.asObject(case)
    return MacMessage.StreamExtent(
        streamID = extent.u32(case, "streamID"),
        firstAbsLine = extent.long(case, "firstAbsLine"),
        totalLines = extent.int(case, "totalLines"),
    )
}

/** `range` is optional: absent (Swift omits a nil) or `null` means no selection. */
internal fun decodeSelectionRange(body: JsonElement): MacMessage {
    val case = "selectionRange"
    val message = body.asObject(case)
    val range = message.ifPresent("range") { key ->
        val span = obj(case, key)
        SelectionSpan(decodePoint(span.obj("$case.range", "start")), decodePoint(span.obj("$case.range", "end")))
    }
    return MacMessage.SelectionRange(message.u32(case, "streamID"), range)
}

private fun decodePoint(point: JsonObject): SelectionPoint {
    val case = "selectionRange.point"
    return SelectionPoint(point.long(case, "absLine"), point.int(case, "column"))
}

internal fun decodeSelectionText(body: JsonElement): MacMessage {
    val case = "selectionText"
    return MacMessage.SelectionText(body.asObject(case).string(case, "text"))
}
