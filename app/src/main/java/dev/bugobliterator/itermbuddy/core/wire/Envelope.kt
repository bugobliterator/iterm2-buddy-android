package dev.bugobliterator.itermbuddy.core.wire

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A frame from the Mac that is not a valid envelope, or a known case with a malformed body.
 *
 * Callers drop such a frame, log it and keep the session, as upstream does
 * (`CompanionSession.swift:120-126`); an empty frame is dropped the same way (`:127`).
 * The session layer enforces this.
 */
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
 * - Integers are plain literals: `15.0` or `1e2` is rejected where Swift's
 *   `JSONDecoder` may accept it. A deliberate deviation; the Mac writes integer literals.
 *
 * JSON leniency: kotlinx accepts some non-standard values (unquoted literals such as
 * `abc`) in positions this decoder ignores, while a BOM or UTF-16 frame is rejected.
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
    // Upstream decides on the payload's FIRST key in document order: an unknown first
    // key is `.unsupported`, a known one goes to the synthesized decode, whose allKeys
    // hides unknown keys, so `{"pong":{},"futureThing":{}}` is `.pong` upstream.
    // Android deliberately throws on any payload without exactly one key.
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
 * upstream sends the bytes with none. A repeated key is null too: upstream's
 * `JSONSerialization` keeps the last value, so `{"pairing":"rejected","pairing":"accepted"}`
 * would read as acceptance there, and kotlinx also keeps the last one silently.
 */
fun decodePairingConfirmation(bytes: ByteArray): Boolean? {
    val element = try {
        parseJson(bytes)
    } catch (_: WireDecodeException) {
        return null
    }
    if (topLevelMemberCount(bytes.decodeToString()) != 1) return null
    val value = (element as? JsonObject)?.takeIf { it.size == 1 }?.get("pairing") as? JsonPrimitive
    if (value == null || !value.isString) return null
    return when (value.content) {
        "accepted" -> true
        "rejected" -> false
        else -> null
    }
}

/**
 * Counts the members of a top-level JSON object by counting `:` at depth 1 outside
 * strings, so a repeated key, which the parsed tree collapses, still counts twice.
 * Only called on text that has already parsed as JSON.
 */
private fun topLevelMemberCount(text: String): Int {
    var depth = 0
    var inString = false
    var escaped = false
    var count = 0
    for (char in text) {
        when {
            inString -> when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            char == '"' -> inString = true
            char == '{' || char == '[' -> depth++
            char == '}' || char == ']' -> depth--
            char == ':' && depth == 1 -> count++
        }
    }
    return count
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
        throw WireDecodeException("requestID is not a non-negative integer (${jsonType(element)})")
    }
    return literal.toLongOrNull() ?: throw WireDecodeException("requestID is out of range (${literal.length} digits)")
}

private fun decodeMacCase(case: String, body: JsonElement, payload: JsonObject): MacMessage = when (case) {
    "hello" -> {
        val hello = body.asObject(case)
        hello.optional(case, "wantsNotificationPermission") { it.isBoolean() }
        hello.optional(case, "aiAvailable") { it.isBoolean() }
        hello.optional(case, "macStatus") { it is JsonObject }
        MacMessage.HelloReply(hello.long(case, "revision"), hello.long(case, "minimumPeer"), hello)
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

private fun jsonType(element: JsonElement): String = when {
    element is JsonObject -> "object"
    element is JsonPrimitive && element.isString -> "string"
    element is JsonPrimitive -> if (element.content == "true" || element.content == "false") "boolean" else "number"
    else -> "array"
}

private fun JsonElement.asObject(case: String): JsonObject =
    this as? JsonObject ?: throw WireDecodeException("$case body is not an object")

private fun JsonElement.isBoolean(): Boolean =
    this is JsonPrimitive && !isString && (content == "true" || content == "false")

private fun JsonObject.long(case: String, key: String): Long {
    val literal = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content
    if (literal == null || !SIGNED_INTEGER.matches(literal)) {
        throw WireDecodeException("$case.$key is missing or not an integer")
    }
    return literal.toLongOrNull() ?: throw WireDecodeException("$case.$key is out of range (${literal.length} digits)")
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
