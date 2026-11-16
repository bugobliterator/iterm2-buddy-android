package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.Fixtures
import java.io.ByteArrayOutputStream
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcFramingTest {
    private fun fixture(path: String): JsonObject = Json.parseToJsonElement(Fixtures.read(path)).jsonObject

    private fun JsonObject.entries(kind: String): List<JsonObject> =
        getValue(kind).jsonArray.map { it.jsonObject }.also { assertTrue("no $kind entries", it.isNotEmpty()) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.hexList(key: String): List<ByteArray> = getValue(key).jsonArray.map { hex(it.jsonPrimitive.content) }

    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun startCodePrefixed(units: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { units.forEach { write(startCode); write(it) } }.toByteArray()

    @Test
    fun parameterSetsRoundTripUpstreamContainer() {
        val sets = fixture("media/parameter-sets.json")
        for (entry in sets.entries("valid")) {
            val name = entry.string("name")
            val parsed = parseParameterSets(hex(entry.string("containerHex")))
            val expected = entry.hexList("setsHex")
            assertEquals("$name: count", expected.size, parsed.size)
            expected.zip(parsed).forEach { (want, got) -> assertArrayEquals(name, want, got) }
        }
        for (entry in sets.entries("invalid")) {
            val name = entry.string("name")
            val error = assertThrows(name, HevcFramingException::class.java) { parseParameterSets(hex(entry.string("containerHex"))) }
            assertEquals("$name: error", entry.string("upstreamError"), error.message)
        }
        // Upstream (CompanionHEVCFraming.swift:50-66) stops after `count` sets and ignores the rest.
        val trailing = parseParameterSets(hex("00010000000240410000ffff"))
        assertEquals(1, trailing.size)
        assertArrayEquals(hex("4041"), trailing[0])
        // A u32 length at or above 2^31 is a huge length, not a negative one.
        assertThrows(HevcFramingException::class.java) { parseParameterSets(hex("0001ffffffff4041")) }
        assertThrows(HevcFramingException::class.java) { parseParameterSets(hex("000180000000")) }
        // The count is u16: 0xffff sets, each needing 4 bytes, is truncated rather than negative.
        assertThrows(HevcFramingException::class.java) { parseParameterSets(hex("ffff00000000")) }
    }

    @Test
    fun csdIsStartCodePrefixedSets() {
        for (entry in fixture("media/parameter-sets.json").entries("valid")) {
            val name = entry.string("name")
            assertArrayEquals(name, startCodePrefixed(entry.hexList("setsHex")), parameterSetsToCsd(hex(entry.string("containerHex"))))
        }
        assertThrows(HevcFramingException::class.java) { parameterSetsToCsd(hex("00010000")) }
    }

    @Test
    fun accessUnitBecomesAnnexB() {
        val units = fixture("media/access-units.json")
        for (entry in units.entries("valid")) {
            val name = entry.string("name")
            assertArrayEquals(name, startCodePrefixed(entry.hexList("nalUnitsHex")), accessUnitToAnnexB(hex(entry.string("payloadHex"))))
        }
        for (entry in units.entries("invalid")) {
            val name = entry.string("name")
            val error = assertThrows(name, HevcFramingException::class.java) { accessUnitToAnnexB(hex(entry.string("payloadHex"))) }
            assertEquals("$name: error", entry.string("upstreamError"), error.message)
        }
        // A NAL length running past the end, including lengths at or above 2^31 read as u32.
        for (payload in listOf("0000000526272829", "ffffffff26", "8000000026", "0000000126000000ff")) {
            assertThrows(payload, HevcFramingException::class.java) { accessUnitToAnnexB(hex(payload)) }
        }
        // The input is never modified.
        val input = hex("00000002262700000000")
        accessUnitToAnnexB(input)
        assertArrayEquals(hex("00000002262700000000"), input)
    }

    @Test
    fun clipFramesParse() {
        val clip = fixture("media/hevc/terminal-clip.json")
        val sets = parseParameterSets(Base64.getDecoder().decode(clip.string("codecExtradataBase64")))
        assertEquals(listOf(32, 33, 34), sets.map { nalType(it) })

        val frames = clip.getValue("frames").jsonArray.map { it.jsonObject }
        assertEquals(15, frames.size)
        frames.forEachIndexed { index, entry ->
            val frame = parseMediaFrame(Base64.getDecoder().decode(entry.string("mediaFrameBase64")))
            assertEquals("frame $index: flags", entry.getValue("flags").jsonPrimitive.long.toInt(), frame.flags)
            assertEquals("frame $index: pts", entry.getValue("ptsMilliseconds").jsonPrimitive.long, frame.ptsMilliseconds)
            assertEquals("frame $index: generationId", 1L, frame.generationId)
            assertEquals("frame $index: sequence", index.toLong(), frame.sequence)
            assertEquals("frame $index: keyframe", index == 0 || index == 10, frame.isKeyframe)
            assertEquals("frame $index: configChanged", index == 0, frame.configChanged)

            val annexB = accessUnitToAnnexB(frame.payload)
            assertArrayEquals("frame $index: member conversion", annexB, frame.annexBAccessUnit())
            val types = splitAnnexB(annexB).map { nalType(it) }
            assertTrue("frame $index: empty access unit", types.isNotEmpty())
            assertTrue("frame $index holds parameter sets: $types", types.none { it in 32..34 })
            if (frame.isKeyframe) {
                val firstVcl = types.firstOrNull { it in 0..31 }
                assertNotNull("frame $index: no VCL", firstVcl)
                assertTrue("frame $index: first VCL type $firstVcl", firstVcl == 19 || firstVcl == 20)
                assertTrue("frame $index: only prefix SEI before VCL", types.takeWhile { it !in 0..31 }.all { it == 39 })
            }
        }
    }

    private fun clipChannelFrames(): List<ByteArray> =
        fixture("media/hevc/terminal-clip.json").getValue("frames").jsonArray.map {
            byteArrayOf(1) + Base64.getDecoder().decode(it.jsonObject.string("mediaFrameBase64"))
        }

    @Test
    fun clipFramesParseFromChannelOffset() {
        val clip = fixture("media/hevc/terminal-clip.json")
        val channelFrames = clipChannelFrames()
        assertEquals(15, channelFrames.size)
        channelFrames.forEachIndexed { index, channel ->
            val viaOffset = parseMediaFrame(channel, offset = 1)
            val viaCopy = parseMediaFrame(channel.copyOfRange(1, channel.size))
            assertEquals("frame $index", viaCopy, viaOffset)
            val entry = clip.getValue("frames").jsonArray[index].jsonObject
            assertEquals("frame $index: pts", entry.getValue("ptsMilliseconds").jsonPrimitive.long, viaOffset.ptsMilliseconds)
        }
    }

    @Test
    fun writeAnnexBMatchesAnnexBAccessUnit() {
        for ((index, channel) in clipChannelFrames().withIndex()) {
            val frame = parseMediaFrame(channel, offset = 1)
            val expected = frame.annexBAccessUnit()
            for (buffer in listOf(ByteBuffer.allocate(frame.payloadSize + 9), ByteBuffer.allocateDirect(frame.payloadSize + 9))) {
                buffer.position(5)
                frame.writeAnnexB(buffer)
                assertEquals("frame $index: position", 5 + frame.payloadSize, buffer.position())
                val written = ByteArray(frame.payloadSize).also { buffer.position(5); buffer.get(it) }
                assertArrayEquals("frame $index: direct=${buffer.isDirect}", expected, written)
            }
        }
    }

    @Test
    fun writeAnnexBNeverWritesPartially() {
        val untouched = 0x5a.toByte()
        // Invalid: the second NAL's length runs past the end; the first NAL is valid.
        val bad = MediaFrame(2, 0, 1, 0, 0, 1, 0, hex("0000000126000000ff2728"))
        val buffer = ByteBuffer.allocate(32).apply { while (hasRemaining()) put(untouched); position(3) }
        assertThrows(HevcFramingException::class.java) { bad.writeAnnexB(buffer) }
        assertEquals(3, buffer.position())
        assertTrue(buffer.array().all { it == untouched })

        // Valid, but one byte short of room.
        val good = MediaFrame(2, 0, 1, 0, 0, 1, 0, hex("000000022627"))
        val small = ByteBuffer.allocate(good.payloadSize + 2).apply { while (hasRemaining()) put(untouched); position(3) }
        assertThrows(BufferOverflowException::class.java) { good.writeAnnexB(small) }
        assertEquals(3, small.position())
        assertTrue(small.array().all { it == untouched })

        // Read-only buffers are refused before anything happens.
        assertThrows(java.nio.ReadOnlyBufferException::class.java) {
            good.writeAnnexB(ByteBuffer.allocate(16).asReadOnlyBuffer())
        }

        // An empty access unit writes nothing and succeeds even with no room.
        MediaFrame(2, 0, 1, 0, 0, 1, 0, ByteArray(0)).writeAnnexB(ByteBuffer.allocate(0))
    }

    @Test
    fun completeParameterSetsNeedVpsSpsAndPps() {
        val clipExtradata = Base64.getDecoder().decode(fixture("media/hevc/terminal-clip.json").string("codecExtradataBase64"))
        assertTrue(hasCompleteHevcParameterSets(clipExtradata))
        val vps = parseParameterSets(clipExtradata)[0]
        val sps = parseParameterSets(clipExtradata)[1]
        val pps = parseParameterSets(clipExtradata)[2]
        assertTrue(hasCompleteHevcParameterSets(container(pps, sps, vps)))
        assertTrue(hasCompleteHevcParameterSets(container(vps, sps, pps, pps)))
        assertTrue(hasCompleteHevcParameterSets(container(vps, sps, pps, hex("4e01"))))

        assertFalse(hasCompleteHevcParameterSets(hex("0000")))
        assertFalse(hasCompleteHevcParameterSets(hex("")))
        assertFalse(hasCompleteHevcParameterSets(hex("00010000")))
        assertFalse(hasCompleteHevcParameterSets(container(vps, sps)))
        assertFalse(hasCompleteHevcParameterSets(container(sps, pps)))
        assertFalse(hasCompleteHevcParameterSets(container(vps, pps)))
        assertFalse(hasCompleteHevcParameterSets(container(vps, sps, ByteArray(0))))
        // The synthetic "three" sets start 0x40, 0x42, 0x44: NAL types 32, 33, 34.
        val complete = mapOf("none" to false, "one" to false, "three" to true, "zero-length-set" to false, "terminal-clip" to true)
        val valid = fixture("media/parameter-sets.json").entries("valid")
        assertEquals(complete.keys, valid.map { it.string("name") }.toSet())
        for (entry in valid) {
            val name = entry.string("name")
            assertEquals(name, complete.getValue(name), hasCompleteHevcParameterSets(hex(entry.string("containerHex"))))
        }
        for (entry in fixture("media/parameter-sets.json").entries("invalid")) {
            assertFalse(entry.string("name"), hasCompleteHevcParameterSets(hex(entry.string("containerHex"))))
        }
    }

    /** Builds the upstream container: `u16 count`, then `u32 length || bytes` per set. */
    private fun container(vararg sets: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(sets.size shr 8); write(sets.size and 0xff)
        for (set in sets) {
            val n = set.size
            write(n ushr 24); write((n shr 16) and 0xff); write((n shr 8) and 0xff); write(n and 0xff)
            write(set)
        }
    }.toByteArray()

    private fun nalType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3f

    /** Splits Annex-B on `00 00 01` start codes (emulation prevention keeps them out of NAL bodies). */
    private fun splitAnnexB(stream: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < stream.size) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else stream.size
            while (n + 1 < starts.size && end > start && stream[end - 1] == 0.toByte()) end--
            stream.copyOfRange(start, end)
        }
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
