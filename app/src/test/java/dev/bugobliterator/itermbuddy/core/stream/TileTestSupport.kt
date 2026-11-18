package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.util.Base64

/** Builders shared by the history tile tests. */
internal object Tiles {
    /** A terminal config with the given extent; `liveTop` snaps to `firstAbsLine + totalLines − rows`. */
    fun config(
        firstAbsLine: Long,
        totalLines: Int,
        rows: Int = 40,
        generation: Long = 1,
        streamID: Long = 1,
        altScreen: Boolean = false,
    ) = StreamConfig(
        streamID, generation, ByteArray(0), 1280, 800, 2.0, 80, rows, CellGeometry(16.0, 32.0, 0.0, 0.0),
        firstAbsLine, totalLines, true, altScreen, false, false,
    )

    /** The reply the Mac would send to [request]: [lineCount] lines (all it asked by default) and a PNG of [pngBytes]. */
    fun reply(
        request: PhoneMessage.FetchHistoryTile,
        lineCount: Int = request.lineCount,
        firstAbsLine: Long = request.firstAbsLine,
        pngBytes: Int = 16,
    ): MacMessage = MacMessage.HistoryTileMessage(
        HistoryTile(request.streamID, request.generationId, firstAbsLine, lineCount, 0, 100_000, ByteArray(pngBytes)),
    )

    fun span(startLine: Long, endLine: Long) = SelectionSpan(SelectionPoint(startLine, 0), SelectionPoint(endLine, 10))

    /** `{"historyTile": {"_0": ...}}` answering the `fetchHistoryTile` body [body]. */
    fun tilePayload(body: JsonObject, pngBytes: Int = 16): JsonObject {
        val tile = JsonObject(
            mapOf(
                "streamID" to body.getValue("streamID"),
                "generationId" to body.getValue("generationId"),
                "firstAbsLine" to body.getValue("firstAbsLine"),
                "lineCount" to body.getValue("lineCount"),
                "windowFirstAbsLine" to JsonPrimitive(0),
                "windowLineCount" to JsonPrimitive(100_000),
                "pngData" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(pngBytes))),
            ),
        )
        return JsonObject(mapOf("historyTile" to JsonObject(mapOf("_0" to tile))))
    }

    /** The `fetchHistoryTile` body of a phone envelope. */
    fun fetchBody(envelope: JsonObject): JsonObject =
        envelope.getValue("payload").jsonObject.getValue("fetchHistoryTile").jsonObject

    fun JsonObject.longAt(key: String): Long = getValue(key).jsonPrimitive.long

    fun selectionRangePayload(streamID: Long, span: SelectionSpan?): JsonObject {
        fun point(p: SelectionPoint) = JsonObject(mapOf("absLine" to JsonPrimitive(p.absLine), "column" to JsonPrimitive(p.column)))
        val range: JsonElement = span?.let { JsonObject(mapOf("start" to point(it.start), "end" to point(it.end))) } ?: JsonNull
        return JsonObject(mapOf("selectionRange" to JsonObject(mapOf("streamID" to JsonPrimitive(streamID), "range" to range))))
    }

    fun extentPayload(streamID: Long, firstAbsLine: Long, totalLines: Int): JsonObject = JsonObject(
        mapOf(
            "streamExtent" to JsonObject(
                mapOf(
                    "streamID" to JsonPrimitive(streamID),
                    "firstAbsLine" to JsonPrimitive(firstAbsLine),
                    "totalLines" to JsonPrimitive(totalLines),
                ),
            ),
        ),
    )

    /** A version 2 media frame (header and payload) with [liveTop], as the Mac encodes it. */
    fun frame(streamID: Long, pts: Long, liveTop: Long, keyframe: Boolean = false, generation: Long = 1): ByteArray {
        val payload = 16
        return ByteBuffer.allocate(34 + payload)
            .put(2).put((if (keyframe) 1 else 0).toByte()).putInt(streamID.toInt()).putInt(pts.toInt()).putLong(pts)
            .putInt(generation.toInt()).putLong(liveTop).putInt(payload)
            .array()
    }

    /** [frame] as the sink receives it, behind the media channel byte. */
    fun media(linkId: Long, encoded: ByteArray) = StreamInput.Media(linkId, byteArrayOf(MEDIA_CHANNEL) + encoded)

    fun Map<Long, TileState>.ready(): List<Long> = filterValues { it is TileState.Ready }.keys.toList()
}
