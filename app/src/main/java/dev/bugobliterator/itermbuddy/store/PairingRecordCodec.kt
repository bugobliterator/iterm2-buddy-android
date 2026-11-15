package dev.bugobliterator.itermbuddy.store

import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.isAcceptedRelayOrigin
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A stored pairing blob that is not a valid record of a known format. It never carries a cause:
 * parser and decoder messages can quote the secret-bearing input. Its message is fixed text.
 */
class PairingRecordFormatException(message: String) : Exception(message)

/**
 * The plaintext form of a [PairingRecord]: one UTF-8 JSON object, written by [encode] as
 *
 * ```
 * {"v":1,"stage":"confirmed","pid":"…","relayOrigin":"https://…",
 *  "macStaticPublic":"<b64>","phoneStaticPrivate":"<b64>","roomSecret":"<b64>"}
 * ```
 *
 * Keys are standard padded base64 of exactly 32 bytes. [decode] is strict: every field present,
 * no other field, `v` exactly the integer [FORMAT_VERSION], a known stage name, a non-empty pid,
 * canonical base64, and a relay origin the pairing-code policy accepts as-is.
 *
 * The returned byte arrays are the caller's to wipe. Strings made on the way (the JSON text and
 * the base64 fields) are immutable on the JVM and cannot be wiped; they are garbage at once.
 */
object PairingRecordCodec {
    const val FORMAT_VERSION = 1

    private const val VERSION = "v"
    private const val STAGE = "stage"
    private const val PID = "pid"
    private const val RELAY_ORIGIN = "relayOrigin"
    private const val MAC_STATIC_PUBLIC = "macStaticPublic"
    private const val PHONE_STATIC_PRIVATE = "phoneStaticPrivate"
    private const val ROOM_SECRET = "roomSecret"
    private val FIELDS = setOf(VERSION, STAGE, PID, RELAY_ORIGIN, MAC_STATIC_PUBLIC, PHONE_STATIC_PRIVATE, ROOM_SECRET)
    private const val KEY_SIZE = 32

    /** Stored stage names, fixed independently of the enum's Kotlin names. */
    private val STAGE_NAMES = mapOf(
        PairingStage.PENDING_CONFIRMATION to "pendingConfirmation",
        PairingStage.CONFIRMED to "confirmed",
        PairingStage.REGISTERED to "registered",
    )
    private val STAGES_BY_NAME = STAGE_NAMES.entries.associate { (stage, name) -> name to stage }

    fun encode(record: PairingRecord): ByteArray {
        val staticKey = record.phoneStaticPrivate
        val secret = record.roomSecret
        try {
            val json = JsonObject(
                linkedMapOf(
                    VERSION to JsonPrimitive(FORMAT_VERSION),
                    STAGE to JsonPrimitive(STAGE_NAMES.getValue(record.stage)),
                    PID to JsonPrimitive(record.code.pairingID),
                    RELAY_ORIGIN to JsonPrimitive(record.code.relayOrigin),
                    MAC_STATIC_PUBLIC to JsonPrimitive(base64.encodeToString(record.code.responderStaticPublicKey)),
                    PHONE_STATIC_PRIVATE to JsonPrimitive(base64.encodeToString(staticKey)),
                    ROOM_SECRET to JsonPrimitive(base64.encodeToString(secret)),
                ),
            )
            return json.toString().toByteArray(Charsets.UTF_8)
        } finally {
            staticKey.fill(0)
            secret.fill(0)
        }
    }

    fun decode(bytes: ByteArray): PairingRecord {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw PairingRecordFormatException("record is not UTF-8")
        }
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            // Not attached as the cause: kotlinx quotes the input around the failing offset.
            throw PairingRecordFormatException("record is not JSON")
        }
        val json = element as? JsonObject ?: throw PairingRecordFormatException("record is not a JSON object")
        if (json.keys != FIELDS) throw PairingRecordFormatException("record fields are missing or unknown")

        val version = json.primitive(VERSION)
        if (version.isString || version.content != FORMAT_VERSION.toString()) {
            throw PairingRecordFormatException("unknown record format version")
        }
        val stage = STAGES_BY_NAME[json.string(STAGE)] ?: throw PairingRecordFormatException("unknown pairing stage")
        val pid = json.string(PID)
        if (pid.isEmpty()) throw PairingRecordFormatException("empty pairing ID")
        val origin = json.string(RELAY_ORIGIN)
        if (!isAcceptedRelayOrigin(origin)) throw PairingRecordFormatException("relay origin is not canonical")

        val macKey = json.key(MAC_STATIC_PUBLIC)
        var staticKey: ByteArray? = null
        var secret: ByteArray? = null
        try {
            staticKey = json.key(PHONE_STATIC_PRIVATE)
            secret = json.key(ROOM_SECRET)
            return PairingRecord(PairingCode(macKey, pid, origin), staticKey, secret, stage)
        } finally {
            staticKey?.fill(0)
            secret?.fill(0)
        }
    }

    private val base64 = Base64.getEncoder()

    private fun JsonObject.primitive(name: String): JsonPrimitive =
        get(name) as? JsonPrimitive ?: throw PairingRecordFormatException("$name is not a JSON primitive")

    private fun JsonObject.string(name: String): String =
        primitive(name).takeIf { it.isString }?.content ?: throw PairingRecordFormatException("$name is not a string")

    /** A 32-byte key in canonical standard base64 (padded, no whitespace, unused bits zero). */
    private fun JsonObject.key(name: String): ByteArray {
        val text = string(name)
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw PairingRecordFormatException("$name is not base64")
        }
        val canonical = base64.encode(bytes)
        val isCanonical = canonical.size == text.length && canonical.indices.all { canonical[it].toInt() == text[it].code }
        canonical.fill(0)
        if (bytes.size != KEY_SIZE || !isCanonical) {
            bytes.fill(0)
            throw PairingRecordFormatException("$name is not $KEY_SIZE bytes of canonical base64")
        }
        return bytes
    }
}
