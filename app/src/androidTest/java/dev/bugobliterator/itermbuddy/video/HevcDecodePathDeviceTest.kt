package dev.bugobliterator.itermbuddy.video

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bugobliterator.itermbuddy.core.stream.MediaFrame
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.parseMediaFrame
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decode path on a real device: the committed clip (`fixtures/media/hevc/terminal-clip.json`,
 * packaged as an androidTest asset) through [HevcVideoOutput] and the device's HEVC decoder into
 * an [ImageReader] surface. Does not touch the pairing store.
 *
 * The clip: 15 frames of 1280x800, IDRs at frames 0 and 10, pts 33 ms apart. An image's
 * timestamp is its frame's presentation time in nanoseconds (`releaseOutputBuffer(index, true)`).
 */
@RunWith(AndroidJUnit4::class)
class HevcDecodePathDeviceTest {
    private class Clip(val config: StreamConfig, val frames: List<MediaFrame>)

    private val clip = loadClip()
    private val readerThread = HandlerThread("HevcDecodePathDeviceTest.reader").apply { start() }
    private lateinit var reader: ImageReader

    /** Image timestamp (ns) to the uptime it arrived at. */
    private val arrivals = ConcurrentHashMap<Long, Long>()
    private val imageCount = AtomicInteger()
    private val requests = AtomicInteger()
    private val outputs = CopyOnWriteArrayList<HevcVideoOutput>()

    @Before
    fun setUp() {
        logDecoderCatalogOnce()
        reader = ImageReader.newInstance(clip.config.pixelWidth, clip.config.pixelHeight, ImageFormat.PRIVATE, 4)
        reader.setOnImageAvailableListener(
            { r ->
                r.acquireNextImage()?.use { image ->
                    arrivals[image.timestamp] = SystemClock.uptimeMillis()
                    imageCount.incrementAndGet()
                }
            },
            Handler(readerThread.looper),
        )
    }

    @After
    fun tearDown() {
        outputs.forEach { it.close() }
        reader.close()
        readerThread.quitSafely()
    }

    @Test
    fun everyClipFrameIsOutput() {
        val video = start()
        val began = SystemClock.uptimeMillis()
        // Paced as the Mac sends them: submitted in one burst, frame 10's IDR would replace frames
        // still pending in the queue (a keyframe replaces everything pending).
        clip.frames.forEachIndexed { i, frame ->
            if (i > 0) SystemClock.sleep(FRAME_GAP_MILLIS)
            assertTrue(video.submit(frame))
        }
        val deadline = began + (clip.frames.size - 1) * FRAME_GAP_MILLIS + 2_000
        assertTrue("15 images within 2 s, got ${imageCount.get()}", awaitCount(clip.frames.size, deadline))
        clip.frames.forEach { assertNotNull("image for pts ${it.ptsMilliseconds}", arrivals[timestampNs(it)]) }
    }

    /**
     * Two bursts, each followed by silence: frames 0-4, then 10-14 (from frame 10's IDR). A decoder
     * that holds the last frame until more input never outputs it, so each burst's last frame must
     * arrive with no more input (within 2 s). The 250 ms bound applies to the faster of the two:
     * one scheduling spike on a shared CI emulator's software decoder (279 ms once) is not a held
     * frame, while a decoder that is slow every time still fails. With the low-latency keys on (the
     * default), whichever format the decoder accepted.
     */
    @Test
    fun lastFrameOfBurstIsOutputWithoutMoreInput() {
        val video = start()
        val latencies = listOf(0..4, 10..14).map { burst ->
            var lastSubmitAt = 0L
            for (i in burst) {
                if (i > burst.first) SystemClock.sleep(FRAME_GAP_MILLIS)
                lastSubmitAt = SystemClock.uptimeMillis()
                assertTrue(video.submit(clip.frames[i]))
            }
            val arrived = awaitImage(clip.frames[burst.last], lastSubmitAt + 2_000)
            assertNotNull("frame ${burst.last} never output without more input", arrived)
            arrived!! - lastSubmitAt
        }
        val stats = video.stats.value
        Log.i(
            TAG,
            "last frame of burst: input to image ${latencies.joinToString(" and ")} ms " +
                "(decoder ${stats.decoderName}, low latency ${stats.lowLatency?.let(::lowLatencyLabel)}, " +
                "accept to output median ${stats.medianLatencyMillis} ms)",
        )
        assertTrue("decoder started with or without the keys: ${stats.lowLatency}", stats.lowLatency != LowLatencyState.OFF)
        assertTrue("the last frame took ${latencies.joinToString(" and ")} ms", latencies.min() <= 250)
    }

