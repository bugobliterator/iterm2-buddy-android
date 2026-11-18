package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat inbox: accept only enqueues, bounded by items and bytes, one consumer, in order, never a drop. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatInboxTest {
    private fun payload(index: Int, size: Int = 16) = ChatInput.Payload(1, index.toLong(), "history", ByteArray(size))

    private fun delivery(index: Int) = ChatInput.Message(1, index.toLong(), MacMessage.Pong)

    @Test
    fun historyAcceptReturnsWithoutDecoding() = runTest {
        val gate = CompletableDeferred<Unit>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        val inbox = ChatInbox(backgroundScope) { input ->
            gate.await()
            consumed += input
        }
        inbox.accept(ChatInput.LinkUp(1, 15, true))
        runCurrent()
        // The consumer is now parked on the gate with the LinkUp; a cap-sized history goes in at once.
        val history = ChatInput.Payload(1, 12, "history", ByteArray(8 shl 20))
        var returned = false
        val accepting = launch {
            inbox.accept(history)
            returned = true
        }
        runCurrent()
        assertTrue("accept returned without waiting for the consumer", returned)
        assertTrue(accepting.isCompleted)
        assertTrue("the consumer has run nothing", consumed.isEmpty())
        assertEquals(2, inbox.queuedItems)
        assertEquals((8L shl 20), inbox.queuedBytes)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<ChatInput>(ChatInput.LinkUp(1, 15, true), history), consumed.toList())
        assertEquals(0, inbox.queuedItems)
        assertEquals(0L, inbox.queuedBytes)
    }

    @Test
    fun inboxBoundsItemsAndBytes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inbox = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 4, inboxBytes = 100)) { gate.await() }

        // Items: four fit, the fifth waits.
        repeat(4) { inbox.accept(delivery(it)) }
        val fifth = launch { inbox.accept(delivery(4)) }
        runCurrent()
        assertFalse("over the item bound", fifth.isCompleted)
        assertEquals(4, inbox.highWater.get())
        gate.complete(Unit)
        runCurrent()
        assertTrue(fifth.isCompleted)

        // Bytes: 60 + 40 fit, one more byte does not, while a byte-free input still finds a slot.
        val held = CompletableDeferred<Unit>()
        val bytes = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 10, inboxBytes = 100)) { held.await() }
        bytes.accept(payload(0, 60))
        bytes.accept(payload(1, 40))
        val overBytes = launch { bytes.accept(payload(2, 1)) }
        runCurrent()
        assertFalse("over the byte bound", overBytes.isCompleted)
        val noBytes = launch { bytes.accept(delivery(3)) }
        runCurrent()
        assertTrue("an input with no payload bytes needs only a slot", noBytes.isCompleted)
        assertEquals(100L, bytes.queuedBytes)
        held.complete(Unit)
        runCurrent()
        assertTrue(overBytes.isCompleted)
    }

    @Test
    fun oversizedInputIsAdmittedOnlyIntoAnEmptyInbox() = runTest {
        val gate = CompletableDeferred<Unit>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        val inbox = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 8, inboxBytes = 100)) {
            gate.await()
            consumed += it
        }
        inbox.accept(delivery(0))
        val oversized = payload(1, 500)
        val accepting = launch { inbox.accept(oversized) }
        runCurrent()
        assertFalse("not while anything else is queued", accepting.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue("admitted once the inbox drained", accepting.isCompleted)
        assertEquals(2, consumed.size)

        // Straight into an empty inbox it goes at once.
        val empty = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 8, inboxBytes = 100)) { CompletableDeferred<Unit>().await() }
        val direct = launch { empty.accept(payload(2, 500)) }
        runCurrent()
        assertTrue(direct.isCompleted)
    }

    @Test
    fun consumerKeepsOrder() = runTest {
        val consumed = CopyOnWriteArrayList<Long?>()
        val inbox = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 16)) { input ->
            consumed += when (input) {
                is ChatInput.Payload -> input.requestID
                is ChatInput.Message -> input.requestID
                else -> -1L
            }
        }
        val posted = CopyOnWriteArrayList<Long>()
        for (index in 0 until 200) {
            if (index % 3 == 0) inbox.accept(payload(index)) else inbox.accept(delivery(index))
            if (index == 100) inbox.post { consumed += 1000L; posted += 1000L }
        }
        runCurrent()
        val expected = (0 until 200).flatMap { if (it == 100) listOf(100L, 1000L) else listOf(it.toLong()) }
        assertEquals(expected, consumed.toList())
        assertEquals(listOf(1000L), posted.toList())
    }

    @Test
    fun consumerFailureIsLoggedWithoutContentAndTheNextInputRuns() = runTest {
        val logs = CopyOnWriteArrayList<String>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        val inbox = ChatInbox(backgroundScope, log = { logs += it }) { input ->
            if (input is ChatInput.Payload) throw IllegalStateException("secret chat text")
            consumed += input
        }
        inbox.accept(payload(0))
        inbox.accept(delivery(1))
        runCurrent()
        assertEquals(listOf<ChatInput>(delivery(1)), consumed.toList())
        assertEquals(listOf("chat consumer failed on Payload(history): IllegalStateException"), logs.toList())
        assertEquals(0, inbox.queuedItems)
    }

    @Test
    fun fullInboxSuspendsAndNeverDrops() = runTest {
        val gate = CompletableDeferred<Unit>()
        val consumed = CopyOnWriteArrayList<Long?>()
        val inbox = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 8, inboxBytes = 1000)) { input ->
            gate.await()
            consumed += (input as ChatInput.Payload).requestID
        }
        val producer = launch { repeat(100) { inbox.accept(payload(it, 50)) } }
        runCurrent()
        assertFalse("the producer is held back", producer.isCompleted)
        assertTrue("never more than the bound", inbox.highWater.get() <= 8)
        gate.complete(Unit)
        runCurrent()
        assertTrue(producer.isCompleted)
        assertEquals((0L until 100L).toList(), consumed.toList())
        assertTrue(inbox.highWater.get() <= 8)
    }

    @Test
    fun payloadShowsCaseAndSizeOnly() {
        val secret = "SENTINEL-chat-text"
        val raw = """{"payload":{"history":{"chatID":"c","messages":[{"text":"$secret"}]}},"requestID":12}""".toByteArray()
        val shown = ChatInput.Payload(3, 12, "history", raw).toString()
        assertEquals("Payload(linkId=3, requestID=12, case=history, bytes=${raw.size})", shown)
        assertFalse(shown.contains(secret))
    }
}
