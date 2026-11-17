package dev.bugobliterator.itermbuddy.video

import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.parameterSetsToCsd
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HevcVideoOutput] on the JVM: a fake [CodecHandle] whose callbacks go through a real single
 * decoder thread, and a fake clock. Each step drains the decoder thread before asserting.
 */
class HevcVideoOutputTest {
    private val clock = AtomicLong(1_000)
    private val thread = TestDecoderThread()
    private val requests = AtomicInteger()
    private val logs = CopyOnWriteArrayList<String>()
    private var catalog: HevcDecoderCatalog = FixedCatalog(listOf(decoder("hw.hevc", lowLatency = true)))
    private var factory = FakeCodecFactory(thread)

    private fun output(maxPending: Long = DEFAULT_MAX_PENDING_INPUT_BYTES, decoderThread: DecoderThread = thread) =
        HevcVideoOutput(
            catalog = catalog,
            codecFactory = factory::create,
            maxPendingInputBytes = maxPending,
            nowMillis = clock::get,
            decoderThread = decoderThread,
            surfaceCallTimeoutMillis = 5_000,
            log = { logs += it },
        ).also { it.setKeyframeRequester { requests.incrementAndGet() } }

    @After
    fun tearDown() {
        thread.quit()
    }

    /** Configured, surface attached, keyframe-request count reset. */
    private fun ready(maxPending: Long = DEFAULT_MAX_PENDING_INPUT_BYTES): HevcVideoOutput {
        val video = output(maxPending)
        assertEquals(VideoReadiness.WaitingForSurface, video.configure(streamConfig()))
        video.attachSurface(fakeSurface())
        thread.drain()
        assertEquals(VideoReadiness.Ready, video.readiness())
        requests.set(0)
        return video
    }

    private fun us(ms: Long) = ms * 1000

    @Test
    fun configuresWithLowLatencyCsdAndMaxInputSize() {
        val video = output()
        val surface = fakeSurface()
        video.configure(streamConfig(width = 3024, height = 1964))
        video.attachSurface(surface)
        thread.drain()
        val codec = factory.last
        val format = checkNotNull(codec.configuredFormat)
        assertTrue(codec.started)
        assertSame(surface, codec.configuredSurface)
        assertEquals(3024, format.width)
        assertEquals(1964, format.height)
        assertTrue(format.lowLatency)
        assertEquals(3024 * 1964, format.maxInputSize)
        assertArrayEquals(parameterSetsToCsd(completeExtradata()), format.csd0)
        assertEquals("hw.hevc", video.stats.value.decoderName)
    }

    @Test
    fun smallStreamGetsTheFourMebibyteInputFloorAndNoLowLatencyWhenUnsupported() {
        catalog = FixedCatalog(listOf(decoder("plain", lowLatency = false)))
        val video = output()
        video.configure(streamConfig(width = 640, height = 400))
        video.attachSurface(fakeSurface())
        thread.drain()
        val format = checkNotNull(factory.last.configuredFormat)
        assertFalse(format.lowLatency)
        assertEquals(4 shl 20, format.maxInputSize)
    }

    @Test
    fun attachingASurfaceBuildsADecoderAndRequestsAKeyframe() {
        val video = output()
        video.configure(streamConfig())
        assertEquals(0, factory.made.size)
        video.attachSurface(fakeSurface())
        assertEquals(1, factory.made.size)
        assertEquals(1, requests.get())
        assertEquals(VideoReadiness.Ready, video.readiness())
    }

