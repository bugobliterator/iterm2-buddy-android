package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.encodeEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTraceTest {
    private var now = 0L
    private var gateOn = false
    private var gateReads = 0
    private val lines = mutableListOf<String>()
    private val trace = InputTrace(gate = { gateReads++; gateOn }, nowMillis = { now }, sink = { lines += it })

    @Test
    fun gateOffEmitsNothingAndBuildsNoStrings() {
        var built = 0
        repeat(100) { trace.log { built++; "never" } }
        assertEquals(0, built)
        assertEquals(emptyList<String>(), lines)
        var offBuilt = 0
        InputTrace.OFF.log { offBuilt++; "never" }
        assertEquals(0, offBuilt)
    }

    @Test
    fun gateIsReadAtMostOnceASecond() {
        repeat(10) { trace.log { "x" } }
        assertEquals(1, gateReads)
        now = 999
        trace.log { "x" }
        assertEquals(1, gateReads)
        // Turned on with setprop: it takes effect at the next read, within a second.
        gateOn = true
        now = 1_000
        trace.log { "on" }
        assertEquals(2, gateReads)
        assertEquals(listOf("on"), lines)
        // And off again.
        gateOn = false
        now = 2_000
        trace.log { "off" }
        assertEquals(listOf("on"), lines)
    }

    @Test
    fun longLinesAreCappedAndMarked() {
        gateOn = true
        trace.log { "x".repeat(600) }
        val line = lines.single()
        assertEquals(InputTrace.MAX_LINE, line.length)
        assertTrue(line.endsWith(InputTrace.TRUNCATED))
        trace.log { "y".repeat(InputTrace.MAX_LINE) }
        assertEquals(InputTrace.MAX_LINE, lines[1].length)
        assertFalse(lines[1].endsWith(InputTrace.TRUNCATED))
    }

    @Test
    fun charactersAreWrittenAsCodePoints() {
        assertEquals("U+0061[a] U+002F[/] U+0062[b]", codePoints("a/b"))
        assertEquals("U+0061[a] U+002A[*] U+0062[b]", codePoints("a*b"))
        assertEquals("U+0020[ ] U+007E[~] U+000A U+007F", codePoints(" ~\n\u007f"))
        assertEquals("U+00E9 U+1F600", codePoints("é😀"))
        assertEquals("\"\"", codePoints(""))
        assertEquals("null", codePoints(null))
    }

    @Test
    fun keyEventsShowCodePointsAndModifiers() {
        assertEquals(
            "text(U+0063[c]) control=true shift=false leftOption=false rightOption=false",
            traceKeyEvent(KeyEvent(Key.Text("c"), KeyModifiers(control = true))),
        )
        assertEquals(
            "special(backspace) control=false shift=false leftOption=false rightOption=false",
            traceKeyEvent(KeyEvent(Key.Special(SpecialKey.BACKSPACE))),
        )
    }

    @Test
    fun envelopesShowTypedStringsAsCodePoints() {
        val guid = "ptys_7K2M9Q4R8T1VH"
        val key = traceEnvelope(encodeEnvelope(PhoneMessage.SendKey(guid, KeyEvent(Key.Text("a/b"))), null))
        assertEquals(
            """{"payload":{"sendKey":{"event":{"key":{"text":{"_0":"U+0061[a] U+002F[/] U+0062[b]"}},""" +
                """"modifiers":{"control":false,"leftOption":false,"rightOption":false,"shift":false}},"sessionGuid":"$guid"}}}""",
            sortedJson(key),
        )
        val paste = traceEnvelope(encodeEnvelope(PhoneMessage.PasteText(guid, "a*b\n"), 7))
        assertTrue(paste, "\"text\":\"U+0061[a] U+002A[*] U+0062[b] U+000A\"" in paste)
        assertTrue(paste, "\"requestID\":7" in paste)
        val special = traceEnvelope(encodeEnvelope(PhoneMessage.SendKey(guid, KeyEvent(Key.Special(SpecialKey.ESCAPE))), null))
        assertTrue(special, "\"escape\"" in special)
        assertTrue(isTracedInput(PhoneMessage.PasteText(guid, "x")))
        assertFalse(isTracedInput(PhoneMessage.FetchSessionTree))
    }

    /** The JSON with object keys sorted, so the comparison does not depend on encoder order. */
    private fun sortedJson(text: String): String {
        fun sort(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.toSortedMap().mapValues { sort(it.value) })
            else -> e
        }
        return sort(kotlinx.serialization.json.Json.parseToJsonElement(text)).toString()
    }
}
