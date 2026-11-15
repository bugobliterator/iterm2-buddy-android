package dev.bugobliterator.itermbuddy.core.wire

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A frame from the Mac that is not a valid envelope, or a known case with a malformed body. */
class WireDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** First byte of a media frame; a JSON control frame starts with `{` (`CompanionFrameChannel.swift`). */
private const val MEDIA_FRAME: Byte = 0x01

private const val REQUEST_ID = "requestID"
private const val PAYLOAD = "payload"

/**
 * Encodes upstream's `CompanionEnvelope`: `{"requestID": n, "payload": {...}}`.
 * A null [requestID] (fire-and-forget) omits the key; it is never written as `null`.
 * [requestID] is upstream's `UInt64`, so it must not be negative.
 */
fun encodeEnvelope(message: PhoneMessage, requestID: Long?): ByteArray {
    require(requestID == null || requestID >= 0) { "requestID must not be negative" }
    val envelope = buildMap<String, JsonElement> {
        if (requestID != null) put(REQUEST_ID, JsonPrimitive(requestID))
        put(PAYLOAD, message.payload())
    }
    return JsonObject(envelope).toString().toByteArray(Charsets.UTF_8)
}

/**
 * Decodes a frame from the Mac into its request ID (null for an unsolicited event)
 * and message, following upstream `CompanionEnvelope.init(from:)`:
 * - Unknown top-level keys and unknown keys inside a known case's body are ignored,
 *   as Swift's keyed decoding containers ignore them.
 * - `"requestID": null` reads as no request ID (`decodeIfPresent`). Any other
 *   value must be a plain non-negative integer literal that fits in a [Long].
 * - The payload must be an object with exactly one key. An unknown key decodes to
 *   [MacMessage.Unsupported]; a known key whose body does not decode throws.
 * - A frame whose first byte is `0x01` is a media frame: `Unsupported("media", {})`.
 *
 * @throws WireDecodeException for anything that is not such an envelope.
 */
fun decodeEnvelope(bytes: ByteArray): Pair<Long?, MacMessage> {
    if (bytes.isNotEmpty() && bytes[0] == MEDIA_FRAME) {
        return null to MacMessage.Unsupported("media", JsonObject(emptyMap()))
    }
    val envelope = parseJson(bytes) as? JsonObject ?: throw WireDecodeException("envelope is not a JSON object")
    val requestID = decodeRequestID(envelope[REQUEST_ID])
    val payload = envelope[PAYLOAD] as? JsonObject ?: throw WireDecodeException("payload is missing or not an object")
    val case = payload.keys.singleOrNull()
        ?: throw WireDecodeException("payload must have exactly one key, has ${payload.size}")
    return requestID to decodeMacCase(case, payload.getValue(case), payload)
}

/**
 * Reads the Mac's first frame on a fresh pairing (upstream `PairingConfirmation`),
 * which travels bare, with no envelope: true for `{"pairing":"accepted"}`, false
 * for `{"pairing":"rejected"}`, and null for anything else.
 *
 * Stricter than upstream's decode, which ignores extra keys: the JSON tree must be
 * an object with exactly the one key `pairing` and exactly one of those two string
 * values. Whitespace between tokens is allowed, since it does not change the tree;
 * upstream sends the bytes with none.
 */
fun decodePairingConfirmation(bytes: ByteArray): Boolean? {
    val element = try {
        parseJson(bytes)
    } catch (_: WireDecodeException) {
        return null
    }
    val value = (element as? JsonObject)?.takeIf { it.size == 1 }?.get("pairing") as? JsonPrimitive
    if (value == null || !value.isString) return null
    return when (value.content) {
        "accepted" -> true
        "rejected" -> false
        else -> null
    }
}

private fun parseJson(bytes: ByteArray): JsonElement {
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw WireDecodeException("frame is not valid UTF-8", e)
    }
    return try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw WireDecodeException("frame is not valid JSON", e)
    }
}

private val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")
private val SIGNED_INTEGER = Regex("-?(0|[1-9][0-9]*)")

private fun decodeRequestID(element: JsonElement?): Long? {
    if (element == null || element == JsonNull) return null
    val literal = (element as? JsonPrimitive)?.takeUnless { it.isString }?.content
    if (literal == null || !UNSIGNED_INTEGER.matches(literal)) {
        throw WireDecodeException("requestID is not a non-negative integer: $element")
    }
    return literal.toLongOrNull() ?: throw WireDecodeException("requestID is out of range: $literal")
}

private fun decodeMacCase(case: String, body: JsonElement, payload: JsonObject): MacMessage = when (case) {
    "hello" -> {
        val hello = body.asObject(case)
        hello.optional(case, "wantsNotificationPermission") { it.isBoolean() }
        hello.optional(case, "aiAvailable") { it.isBoolean() }
        hello.optional(case, "macStatus") { it is JsonObject }
        MacMessage.HelloReply(hello.int(case, "revision"), hello.int(case, "minimumPeer"), hello)
    }
    "relayRoomSecretStored" -> MacMessage.RelayRoomSecretStored.also { body.asObject(case) }
    "pong" -> MacMessage.Pong.also { body.asObject(case) }
    "unpaired" -> MacMessage.Unpaired.also { body.asObject(case) }
    "error" -> {
        val error = body.asObject(case)[UNLABELLED] as? JsonObject
            ?: throw WireDecodeException("error.$UNLABELLED is missing or not an object")
        error.string(case, "code")
        error.string(case, "message")
        MacMessage.Error(error)
    }
    else -> MacMessage.Unsupported(case, payload)
}

private fun JsonElement.asObject(case: String): JsonObject =
    this as? JsonObject ?: throw WireDecodeException("$case body is not an object")

private fun JsonElement.isBoolean(): Boolean =
    this is JsonPrimitive && !isString && (content == "true" || content == "false")

private fun JsonObject.int(case: String, key: String): Int {
    val literal = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content
    if (literal == null || !SIGNED_INTEGER.matches(literal)) {
        throw WireDecodeException("$case.$key is missing or not an integer")
    }
    return literal.toIntOrNull() ?: throw WireDecodeException("$case.$key is out of range: $literal")
}

private fun JsonObject.string(case: String, key: String): String {
    val value = get(key) as? JsonPrimitive
    if (value == null || !value.isString) throw WireDecodeException("$case.$key is missing or not a string")
    return value.content
}

/** An optional field: absent or `null` reads as nil, as `decodeIfPresent` does; otherwise it must be [valid]. */
private fun JsonObject.optional(case: String, key: String, valid: (JsonElement) -> Boolean) {
    val value = get(key)
    if (value != null && value != JsonNull && !valid(value)) throw WireDecodeException("$case.$key has the wrong type")
}
