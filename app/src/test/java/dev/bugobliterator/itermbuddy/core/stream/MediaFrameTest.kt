package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFrameTest {
    private val fixture: JsonObject by lazy { Json.parseToJsonElement(Fixtures.read("media/frames.json")).jsonObject }

    private fun entries(kind: String): List<JsonObject> =
        fixture.getValue(kind).jsonArray.map { it.jsonObject }.also { assertTrue("no $kind frames", it.isNotEmpty()) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    @Test
    fun validFramesMatchUpstream() {
        for (entry in entries("valid")) {
            val name = entry.string("name")
            val encoded = hex(entry.string("encodedHex"))
            val channel = hex(entry.string("channelHex"))
            assertEquals("$name: channel marker", 0x01.toByte(), channel[0])
            assertArrayEquals("$name: channel minus marker", encoded, channel.copyOfRange(1, channel.size))

            val frame = parseMediaFrame(encoded)
            val version = entry.long("version").toInt()
            assertEquals("$name: version", version, frame.version)
            assertEquals("$name: flags", entry.long("flags").toInt(), frame.flags)
            assertEquals("$name: streamID", entry.long("streamID"), frame.streamID)
            assertEquals("$name: sequence", entry.long("sequence"), frame.sequence)
            assertEquals("$name: pts", entry.long("ptsMilliseconds"), frame.ptsMilliseconds)
            if (version == 1) {
                assertNull("$name: generationId", frame.generationId)
                assertNull("$name: liveTop", frame.liveTop)
            } else {
                assertEquals("$name: generationId", entry.long("generationId"), frame.generationId)
                assertEquals("$name: liveTop", entry.long("liveTop"), frame.liveTop)
            }
            val payload = hex(entry.string("payloadHex"))
            assertArrayEquals("$name: payload", payload, frame.payload)
            assertEquals("$name: payloadSize", payload.size, frame.payloadSize)
            assertEquals("$name: isKeyframe", frame.flags and 1 != 0, frame.isKeyframe)
            assertEquals("$name: configChanged", frame.flags and 2 != 0, frame.configChanged)
        }
        // The edges the fixture must cover, so a regenerated fixture cannot silently drop them.
        val names = entries("valid").map { it.string("name") }
        for (edge in listOf("v2-negative-live-top", "v2-ids-above-2-31", "v2-empty-payload", "v1-empty-payload")) {
            assertTrue("fixture lacks $edge", edge in names)
        }
    }

    @Test
    fun invalidFramesAreRejected() {
        for (entry in entries("invalid")) {
            val name = entry.string("name")
            val channel = hex(entry.string("channelHex"))
            assertEquals("$name: channel marker", 0x01.toByte(), channel[0])
            assertArrayEquals("$name: channel minus marker", hex(entry.string("encodedHex")), channel.copyOfRange(1, channel.size))
            val error = assertThrows(name, MediaFrameException::class.java) { parseMediaFrame(hex(entry.string("encodedHex"))) }
            assertEquals("$name: error", entry.string("upstreamError"), error.message)
        }
    }

    @Test
    fun ptsWithTopBitSetIsRejected() {
        val valid = hex(entries("valid").single { it.string("name") == "v2-delta" }.string("encodedHex"))
        val parsed = parseMediaFrame(valid.copyOf())
        assertEquals(523118075L, parsed.ptsMilliseconds)
        val bad = valid.copyOf().also { it[10] = 0x80.toByte() }
        assertThrows(MediaFrameException::class.java) { parseMediaFrame(bad) }
    }

    @Test
    fun payloadIsNotSharedWithInputOrReaders() {
        val encoded = hex(entries("valid").single { it.string("name") == "v2-delta" }.string("encodedHex"))
        val frame = parseMediaFrame(encoded)
        encoded.fill(0)
        assertArrayEquals(hex("2021222324"), frame.payload)
        frame.payload.fill(0)
        assertArrayEquals(hex("2021222324"), frame.payload)

        val source = hex("0000000140")
        val built = MediaFrame(2, 1, 1, 0, 0, 1, 0, source)
        source.fill(0x7f)
        assertArrayEquals(hex("0000000140"), built.payload)
        assertArrayEquals(hex("0000000140"), built.annexBAccessUnit())
    }

    @Test
    fun framesCompareByContent() {
        val encoded = hex(entries("valid").single { it.string("name") == "v2-delta" }.string("encodedHex"))
        val first = parseMediaFrame(encoded)
        val second = parseMediaFrame(encoded.copyOf())
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val otherPayload = encoded.copyOf().also { it[it.size - 1] = 0 }
        assertFalse(first == parseMediaFrame(otherPayload))
    }

    @Test
    fun toStringOmitsPayload() {
        val sentinel = "SENTINEL-PAYLOAD".toByteArray(Charsets.US_ASCII)
        val frame = MediaFrame(2, 3, 1, 0, 523118042, 1, 4000, sentinel)
        val text = frame.toString()
        assertFalse(text, "SENTINEL" in text)
        assertFalse(text, sentinel.joinToString("") { "%02x".format(it) } in text)
        assertFalse(text, sentinel.joinToString(", ") in text)
        assertTrue(text, "${sentinel.size} bytes" in text)
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
