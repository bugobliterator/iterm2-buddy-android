package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private fun delivery(index: Int) = ChatInput.Message(1, index.toLong(), MacMessage.Pong, 0)

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
    fun postTakenByACancelledReceiveIsReportedDropped() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val inbox = ChatInbox(scope) { }
        runCurrent()
        // The consumer waits in receive; the post is handed to it, and it is cancelled before it resumes.
        var ran = false
        var dropped = 0
        inbox.post(onDropped = { dropped++ }) { ran = true }
        job.cancel()
        runCurrent()
        assertFalse(ran)
        assertEquals(1, dropped)
        // Queued behind it, and posted after the stop: dropped too, once each.
        inbox.post(onDropped = { dropped++ }) { ran = true }
        assertEquals(2, dropped)
        assertFalse(ran)
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

    /** An Error (an OutOfMemoryError in a big decode) fails that input only; the consumer goes on. */
    @Test
    fun consumerSurvivesAnError() = runTest {
        val logs = CopyOnWriteArrayList<String>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        var stopped = 0
        val inbox = ChatInbox(backgroundScope, log = { logs += it }, onStopped = { stopped++ }) { input ->
            if (input is ChatInput.Payload) throw OutOfMemoryError("secret chat text")
            consumed += input
        }
        inbox.accept(payload(0))
        var posted = false
        inbox.post { throw StackOverflowError() }
        inbox.post { posted = true }
        inbox.accept(delivery(1))
        runCurrent()
        assertEquals(listOf<ChatInput>(delivery(1)), consumed.toList())
        assertTrue(posted)
        assertFalse(inbox.stopped)
        assertEquals(0, stopped)
        assertEquals(
            listOf("chat consumer failed on Payload(history): OutOfMemoryError", "chat consumer failed on posted work: StackOverflowError"),
            logs.toList(),
        )
    }

    @Test
    fun stoppedConsumerIsReportedOnce() = runTest {
        val job = Job()
        var stopped = 0
        val inbox = ChatInbox(CoroutineScope(StandardTestDispatcher(testScheduler) + job), onStopped = { stopped++ }) { }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(inbox.stopped)
        assertEquals(1, stopped)
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

    /** A cancellation of consume's own (a timeout inside it) fails that input only; the consumer goes on. */
    @Test
    fun consumerSurvivesItsOwnTimeout() = runTest {
        val logs = CopyOnWriteArrayList<String>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        val inbox = ChatInbox(backgroundScope, log = { logs += it }) { input ->
            if (input is ChatInput.Payload) withTimeout(1.seconds) { awaitCancellation() }
            consumed += input
        }
        inbox.accept(payload(0))
        inbox.accept(delivery(1))
        testScheduler.advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf<ChatInput>(delivery(1)), consumed.toList())
        assertEquals(listOf("chat consumer failed on Payload(history): TimeoutCancellationException"), logs.toList())
        assertFalse(inbox.stopped)
        inbox.accept(delivery(2))
        runCurrent()
        assertEquals(2, consumed.size)
    }

    /** Once the consumer's scope is cancelled, accept fails at once, also for one already waiting for room. */
    @Test
    fun deadConsumerFailsAcceptAtOnce() = runTest {
        val job = Job()
        val logs = CopyOnWriteArrayList<String>()
        val inbox = ChatInbox(CoroutineScope(backgroundScope.coroutineContext + job), ChatBudgets(inboxItems = 1), log = { logs += it }) {
            awaitCancellation()
        }
        inbox.accept(delivery(0))
        runCurrent()
        val waiting = async { runCatching { inbox.accept(delivery(1)) }.exceptionOrNull() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        job.cancel()
        runCurrent()
        assertTrue(inbox.stopped)
        assertTrue("${waiting.await()}", waiting.await() is ChatInboxStoppedException)
        val startedAt = testScheduler.currentTime
        val failure = runCatching { inbox.accept(delivery(2)) }.exceptionOrNull()
        assertTrue("$failure", failure is ChatInboxStoppedException)
        assertEquals(startedAt, testScheduler.currentTime)
        assertEquals("logged once", listOf("the chat consumer has stopped; refusing chat inputs"), logs.toList())
    }

    @Test
    fun decodedMessagesChargeTheirFrameBytes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inbox = ChatInbox(backgroundScope, ChatBudgets(inboxItems = 10, inboxBytes = 100)) { gate.await() }
        inbox.accept(ChatInput.Message(1, null, MacMessage.Pong, 60))
        inbox.accept(ChatInput.PayloadTooLarge(1, "history", null, 12, 1 shl 30))
        val over = launch { inbox.accept(ChatInput.Message(1, null, MacMessage.Pong, 41)) }
        runCurrent()
        assertEquals(60L, inbox.queuedBytes)
        assertFalse("a decoded message's frame counts toward the byte bound", over.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(over.isCompleted)
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
