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
        assertEquals(1, requests.get())
        thread.drain()
        assertEquals(1, factory.made.size)
        assertEquals(VideoReadiness.Ready, video.readiness())
    }

    @Test
    fun incompleteParameterSetsAreNeverConfigured() {
        val video = output()
        video.attachSurface(fakeSurface())
        requests.set(0)
        assertEquals(VideoReadiness.WaitingForConfig, video.configure(streamConfig(extradata = incompleteExtradata())))
        thread.drain()
        assertEquals(0, factory.made.size)
        assertEquals("the controller asks for the keyframe", 0, requests.get())
        assertEquals(VideoReadiness.WaitingForConfig, video.readiness())
        assertFalse(video.submit(frame(1, keyframe = true)))
        assertEquals(VideoReadiness.WaitingForConfig, video.configure(streamConfig(extradata = byteArrayOf(0, 1, 0))))
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
        factory = FakeCodecFactory(thread) { bufferCapacity = 1 shl 20 }
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
        catalog = FixedCatalog(listOf(decoder("hw.hevc"), decoder("sw.hevc", hardware = false)))
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

        // From a callback. Two failures of the hardware decoder with no output: the next is software.
        assertTrue(video.submit(frame(20, keyframe = true)))
        thread.drain()
        assertEquals(3, factory.made.size)
        assertEquals(listOf("hw.hevc", "hw.hevc", "sw.hevc"), factory.requested.map { it.name })
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
        thread.drain()
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
        thread.drain()
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
        // attachSurface never waits; detach and close each wait once and give up.
        assertEquals(2, logs.count { it.contains("did not finish") })
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

    // ---- Decoder fallback ----

    private fun twoDecoders() = FixedCatalog(listOf(decoder("sw.hevc", hardware = false), decoder("hw.hevc")))

    @Test
    fun fallsBackToSoftwareAfterRepeatedHardwareFailures() {
        catalog = twoDecoders()
        factory = FakeCodecFactory(thread) { throwOnConfigure = name == "hw.hevc" }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        assertEquals(VideoReadiness.Ready, video.readiness())
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        // Two hardware failures, then software.
        assertTrue(video.submit(frame(2, keyframe = true)))
        thread.drain()
        assertEquals(listOf("hw.hevc", "hw.hevc", "sw.hevc"), factory.requested.map { it.name })
        assertEquals(listOf(us(2)), factory.last.queued)
        assertEquals("sw.hevc", video.stats.value.decoderName)
        assertEquals(VideoReadiness.Ready, video.readiness())
    }

    @Test
    fun insufficientResourceFallsBackAtOnce() {
        catalog = twoDecoders()
        factory = FakeCodecFactory(thread) {
            if (name == "hw.hevc") configureError = InsufficientResourceException(IllegalStateException("busy"))
        }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        assertEquals(listOf("hw.hevc", "sw.hevc"), factory.requested.map { it.name })
        assertEquals(listOf(us(1)), factory.last.queued)
    }

    @Test
    fun outputClearsTheFailureCount() {
        catalog = twoDecoders()
        factory = FakeCodecFactory(thread) { index -> throwOnConfigure = index == 0 }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        // One failure; the rebuilt hardware decoder outputs a frame, which clears the count.
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        factory.last.output(us(1))
        thread.drain()
        // One more failure: still the hardware decoder.
        factory.last.error()
        thread.drain()
        assertTrue(video.submit(frame(2, keyframe = true)))
        thread.drain()
        assertEquals(listOf("hw.hevc", "hw.hevc", "hw.hevc"), factory.requested.map { it.name })
        assertFalse(factory.last.released)
    }

    @Test
    fun everyCandidateFailingIsNoDecoder() {
        factory = FakeCodecFactory(thread) { throwOnConfigure = true }
        val video = output()
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        thread.drain()
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        assertEquals(2, factory.made.size)
        assertEquals(VideoReadiness.NoDecoder, video.readiness())
        assertFalse(video.submit(frame(2, keyframe = true)))
        assertEquals("no request once nothing is left", 2, requests.get())
        // A new config starts over.
        assertEquals(VideoReadiness.Ready, video.configure(streamConfig(extradata = completeExtradata(tag = 3))))
    }

    @Test
    fun unparseableAccessUnitIsDroppedWithoutFailingTheDecoder() {
        val video = ready()
        clock.set(4_000)
        // A NAL length running past the end of the payload.
        val bad = dev.bugobliterator.itermbuddy.core.stream.MediaFrame(
            version = 2, flags = 1, streamID = 1, sequence = 1, ptsMilliseconds = 5, generationId = 1, liveTop = 0,
            payload = byteArrayOf(0, 0, 0, 9, 1),
        )
        assertTrue(video.submit(bad))
        thread.drain()
        assertFalse(factory.last.released)
        assertEquals(1, requests.get())
        assertEquals(4_000L, video.outputOverdueSinceMillis())
        assertTrue(video.submit(frame(6, keyframe = true)))
        thread.drain()
        assertEquals(listOf(us(6)), factory.last.queued)
        assertEquals(1, factory.made.size)
    }

    // ---- Input size ----

    @Test
    fun keyframeOverTheInputSizeRebuildsOnceWithSixteenMebibytes() {
        val video = ready()
        assertTrue(video.submit(frame(1, keyframe = true)))
        // A P-frame over 4 MiB is refused and does not rebuild; what follows waits for a keyframe.
        assertFalse(video.submit(frame(2, keyframe = false, nalBytes = 5 shl 20)))
        assertFalse(video.submit(frame(3, keyframe = false)))
        thread.drain()
        assertEquals(1, factory.made.size)
        // A keyframe over 4 MiB rebuilds with 16 MiB and is decoded.
        assertTrue(video.submit(frame(4, keyframe = true, nalBytes = 5 shl 20)))
        thread.drain()
        assertEquals(2, factory.made.size)
        assertTrue(factory.made[0].released)
        assertEquals(16 shl 20, factory.last.configuredFormat?.maxInputSize)
        assertEquals(listOf(us(4)), factory.last.queued)
        assertEquals(0, requests.get())
        // Once only: a keyframe over 16 MiB is refused, no rebuild.
        assertFalse(video.submit(frame(5, keyframe = true, nalBytes = 16 shl 20)))
        assertTrue(video.submit(frame(6, keyframe = true, nalBytes = 6 shl 20)))
        thread.drain()
        assertEquals(2, factory.made.size)
    }

    // ---- Surfaces and the decoder list ----

    @Test
    fun attachingTheSameSurfaceAgainIsANoOp() {
        val video = output()
        val surface = fakeSurface()
        video.configure(streamConfig())
        video.attachSurface(surface)
        thread.drain()
        assertTrue(video.submit(frame(1, keyframe = true)))
        video.attachSurface(surface)
        thread.drain()
        assertEquals(1, factory.made.size)
        assertEquals(1, requests.get())
        assertEquals(listOf(us(1)), factory.last.queued)
    }

    @Test
    fun attachDoesNotWaitForTheDecoderThread() {
        val wedged = WedgedDecoderThread()
        val video = HevcVideoOutput(
            catalog = catalog, codecFactory = factory::create, nowMillis = clock::get, decoderThread = wedged,
            surfaceCallTimeoutMillis = 60_000, log = { logs += it },
        )
        video.setKeyframeRequester { requests.incrementAndGet() }
        video.configure(streamConfig())
        video.attachSurface(fakeSurface())
        assertEquals(1, requests.get())
        assertEquals(VideoReadiness.Ready, video.readiness())
    }

    @Test
    fun decoderListIsReadOnceOnTheDecoderThread() {
        val counting = FixedCatalog(listOf(decoder("hw.hevc")))
        catalog = counting
        val video = output()
        thread.drain()
        video.configure(streamConfig())
        video.configure(streamConfig(width = 1440))
        video.configure(streamConfig(extradata = completeExtradata(tag = 4)))
        assertEquals(1, counting.calls.get())
        assertEquals(listOf("TestDecoderThread"), counting.callThreads)
    }

    @Test
    fun shapeNoDecoderSupportsIsNoDecoderNotTooLarge() {
        catalog = FixedCatalog(listOf(decoder("hw.hevc", supports = { w, h -> w <= 4096 && h <= 2304 && w % 2 == 0 && h % 2 == 0 })))
        val video = output()
        video.attachSurface(fakeSurface())
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig(width = 1281, height = 800)))
        assertEquals(VideoReadiness.NoDecoder, video.configure(streamConfig(width = 0, height = 0)))
        assertEquals(VideoReadiness.TooLarge(5120, 2880, 4096, 2304), video.configure(streamConfig(width = 5120, height = 2880)))
    }

    @Test
    fun incompleteThenCompleteConfigBuildsTheDecoder() {
        val video = output()
        video.attachSurface(fakeSurface())
        assertEquals(VideoReadiness.WaitingForConfig, video.configure(streamConfig(extradata = incompleteExtradata())))
        thread.drain()
        assertEquals(0, factory.made.size)
        assertEquals(VideoReadiness.Ready, video.configure(streamConfig()))
        thread.drain()
        assertEquals(1, factory.made.size)
        assertTrue(video.submit(frame(1, keyframe = true)))
        thread.drain()
        assertEquals(listOf(us(1)), factory.last.queued)
    }

    @Test
    fun framesOutSinceResetCountsOnlyTheCurrentPicture() {
        val video = ready()
        assertEquals(0L, video.stats.value.framesOutSinceReset)
        assertTrue(video.submit(frame(1, keyframe = true)))
        assertTrue(video.submit(frame(2, keyframe = false)))
        thread.drain()
        val first = factory.last
        first.output(us(1))
        thread.drain()
        assertEquals(1L, video.stats.value.framesOutSinceReset)
        // A reset zeroes it at once, and a late output of the released codec does not count.
        video.reset()
        assertEquals(0L, video.stats.value.framesOutSinceReset)
        first.output(us(2))
        thread.drain()
        assertEquals(0L, video.stats.value.framesOutSinceReset)
        // A new config that rebuilds the decoder starts a new picture too.
        video.configure(streamConfig())
        thread.drain()
        assertTrue(video.submit(frame(3, keyframe = true)))
        thread.drain()
        factory.last.output(us(3))
        thread.drain()
        assertEquals(1L, video.stats.value.framesOutSinceReset)
        video.configure(streamConfig(width = 1440, height = 900))
        assertEquals(0L, video.stats.value.framesOutSinceReset)
        // The same config again keeps the decoder and the count.
        assertTrue(video.submit(frame(4, keyframe = true)))
        thread.drain()
        factory.last.output(us(4))
        thread.drain()
        video.configure(streamConfig(width = 1440, height = 900))
        assertEquals(1L, video.stats.value.framesOutSinceReset)
        // Losing and regaining the surface keeps it: the new surface is blank, never stale.
        video.detachSurface()
        video.attachSurface(fakeSurface())
        thread.drain()
        assertEquals(1L, video.stats.value.framesOutSinceReset)
        assertEquals("framesOut is cumulative", 3L, video.stats.value.framesOut)
    }

    @Test
    fun lastDecoderNameOutlivesTheDecoder() {
        val video = ready()
        assertEquals("hw.hevc", video.stats.value.decoderName)
        assertEquals("hw.hevc", video.stats.value.lastDecoderName)
        video.reset()
        thread.drain()
        assertNull(video.stats.value.decoderName)
        assertEquals("hw.hevc", video.stats.value.lastDecoderName)
    }
}