    @Test
    fun incompleteParameterSetsAreNeverConfigured() {
        val video = output()
        video.attachSurface(fakeSurface())
        requests.set(0)
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig(extradata = incompleteExtradata())))
        thread.drain()
        assertEquals(0, factory.made.size)
        assertEquals(1, requests.get())
        assertFalse(video.submit(frame(1, keyframe = true)))
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig(extradata = byteArrayOf(0, 1, 0))))
        // A complete config afterwards builds one.
        assertEquals(VideoReadiness.Ready, video.configure(streamConfig()))
        thread.drain()
        assertEquals(1, factory.made.size)
    }

    @Test
    fun noDecoderAndTooLargeReadiness() {
        catalog = FixedCatalog(emptyList())
        val none = output()
        none.attachSurface(fakeSurface())
        assertEquals(VideoReadiness.NoDecoder, none.configure(streamConfig()))
        assertNull(none.maxSize())
        assertFalse(none.submit(frame(1, keyframe = true)))

        catalog = FixedCatalog(listOf(decoder("hw", maxWidth = 4096, maxHeight = 2304)))
        val large = output()
        large.attachSurface(fakeSurface())
        assertEquals(VideoReadiness.TooLarge(5120, 2880, 4096, 2304), large.configure(streamConfig(width = 5120, height = 2880)))
        assertEquals(4096 to 2304, large.maxSize())
        assertFalse(large.submit(frame(1, keyframe = true)))
        thread.drain()
        assertEquals(0, factory.made.size)
    }

    @Test
    fun throwingCatalogReadsAsNoDecoder() {
        catalog = object : HevcDecoderCatalog {
            override fun decoders(): List<DecoderInfo> = throw IllegalStateException("MediaCodecList failed")
        }
        val video = output()
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig()))
    }

    @Test
    fun sameConfigKeepsTheDecoder() {
        val video = ready()
        val codec = factory.last
        assertEquals(VideoReadiness.Ready, video.configure(streamConfig(generation = 2)))
        thread.drain()
        assertEquals(1, factory.made.size)
        assertFalse(codec.released)
        // Changed extradata or size rebuilds, releasing the old codec first.
        video.configure(streamConfig(extradata = completeExtradata(tag = 7)))
        thread.drain()
        assertEquals(2, factory.made.size)
        assertTrue(codec.released)
        video.configure(streamConfig(extradata = completeExtradata(tag = 7), width = 1440))
        thread.drain()
        assertEquals(3, factory.made.size)
        assertTrue(factory.made[1].released)
    }

    @Test
    fun keyframesWithoutConfigChangeDoNotRebuild() {
        val video = ready()
        for (i in 0L until 10) {
            assertTrue(video.submit(frame(i * 33, keyframe = i % 3 == 0L)))
            thread.drain()
        }
        assertEquals(1, factory.made.size)
        assertFalse(factory.last.released)
        assertEquals((0L until 10).map { us(it * 33) }, factory.last.queued)
    }

    @Test
    fun inputIsWrittenAsAnnexBWithPtsInMicroseconds() {
        val video = ready()
        val keyframe = frame(523_118_042, keyframe = true, nalBytes = 5)
        assertTrue(video.submit(keyframe))
        thread.drain()
        assertEquals(listOf(us(523_118_042)), factory.last.queued)
        assertArrayEquals(keyframe.annexBAccessUnit(), factory.last.queuedBytes.single())
    }

    @Test
    fun submitWithoutDecoderOrBeforeKeyframeIsRefused() {
        val video = output()
        assertFalse("no config, no surface", video.submit(frame(0, keyframe = true)))
        video.configure(streamConfig())
        assertFalse("no surface", video.submit(frame(0, keyframe = true)))
        video.attachSurface(fakeSurface())
        assertFalse("before the first keyframe", video.submit(frame(1, keyframe = false)))
        assertTrue(video.submit(frame(2, keyframe = true)))
        assertTrue(video.submit(frame(3, keyframe = false)))
    }

    @Test
    fun outputIsRenderedAtOnceAndClearsTheClock() {
        val video = ready()
        clock.set(2_000)
        assertTrue(video.submit(frame(10, keyframe = true)))
        thread.drain()
        assertEquals(2_000L, video.outputOverdueSinceMillis())
        clock.set(2_030)
        factory.last.output(us(10))
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())
        assertEquals(listOf(0 to true), factory.last.rendered)
        val stats = video.stats.value
        assertEquals(1L, stats.framesIn)
        assertEquals(1L, stats.framesOut)
        assertEquals(30L, stats.lastLatencyMillis)
    }

    @Test
    fun partialOutputKeepsOldestPendingTime() {
        val video = ready()
        clock.set(5_000)
        assertTrue(video.submit(frame(100, keyframe = true)))
        clock.set(5_033)
        assertTrue(video.submit(frame(133, keyframe = false)))
        thread.drain()
        assertEquals(5_000L, video.outputOverdueSinceMillis())
        factory.last.output(us(100))
        thread.drain()
        assertEquals(5_033L, video.outputOverdueSinceMillis())
        factory.last.output(us(133))
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())
    }

    @Test
    fun outputWithUnknownPresentationTimeStillPopsOne() {
        val video = ready()
        clock.set(5_000)
        video.submit(frame(100, keyframe = true))
        clock.set(5_033)
        video.submit(frame(133, keyframe = false))
        thread.drain()
        factory.last.output(0)
        thread.drain()
        assertEquals(5_033L, video.outputOverdueSinceMillis())
    }

    @Test
    fun replacementKeyframesKeepTheClock() {
        factory = FakeCodecFactory(thread) { recycleInputs = false }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        // Fill every input buffer; nothing is ever freed, so later frames wait in the queue.
        repeat(4) { assertTrue(video.submit(frame(it.toLong(), keyframe = it == 0))) }
        thread.drain()
        factory.last.queued.forEach { factory.last.output(it) }
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())

        val first = 10_000L
        for (step in 0..6) {
            clock.set(first + step * 500L)
            assertTrue(video.submit(frame(1_000L + step, keyframe = true)))
            thread.drain()
            assertEquals(first, video.outputOverdueSinceMillis())
            assertEquals(1, video.pendingInputCount())
        }
        assertEquals(1, factory.made.size)
    }

    @Test
    fun overBudgetDropKeepsTheClock() {
        factory = FakeCodecFactory(thread) { recycleInputs = false }
        val video = output(maxPending = 1_000)
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        repeat(4) { assertTrue(video.submit(frame(it.toLong(), keyframe = it == 0, nalBytes = 8))) }
        thread.drain()
        factory.last.queued.forEach { factory.last.output(it) }
        thread.drain()
        clock.set(20_000)
        assertTrue(video.submit(frame(10, keyframe = false, nalBytes = 400)))
        clock.set(20_100)
        assertTrue(video.submit(frame(11, keyframe = false, nalBytes = 400)))
        clock.set(20_200)
        assertFalse(video.submit(frame(12, keyframe = false, nalBytes = 400)))
        assertEquals(0, video.pendingInputCount())
        assertEquals(20_000L, video.outputOverdueSinceMillis())
        assertFalse("waits for a keyframe", video.submit(frame(13, keyframe = false)))
        clock.set(20_300)
        assertTrue(video.submit(frame(14, keyframe = true)))
        assertEquals(20_000L, video.outputOverdueSinceMillis())
    }

    @Test
    fun oversizedFrameIsRefusedWithoutAClockEntry() {
        val video = ready(maxPending = 1_000)
        assertFalse(video.submit(frame(1, keyframe = true, nalBytes = 2_000)))
        assertNull(video.outputOverdueSinceMillis())
        assertFalse(video.submit(frame(2, keyframe = false)))
        assertTrue(video.submit(frame(3, keyframe = true)))
    }

    @Test
    fun frameLargerThanTheInputBufferIsACodecFailure() {
        factory = FakeCodecFactory(thread)
        val video = ready()
        clock.set(3_000)
        // The fake's input buffers hold 1 MiB; the queue's budget is 16 MiB.
        assertTrue(video.submit(frame(1, keyframe = true, nalBytes = 2 shl 20)))
        thread.drain()
        val codec = factory.last
        assertTrue(codec.released)
        assertTrue(codec.queued.isEmpty())
        assertEquals(1, requests.get())
        assertEquals(3_000L, video.outputOverdueSinceMillis())
        assertEquals(VideoReadiness.Ready, video.readiness())
        // The next keyframe rebuilds.
        assertFalse(video.submit(frame(2, keyframe = false)))
        assertTrue(video.submit(frame(3, keyframe = true)))
        thread.drain()
        assertEquals(2, factory.made.size)
        assertEquals(listOf(us(3)), factory.last.queued)
    }

    @Test
    fun codecExceptionsReleaseAndRequestKeyframe() {
        // From configure.
        factory = FakeCodecFactory(thread) { index ->
            throwOnConfigure = index == 0
            throwOnQueueInput = index == 1
        }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        assertTrue(factory.made[0].released)
        assertEquals("attach + failure", 2, requests.get())
        assertFalse("no rebuild until a keyframe", video.submit(frame(0, keyframe = false)))
        assertEquals(1, factory.made.size)

        // From queueInput.
        assertTrue(video.submit(frame(10, keyframe = true)))
        thread.drain()
        assertEquals(2, factory.made.size)
        assertTrue(factory.made[1].released)
        assertEquals(0, video.pendingInputCount())
        assertEquals(3, requests.get())

        // From a callback.
        assertTrue(video.submit(frame(20, keyframe = true)))
        thread.drain()
        assertEquals(3, factory.made.size)
        assertTrue(video.submit(frame(21, keyframe = false)))
        factory.made[2].error()
        thread.drain()
        assertTrue(factory.made[2].released)
        assertEquals(0, video.pendingInputCount())
        assertEquals(4, requests.get())

        // A keyframe afterwards decodes.
        assertTrue(video.submit(frame(30, keyframe = true)))
        thread.drain()
        assertEquals(listOf(us(30)), factory.made[3].queued)
        factory.made[3].output(us(30))
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())
        assertFalse(factory.made[3].released)
    }

    @Test
    fun codecCreationFailureRequestsKeyframe() {
        factory.throwOnCreate = true
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        assertEquals(2, requests.get())
        factory.throwOnCreate = false
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        assertEquals(listOf(us(1)), factory.last.queued)
    }

    @Test
    fun releasedCodecKeepsTheClock() {
        val video = ready()
        clock.set(7_000)
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        clock.set(7_500)
        factory.last.error()
        thread.drain()
        assertTrue(factory.made[0].released)
        assertEquals(7_000L, video.outputOverdueSinceMillis())
        clock.set(8_000)
        assertTrue(video.submit(frame(2, keyframe = true)))
        thread.drain()
        assertEquals(7_000L, video.outputOverdueSinceMillis())
        factory.last.output(us(2))
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())
    }

    @Test
    fun staleCallbacksFromAReleasedCodecAreIgnored() {
        val video = ready()
        val old = factory.last
        video.configure(streamConfig(extradata = completeExtradata(tag = 9)))
        thread.drain()
        clock.set(9_000)
        assertTrue(video.submit(frame(5, keyframe = true)))
        thread.drain()
        old.output(us(5))
        old.error()
        thread.drain()
        assertEquals(9_000L, video.outputOverdueSinceMillis())
        assertEquals(0, requests.get())
        assertFalse(factory.last.released)
    }

    @Test
    fun surfaceReattachRecoversWithKeyframe() {
        val video = ready()
        val first = factory.last
        assertTrue(video.submit(frame(0, keyframe = true)))
        thread.drain()
        video.detachSurface()
        assertTrue("detach releases before returning", first.released)
        assertEquals(VideoReadiness.WaitingForSurface, video.readiness())
        assertFalse(video.submit(frame(1, keyframe = true)))
        requests.set(0)
        video.attachSurface(fakeSurface())
        assertEquals(1, requests.get())
        assertEquals(VideoReadiness.Ready, video.readiness())
        assertNotSame(first, factory.last)
        assertFalse(video.submit(frame(2, keyframe = false)))
        assertTrue(video.submit(frame(10, keyframe = true)))
        thread.drain()
        assertEquals(listOf(us(10)), factory.last.queued)
        factory.last.output(us(10))
        thread.drain()
        assertNull(video.outputOverdueSinceMillis())
    }

    @Test
    fun resetForgetsTheDecoderAndTheClock() {
        val video = ready()
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        video.reset()
        thread.drain()
        assertTrue(factory.last.released)
        assertNull(video.outputOverdueSinceMillis())
        assertNull(video.maxSize())
        assertFalse(video.submit(frame(2, keyframe = true)))
        // The surface is still attached: a new config builds a decoder again.
        assertEquals(VideoReadiness.Ready, video.configure(streamConfig()))
        thread.drain()
        assertEquals(2, factory.made.size)
    }

    @Test
    fun closeReleasesAndEndsTheThreadAndLaterCallsAreSafe() {
        val video = ready()
        val codec = factory.last
        video.close()
        assertTrue(codec.released)
        assertTrue(thread.isShutdown)
        video.close()
        assertEquals(1, thread.quits.get())
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig()))
        assertFalse(video.submit(frame(1, keyframe = true)))
        video.attachSurface(fakeSurface())
        video.detachSurface()
        video.reset()
        assertEquals(VideoReadiness.NoDecoder, video.readiness())
    }

    @Test
    fun wedgedDecoderThreadCannotHangSurfaceCalls() {
        val wedged = WedgedDecoderThread()
        val video = HevcVideoOutput(
            catalog = catalog, codecFactory = factory::create, nowMillis = clock::get, decoderThread = wedged,
            surfaceCallTimeoutMillis = 20, log = { logs += it },
        )
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        video.detachSurface()
        video.close()
        assertEquals(3, logs.count { it.contains("did not finish") })
    }

    @Test
    fun throwingStopAndReleaseDoNotEscape() {
        factory = FakeCodecFactory(thread) { throwOnStopAndRelease = true }
        val video = ready()
        val codec = factory.last
        video.detachSurface()
        assertTrue(codec.released)
        assertEquals(VideoReadiness.WaitingForSurface, video.readiness())
        video.close()
        assertTrue(thread.isShutdown)
    }
}
