package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AckSenderTest {
    /** While a send is held up, newer acks for the same stream replace the pending one: only the newest goes. */
    @Test
    fun newestAckReplacesPendingOne() = runTest {
        val link = ScriptedStreamLink()
        val gate = CompletableDeferred<Unit>()
        link.sendGate = gate
        val sender = AckSender(link, backgroundScope)
        sender.ack(linkId = 1, streamID = 1, ptsMilliseconds = 10)
        runCurrent() // the sender takes pts 10 and waits on the gate
        sender.ack(linkId = 1, streamID = 1, ptsMilliseconds = 20)
        sender.ack(linkId = 1, streamID = 1, ptsMilliseconds = 30)
        sender.ack(linkId = 1, streamID = 1, ptsMilliseconds = 40)
        runCurrent()
        assertEquals(emptyList<PhoneMessage>(), link.sent.map { it.message })
        gate.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(PhoneMessage.StreamAck(1, 10, 0), PhoneMessage.StreamAck(1, 40, 0)),
            link.sent.map { it.message },
        )
        assertEquals(listOf<Long?>(1, 1), link.sent.map { it.onLink })
    }

    /** Acks go out in call order, each on its own link, all with queueDepth 0; a dead link drops its ack quietly. */
    @Test
    fun acksAreSentInOrder() = runTest {
        val link = ScriptedStreamLink()
        link.currentLink = 2
        val sender = AckSender(link, backgroundScope)
        val expected = mutableListOf<PhoneMessage.StreamAck>()
        sender.ack(linkId = 1, streamID = 9, ptsMilliseconds = 1) // link 1 has ended: dropped
        for (pts in 0L until 100L) {
            sender.ack(linkId = 2, streamID = 3, ptsMilliseconds = pts * 33)
            expected += PhoneMessage.StreamAck(3, pts * 33, 0)
            runCurrent()
        }
        assertEquals(expected, link.sent.map { it.message })
        assertEquals(List(100) { 2L }, link.sent.map { it.onLink })
        // Two streams pending together keep their first-call order.
        link.sent.clear()
        sender.ack(linkId = 2, streamID = 4, ptsMilliseconds = 7)
        sender.ack(linkId = 2, streamID = 5, ptsMilliseconds = 8)
        runCurrent()
        assertEquals(listOf(PhoneMessage.StreamAck(4, 7, 0), PhoneMessage.StreamAck(5, 8, 0)), link.sent.map { it.message })
    }
}
