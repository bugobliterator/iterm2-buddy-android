package dev.bugobliterator.itermbuddy.core.wire

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A message the phone sends to the Mac: the pairing subset of upstream
 * `CompanionClientMessage` (`sources/Companion/Shared/CompanionMessages.swift`).
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
}

/**
 * A message the Mac sends to the phone: the pairing subset of upstream
 * `CompanionHostMessage`. Every other host case decodes to [Unsupported].
 */
sealed interface MacMessage {
    /**
     * `hello(revision:minimumPeer:wantsNotificationPermission:aiAvailable:macStatus:)`.
     * [raw] is the whole case body, so later plans can read the optional fields
     * (`macStatus` arrives only for a phone at revision 14 or later).
     */
    data class HelloReply(val revision: Int, val minimumPeer: Int, val raw: JsonObject) : MacMessage

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
     * A case this build does not handle: [case] is its discriminator and [raw] the
     * payload object `{"<case>": ...}`. A media frame is `Unsupported("media", {})`.
     */
    data class Unsupported(val case: String, val raw: JsonObject) : MacMessage
}

/** Key Swift's synthesized `Codable` gives the first unlabelled associated value. */
internal const val UNLABELLED = "_0"

private val EMPTY = JsonObject(emptyMap())

private fun case(name: String, body: JsonObject) = JsonObject(mapOf(name to body))
