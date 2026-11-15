package dev.bugobliterator.itermbuddy.store

import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PairingRecordCodecTest {
    private val code = PairingCode(ByteArray(32) { (it + 1).toByte() }, "pid-1", ORIGIN)

    private fun record(stage: PairingStage) =
        PairingRecord(code, ByteArray(32) { (0x40 + it).toByte() }, ByteArray(32) { (0x80 + it).toByte() }, stage)

    private val valid: JsonObject by lazy {
        Json.parseToJsonElement(PairingRecordCodec.encode(record(PairingStage.CONFIRMED)).decodeToString()).jsonObject
    }

    private fun with(key: String, value: JsonElement?): ByteArray {
        val map = valid.toMutableMap()
        if (value == null) map.remove(key) else map[key] = value
        return JsonObject(map).toString().toByteArray()
    }

    private fun b64(size: Int, fill: Int = 7) = JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(size) { fill.toByte() }))

    private fun assertRejected(label: String, bytes: ByteArray) {
        try {
            val decoded = PairingRecordCodec.decode(bytes)
            fail("$label: expected rejection, got $decoded")
        } catch (_: PairingRecordFormatException) {
            // expected
        }
    }

    @Test
    fun roundTripsEveryStage() {
        for (stage in PairingStage.entries) {
            val original = record(stage)
            assertEquals(stage.name, original, PairingRecordCodec.decode(PairingRecordCodec.encode(original)))
        }
    }

    @Test
    fun roundTripsAnOriginWithPortAndCase() {
        val origin = PairingRecord(
            PairingCode(ByteArray(32), "p", "https://Mac.Example:443"),
            ByteArray(32),
            ByteArray(32),
            PairingStage.PENDING_CONFIRMATION,
        )
        assertEquals(origin, PairingRecordCodec.decode(PairingRecordCodec.encode(origin)))
    }

    @Test
    fun encodesAVersionedObjectWithExactlyTheKnownFields() {
        assertEquals(
            setOf("v", "stage", "pid", "relayOrigin", "macStaticPublic", "phoneStaticPrivate", "roomSecret"),
            valid.keys,
        )
        assertEquals(JsonPrimitive(1), valid["v"])
        assertEquals(1, PairingRecordCodec.FORMAT_VERSION)
        assertEquals(JsonPrimitive("confirmed"), valid["stage"])
        assertEquals(JsonPrimitive("pid-1"), valid["pid"])
        assertEquals(JsonPrimitive(ORIGIN), valid["relayOrigin"])
    }

    @Test
    fun stageNamesAreStable() {
        val names = PairingStage.entries.map {
            Json.parseToJsonElement(PairingRecordCodec.encode(record(it)).decodeToString()).jsonObject["stage"]
        }
        assertEquals(
            listOf(JsonPrimitive("pendingConfirmation"), JsonPrimitive("confirmed"), JsonPrimitive("registered")),
            names,
        )
    }

    @Test
    fun rejectsNonObjects() {
        val cases = mapOf(
            "empty" to ByteArray(0),
            "garbage" to "not json".toByteArray(),
            "array" to "[]".toByteArray(),
            "string" to "\"x\"".toByteArray(),
            "null" to "null".toByteArray(),
            "truncated" to PairingRecordCodec.encode(record(PairingStage.CONFIRMED)).let { it.copyOf(it.size - 1) },
            "invalid utf-8" to byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()),
            "trailing data" to (PairingRecordCodec.encode(record(PairingStage.CONFIRMED)) + "{}".toByteArray()),
        )
        for ((label, bytes) in cases) assertRejected(label, bytes)
    }

    @Test
    fun rejectsMissingOrNullFields() {
        for (key in valid.keys) {
            assertRejected("missing $key", with(key, null))
            assertRejected("null $key", with(key, JsonNull))
        }
    }

    @Test
    fun rejectionsNeverCarrySecretText() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { (0x40 + it).toByte() })
        val room = Base64.getEncoder().encodeToString(ByteArray(32) { (0x80 + it).toByte() })
        val text = valid.toString()
        val cases = listOf(
            // Malformed JSON right next to, and inside, the secrets: kotlinx quotes the input near the error.
            text.replace("\"$secret\"", "$secret"),
            text.replace("\"$room\"", "\"$room"),
            text.replace("\"$room\"", "\"${room.take(20)}\u0001${room.drop(20)}\""),
            text.dropLast(1),
            text.replace(secret, secret.take(10) + "!" + secret.drop(11)),
        )
        for (case in cases) {
            val error = try {
                PairingRecordCodec.decode(case.toByteArray())
                fail("accepted: case ${cases.indexOf(case)}")
                return
            } catch (e: PairingRecordFormatException) {
                e
            }
            assertNull(error.cause)
            val message = error.message.orEmpty()
            for (value in listOf(secret, room)) {
                for (window in value.windowed(8)) {
                    assertFalse("case ${cases.indexOf(case)} message '$message' quotes a secret", message.contains(window))
                }
            }
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertRejected("extra", with("extra", JsonPrimitive(1)))
    }

    @Test
    fun rejectsUnknownOrMalformedVersions() {
        val versions = listOf(
            JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(-1), JsonPrimitive("1"), JsonPrimitive(1.0),
            JsonPrimitive(true), JsonPrimitive(Long.MAX_VALUE), JsonObject(emptyMap()),
        )
        for (version in versions) assertRejected("v=$version", with("v", version))
        assertRejected("v=1e0", valid.toString().replace("\"v\":1", "\"v\":1e0").toByteArray())
        assertRejected("v=01", valid.toString().replace("\"v\":1", "\"v\":01").toByteArray())
    }

    @Test
    fun rejectsUnknownStages() {
        val stages = listOf(
            JsonPrimitive("CONFIRMED"), JsonPrimitive("done"), JsonPrimitive(""), JsonPrimitive(1),
            JsonPrimitive("confirmed "), JsonObject(emptyMap()),
        )
        for (stage in stages) assertRejected("stage=$stage", with("stage", stage))
    }

    @Test
    fun rejectsWrongKeySizes() {
        for (key in listOf("macStaticPublic", "phoneStaticPrivate", "roomSecret")) {
            for (size in listOf(0, 16, 31, 33, 64)) assertRejected("$key size $size", with(key, b64(size)))
            assertRejected("$key not a string", with(key, JsonPrimitive(1)))
        }
    }

    @Test
    fun rejectsNonCanonicalBase64() {
        val good = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val variants = listOf(
            good.trimEnd('='),
            "$good=",
            " $good",
            good.substring(0, good.length - 2) + "B=",
            Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0xFB.toByte() }),
            Base64.getMimeEncoder().encodeToString(ByteArray(32)).let { it.substring(0, 10) + "\r\n" + it.substring(10) },
        )
        for (key in listOf("macStaticPublic", "phoneStaticPrivate", "roomSecret")) {
            for (variant in variants) assertRejected("$key='$variant'", with(key, JsonPrimitive(variant)))
        }
    }

    @Test
    fun rejectsOriginsThePairingCodePolicyRejects() {
        val origins = listOf(
            "", "http://host", "https://host/", "https://host/path", "https://user@host", "https://host?x",
            "https://host#f", "https://ho st", "https://host:", "ftp://host", "https://", "host",
            "https://h%40st", "HTTPS://host",
        )
        for (origin in origins) assertRejected("origin=$origin", with("relayOrigin", JsonPrimitive(origin)))
        assertRejected("origin number", with("relayOrigin", JsonPrimitive(1)))
    }

    @Test
    fun acceptsOriginsThePairingCodePolicyAccepts() {
        for (origin in listOf(ORIGIN, "https://Host.Example:443", "https://[::1]:8443", "https://10.0.0.1")) {
            val decoded = PairingRecordCodec.decode(with("relayOrigin", JsonPrimitive(origin)))
            assertEquals(origin, decoded.code.relayOrigin)
        }
    }

    @Test
    fun rejectsEmptyOrNonStringPairingID() {
        assertRejected("pid empty", with("pid", JsonPrimitive("")))
        assertRejected("pid number", with("pid", JsonPrimitive(3)))
    }

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
    }
}