    /**
     * A decoder whose start throws for the low-latency format (a test key makes that format differ
     * from the minimal one on any decoder): the same codec is reset, started with the minimal
     * format, and decodes.
     */
    @Test
    fun startFailureWithTheLowLatencyKeysFallsBackAndDecodes() {
        val fallbacks = LowLatencyFallbacks()
        val video = start(
            codecFactory = { info -> LowLatencyFaultHandle(mediaCodecHandle(info), failStart = true) },
            fallbacks = fallbacks,
            extraRequestedKeys = mapOf(TEST_KEY to 1),
        )
        for (i in 0..4) assertTrue(video.submit(clip.frames[i]))
        assertNotNull("an image with the minimal format", awaitImage(clip.frames[4], SystemClock.uptimeMillis() + 2_000))
        assertEquals(LowLatencyState.FALLBACK, video.stats.value.lowLatency)
        assertTrue("a one-off start failure is not remembered", checkNotNull(video.stats.value.decoderName) !in fallbacks)
    }

    /**
     * A decoder running the low-latency format that errors before its first output: it is
     * remembered, rebuilt with the minimal format and a keyframe requested; the next keyframe
     * decodes.
     */
    @Test
    fun errorBeforeFirstOutputRebuildsWithTheMinimalFormat() {
        val fallbacks = LowLatencyFallbacks()
        val video = start(
            codecFactory = { info -> LowLatencyFaultHandle(mediaCodecHandle(info), errorBeforeOutput = true) },
            fallbacks = fallbacks,
            extraRequestedKeys = mapOf(TEST_KEY to 1),
        )
        assertTrue("started with the low-latency keys", awaitTrue { video.stats.value.lowLatency == LowLatencyState.ON })
        val decoder = checkNotNull(video.stats.value.decoderName)
        val before = requests.get()
        assertTrue(video.submit(clip.frames[0]))
        assertTrue("rebuilt and a keyframe requested", awaitTrue { requests.get() > before && video.stats.value.lowLatency == LowLatencyState.FALLBACK })
        assertTrue(decoder in fallbacks)
        assertTrue(video.submit(clip.frames[10]))
        assertNotNull("the rebuilt decoder outputs", awaitImage(clip.frames[10], SystemClock.uptimeMillis() + 2_000))
    }

    @Test
    fun surfaceReattachRecoversWithKeyframe() {
        val video = start()
        for (i in 0..4) assertTrue(video.submit(clip.frames[i]))
        assertNotNull(awaitImage(clip.frames[4], SystemClock.uptimeMillis() + 2_000))
        video.detachSurface()
        assertEquals(VideoReadiness.WaitingForSurface, video.readiness())
        requests.set(0)
        video.attachSurface(reader.surface)
        assertEquals(1, requests.get())
        assertEquals(VideoReadiness.Ready, video.readiness())
        assertTrue(video.submit(clip.frames[10]))
        assertNotNull(awaitImage(clip.frames[10], SystemClock.uptimeMillis() + 2_000))
    }

