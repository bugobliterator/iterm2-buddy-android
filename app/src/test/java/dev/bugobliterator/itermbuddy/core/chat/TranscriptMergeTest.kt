package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergeTest {
    private val budgets = AttachmentBudgets()

    private fun id(n: Int): String = "22222222-3333-4444-8555-%012d".format(n)

    private fun message(n: Int, body: MessageBody, author: Participant = Participant.AGENT, sent: Double = 1000.0 + n) =
        ChatMessage(CHAT, author, body, sent, id(n))

    private fun append(n: Int, chunk: String, target: Int) = message(n, MessageBody.Append(chunk, id(target)))

    private fun appendAttachment(n: Int, attachment: Attachment, target: Int) =
        message(n, MessageBody.AppendAttachment(attachment, id(target)))

    private fun image(id: String, size: Int, mimeType: String = "image/png") =
        Attachment(false, id, AttachmentKind.File("shot", mimeType, size, ByteArray(size) { it.toByte() }))

    private fun files(transcript: Transcript): List<AttachmentKind.File> = transcript.messages().flatMap { message ->
        (message.body as? MessageBody.Multipart)?.parts.orEmpty()
            .mapNotNull { ((it as? Subpart.AttachmentPart)?.attachment?.kind as? AttachmentKind.File) }
    }

    @Test
    fun streamingMergeMatchesUpstreamVectors() {
        val cases = Json.parseToJsonElement(Fixtures.read("chat/streaming-merge.json")).jsonArray
        assertEquals(19, cases.size)
        for (element in cases) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val chatID = case.getValue("chatID").jsonPrimitive.content
            val transcript = Transcript(decodeChatMessages(case.getValue("transcript").jsonArray, budgets), budgets)
            for (delta in case.getValue("deltas").jsonArray) {
                // A live delivery is decoded on its own, as the receive loop decodes one.
                val result = transcript.applyDelivery(decodeChatMessage(delta as JsonObject, budgets), chatID)
                if (name != "append-to-non-text-target-is-ignored" && name != "commit-is-ignored") {
                    assertFalse("$name: $result", result is MergeResult.Ignored)
                }
            }
            val expected = decodeChatMessages(case.getValue("expected").jsonArray, budgets)
            assertEquals(name, expected, transcript.messages())
        }
    }

    @Test
    fun oneThousandDeltaReplyIsOneMessage() {
        val case = Json.parseToJsonElement(Fixtures.read("chat/streaming-merge.json")).jsonArray
            .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "one-thousand-delta-reply" }
        val deltas = case.getValue("deltas") as JsonArray
        assertTrue(deltas.size >= 1000)
        val transcript = Transcript(decodeChatMessages(case.getValue("transcript").jsonArray, budgets), budgets)
        deltas.forEach { transcript.applyDelivery(decodeChatMessage(it.jsonObject, budgets), CHAT) }
        assertEquals(decodeChatMessages(case.getValue("expected").jsonArray, budgets).size, transcript.size)
    }

    @Test
    fun ownEchoIsUpsertedNotDuplicated() {
        val question = message(1, MessageBody.PlainText("question"), Participant.USER)
        val sent = message(2, MessageBody.PlainText("Ship it"), Participant.USER, sent = 2000.0)
        val transcript = Transcript(listOf(question), budgets)
        assertEquals(MergeResult.Appended(1), transcript.applyDelivery(sent, CHAT))
        val echo = sent.copy(sentMillis = 2001.0)
        assertEquals(MergeResult.Replaced(1), transcript.applyDelivery(echo, CHAT))
        assertEquals(MergeResult.Replaced(1), transcript.applyDelivery(echo, CHAT))
        assertEquals(listOf(question, echo), transcript.messages())
    }

    @Test
    fun appendToNonTextTargetIsIgnored() {
        val notice = message(1, MessageBody.ClientNotice(ClientAction.Notice("hello")))
        val transcript = Transcript(listOf(notice), budgets)
        val version = transcript.version
        assertEquals(MergeResult.Ignored(IgnoreReason.TARGET_NOT_TEXT), transcript.applyDelivery(append(2, "x", target = 1), CHAT))
        assertEquals(
            MergeResult.Ignored(IgnoreReason.TARGET_NOT_TEXT),
            transcript.applyDelivery(appendAttachment(3, Attachment(true, "ci", AttachmentKind.Code("y")), target = 1), CHAT),
        )
        assertEquals(listOf(notice), transcript.messages())
        assertEquals(version, transcript.version)
    }

    @Test
    fun hiddenDeliveryIsIgnored() {
        val reply = message(1, MessageBody.Markdown("Done."))
        val transcript = Transcript(listOf(reply, message(5, MessageBody.Hidden("renameChat"))), budgets)
        assertEquals("a history's hidden messages are dropped", listOf(reply), transcript.messages())
        val version = transcript.version
        val hidden = MergeResult.Ignored(IgnoreReason.HIDDEN)
        assertEquals(hidden, transcript.applyDelivery(message(2, MessageBody.Hidden("renameChat")), CHAT))
        assertEquals(hidden, transcript.applyDelivery(message(3, MessageBody.Commit(id(1))), CHAT))
        assertEquals(hidden, transcript.applyDelivery(message(1, MessageBody.Hidden("setPermissions")), CHAT))
        assertEquals(listOf(reply), transcript.messages())
        assertEquals(version, transcript.version)
    }

    @Test
    fun unreadableDeltaIsIgnored() {
        val reply = message(1, MessageBody.Markdown("Partial"))
        val transcript = Transcript(listOf(reply), budgets)
        val unreadable = MergeResult.Ignored(IgnoreReason.UNREADABLE_DELTA)
        assertEquals(unreadable, transcript.applyDelivery(message(2, MessageBody.Unreadable("append")), CHAT))
        assertEquals(unreadable, transcript.applyDelivery(message(3, MessageBody.Unreadable("appendAttachment")), CHAT))
        assertEquals(listOf(reply), transcript.messages())
        // Any other unreadable message is a message of its own, shown as unreadable.
        assertEquals(MergeResult.Appended(1), transcript.applyDelivery(message(4, MessageBody.Unreadable("markdown")), CHAT))
    }

    @Test
    fun removeAllIsIdempotent() {
        val transcript = Transcript((1..4).map { message(it, MessageBody.Markdown("m$it")) }, budgets)
        val ids = setOf(id(3), id(4), id(9))
        assertEquals(2, transcript.removeAll(ids))
        val version = transcript.version
        assertEquals(listOf(id(1), id(2)), transcript.messages().map { it.uniqueID })
        assertEquals(0, transcript.removeAll(ids))
        assertEquals(version, transcript.version)
        assertEquals(listOf(id(1), id(2)), transcript.messages().map { it.uniqueID })
        assertEquals(1, transcript.indexOf(id(2)))
        assertEquals(-1, transcript.indexOf(id(3)))
    }

    @Test
    fun removeAllMatchesLowercaseIds() {
        val transcript = Transcript(listOf(message(1, MessageBody.Markdown("m"))), budgets)
        assertEquals(1, transcript.removeAll(setOf(id(1).lowercase())))
        assertEquals(0, transcript.size)
    }

    @Test
    fun deltaForRemovedTargetIsIgnored() {
        val transcript = Transcript(listOf(message(1, MessageBody.PlainText("q"), Participant.USER)), budgets)
        transcript.applyDelivery(message(2, MessageBody.Markdown("The answer")), CHAT)
        transcript.applyDelivery(append(3, " is", target = 2), CHAT)
        transcript.removeAll(transcript.suffixFrom(id(2)).toSet())
        val removed = MergeResult.Ignored(IgnoreReason.TARGET_REMOVED)
        assertEquals(removed, transcript.applyDelivery(append(4, " 42.", target = 2), CHAT))
        assertEquals(
            removed,
            transcript.applyDelivery(appendAttachment(5, Attachment(true, "ci", AttachmentKind.Code("x")), target = 2), CHAT),
        )
        // A removed ID the phone never saw (a hidden row echoed by messagesRemoved) is a tombstone too.
        transcript.removeAll(setOf(id(7)))
        assertEquals(removed, transcript.applyDelivery(append(8, "fragment", target = 7), CHAT))
        assertEquals(listOf(id(1)), transcript.messages().map { it.uniqueID })
        // A new history replaces the transcript, and with it the tombstones.
        val fresh = Transcript(listOf(message(1, MessageBody.PlainText("q"), Participant.USER)), budgets)
        assertEquals(MergeResult.Appended(1), fresh.applyDelivery(append(9, "new", target = 2), CHAT))
    }

    @Test
    fun suffixFromUnknownIdIsEmpty() {
        val transcript = Transcript((1..3).map { message(it, MessageBody.Markdown("m$it")) }, budgets)
        assertEquals(emptyList<String>(), transcript.suffixFrom(id(9)))
        assertEquals(listOf(id(2), id(3)), transcript.suffixFrom(id(2)))
        assertEquals(listOf(id(1), id(2), id(3)), transcript.suffixFrom(id(1).lowercase()))
    }

    @Test
    fun missingTargetStartsAnAgentMessageInTheNamedChat() {
        val transcript = Transcript(emptyList(), budgets)
        val delta = append(2, "Hello", target = 1).copy(chatID = "other-chat-id", author = Participant.USER, sentMillis = 77.0)
        assertEquals(MergeResult.Appended(0), transcript.applyDelivery(delta, CHAT))
        assertEquals(listOf(ChatMessage(CHAT, Participant.AGENT, MessageBody.Markdown("Hello"), 77.0, id(1))), transcript.messages())
    }

    @Test
    fun versionAndChangedIndexFollowEachChange() {
        val transcript = Transcript(listOf(message(1, MessageBody.Markdown("a")), message(2, MessageBody.Markdown("b"))), budgets)
        val start = transcript.version
        assertNull(transcript.lastChangedIndex)
        transcript.applyDelivery(append(3, "!", target = 1), CHAT)
        assertEquals(start + 1, transcript.version)
        assertEquals(0, transcript.lastChangedIndex)
        transcript.applyDelivery(message(4, MessageBody.Markdown("c")), CHAT)
        assertEquals(start + 2, transcript.version)
        assertEquals(2, transcript.lastChangedIndex)
        transcript.removeAll(setOf(id(4)))
        assertEquals(start + 3, transcript.version)
        assertNull(transcript.lastChangedIndex)
    }

    @Test
    fun readsBetweenDeltasSeeEveryDelta() {
        val transcript = Transcript(listOf(message(1, MessageBody.Multipart(listOf(Subpart.Markdown("Steps:"))))), budgets)
        val expected = StringBuilder("Steps:")
        for (n in 0 until 20) {
            val chunk = " $n"
            transcript.applyDelivery(append(100 + n, chunk, target = 1), CHAT)
            expected.append(chunk)
            if (n % 3 == 0) {
                assertEquals(MessageBody.Multipart(listOf(Subpart.Markdown(expected.toString()))), transcript[0].body)
            }
        }
        // An attachment after the text closes the text part; text after it starts a new markdown part.
        val code = Attachment(true, "ci", AttachmentKind.Code("a"))
        transcript.applyDelivery(appendAttachment(200, code, target = 1), CHAT)
        transcript.applyDelivery(appendAttachment(201, Attachment(false, "ci", AttachmentKind.Code("b")), target = 1), CHAT)
        transcript.applyDelivery(appendAttachment(202, Attachment(true, "ci", AttachmentKind.Code("c")), target = 1), CHAT)
        transcript.applyDelivery(append(203, "after", target = 1), CHAT)
        transcript.applyDelivery(append(204, " code", target = 1), CHAT)
        assertEquals(
            MessageBody.Multipart(
                listOf(
                    Subpart.Markdown(expected.toString()),
                    Subpart.AttachmentPart(Attachment(true, "ci", AttachmentKind.Code("abc"))),
                    Subpart.Markdown("after code"),
                ),
            ),
            transcript.messages().single().body,
        )
    }

    @Test
    fun duplicateIdsMergeIntoTheFirstOnly() {
        val first = message(1, MessageBody.Markdown("first"))
        val second = message(1, MessageBody.PlainText("second"))
        val transcript = Transcript(listOf(first, second), budgets)
        transcript.applyDelivery(append(2, "!", target = 1), CHAT)
        assertEquals(listOf(MessageBody.Markdown("first!"), MessageBody.PlainText("second")), transcript.messages().map { it.body })
        assertEquals(MessageBody.PlainText("second"), transcript[1].body)
    }

    @Test
    fun upsertDuringAStreamReplacesTheStreamedText() {
        val transcript = Transcript(listOf(message(1, MessageBody.PlainText("Draft"))), budgets)
        transcript.applyDelivery(append(2, " one", target = 1), CHAT)
        transcript.applyDelivery(message(1, MessageBody.Markdown("Final")), CHAT)
        transcript.applyDelivery(append(3, "!", target = 1), CHAT)
        assertEquals(MessageBody.Markdown("Final!"), transcript[0].body)
    }

    @Test
    fun appendToPlainTextStaysPlainText() {
        val transcript = Transcript(listOf(message(1, MessageBody.PlainText("Checking"))), budgets)
        transcript.applyDelivery(append(2, " the", target = 1), CHAT)
        transcript.applyDelivery(append(3, " log.", target = 1), CHAT)
        assertEquals(MessageBody.PlainText("Checking the log."), transcript[0].body)
    }

    @Test
    fun statusUpdatesCoalesceIntoOneFlatMultipart() {
        val first = Attachment(true, "status-a", AttachmentKind.StatusUpdate(AgentStatusUpdate.WebSearchStarted))
        val transcript = Transcript(listOf(message(1, MessageBody.Multipart(listOf(Subpart.AttachmentPart(first))))), budgets)
        val nested = AgentStatusUpdate.Multipart(listOf(AgentStatusUpdate.CodeInterpreterStarted, AgentStatusUpdate.CodeInterpreterFinished))
        transcript.applyDelivery(appendAttachment(2, Attachment(false, "status-b", AttachmentKind.StatusUpdate(nested)), target = 1), CHAT)
        transcript.applyDelivery(
            appendAttachment(3, Attachment(false, "other", AttachmentKind.StatusUpdate(AgentStatusUpdate.ReasoningSummaryUpdate("r"))), target = 1),
            CHAT,
        )
        val coalesced = Attachment(
            true,
            "status-a",
            AttachmentKind.StatusUpdate(
                AgentStatusUpdate.Multipart(
                    listOf(
                        AgentStatusUpdate.WebSearchStarted,
                        AgentStatusUpdate.CodeInterpreterStarted,
                        AgentStatusUpdate.CodeInterpreterFinished,
                        AgentStatusUpdate.ReasoningSummaryUpdate("r"),
                    ),
                ),
            ),
        )
        assertEquals(MessageBody.Multipart(listOf(Subpart.AttachmentPart(coalesced))), transcript[0].body)
    }

    @Test
    fun transcriptImageBudgetHoldsAcrossLiveDeltas() {
        val small = AttachmentBudgets(maxImageBytes = 10, maxTranscriptImageBytes = 25)
        fun withImage(n: Int, size: Int) = message(n, MessageBody.Multipart(listOf(Subpart.AttachmentPart(image("img$n", size)))))
        val transcript = Transcript(listOf(withImage(1, 10), withImage(2, 10)), small)
        assertEquals(20L, transcript.keptImageBytes)

        // Each live delivery was decoded with a fresh allowance; the transcript still holds 25.
        transcript.applyDelivery(withImage(3, 10), CHAT)
        assertEquals(20L, transcript.keptImageBytes)
        val stripped = files(transcript)[2]
        assertNull(stripped.copyBytes())
        assertEquals(10, stripped.size)
        assertEquals("shot", stripped.name)

        transcript.applyDelivery(withImage(4, 5), CHAT)
        assertEquals(25L, transcript.keptImageBytes)

        // A streamed image attachment starting a message is under the same budget.
        transcript.applyDelivery(appendAttachment(5, image("img5", 1), target = 6), CHAT)
        assertEquals(25L, transcript.keptImageBytes)
        assertNull(files(transcript)[4].copyBytes())

        // Removing and replacing release what they held.
        transcript.removeAll(setOf(id(1)))
        assertEquals(15L, transcript.keptImageBytes)
        transcript.applyDelivery(message(2, MessageBody.Markdown("no image now")), CHAT)
        assertEquals(5L, transcript.keptImageBytes)
        transcript.applyDelivery(withImage(7, 10), CHAT)
        assertEquals(15L, transcript.keptImageBytes)
        assertEquals(15L, files(transcript).sumOf { it.keptByteCount.toLong() })
    }

    @Test
    fun historyOverTheTranscriptImageBudgetIsTrimmed() {
        val small = AttachmentBudgets(maxImageBytes = 10, maxTranscriptImageBytes = 15)
        val messages = (1..3).map { message(it, MessageBody.Multipart(listOf(Subpart.AttachmentPart(image("i$it", 8))))) }
        val transcript = Transcript(messages, small)
        assertEquals(8L, transcript.keptImageBytes)
        assertEquals(listOf(8, 0, 0), files(transcript).map { it.keptByteCount })
    }

    @Test
    fun streamedImageChunksConcatenateWithinTheBudgets() {
        val small = AttachmentBudgets(maxImageBytes = 10, maxTranscriptImageBytes = 100)
        val first = Attachment(false, "file-a", AttachmentKind.File("a", "image/", 4, byteArrayOf(1, 2, 3, 4)))
        val transcript = Transcript(listOf(message(1, MessageBody.Multipart(listOf(Subpart.AttachmentPart(first))))), small)
        transcript.applyDelivery(
            appendAttachment(2, Attachment(true, "file-a", AttachmentKind.File(".png", "png", 3, byteArrayOf(5, 6, 7))), target = 1),
            CHAT,
        )
        val joined = files(transcript).single()
        assertEquals("a.png", joined.name)
        assertEquals("image/png", joined.mimeType)
        assertEquals(7, joined.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), joined.copyBytes())
        assertEquals(7L, transcript.keptImageBytes)

        // Over the per-image limit the bytes go; the name, type and size stay.
        transcript.applyDelivery(
            appendAttachment(3, Attachment(true, "file-a", AttachmentKind.File("", "", 4, byteArrayOf(8, 9, 10, 11))), target = 1),
            CHAT,
        )
        val over = files(transcript).single()
        assertEquals(11, over.size)
        assertNull(over.copyBytes())
        assertEquals(0L, transcript.keptImageBytes)
    }

    @Test
    fun applyIsLinearInDeltas() {
        // Warm up, so class loading and JIT do not count.
        repeat(3) { streamInto(otherMessages = 50, deltas = 2_000) }
        val shortRun = streamInto(otherMessages = 5_000, deltas = 2_500)
        val longRun = streamInto(otherMessages = 5_000, deltas = 10_000)

        // Every other message is the same object it was: nothing else was copied.
        assertTrue("another message was copied", longRun.othersUntouched)
        assertEquals(10_000 * CHUNK.length + "Start".length, longRun.finalLength)

        val shortPerChar = shortRun.allocated.toDouble() / shortRun.appendedChars
        val longPerChar = longRun.allocated.toDouble() / longRun.appendedChars
        // A quadratic merge would allocate about 4x more per appended char on the 4x longer run.
        assertTrue("allocation per appended char grows: $shortPerChar, $longPerChar", longPerChar < shortPerChar * 1.5)
        assertTrue("allocated $longPerChar bytes per appended char", longPerChar < MAX_ALLOCATED_PER_APPENDED_CHAR)
    }

    private class StreamRun(val allocated: Long, val appendedChars: Long, val othersUntouched: Boolean, val finalLength: Int)

    private fun streamInto(otherMessages: Int, deltas: Int): StreamRun {
        val others = (1..otherMessages).map { message(it, MessageBody.Markdown("other $it")) }
        val target = otherMessages / 2
        val history = others.toMutableList().also { it.add(target, message(0, MessageBody.Markdown("Start"))) }
        val transcript = Transcript(history, budgets)
        val inputs = (1..deltas).map { append(otherMessages + it, CHUNK, target = 0) }
        val allocated = allocatedBy {
            for (delta in inputs) transcript.applyDelivery(delta, CHAT)
            // One read at the end: the reply as the screen would show it.
            transcript[target]
        }
        val untouched = others.withIndex().all { (i, other) -> transcript[if (i < target) i else i + 1] === other }
        val finalLength = (transcript[target].body as MessageBody.Markdown).text.length
        return StreamRun(allocated, deltas.toLong() * CHUNK.length, untouched, finalLength)
    }

    private fun allocatedBy(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun toStringShowsNoContent() {
        val transcript = Transcript(listOf(message(1, MessageBody.Markdown(SENTINEL))), budgets)
        transcript.applyDelivery(append(2, SENTINEL, target = 1), CHAT)
        val ignored = transcript.applyDelivery(message(3, MessageBody.Hidden("renameChat")), CHAT)
        for (text in listOf(transcript.toString(), ignored.toString(), MergeResult.Appended(1).toString())) {
            assertFalse(text, text.contains(SENTINEL))
        }
    }

    @Test
    fun messagesIsASnapshot() {
        val transcript = Transcript(listOf(message(1, MessageBody.Markdown("a"))), budgets)
        val before = transcript.messages()
        transcript.applyDelivery(message(2, MessageBody.Markdown("b")), CHAT)
        assertEquals(1, before.size)
        assertSame(before[0], transcript[0])
        assertTrue(before !is MutableList<*> || runCatching { (before as MutableList<ChatMessage>).clear() }.isFailure)
    }

    private companion object {
        const val CHAT = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
        const val SENTINEL = "SENTINEL-transcript-content"
        const val CHUNK = "chunk of a reply "

        /**
         * Bytes allocated per appended UTF-16 char by a whole run of deltas, garbage included: the
         * growing buffer (2 bytes a char, doubled at most), one result per delta and the final read.
         */
        const val MAX_ALLOCATED_PER_APPENDED_CHAR = 16.0
    }
}
