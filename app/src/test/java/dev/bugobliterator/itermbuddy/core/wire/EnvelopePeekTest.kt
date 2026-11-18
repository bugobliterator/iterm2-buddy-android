package dev.bugobliterator.itermbuddy.core.wire

import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopePeekTest {
    /** The raw `json` value of a sample file, or the `bytes` of a bare confirmation frame. */
    private fun frameOf(file: File): ByteArray? {
        val text = file.readText(Charsets.UTF_8)
        val tree = Json.parseToJsonElement(text).jsonObject
        tree["bytes"]?.let { return it.jsonPrimitive.content.toByteArray(Charsets.UTF_8) }
        if ("json" !in tree) return null
        val raw = text.substringAfter("\"json\" : ").trimEnd().dropLast(1).trimEnd()
        assertEquals(file.path, tree.getValue("json"), Json.parseToJsonElement(raw))
        return raw.toByteArray(Charsets.UTF_8)
    }

    private val sampleFiles: List<File> by lazy {
        val root = File(checkNotNull(System.getProperty("fixtures.dir")) { "run tests through Gradle" }, "messages")
        root.walk().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.path }.toList()
    }

    private fun case(message: MacMessage): String? = (message as? MacMessage.Unsupported)?.case

    @Test
    fun peekAgreesWithDecodeOnEverySample() {
        var compared = 0
        for (file in sampleFiles) {
            val frame = frameOf(file) ?: continue
            val decoded = runCatching { decodeEnvelope(frame) }
            if (decoded.isFailure) {
                // Only a frame that is not an envelope at all (the bare pairing confirmation).
                assertThrows(file.path, WireDecodeException::class.java) { peekEnvelope(frame) }
                continue
            }
            val (requestID, message) = decoded.getOrThrow()
            val payloadCase = Json.parseToJsonElement(frame.decodeToString()).jsonObject.getValue("payload").jsonObject.keys.single()
            assertEquals(file.path, EnvelopePeek(requestID, payloadCase), peekEnvelope(frame))
            case(message)?.let { assertEquals(file.path, it, payloadCase) }
            compared++
        }
        assertTrue("compared only $compared samples", compared >= 100)
    }

    @Test
    fun peekRejectsWhatDecodeRejects() {
        val frames = listOf(
            "", " ", "[]", "3", "\"payload\"", "null", "{}", "{\"requestID\":1}",
            """{"payload":3}""", """{"payload":"x"}""", """{"payload":[]}""", """{"payload":null}""",
            """{"payload":{}}""", """{"payload":{"pong":{},"ping":{}}}""", """{"payload":{"pong":{},"ping":{},"pong":{}}}""",
            """{"requestID":-1,"payload":{"pong":{}}}""", """{"requestID":1.5,"payload":{"pong":{}}}""",
            """{"requestID":"1","payload":{"pong":{}}}""", """{"requestID":1e2,"payload":{"pong":{}}}""",
            """{"requestID":01,"payload":{"pong":{}}}""", """{"requestID":99999999999999999999,"payload":{"pong":{}}}""",
            """{"requestID":9223372036854775808,"payload":{"pong":{}}}""",
            """{"requestID":true,"payload":{"pong":{}}}""", """{"requestID":{},"payload":{"pong":{}}}""",
            """{"requestID":[1],"payload":{"pong":{}}}""", """{"requestID":nul,"payload":{"pong":{}}}""",
            """{"requestID":12abc,"payload":{"pong":{}}}""", """{"payload":{"pong":{}},"requestID":"7"}""",
            // Not JSON.
            """{"payload":{"pong":{}}""", """{"payload":{"pong":{}}} x""", """{"payload":{"pong":{}}}}""",
            """{"payload":{"pong":{}},}""", """{"payload" {"pong":{}}}""", """{"payload":{"pong":{}} "x":1}""",
            """{"payload":{"pong":{]}}""", """{"payload":{"pong":"x}}""", """{"payload":{"pong":{}},"x":}""",
            "{\"payload\":{\"pong\":{}}}\u0000", "﻿{\"payload\":{\"pong\":{}}}",
            """{"payload":{"pong":{}},"x":[1,2}""", """{"payload":{"pong":{}},"x":"\q"}""",
        )
        for (text in frames) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertThrows("decode: $text", WireDecodeException::class.java) { decodeEnvelope(bytes) }
            assertThrows("peek: $text", WireDecodeException::class.java) { peekEnvelope(bytes) }
        }
        val media = byteArrayOf(0x01, '{'.code.toByte(), '}'.code.toByte())
        assertThrows(WireDecodeException::class.java) { peekEnvelope(media) }
    }

    @Test
    fun peekIsStringAware() {
        val frames = mapOf(
            // "payload" and braces inside string values, an escaped quote, the request ID after the payload.
            """{"note":"\"payload\":{\"x\":{}} }","payload":{"history":{"chatID":"a}\"{","messages":[],"extra":[{"x":"]}"}],"maxSeq":1}},"requestID":12}"""
                to EnvelopePeek(12, "history"),
            """{"payload":{"selectionText":{"text":"\\"}},"requestID":0}""" to EnvelopePeek(0, "selectionText"),
            """ { "requestID" : null , "payload" : { "chatListChanged" : { "chats" : [ ] } } } """
                to EnvelopePeek(null, "chatListChanged"),
            // A repeated key keeps its last value, as kotlinx's tree does.
            """{"requestID":"x","payload":[],"requestID":4,"payload":{"pong":{}}}""" to EnvelopePeek(4, "pong"),
            """{"payload":{"pong":{},"pong":{"a":1}}}""" to EnvelopePeek(null, "pong"),
            // Escaped keys read as what they spell.
            "{\"request\\u0049D\":5,\"pay\\u006coad\":{\"p\\u006fng\":{}}}" to EnvelopePeek(5, "pong"),
            "{\"payload\":{\"caf\\u00e9\\ud83d\\ude80\":1}}" to EnvelopePeek(null, "caf\u00E9\uD83D\uDE80"),
            """{"payload":{"π":true}}""" to EnvelopePeek(null, "π"),
            // Unknown top-level keys of every shape are skipped.
            """{"a":[1,{"b":[[]]},"]"],"c":-1.5e3,"d":true,"e":null,"payload":{"futureThing":[1,2]},"requestID":18446744}"""
                to EnvelopePeek(18446744, "futureThing"),
            """{"requestID":9223372036854775807,"payload":{"pong":{}}}""" to EnvelopePeek(Long.MAX_VALUE, "pong"),
        )
        for ((text, expected) in frames) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val decoded = decodeEnvelope(bytes)
            assertEquals("decode: $text", expected.requestID, decoded.first)
            assertEquals("peek: $text", expected, peekEnvelope(bytes))
        }
        assertEquals(setOf("history", "chatsAndSessions", "chatListChanged"), RAW_CHAT_CASES)
    }

    @Test
    fun peekAllocatesNoBodyCopy() {
        val frame = largeHistoryFrame(8 shl 20)
        val small = """{"payload":{"pong":{}},"requestID":3}""".toByteArray()
        repeat(20) {
            peekEnvelope(small)
            peekEnvelope(frame)
        }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        val peek = peekEnvelope(frame)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(EnvelopePeek(12, "history"), peek)
        assertTrue("peeking an ${frame.size}-byte frame allocated $allocated bytes", allocated < 64 * 1024)
    }

    /** A `history` frame of about [targetBytes], with the request ID last so the scan must cross the body. */
    @Test
    fun peekChatIDReadsTheBodysChatID() {
        val chat = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
        assertEquals(chat, peekChatID("""{"requestID":12,"payload":{"history":{"messages":[{"chatID":"inner"}],"chatID":"$chat"}}}""".toByteArray()))
        assertEquals("escaped key", chat, peekChatID("""{"payload":{"delivery":{"chat\u0049D":"$chat"}}}""".toByteArray()))
        assertEquals("only the body's own key", null, peekChatID("""{"payload":{"chatListChanged":{"chats":[{"chatID":"x"}]}}}""".toByteArray()))
    }

    @Test
    fun peekChatIDIsNullAndNeverThrowsOnBadFrames() {
        val longID = "a".repeat(257)
        val deep = "[".repeat(2_000) + "]".repeat(2_000)
        val frames = mapOf(
            "truncated" to """{"payload":{"history":{"chatID":"6A1D3F5B""",
            "truncated after the key" to """{"payload":{"history":{"chatID":""",
            "non-string chatID" to """{"payload":{"history":{"chatID":42}}}""",
            "object chatID" to """{"payload":{"history":{"chatID":{"id":"x"}}}}""",
            "chatID over 256 bytes" to """{"payload":{"history":{"chatID":"$longID"}}}""",
            "bad unicode escape" to """{"payload":{"history":{"chatID":"\u12G4"}}}""",
            "short unicode escape" to """{"payload":{"history":{"chatID":"\u12"}}}""",
            "deep nesting before it" to """{"payload":{"history":{"messages":$deep,"chatID":"""",
            "deep nesting, unterminated" to """{"payload":{"history":{"messages":${"[".repeat(5_000)}""",
            "not an object" to """[1,2,3]""",
            "empty" to "",
        )
        for ((name, text) in frames) assertEquals(name, null, peekChatID(text.toByteArray()))
        val media = byteArrayOf(0x01) + """{"payload":{"history":{"chatID":"x"}}}""".toByteArray()
        assertEquals("media byte", null, peekChatID(media))
        assertEquals("256 bytes is still read", "b".repeat(256), peekChatID("""{"payload":{"history":{"chatID":"${"b".repeat(256)}"}}}""".toByteArray()))
    }

    private fun largeHistoryFrame(targetBytes: Int): ByteArray {
        val head = """{"payload":{"history":{"chatID":"6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E","maxSeq":1,"messages":["""
        val tail = """]}},"requestID":12}"""
        val message = """{"author":"agent","chatID":"6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E","content":{"markdown":""" +
            """{"_0":"Step \"done\" {ok} [1] \\ ✓ — payload: }"}},"sentDate":1795000000000,""" +
            """"uniqueID":"11111111-2222-4333-8444-000000000001"}"""
        val count = (targetBytes - head.length - tail.length) / (message.toByteArray().size + 1)
        return buildString {
            append(head)
            repeat(count) { index ->
                if (index > 0) append(',')
                append(message)
            }
            append(tail)
        }.toByteArray(Charsets.UTF_8)
    }
}
