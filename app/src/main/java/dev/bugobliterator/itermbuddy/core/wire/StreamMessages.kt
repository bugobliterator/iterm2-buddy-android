package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import dev.bugobliterator.itermbuddy.core.stream.CellGeometry
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Codecs for the live-session cases of PhoneMessage and MacMessage
 * (upstream `sources/Companion/Shared/CompanionMessages.swift`). Encoding writes what
 * Swift's synthesized `Codable` writes, keys in any order (only the tree is compared).
 * Decoding follows `Envelope.kt`'s rules: unknown keys are ignored, optional fields
 * read as `decodeIfPresent`, and a known case with a bad body throws WireDecodeException.
 */

/**
 * Upstream `CompanionStreamParams`. The defaults are exactly what iOS sends and what this
 * app sends: `{"supportedCodecs":["hevc"],"maxFrameRate":30,"maxMediaFrameVersion":2}`.
 * `maxBitrate` is never sent (Swift omits a nil optional), nor is a null
 * [maxMediaFrameVersion].
 */
data class StreamParams(
    val supportedCodecs: List<StreamCodec> = listOf(StreamCodec.HEVC),
    val maxFrameRate: Double = 30.0,
    val maxMediaFrameVersion: Int? = 2,
) {
    init {
        require(StreamCodec.UNKNOWN !in supportedCodecs) { "only a known codec can be offered" }
    }
}

/**
 * Upstream `CompanionStreamCodec`. [UNKNOWN] (raw null) is Android's own: a codec string this
 * build does not know decodes to it rather than failing the `streamStarted` reply, and it can
 * never be offered in [StreamParams]. v3.7.7 always answers `hevc`. The stream controller must
 * stop any started stream whose codec is not [HEVC]; it is the only one this app decodes.
 */
