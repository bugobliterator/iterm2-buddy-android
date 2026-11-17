package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.stream.isStreamScoped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySelectionMessagesTest {
    /** [bytes] is the sample's `json` text exactly as the fixture file holds it, escapes included. */
    private class Sample(val path: String, val direction: String, val json: JsonObject, val bytes: ByteArray) {
        val name: String get() = path.substringAfterLast('/').removeSuffix(".json")
    }

    /** Slices the raw `json` value, which the generator writes last, out of a fixture file. */
    private fun rawJson(path: String, text: String): String {
        val marker = "\"json\" : "
        check(text.indexOf(marker) == text.lastIndexOf(marker) && marker in text) { "$path: no single json key" }
        val raw = text.substringAfter(marker).trimEnd().dropLast(1).trimEnd()
        check(raw.endsWith("}")) { "$path: json is not the last value" }
        return raw
    }

    private val samples: List<Sample> by lazy {
        val paths = Fixtures.list("messages/history-and-selection")
        assertTrue("messages/history-and-selection is empty", paths.isNotEmpty())
        paths.map { path ->
            val text = Fixtures.read(path)
            val file = Json.parseToJsonElement(text).jsonObject
            val json = file.getValue("json").jsonObject
            val raw = rawJson(path, text)
            assertEquals("$path: sliced json", json, Json.parseToJsonElement(raw))
            Sample(path, file.getValue("direction").jsonPrimitive.content, json, raw.toByteArray(Charsets.UTF_8))
        }
    }

    private fun samples(direction: String): List<Sample> =
        samples.filter { it.direction == direction }.also { assertTrue("no $direction samples", it.isNotEmpty()) }

    private fun sample(name: String): Sample = samples.single { it.name == name }

    /** Runs [check] on each sample; any failure becomes an AssertionError naming the sample. */
    private fun forEachSample(samples: List<Sample>, check: (Sample) -> Unit) {
        for (sample in samples) {
            try {
                check(sample)
            } catch (e: Throwable) {
                throw AssertionError("${sample.path}: $e", e)
            }
        }
    }

    private fun tree(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    private val guid = "ptys_7K2M9Q4R8T1VH"
    private val anchor = SelectionPoint(absLine = 4010, column = 2)
    private val live = SelectionPoint(absLine = 4011, column = 41)

    /** Every phoneToMac sample, with the message and request ID iOS sends it with. */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        "fetch-history-tile" to (PhoneMessage.FetchHistoryTile(1, firstAbsLine = 3000, lineCount = 50, generationId = 1) to 7L),
        "report-scroll-wheel-up" to (PhoneMessage.ReportScrollWheel(1, up = true, lines = 3) to null),
        "report-scroll-wheel-down" to (PhoneMessage.ReportScrollWheel(1, up = false, lines = 1) to null),
        "selection-gesture-move" to
            (PhoneMessage.SelectionGesture(1, SelectionPhase.MOVE, SelectionMode.CHARACTER, live) to null),
        "selection-gesture-end" to
            (PhoneMessage.SelectionGesture(1, SelectionPhase.END, SelectionMode.CHARACTER, live) to null),
        "clear-selection" to (PhoneMessage.ClearSelection(1) to null),
        "select-all-in-stream" to (PhoneMessage.SelectAllInStream(1) to null),
        "copy-selection" to (PhoneMessage.CopySelection(guid) to 8L),
    ) + SelectionMode.entries.associate {
        "selection-gesture-begin-${it.raw}" to (PhoneMessage.SelectionGesture(1, SelectionPhase.BEGIN, it, anchor) to null)
    }

    /** The sample tile's PNG: one black grayscale pixel (the harness cannot run AppKit's renderer). */
    private val samplePng: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAADUlEQVR4AQECAP3/AAAAAgABfgUN0gAAAABJRU5ErkJggg==",
    )

    private fun error(sample: Sample): MacMessage.Error = MacMessage.Error(
        sample.json.getValue("payload").jsonObject.getValue("error").jsonObject.getValue("_0").jsonObject,
    )

    /** Every macToPhone sample by name, with the request ID and message it decodes to. */
    private val macMessages: Map<String, (Sample) -> Pair<Long?, MacMessage>> = mapOf(
        "history-tile" to { _ ->
            7L to MacMessage.HistoryTileMessage(HistoryTile(1, 1, 3000, 50, 3000, 1025, samplePng))
        },
        "history-tile-evicted" to { _ ->
            7L to MacMessage.HistoryTileMessage(HistoryTile(1, 1, 3100, 0, 3100, 1025, ByteArray(0)))
        },
        "stream-extent" to { _ -> null to MacMessage.StreamExtent(1, firstAbsLine = 3100, totalLines = 1025) },
        "selection-range" to { _ -> null to MacMessage.SelectionRange(1, SelectionSpan(anchor, live)) },
        "selection-range-cleared" to { _ -> null to MacMessage.SelectionRange(1, null) },
        "selection-text" to { _ -> 8L to MacMessage.SelectionText("make test\ngit status --short") },
        "selection-text-empty" to { _ -> 8L to MacMessage.SelectionText("") },
        "error-no-such-stream" to { sample -> 7L to error(sample) },
    )

    @Test
    fun historySelectionPhoneSamplesEncodeExactly() {
        val phoneSamples = samples("phoneToMac")
        forEachSample(phoneSamples) { sample ->
            val (message, requestID) = phoneMessages[sample.name] ?: throw AssertionError("no table entry")
            assertEquals(sample.path, sample.json, tree(encodeEnvelope(message, requestID)))
        }
        assertEquals("table entries without a sample", phoneMessages.keys, phoneSamples.map { it.name }.toSet())
    }

    @Test
    fun historySelectionMacSamplesDecode() {
        val macSamples = samples("macToPhone")
        forEachSample(macSamples) { sample ->
            val expected = macMessages[sample.name]?.invoke(sample) ?: throw AssertionError("no expectation")
            assertEquals(sample.path, expected, decodeEnvelope(sample.bytes))
        }
        assertEquals("expectations without a sample", macMessages.keys, macSamples.map { it.name }.toSet())

        val tile = (decodeEnvelope(sample("history-tile").bytes).second as MacMessage.HistoryTileMessage).tile
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(signature, tile.pngData.copyOf(signature.size))
        val evicted = (decodeEnvelope(sample("history-tile-evicted").bytes).second as MacMessage.HistoryTileMessage).tile
        assertEquals(0, evicted.lineCount)
        assertEquals(0, evicted.pngSize)
        val cleared = decodeEnvelope(sample("selection-range-cleared").bytes).second as MacMessage.SelectionRange
        assertNull(cleared.range)
        assertFalse("Swift omits a nil range", "range" in sample("selection-range-cleared").bytes.decodeToString())
    }

    @Test
    fun everySelectionModeHasASample() {
        val prefix = "selection-gesture-begin-"
        val sampled = samples("phoneToMac").map { it.name }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        assertEquals(SelectionMode.entries.map { it.raw }.toSet(), sampled.toSet())
    }

    @Test
    fun streamExtentAndSelectionRangeAreStreamScoped() {
        assertTrue(isStreamScoped(MacMessage.StreamExtent(1, 3100, 1025)))
        assertTrue(isStreamScoped(MacMessage.SelectionRange(1, null)))
        assertTrue(isStreamScoped(MacMessage.SelectionRange(1, SelectionSpan(anchor, live))))
        // Replies go to their request's waiter.
        assertFalse(isStreamScoped(MacMessage.HistoryTileMessage(HistoryTile(1, 1, 3000, 50, 3000, 1025, samplePng))))
        assertFalse(isStreamScoped(MacMessage.SelectionText("x")))
    }

    @Test
    fun selectionTextToStringHidesText() {
        val secret = "kittens-secret"
        val text = MacMessage.SelectionText(secret)
        assertFalse(text.toString(), secret in text.toString())
        assertEquals("SelectionText(text(len=14))", text.toString())
        val decoded = decodeEnvelope("""{"requestID":8,"payload":{"selectionText":{"text":"$secret"}}}""".toByteArray())
        assertFalse(decoded.toString(), secret in decoded.toString())
        val tile = HistoryTile(1, 1, 3000, 50, 3000, 1025, secret.toByteArray())
        assertFalse(tile.toString(), secret in tile.toString())
        assertTrue(tile.toString(), "pngData=14 bytes" in tile.toString())
    }

    @Test
    fun historyTileOwnsItsPng() {
        val input = samplePng.copyOf()
        val tile = HistoryTile(1, 1, 3000, 50, 3000, 1025, input)
        val hash = tile.hashCode()
        input.fill(0)
        tile.pngData.fill(0)
        assertEquals(HistoryTile(1, 1, 3000, 50, 3000, 1025, samplePng), tile)
        assertEquals(hash, tile.hashCode())
        assertEquals(samplePng.size, tile.pngSize)
    }

    @Test
    fun optionalAndUnknownFieldsFollowUpstream() {
        val cases = mapOf(
            """{"payload":{"selectionRange":{"streamID":1,"range":null}}}""" to MacMessage.SelectionRange(1, null),
            """{"payload":{"selectionRange":{"streamID":1,"future":true}}}""" to MacMessage.SelectionRange(1, null),
            """{"payload":{"streamExtent":{"streamID":2,"firstAbsLine":0,"totalLines":25,"x":[1]}}}""" to
                MacMessage.StreamExtent(2, 0, 25),
            """{"payload":{"selectionText":{"text":"a","_1":2}}}""" to MacMessage.SelectionText("a"),
            """{"payload":{"selectionRange":{"streamID":1,"range":{"start":{"absLine":5,"column":0,"x":1},""" +
                """"end":{"absLine":6,"column":79}}}}}""" to
                MacMessage.SelectionRange(1, SelectionSpan(SelectionPoint(5, 0), SelectionPoint(6, 79))),
        )
        for ((text, expected) in cases) {
            assertEquals(text, null to expected, decodeEnvelope(text.toByteArray()))
        }
    }

    @Test
    fun badHistorySelectionBodiesThrow() {
        val tile = sample("history-tile").json.toString()
        val bad = listOf(
            tile.replace("\"lineCount\":50,", ""),
            tile.replace("\"streamID\":1", "\"streamID\":4294967296"),
            tile.replace("\"generationId\":1", "\"generationId\":-1"),
            tile.replace("\"firstAbsLine\":3000", "\"firstAbsLine\":3e3"),
            tile.replace("\"windowLineCount\":1025", "\"windowLineCount\":\"1025\""),
            tile.replace("\"pngData\":\"iVBOR", "\"pngData\":\"*VBOR"),
            tile.replace("\"pngData\":\"iVBOR", "\"pngData\":\"iVBO"),
            """{"payload":{"historyTile":{"streamID":1}}}""",
            """{"payload":{"streamExtent":{"streamID":1,"firstAbsLine":3100}}}""",
            """{"payload":{"streamExtent":{"streamID":1,"firstAbsLine":3100,"totalLines":10.5}}}""",
            """{"payload":{"selectionRange":{"range":null}}}""",
            """{"payload":{"selectionRange":{"streamID":1,"range":{"start":{"absLine":1,"column":0}}}}}""",
            """{"payload":{"selectionRange":{"streamID":1,"range":{"start":{"absLine":1},"end":{"absLine":1,"column":0}}}}}""",
            """{"payload":{"selectionRange":{"streamID":1,"range":[]}}}""",
            """{"payload":{"selectionText":{}}}""",
            """{"payload":{"selectionText":{"text":null}}}""",
            """{"payload":{"selectionText":{"_0":"a"}}}""",
        )
        for (text in bad) {
            assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
        }
    }

    @Test
    fun streamIdsAreCheckedOnEncode() {
        val point = SelectionPoint(0, 0)
        val messages = listOf(
            PhoneMessage.FetchHistoryTile(4294967296, 0, 50, 1),
            PhoneMessage.FetchHistoryTile(1, 0, 50, -1),
            PhoneMessage.ReportScrollWheel(-1, true, 1),
            PhoneMessage.SelectionGesture(4294967296, SelectionPhase.BEGIN, SelectionMode.CHARACTER, point),
            PhoneMessage.ClearSelection(-1),
            PhoneMessage.SelectAllInStream(4294967296),
        )
        for (message in messages) {
            assertThrows("$message", IllegalArgumentException::class.java) { message.payload() }
        }
    }
}