    @Test
    fun codecExceptionsReleaseAndRequestKeyframe() {
        // The first codec's configure throws for both formats (the low-latency one and its
        // minimal retry), so it is an ordinary decoder failure whatever the device's decoder.
        val failConfigure = AtomicBoolean(true)
        val failQueueInput = AtomicBoolean(false)
        val failCallback = AtomicBoolean(false)
        val releases = AtomicInteger()
        val factory = { info: DecoderInfo ->
            FaultInjectingHandle(mediaCodecHandle(info), failConfigure.getAndSet(false), failQueueInput, failCallback, releases)
        }
        val video = start(codecFactory = factory)

        // From configure: attach built a codec whose configure threw.
        assertTrue(awaitTrue { releases.get() == 1 })
        assertTrue("attach and the failure", awaitTrue { requests.get() == 2 })
        assertEquals(0, video.pendingInputCount())

        // From queueInput. The second failure with no output moves to the next candidate decoder
        // (the Pixel lists a hardware and a software HEVC decoder).
        failQueueInput.set(true)
        assertTrue(video.submit(clip.frames[0]))
        assertTrue(awaitTrue { releases.get() == 2 })
        assertTrue(awaitTrue { requests.get() == 3 })
        assertEquals(0, video.pendingInputCount())

        // From a callback.
        failCallback.set(true)
        assertTrue(video.submit(clip.frames[0]))
        assertTrue(awaitTrue { releases.get() == 3 })
        assertTrue(awaitTrue { requests.get() == 4 })
        assertEquals(0, video.pendingInputCount())

        assertTrue(video.submit(clip.frames[10]))
        assertNotNull(awaitImage(clip.frames[10], SystemClock.uptimeMillis() + 2_000))
    }

    @Test
    fun overdueOutputClearsOnOutput() {
        val video = start()
        for (i in 0..4) assertTrue(video.submit(clip.frames[i]))
        assertNotNull(awaitImage(clip.frames[4], SystemClock.uptimeMillis() + 2_000))
        // The output callback pops the clock right after rendering; the image may land first.
        assertTrue(awaitTrue(200) { video.outputOverdueSinceMillis() == null })
        assertNull(video.outputOverdueSinceMillis())
    }

    @Test
    fun partialOutputKeepsOldestPendingTime() {
        val clock = AtomicLong(1_000)
        val codecs = CopyOnWriteArrayList<ScriptedCodecHandle>()
        val decoderThread = HandlerDecoderThread("HevcDecodePathDeviceTest.scripted")
        val video = start(
            codecFactory = { ScriptedCodecHandle(inputs = 4).also { codecs += it } },
            nowMillis = clock::get,
            decoderThread = decoderThread,
        )
        assertTrue(video.submit(clip.frames[0]))
        clock.set(1_033)
        assertTrue(video.submit(clip.frames[1]))
        drain(decoderThread)
        assertEquals(2, codecs.single().queued.size)
        codecs.single().output(clip.frames[0].ptsMilliseconds * 1000)
        drain(decoderThread)
        assertEquals(1_033L, video.outputOverdueSinceMillis())
    }

    @Test
    fun replacementKeyframesKeepTheClock() {
        val clock = AtomicLong(1_000)
        val codecs = CopyOnWriteArrayList<ScriptedCodecHandle>()
        val decoderThread = HandlerDecoderThread("HevcDecodePathDeviceTest.scripted")
        // No free input buffer ever: every keyframe waits in the queue and replaces the one before.
        val video = start(
            codecFactory = { ScriptedCodecHandle(inputs = 0, recycleInputs = false).also { codecs += it } },
            nowMillis = clock::get,
            decoderThread = decoderThread,
        )
        val keyframes = listOf(clip.frames[0], clip.frames[10])
        val first = 5_000L
        for (step in 0..6) {
            clock.set(first + step * 500L)
            assertTrue(video.submit(keyframes[step % 2]))
            drain(decoderThread)
            assertEquals("after ${step * 500} ms", first, video.outputOverdueSinceMillis())
            assertEquals(1, video.pendingInputCount())
        }
        assertTrue(codecs.single().queued.isEmpty())
        assertEquals(1, codecs.size)
    }

    // ---- Helpers ----

