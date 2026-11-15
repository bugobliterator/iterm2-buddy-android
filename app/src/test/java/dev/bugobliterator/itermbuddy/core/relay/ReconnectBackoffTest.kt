package dev.bugobliterator.itermbuddy.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {
    @Test
    fun backoffBoundsPerAttempt() {
        // Full jitter: rand(0, attempt == 1 ? 3 : min(30, 2^attempt)) seconds.
        val capsMillis = mapOf(1 to 3_000L, 2 to 4_000L, 5 to 30_000L, 9 to 30_000L)
        for ((attempt, cap) in capsMillis) {
            val random = Random(attempt * 7919)
            val delays = List(2_000) { nextDelayMillis(attempt, null, random) }
            assertTrue("attempt $attempt below zero: ${delays.min()}", delays.min() >= 0)
            assertTrue("attempt $attempt above $cap: ${delays.max()}", delays.max() <= cap)
            // The full range is used, so the cap is not set lower than upstream's.
            assertTrue("attempt $attempt never near $cap: ${delays.max()}", delays.max() > cap * 9 / 10)
            assertTrue("attempt $attempt never near zero: ${delays.min()}", delays.min() < cap / 10)
            assertEquals(
                "attempt $attempt is deterministic for a seed",
                delays,
                Random(attempt * 7919).let { seeded -> List(2_000) { nextDelayMillis(attempt, null, seeded) } },
            )
        }
    }

    @Test
    fun backoffRejectsAttemptBelowOne() {
        assertThrows(IllegalArgumentException::class.java) { nextDelayMillis(0, null, Random(1)) }
    }

    @Test
    fun displacedWaitsSixtySeconds() {
        for (attempt in listOf(1, 2, 9)) {
            assertEquals(60_000L, nextDelayMillis(attempt, "displaced", Random(attempt)))
        }
    }

    @Test
    fun quotaWaitsThirtyMinutes() {
        for (attempt in listOf(1, 2, 9)) {
            assertEquals(30 * 60_000L, nextDelayMillis(attempt, "daily quota exceeded", Random(attempt)))
        }
    }

    @Test
    fun otherCloseReasonsUseJitter() {
        for (reason in listOf("peer gone", "mac offline", "")) {
            assertTrue(reason, nextDelayMillis(1, reason, Random(3)) <= 3_000L)
        }
    }

    @Test
    fun http403IsFatal() {
        for (status in listOf(400, 401, 403, 404, 413, 421, 499)) {
            assertTrue("$status", isFatalHttp(status))
        }
    }

    @Test
    fun http429Retries() {
        for (status in listOf(429, 500, 502, 503, 504)) {
            assertFalse("$status", isFatalHttp(status))
        }
    }
}
