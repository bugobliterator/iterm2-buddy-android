package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A message the phone sends to the Mac: the pairing and live-session subset of upstream
 * `CompanionClientMessage` (`sources/Companion/Shared/CompanionMessages.swift`).
 * The live-session cases' encoders are in `StreamMessages.kt`.
 *
 * Payloads follow Swift's synthesized enum coding: `{"<case>": {...}}`, labelled
 * values by name, an unlabelled value under `_0`, `{}` for a case with no values,
 * and `Data` as standard padded base64.
 */
sealed interface PhoneMessage {
    /** The payload object, `{"<case>": {...}}`. */
    fun payload(): JsonObject

    /** `hello(revision:minimumPeer:)`. The defaults are what this app advertises. */
    data class Hello(
        val revision: Int = ProtocolRevision.REVISION,
        val minimumPeer: Int = ProtocolRevision.MINIMUM_PEER,
    ) : PhoneMessage {
        override fun payload(): JsonObject = case(
            "hello",
            JsonObject(mapOf("revision" to JsonPrimitive(revision), "minimumPeer" to JsonPrimitive(minimumPeer))),
        )
    }

    /** `relayRoomSecret(Data)`. Holds its own copy of [secret]. */
    class RelayRoomSecret(secret: ByteArray) : PhoneMessage {
        private val bytes = secret.copyOf()

        val secret: ByteArray get() = bytes.copyOf()

        override fun payload(): JsonObject = case(
            "relayRoomSecret",
            JsonObject(mapOf(UNLABELLED to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))),
        )

        override fun equals(other: Any?): Boolean = other is RelayRoomSecret && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "RelayRoomSecret(${bytes.size} bytes)"
    }

    data object Ping : PhoneMessage {
        override fun payload(): JsonObject = case("ping", EMPTY)
    }

    /** Fire-and-forget: sent without a request ID. */
    data object Unpairing : PhoneMessage {
        override fun payload(): JsonObject = case("unpairing", EMPTY)
    }

    /** `fetchSessionTree`, a request: the Mac replies with [MacMessage.SessionTreeMessage]. */
    data object FetchSessionTree : PhoneMessage {
        override fun payload(): JsonObject = case("fetchSessionTree", EMPTY)
    }

    /**
     * `startSessionStream(sessionGuid:params:)`, a request: the Mac replies with
     * [MacMessage.StreamStarted] or an error.
     */
    data class StartSessionStream(val sessionGuid: String, val params: StreamParams = StreamParams()) : PhoneMessage {
        override fun payload(): JsonObject = encodeStartSessionStream(this)
    }

    /** `stopSessionStream(streamID:)`, fire-and-forget; the Mac answers with `streamEnded`. */
    data class StopSessionStream(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("stopSessionStream", streamID)
    }

    /**
     * `streamAck(streamID:lastPTSMilliseconds:queueDepth:)`, fire-and-forget.
     * [lastPTSMilliseconds] is upstream `UInt64`. The app only ever sends [queueDepth] 0.
     */
    data class StreamAck(val streamID: Long, val lastPTSMilliseconds: Long, val queueDepth: Int = 0) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamAck(this)
    }

    /** `requestKeyframe(streamID:)`, fire-and-forget. */
    data class RequestKeyframe(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("requestKeyframe", streamID)
    }

    /** `sendKey(sessionGuid:event:)`, fire-and-forget. */
    data class SendKey(val sessionGuid: String, val event: KeyEvent) : PhoneMessage {
        override fun payload(): JsonObject = encodeSendKey(this)

        override fun toString(): String = "SendKey(sessionGuid=$sessionGuid, event=$event)"
    }

    /** `pasteText(sessionGuid:text:)`, fire-and-forget. [toString] shows only the text's length. */
    data class PasteText(val sessionGuid: String, val text: String) : PhoneMessage {
        override fun payload(): JsonObject = encodePasteText(this)

        override fun toString(): String = "PasteText(sessionGuid=$sessionGuid, text(len=${text.length}))"
    }

    /** `resizeSession(sessionGuid:columns:rows:)`, fire-and-forget; never sent automatically. */
    data class ResizeSession(val sessionGuid: String, val columns: Int, val rows: Int) : PhoneMessage {
        override fun payload(): JsonObject = encodeResizeSession(this)
    }
}

/**
 * A message the Mac sends to the phone: the pairing and live-session subset of upstream
 * `CompanionHostMessage`. Every other host case decodes to [Unsupported]. The
 * live-session decoders are in `StreamMessages.kt`.
 */
sealed interface MacMessage {
    /**
     * `hello(revision:minimumPeer:wantsNotificationPermission:aiAvailable:macStatus:)`.
     * [raw] is the whole case body, so later plans can read the optional fields
     * (`macStatus` arrives only for a phone at revision 14 or later). The revisions
     * are [Long] to match Swift's 64-bit `Int`.
     */
    data class HelloReply(val revision: Long, val minimumPeer: Long, val raw: JsonObject) : MacMessage

    data object RelayRoomSecretStored : MacMessage

    data object Pong : MacMessage

    /** Unsolicited: the Mac has dropped this pairing. */
    data object Unpaired : MacMessage

    /** `error(CompanionError)`: [raw] is the `CompanionError` object, `{"code", "message"}`. */
    data class Error(val raw: JsonObject) : MacMessage {
        val code: String get() = (raw.getValue("code") as JsonPrimitive).content
        val message: String get() = (raw.getValue("message") as JsonPrimitive).content
    }

    /**
     * `sessionTree(CompanionSessionTree)`: the reply to [PhoneMessage.FetchSessionTree], or,
     * with no request ID, the Mac's unsolicited push (phone revision 13 or later).
     */
    data class SessionTreeMessage(val tree: SessionTree) : MacMessage

    /** `streamStarted(CompanionStreamStarted)`: [streamID] is upstream `UInt32`; [codec] is `hevc` or `h264`. */
    data class StreamStarted(val streamID: Long, val codec: String) : MacMessage

    /** `streamConfig(CompanionStreamConfig)`. */
    data class StreamConfigMessage(val config: StreamConfig) : MacMessage

    /** `streamEnded(streamID:reason:)`, unsolicited. */
    data class StreamEnded(val streamID: Long, val reason: StreamEndReason) : MacMessage

    /**
     * A case this build does not handle: [case] is its discriminator and [raw] the
     * payload object `{"<case>": ...}`. A media frame is `Unsupported("media", {})`.
     */
    data class Unsupported(val case: String, val raw: JsonObject) : MacMessage
}

/** Key Swift's synthesized `Codable` gives the first unlabelled associated value. */
internal const val UNLABELLED = "_0"

internal val EMPTY = JsonObject(emptyMap())

internal fun case(name: String, body: JsonObject) = JsonObject(mapOf(name to body))
