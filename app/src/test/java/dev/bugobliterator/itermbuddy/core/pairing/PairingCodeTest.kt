package dev.bugobliterator.itermbuddy.core.pairing

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(Fixtures.read("crypto-vectors.json")).jsonObject
    }

    private fun entries(key: String): List<Pair<String, JsonObject>> {
        val array: JsonArray = vectors.getValue(key).jsonArray
        assertTrue("$key is empty", array.isNotEmpty())
        return array.mapIndexed { index, element ->
            val entry = element.jsonObject
            val label = entry["name"]?.jsonPrimitive?.content ?: "#$index"
            "$key[$label]" to entry
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun canonicalMatchesVectors() {
        for ((label, entry) in entries("canonical")) {
            val fields = entry.getValue("fields").jsonArray.map { hex(it.jsonPrimitive.content) }
            val encoded = canonical(entry.string("domain"), fields)
            assertEquals(label, entry.string("encoded"), encoded.toHex())
        }
    }

    @Test
    fun pairingCodesMatchAndroidPolicy() {
        for ((label, entry) in entries("pairingCodes")) {
            val result = parsePairingCode(entry.string("qr"))
            if (entry.getValue("androidAccepts").jsonPrimitive.boolean) {
                if (result !is PairingCodeResult.Ok) throw AssertionError("$label: expected Ok, got $result")
                val code = result.code
                assertArrayEquals(
                    "$label responderStaticPublicKey",
                    hex(entry.string("responderStaticPublicKey")),
                    code.responderStaticPublicKey,
                )
                assertEquals("$label pairingID", entry.string("pairingID"), code.pairingID)
                assertEquals("$label relayOrigin", entry.string("relayOrigin"), code.relayOrigin)
            } else {
                if (result !is PairingCodeResult.Rejected) {
                    throw AssertionError("$label: expected Rejected, got $result")
                }
                entry["androidReason"]?.let {
                    assertEquals("$label reason", it.jsonPrimitive.content, result.reason)
                }
                assertTrue("$label message is blank", result.message.isNotBlank())
            }
        }
    }

    @Test
    fun resolverModeIsRejectedWithSettingsHint() {
        val resolver = entries("pairingCodes").filter { (_, entry) ->
            entry["androidReason"]?.jsonPrimitive?.content == "resolverUnsupported"
        }
        assertTrue("no resolverUnsupported vector", resolver.isNotEmpty())
        for ((label, entry) in resolver) {
            assertEquals(
                label,
                PairingCodeResult.Rejected(
                    "resolverUnsupported",
                    "This QR uses iTerm2's resolver mode. " +
                        "Set companionResolverURL empty in iTerm2's Advanced settings.",
                ),
                parsePairingCode(entry.string("qr")),
            )
        }
    }

    @Test
    fun roomNamesMatchVectors() {
        for ((label, entry) in entries("roomNames")) {
            val code = PairingCode(
                responderStaticPublicKey = hex(entry.string("responderStaticPublicKey")),
                pairingID = entry.string("pairingID"),
                relayOrigin = "https://relay.example.com",
            )
            assertEquals(label, entry.string("roomName"), roomName(code))
        }
    }

    @Test
    fun prologuesMatchVectors() {
        for ((label, entry) in entries("prologues")) {
            val code = PairingCode(
                responderStaticPublicKey = ByteArray(32),
                pairingID = entry.string("pairingID"),
                relayOrigin = "https://relay.example.com",
            )
            assertEquals(label, entry.string("prologue"), code.prologue.toHex())
        }
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd-length hex: $text" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