enum class StreamCodec(val raw: String?) {
    HEVC("hevc"),
    H264("h264"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromRaw(raw: String): StreamCodec = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * Upstream `CompanionStreamEndReason`. [UNKNOWN] (raw null) is Android's own: a reason this
 * build does not know, or a missing or non-string one, decodes to it, so a stream end is
 * never refused.
 * [ERROR] is defined upstream and never sent by v3.7.7.
 */
enum class StreamEndReason(val raw: String?) {
    STOPPED_BY_CLIENT("stoppedByClient"),
    SESSION_CLOSED("sessionClosed"),
    SUPERSEDED("superseded"),
    DATA_LIMIT_REACHED("dataLimitReached"),
    ERROR("error"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromRaw(raw: String): StreamEndReason = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

private fun requireU32(name: String, value: Long) {
    require(value in 0..U32_MAX) { "$name must be a UInt32" }
}

private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))

internal fun encodeStartSessionStream(message: PhoneMessage.StartSessionStream): JsonObject {
    val params = message.params
    // Insertion order is the iOS text order, which startParamsAreExactlyIos pins.
    val paramsBody = linkedMapOf<String, JsonElement>(
        "supportedCodecs" to JsonArray(params.supportedCodecs.map { JsonPrimitive(checkNotNull(it.raw)) }),
        "maxFrameRate" to swiftDouble(params.maxFrameRate),
    )
    params.maxMediaFrameVersion?.let { paramsBody["maxMediaFrameVersion"] = JsonPrimitive(it) }
    return case(
        "startSessionStream",
        obj("sessionGuid" to JsonPrimitive(message.sessionGuid), "params" to JsonObject(paramsBody)),
    )
}

internal fun encodeStreamIdCase(name: String, streamID: Long): JsonObject {
    requireU32("streamID", streamID)
    return case(name, obj("streamID" to JsonPrimitive(streamID)))
}

internal fun encodeStreamAck(message: PhoneMessage.StreamAck): JsonObject {
    requireU32("streamID", message.streamID)
    require(message.lastPTSMilliseconds >= 0) { "lastPTSMilliseconds is a UInt64" }
    return case(
        "streamAck",
        obj(
            "streamID" to JsonPrimitive(message.streamID),
            "lastPTSMilliseconds" to JsonPrimitive(message.lastPTSMilliseconds),
            "queueDepth" to JsonPrimitive(message.queueDepth),
        ),
    )
}

internal fun encodeSendKey(message: PhoneMessage.SendKey): JsonObject =
    case("sendKey", obj("sessionGuid" to JsonPrimitive(message.sessionGuid), "event" to encodeKeyEvent(message.event)))

private fun encodeKeyEvent(event: KeyEvent): JsonObject {
    val key = when (val k = event.key) {
        is Key.Text -> case("text", obj(UNLABELLED to JsonPrimitive(k.text)))
        is Key.Special -> case("special", obj(UNLABELLED to JsonPrimitive(k.key.raw)))
    }
    val m = event.modifiers
    // All four always present: the Mac's synthesized decode requires every key.
    val modifiers = obj(
        "control" to JsonPrimitive(m.control),
        "shift" to JsonPrimitive(m.shift),
        "leftOption" to JsonPrimitive(m.leftOption),
        "rightOption" to JsonPrimitive(m.rightOption),
    )
    return obj("key" to key, "modifiers" to modifiers)
}

internal fun encodePasteText(message: PhoneMessage.PasteText): JsonObject =
    case("pasteText", obj("sessionGuid" to JsonPrimitive(message.sessionGuid), "text" to JsonPrimitive(message.text)))

internal fun encodeResizeSession(message: PhoneMessage.ResizeSession): JsonObject = case(
    "resizeSession",
    obj(
        "sessionGuid" to JsonPrimitive(message.sessionGuid),
        "columns" to JsonPrimitive(message.columns),
        "rows" to JsonPrimitive(message.rows),
    ),
)

/** The value of a single-value case, `{"_0": {...}}`. */
private fun JsonElement.unlabelled(case: String): JsonObject = asObject(case).obj(case, UNLABELLED)

private fun JsonArray.objects(path: String): List<JsonObject> =
    map { it as? JsonObject ?: throw WireDecodeException("$path holds a non-object") }

internal fun decodeSessionTree(body: JsonElement): MacMessage {
    val tree = body.unlabelled("sessionTree")
    val windows = tree.array("sessionTree", "windows").objects("sessionTree.windows").map { window ->
        val tabs = window.array("window", "tabs").objects("window.tabs").map { tab ->
            val panes = tab.array("tab", "panes").objects("tab.panes").map { pane ->
                val peers = pane.array("pane", "peers").objects("pane.peers").map { peer ->
                    PeerEntry(peer.string("peer", "roleName"), decodeSessionSummary(peer.obj("peer", "session")))
                }
                PaneEntry(decodeSessionSummary(pane.obj("pane", "session")), peers)
            }
            TabEntry(tab.string("tab", "title"), panes)
        }
        WindowEntry(window.string("window", "title"), tabs)
    }
    return MacMessage.SessionTreeMessage(SessionTree(windows))
}

/** Upstream's custom decode: `isBrowser` absent (a Mac before revision 15) reads false. */
private fun decodeSessionSummary(summary: JsonObject): SessionSummary = SessionSummary(
    guid = summary.string("session", "guid"),
    name = summary.string("session", "name"),
    subtitle = summary.string("session", "subtitle"),
    isBrowser = summary.ifPresent("isBrowser") { boolean("session", it) } ?: false,
)

internal fun decodeStreamStarted(body: JsonElement): MacMessage {
    val started = body.unlabelled("streamStarted")
    // An unknown codec string is UNKNOWN, not a bad body; a missing or non-string one still throws.
    val codec = StreamCodec.fromRaw(started.string("streamStarted", "codec"))
    return MacMessage.StreamStarted(started.u32("streamStarted", "streamID"), codec)
}

/** Upstream `CompanionStreamConfig.init(from:)`: required fields first, then the `decodeIfPresent` ones. */
internal fun decodeStreamConfig(body: JsonElement): MacMessage {
    val case = "streamConfig"
    val c = body.unlabelled(case)
    val config = StreamConfig(
        streamID = c.u32(case, "streamID"),
        generationId = c.u32(case, "generationId"),
        codecExtradata = c.base64(case, "codecExtradata"),
        pixelWidth = c.int(case, "pixelWidth"),
        pixelHeight = c.int(case, "pixelHeight"),
        scale = c.double(case, "scale"),
        columns = c.int(case, "columns"),
        rows = c.int(case, "rows"),
        cellGeometry = c.ifPresent("cellGeometry") { decodeCellGeometry(obj(case, it)) },
        firstAbsLine = c.ifPresent("firstAbsLine") { long(case, it) } ?: 0L,
        totalLines = c.ifPresent("totalLines") { int(case, it) } ?: 0,
        canResize = c.ifPresent("canResize") { boolean(case, it) },
        altScreen = c.ifPresent("altScreen") { boolean(case, it) } ?: false,
        scrollWheelReporting = c.ifPresent("scrollWheelReporting") { boolean(case, it) } ?: false,
        isBrowser = c.ifPresent("isBrowser") { boolean(case, it) } ?: false,
    )
    return MacMessage.StreamConfigMessage(config)
}

private fun decodeCellGeometry(geometry: JsonObject): CellGeometry {
    val case = "streamConfig.cellGeometry"
    return CellGeometry(
        cellWidth = geometry.double(case, "cellWidth"),
        cellHeight = geometry.double(case, "cellHeight"),
        leftMargin = geometry.double(case, "leftMargin"),
        topMargin = geometry.double(case, "topMargin"),
    )
}

internal fun decodeStreamEnded(body: JsonElement): MacMessage {
    val ended = body.asObject("streamEnded")
    // Lenient beyond upstream: a stream end is never refused, whatever its reason.
    val raw = (ended["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val reason = raw?.let(StreamEndReason::fromRaw) ?: StreamEndReason.UNKNOWN
    return MacMessage.StreamEnded(ended.u32("streamEnded", "streamID"), reason)
}
