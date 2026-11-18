package dev.bugobliterator.itermbuddy.core.wire

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `fetchSessionContent` and its `sessionContent` reply (upstream `CompanionMessages.swift:156-163,
 * 753, 1046`), written by hand to Swift's synthesized enum coding: labelled values by name, the
 * unlabelled struct under `_0`, `Data` as padded base64.
 */
class SessionContentMessagesTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)

    @Test
    fun fetchSessionContentEncodesLabelledValues() {
        val frame = encodeEnvelope(PhoneMessage.FetchSessionContent("ptys_7K2M9Q4R8T1VH", 3, 24), requestID = 21)
        assertEquals(
            Json.parseToJsonElement(
                """{"requestID":21,"payload":{"fetchSessionContent":{"sessionGuid":"ptys_7K2M9Q4R8T1VH","firstLine":3,"lineCount":24}}}""",
            ),
            Json.parseToJsonElement(frame.decodeToString()),
        )
    }

    @Test
    fun sessionContentDecodesWithItsRequestID() {
        val base64 = Base64.getEncoder().encodeToString(png)
        val frame = """{"requestID":21,"payload":{"sessionContent":{"_0":""" +
            """{"guid":"ptys_7K2M9Q4R8T1VH","firstLine":0,"lineCount":24,"pngData":"$base64"}}}}"""
        val (requestID, message) = decodeEnvelope(frame.toByteArray())
        assertEquals(21L, requestID)
        val content = (message as MacMessage.SessionContentMessage).content
        assertEquals("ptys_7K2M9Q4R8T1VH", content.guid)
        assertEquals(0, content.firstLine)
        assertEquals(24, content.lineCount)
        assertArrayEquals(png, content.pngData)
        assertEquals(png.size, content.pngSize)
        assertEquals(SessionContent("ptys_7K2M9Q4R8T1VH", 0, 24, png), content)
    }

    @Test
    fun sessionContentKeepsItsOwnCopyAndPrintsOnlyTheSize() {
        val bytes = png.copyOf()
        val content = SessionContent("g", 1, 2, bytes)
        bytes[0] = 0
        content.pngData[1] = 0
        assertArrayEquals(png, content.pngData)
        val printed = MacMessage.SessionContentMessage(content).toString()
        assertEquals("SessionContentMessage(content=SessionContent(guid=g, firstLine=1, lineCount=2, pngData=11 bytes))", printed)
        assertFalse(printed, Base64.getEncoder().encodeToString(png) in printed)
    }

    @Test
    fun malformedSessionContentIsRejected() {
        val bad = listOf(
            """{"payload":{"sessionContent":{}}}""",
            """{"payload":{"sessionContent":{"_0":{"guid":"g","firstLine":0,"lineCount":1}}}}""",
            """{"payload":{"sessionContent":{"_0":{"guid":"g","firstLine":0.5,"lineCount":1,"pngData":""}}}}""",
            """{"payload":{"sessionContent":{"_0":{"guid":"g","firstLine":0,"lineCount":1,"pngData":"abc"}}}}""",
            """{"payload":{"sessionContent":{"_0":{"guid":1,"firstLine":0,"lineCount":1,"pngData":""}}}}""",
        )
        for (frame in bad) {
            assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
        }
        // An unsolicited one decodes too; the session drops it as an orphan reply only when it has an ID.
        assertNull(decodeEnvelope("""{"payload":{"sessionContent":{"_0":{"guid":"g","firstLine":0,"lineCount":0,"pngData":""}}}}""".toByteArray()).first)
    }
}