    private fun start(
        codecFactory: (DecoderInfo) -> CodecHandle = ::mediaCodecHandle,
        nowMillis: () -> Long = SystemClock::uptimeMillis,
        decoderThread: DecoderThread = HandlerDecoderThread(),
        fallbacks: LowLatencyFallbacks = LowLatencyFallbacks(),
        extraRequestedKeys: Map<String, Int> = emptyMap(),
    ): HevcVideoOutput {
        val video = HevcVideoOutput(
            catalog = MediaCodecCatalog(),
            codecFactory = codecFactory,
            nowMillis = nowMillis,
            decoderThread = decoderThread,
            // Its own memory: a test's rejected keys must not change what the next test configures.
            lowLatencyFallbacks = fallbacks,
            extraRequestedKeys = extraRequestedKeys,
        )
        outputs += video
        video.setKeyframeRequester { requests.incrementAndGet() }
        assertEquals(VideoReadiness.WaitingForSurface, video.configure(clip.config))
        video.attachSurface(reader.surface)
        return video
    }

    /**
     * The device's whole HEVC decoder catalog, once per run, for the report: name, hardware or
     * software, FEATURE_LowLatency and the largest size, in the app's preference order.
     */
    private fun logDecoderCatalogOnce() {
        if (!catalogLogged.compareAndSet(false, true)) return
        val decoders = MediaCodecCatalog().decoders()
        Log.i(TAG, "HEVC decoder catalog: ${decoders.size} decoder(s)")
        for ((index, info) in decoders.withIndex()) {
            Log.i(
                TAG,
                "decoder ${index + 1}: ${info.name} isHardware=${info.isHardware} lowLatency=${info.supportsLowLatency} " +
                    "max=${info.maxWidth}x${info.maxHeight}",
            )
        }
    }

    private fun timestampNs(frame: MediaFrame) = frame.ptsMilliseconds * 1_000_000

    private fun awaitImage(frame: MediaFrame, deadline: Long): Long? {
        while (SystemClock.uptimeMillis() < deadline) {
            arrivals[timestampNs(frame)]?.let { return it }
            SystemClock.sleep(1)
        }
        return arrivals[timestampNs(frame)]
    }

    private fun awaitCount(count: Int, deadline: Long): Boolean {
        while (SystemClock.uptimeMillis() < deadline && imageCount.get() < count) SystemClock.sleep(1)
        return imageCount.get() >= count
    }

    private fun awaitTrue(timeoutMillis: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(1)
        return condition()
    }

