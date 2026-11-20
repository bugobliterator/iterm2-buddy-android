package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.relay.ArrivalStamping
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** [LatencyMeter] in virtual time: its clock is the test scheduler's, in nanoseconds. */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencyMeterTest {
    private val stamping = ArrivalStamping()

    private fun TestScope.meter(window: Int = LatencyMeter.DEFAULT_WINDOW) =
        LatencyMeter(nowNanos = { nanos() }, window = window, stamping = stamping)

    private fun TestScope.nanos(): Long = currentTime * MILLI

    /** Starts measuring with pings that never answer, so only the hooks make numbers. */
    private fun TestScope.measure(meter: LatencyMeter): Job = backgroundScope.launch { meter.measure { false } }

    @Test
    fun hooksRecordNothingUnlessMeasuring() = runTest {
        val meter = meter()
        assertFalse(meter.enabled)
        meter.keySent()
        meter.mediaArrived(nanos())
        meter.mediaSubmitted(nanos())
        assertEquals(LatencyStats(), meter.stats.value)

        assertFalse(stamping.on)
        val job = measure(meter)
        runCurrent()
        assertTrue(meter.enabled)
        assertTrue("relay arrivals are stamped while measuring", stamping.on)
        job.cancel()
        runCurrent()
        assertFalse(meter.enabled)
        assertFalse(stamping.on)
    }

    @Test
    fun pingRoundTripsAreTimedEveryInterval() = runTest {
        val meter = meter()
        val link = ScriptedStreamLink()
        val active = ActiveStream(linkId = 1, streamID = 3, sessionGuid = "guid", watchEpoch = 1, config = null, liveTop = null)
        backgroundScope.launch { meter.measure(interval = 2.seconds) { pingActiveLink(link, active) } }
        val replyAfter = listOf(30L, 10L, 20L)
        for (millis in replyAfter) {
            val pending = link.requests.receive()
            assertEquals(PhoneMessage.Ping, pending.message)
            assertEquals(1L, pending.onLink)
            advanceTimeBy(millis)
            pending.reply.complete(MacMessage.Pong)
            runCurrent()
            assertEquals(millis * MILLI, meter.stats.value.pingLast)
            advanceTimeBy(2_000)
        }
        assertEquals(20L * MILLI, meter.stats.value.pingMedian)
    }

    @Test
    fun aPingThatFailsOrFindsNoStreamRecordsNothingAndTheLoopGoesOn() = runTest {
        val meter = meter()
        var calls = 0
        backgroundScope.launch {
            meter.measure(interval = 2.seconds) {
                calls++
                when (calls) {
                    1 -> throw CompanionRequestException("no reply")
                    2 -> pingActiveLink(ScriptedStreamLink(), active = null)
                    else -> {
                        delay(5)
                        true
                    }
                }
            }
        }
        runCurrent()
        advanceTimeBy(2_001)
        assertNull(meter.stats.value.pingLast)
        advanceTimeBy(2_010)
        assertEquals(3, calls)
        assertEquals(5L * MILLI, meter.stats.value.pingLast)
    }

    @Test
    fun keyToFrameIsFromEachKeyToTheFirstFrameThatArrivesAfterIt() = runTest {
        val meter = meter()
        measure(meter)
        runCurrent()
        meter.keySent()
        advanceTimeBy(20)
        meter.keySent()
        advanceTimeBy(60)
        // The frame arrived from the relay 5 ms ago (it waited in the phone's queue).
        meter.mediaArrived(nanos() - 5 * MILLI)
        assertEquals(55L * MILLI, meter.stats.value.keyToFrameLast)
        assertEquals(55L * MILLI, meter.stats.value.keyToFrameMedian)
        // Both keys were matched: the next frame matches none.
        advanceTimeBy(30)
        meter.mediaArrived(nanos())
        assertEquals(55L * MILLI, meter.stats.value.keyToFrameLast)
    }

    @Test
    fun aKeySentAfterTheFrameArrivedWaitsForTheNextFrame() = runTest {
        val meter = meter()
        measure(meter)
        runCurrent()
        val arrived = nanos()
        advanceTimeBy(10)
        meter.keySent()
        meter.mediaArrived(arrived)
        assertNull(meter.stats.value.keyToFrameLast)
        advanceTimeBy(40)
        meter.mediaArrived(nanos())
        assertEquals(40L * MILLI, meter.stats.value.keyToFrameLast)
    }

    @Test
    fun aKeyNoFrameFollowsWithinTheWindowIsNotMatched() = runTest {
        val meter = meter()
        measure(meter)
        runCurrent()
        meter.keySent()
        advanceTimeBy(2_500)
        meter.mediaArrived(nanos())
        assertNull(meter.stats.value.keyToFrameLast)
    }

    @Test
    fun frameIntervalsAndFramesPerSecondFollowArrivals() = runTest {
        val meter = meter()
        measure(meter)
        runCurrent()
        repeat(10) {
            meter.mediaArrived(nanos())
            advanceTimeBy(33)
        }
        val stats = meter.stats.value
        assertEquals(33L * MILLI, stats.frameIntervalLast)
        assertEquals(33L * MILLI, stats.frameIntervalMedian)
        assertEquals(10, stats.framesPerSecond)
        // Ten more over the next second at 100 ms: only those still count.
        repeat(10) {
            advanceTimeBy(100)
            meter.mediaArrived(nanos())
        }
        assertEquals(10, meter.stats.value.framesPerSecond)
        assertEquals(100L * MILLI, meter.stats.value.frameIntervalLast)
    }

    @Test
    fun framesPerSecondFallsToZeroAtTheNextPingOnceFramesStop() = runTest {
        val meter = meter()
        // The pings go unanswered (no stream): each attempt still updates the numbers.
        backgroundScope.launch { meter.measure(interval = 2.seconds) { false } }
        runCurrent()
        repeat(5) {
            meter.mediaArrived(nanos())
            advanceTimeBy(10)
        }
        assertEquals(5, meter.stats.value.framesPerSecond)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, meter.stats.value.framesPerSecond)
        assertNull(meter.stats.value.pingLast)
    }

    @Test
    fun receiveToSubmitIsFromArrivalToTheDecoderTakingTheFrame() = runTest {
        val meter = meter()
        measure(meter)
        runCurrent()
        val arrived = nanos()
        advanceTimeBy(3)
        meter.mediaSubmitted(arrived)
        assertEquals(3L * MILLI, meter.stats.value.receiveToSubmitLast)
        meter.mediaSubmitted(NO_RECEIVE_TIME)
        assertEquals(3L * MILLI, meter.stats.value.receiveToSubmitLast)
    }

    @Test
    fun mediansCoverTheWindowOnlyAndMeasuringAgainStartsFresh() = runTest {
        val meter = meter(window = 3)
        val job = measure(meter)
        runCurrent()
        for (millis in listOf(100L, 1L, 2L, 3L)) {
            val arrived = nanos()
            advanceTimeBy(millis)
            meter.mediaSubmitted(arrived)
        }
        // The window holds 1, 2, 3: the 100 has aged out.
        assertEquals(2L * MILLI, meter.stats.value.receiveToSubmitMedian)
        job.cancel()
        runCurrent()
        // Kept after measuring stops, cleared when it starts again.
        assertEquals(3L * MILLI, meter.stats.value.receiveToSubmitLast)
        measure(meter)
        runCurrent()
        assertEquals(LatencyStats(), meter.stats.value)
    }

    @Test
    fun theOverlayTextIsNumbersOnly() {
        val text = latencyStatsText(
            LatencyStats(
                pingLast = 12_340_000,
                pingMedian = 11_000_000,
                keyToFrameLast = 80_000_000,
                framesPerSecond = 30,
                frameIntervalLast = 33_300_000,
                frameIntervalMedian = 33_000_000,
                receiveToSubmitLast = 400_000,
                receiveToSubmitMedian = 500_000,
            ),
        )
        assertEquals(
            "ping 12.3 (med 11.0) ms\nkey→frame 80.0 (med —) ms\nrx 30 fps · gap 33.3 (med 33.0) ms\nrx→submit 0.4 (med 0.5) ms",
            text,
        )
        assertEquals(
            "ping — (med —) ms\nkey→frame — (med —) ms\nrx — fps · gap — (med —) ms\nrx→submit — (med —) ms",
            latencyStatsText(LatencyStats()),
        )
    }

    private companion object {
        const val MILLI = 1_000_000L
    }
}
