package dev.bugobliterator.itermbuddy.video

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIB = 1 shl 20

class PendingInputQueueTest {
    @Test
    fun waitsForAKeyframeFirst() {
        val queue = PendingInputQueue(16L * MIB)
        assertFalse(queue.offer(sizedFrame(0, keyframe = false, payloadBytes = 8)))
        assertTrue(queue.offer(sizedFrame(1, keyframe = true, payloadBytes = 8)))
        assertTrue(queue.offer(sizedFrame(2, keyframe = false, payloadBytes = 8)))
        assertEquals(listOf(1L, 2L), generateSequence { queue.poll() }.map { it.ptsMilliseconds }.toList())
        assertEquals(0L, queue.bytes)
    }

    @Test
    fun overBudgetDropsNonKeyframesAndWaitsForKeyframe() {
        val queue = PendingInputQueue(10L * MIB)
        assertTrue(queue.offer(sizedFrame(0, keyframe = true, payloadBytes = 2 * MIB)))
        repeat(4) { assertTrue(queue.offer(sizedFrame(1L + it, keyframe = false, payloadBytes = 2 * MIB))) }
        assertEquals(10L * MIB, queue.bytes)
        // The next P-frame would exceed the budget: refused, pending P-frames dropped, the keyframe kept.
        assertFalse(queue.offer(sizedFrame(5, keyframe = false, payloadBytes = 1)))
        assertEquals(2L * MIB, queue.bytes)
        assertEquals(1, queue.size)
        assertTrue(queue.waitingForKeyframe)
        // Even a small P-frame is refused until a keyframe.
        assertFalse(queue.offer(sizedFrame(6, keyframe = false, payloadBytes = 4)))
        assertTrue(queue.offer(sizedFrame(7, keyframe = true, payloadBytes = 4)))
        assertTrue(queue.offer(sizedFrame(8, keyframe = false, payloadBytes = 4)))
        assertEquals(listOf(7L, 8L), generateSequence { queue.poll() }.map { it.ptsMilliseconds }.toList())
    }

    @Test
    fun keyframeReplacesPendingFrames() {
        val queue = PendingInputQueue(16L * MIB)
        assertTrue(queue.offer(sizedFrame(0, keyframe = true, payloadBytes = 100)))
        assertTrue(queue.offer(sizedFrame(1, keyframe = false, payloadBytes = 50)))
        assertTrue(queue.offer(sizedFrame(2, keyframe = true, payloadBytes = 30)))
        assertEquals(30L, queue.bytes)
        assertEquals(2L, queue.poll()?.ptsMilliseconds)
        assertNull(queue.poll())
    }

    @Test
    fun oversizedFrameIsRefused() {
        val queue = PendingInputQueue(16L * MIB)
        assertTrue(queue.offer(sizedFrame(0, keyframe = true, payloadBytes = 100)))
        assertFalse(queue.offer(sizedFrame(1, keyframe = true, payloadBytes = 17 * MIB)))
        assertEquals(100L, queue.bytes)
        assertTrue(queue.waitingForKeyframe)
        assertFalse(queue.offer(sizedFrame(2, keyframe = false, payloadBytes = 4)))
    }

    @Test
    fun retainedBytesBoundedWhileDecoderBlocked() {
        val max = 16L * MIB
        val queue = PendingInputQueue(max)
        var pts = 0L
        repeat(20) {
            queue.offer(sizedFrame(pts++, keyframe = true, payloadBytes = MIB))
            assertTrue(queue.bytes <= max)
            repeat(10) {
                queue.offer(sizedFrame(pts++, keyframe = false, payloadBytes = MIB))
                assertTrue(queue.bytes <= max)
            }
        }
        assertTrue(queue.bytes <= max)
    }

    @Test
    fun clearEmptiesAndWaitsForKeyframe() {
        val queue = PendingInputQueue(16L * MIB)
        assertTrue(queue.offer(sizedFrame(0, keyframe = true, payloadBytes = 8)))
        queue.clear()
        assertEquals(0L, queue.bytes)
        assertNull(queue.poll())
        assertFalse(queue.offer(sizedFrame(1, keyframe = false, payloadBytes = 8)))
    }

    @Test
    fun offersFromManyThreadsKeepTheBound() {
        val max = 4L * MIB
        val queue = PendingInputQueue(max)
        val failures = CopyOnWriteArrayList<Throwable>()
        val threads = List(4) { t ->
            Thread {
                try {
                    offerAndPoll(queue, t, max)
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(emptyList<Throwable>(), failures)
        assertTrue(queue.bytes <= max)
        var sum = 0L
        while (true) sum += queue.poll()?.payloadSize ?: break
        assertEquals(0L, queue.bytes)
        assertTrue(sum <= max)
    }

    private fun offerAndPoll(queue: PendingInputQueue, t: Int, max: Long) {
        repeat(500) { i ->
            queue.offer(sizedFrame(i.toLong(), keyframe = i % 50 == t, payloadBytes = 64 * 1024))
            if (i % 3 == 0) queue.poll()
            check(queue.bytes <= max)
        }
    }
}
