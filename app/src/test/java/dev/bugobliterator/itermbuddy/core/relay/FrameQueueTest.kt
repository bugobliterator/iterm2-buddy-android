package dev.bugobliterator.itermbuddy.core.relay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrival stamps [FrameQueue] keeps for the live diagnostics. */
class FrameQueueTest {
    @Test
    fun eachReceiveReportsWhenThatFrameWasOffered() = runTest {
        var clock = 100L
        val queue = FrameQueue(maxQueuedBytes = 1_000, nanoTime = { clock })
        assertEquals(FrameQueue.NO_ARRIVAL, queue.lastReceivedAtNanos)
        queue.offer(byteArrayOf(1))
        clock = 250
        queue.offer(byteArrayOf(2))
        clock = 900
        queue.offer(byteArrayOf(3))

        assertArrayEquals(byteArrayOf(1), queue.incoming.receive())
        assertEquals(100L, queue.lastReceivedAtNanos)
        assertArrayEquals(byteArrayOf(2), queue.incoming.receiveCatching().getOrNull())
        assertEquals(250L, queue.lastReceivedAtNanos)
        assertArrayEquals(byteArrayOf(3), queue.incoming.tryReceive().getOrNull())
        assertEquals(900L, queue.lastReceivedAtNanos)
        // Nothing left: the stamp stays.
        assertTrue(queue.incoming.tryReceive().isFailure)
        assertEquals(900L, queue.lastReceivedAtNanos)
        assertEquals(0L, queue.unreadBytes)
    }

    @Test
    fun aForLoopReportsArrivalsToo() = runTest {
        var clock = 0L
        val queue = FrameQueue(maxQueuedBytes = 1_000, nanoTime = { clock })
        val offered = listOf(10L, 20L, 30L)
        for (at in offered) {
            clock = at
            queue.offer(byteArrayOf(at.toByte()))
        }
        queue.close()
        val seen = mutableListOf<Long>()
        for (frame in queue.incoming) seen += queue.lastReceivedAtNanos
        assertEquals(offered, seen)
    }
}
