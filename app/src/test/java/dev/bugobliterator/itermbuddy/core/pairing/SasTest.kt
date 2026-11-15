package dev.bugobliterator.itermbuddy.core.pairing

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SasTest {
    private val vectors: List<Pair<String, JsonObject>> by lazy {
        val array = Json.parseToJsonElement(Fixtures.read("crypto-vectors.json"))
            .jsonObject.getValue("sas").jsonArray
        assertTrue("sas is empty", array.isNotEmpty())
        array.mapIndexed { index, element ->
            val entry = element.jsonObject
            "sas[${entry["name"]?.jsonPrimitive?.content ?: "#$index"}]" to entry
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun sasMatchesVectors() {
        for ((label, entry) in vectors) {
            assertEquals(label, entry.string("code"), sasCode(hex(entry.string("handshakeHash"))))
        }
    }

    @Test
    fun sasKeepsLeadingZeros() {
        val (label, entry) = vectors.single { (_, entry) -> entry.string("name") == "leadingZeros" }
        assertEquals(label, "000012", sasCode(hex(entry.string("handshakeHash"))))
    }

    @Test
    fun handshakeHashMustBeThirtyTwoBytes() {
        for (size in listOf(0, 31, 33, 64)) {
            val error = assertThrows("handshakeHash of $size bytes", IllegalArgumentException::class.java) {
                sasCode(ByteArray(size))
            }
            assertEquals("handshakeHash of $size bytes", "handshakeHash must be 32 bytes", error.message)
        }
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd-length hex: $text" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