    private fun drain(thread: DecoderThread) {
        repeat(3) {
            val done = CountDownLatch(1)
            thread.post { done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
        }
    }

    private fun loadClip(): Clip {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = JSONObject(assets.open("terminal-clip.json").bufferedReader().use { it.readText() })
        val extradata = Base64.decode(json.getString("codecExtradataBase64"), Base64.DEFAULT)
        val frames = json.getJSONArray("frames")
        val config = StreamConfig(
            streamID = 1, generationId = 1, codecExtradata = extradata,
            pixelWidth = json.getInt("pixelWidth"), pixelHeight = json.getInt("pixelHeight"),
            scale = 2.0, columns = 80, rows = 24, cellGeometry = null, firstAbsLine = 0, totalLines = 24,
            canResize = true, altScreen = false, scrollWheelReporting = false, isBrowser = false,
        )
        return Clip(
            config,
            List(frames.length()) { parseMediaFrame(Base64.decode(frames.getJSONObject(it).getString("mediaFrameBase64"), Base64.DEFAULT)) },
        )
    }

    /** Wraps the real codec: configure always throws when [failConfigure]; the others throw once each when armed. */
    private class FaultInjectingHandle(
        private val real: CodecHandle,
        private val failConfigure: Boolean,
        private val failQueueInput: AtomicBoolean,
        private val failCallback: AtomicBoolean,
        private val releases: AtomicInteger,
    ) : CodecHandle by real {
        override fun setCallback(callback: CodecHandle.Callback) {
            real.setCallback(
                object : CodecHandle.Callback by callback {
                    override fun onOutputAvailable(index: Int, presentationTimeUs: Long) {
                        if (failCallback.getAndSet(false)) {
                            callback.onError(IllegalStateException("injected callback failure"))
                        } else {
                            callback.onOutputAvailable(index, presentationTimeUs)
                        }
                    }
                },
            )
        }

        override fun configure(format: CodecFormat, surface: Surface) {
            if (failConfigure) throw IllegalStateException("injected configure failure")
            real.configure(format, surface)
        }

        override fun queueInput(index: Int, size: Int, presentationTimeUs: Long) {
            if (failQueueInput.getAndSet(false)) throw IllegalStateException("injected queueInput failure")
            real.queueInput(index, size, presentationTimeUs)
        }

        override fun release() {
            releases.incrementAndGet()
            real.release()
        }
    }

    /**
     * Wraps the real codec. With [failStart], start throws while the configured format is the
     * low-latency one. With [errorBeforeOutput], the low-latency format's first output is reported
     * as an error instead (the codec fails before its first output).
     */
    private class LowLatencyFaultHandle(
        private val real: CodecHandle,
        private val failStart: Boolean = false,
        private val errorBeforeOutput: Boolean = false,
    ) : CodecHandle by real {
        @Volatile private var request: LowLatencyRequest? = null

        override fun setCallback(callback: CodecHandle.Callback) {
            real.setCallback(
                object : CodecHandle.Callback by callback {
                    override fun onOutputAvailable(index: Int, presentationTimeUs: Long) {
                        if (errorBeforeOutput && request == LowLatencyRequest.REQUESTED) {
                            callback.onError(IllegalStateException("injected error before the first output"))
                        } else {
                            callback.onOutputAvailable(index, presentationTimeUs)
                        }
                    }
                },
            )
        }

        override fun configure(format: CodecFormat, surface: Surface) {
            request = format.request
            real.configure(format, surface)
        }

        override fun start() {
            if (failStart && request == LowLatencyRequest.REQUESTED) throw IllegalStateException("injected start failure")
            real.start()
        }
    }

    /** A codec that decodes nothing: it frees [inputs] input buffers and outputs only when told. */
    private class ScriptedCodecHandle(private val inputs: Int, private val recycleInputs: Boolean = true) : CodecHandle {
        override val name: String = "scripted"
        val queued = CopyOnWriteArrayList<Long>()
        private lateinit var handler: Handler
        private lateinit var callback: CodecHandle.Callback
        private val buffers = HashMap<Int, ByteBuffer>()

        override fun setCallback(callback: CodecHandle.Callback) {
            this.callback = callback
            handler = Handler(checkNotNull(Looper.myLooper()))
        }

        override fun configure(format: CodecFormat, surface: Surface) = Unit

        override fun reset() = Unit

        override fun start() = repeat(inputs) { index -> handler.post { callback.onInputAvailable(index) } }

        override fun inputBuffer(index: Int): ByteBuffer = buffers.getOrPut(index) { ByteBuffer.allocate(1 shl 20) }

        override fun queueInput(index: Int, size: Int, presentationTimeUs: Long) {
            queued += presentationTimeUs
            if (recycleInputs) handler.post { callback.onInputAvailable(index) }
        }

        override fun releaseOutput(index: Int, render: Boolean) = Unit

        override fun stop() = Unit

        override fun release() = Unit

        fun output(presentationTimeUs: Long) {
            handler.post { callback.onOutputAvailable(0, presentationTimeUs) }
        }
    }

    private companion object {
        const val TAG = "HevcDecodePathDeviceTest"

        /** The catalog is logged once per test run (the class is instantiated per test). */
        val catalogLogged = AtomicBoolean(false)
        const val FRAME_GAP_MILLIS = 33L

        /** Makes the low-latency format differ from the minimal one on any decoder; codecs ignore unknown keys. */
        const val TEST_KEY = "itermbuddy-test-low-latency"
    }
}
