package dev.bugobliterator.itermbuddy.video

import android.view.Surface
import dev.bugobliterator.itermbuddy.core.stream.MediaFrame
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertTrue

/**
 * A [DecoderThread] on one real thread, as the production `HandlerThread`: tasks run in post
 * order. [drain] waits until everything posted so far (and anything those tasks posted) has run.
 */
class TestDecoderThread : DecoderThread {
    @Volatile private var thread: Thread? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TestDecoderThread").also { thread = it }
    }
    val quits = AtomicInteger()

    override fun post(task: Runnable): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun isCurrent(): Boolean = Thread.currentThread() === thread

    override fun quit() {
        quits.incrementAndGet()
        executor.shutdown()
    }

    /** Runs until the thread is idle: tasks may post more (codec callbacks), so it loops a few rounds. */
    fun drain() {
        repeat(4) {
            val done = CountDownLatch(1)
            if (!post { done.countDown() }) return
            assertTrue("decoder thread wedged", done.await(10, TimeUnit.SECONDS))
        }
    }

    val isShutdown: Boolean get() = executor.isShutdown
}

/** A [DecoderThread] that never runs anything: a wedged codec call. */
class WedgedDecoderThread : DecoderThread {
    val posted = AtomicInteger()

    override fun post(task: Runnable): Boolean {
        posted.incrementAndGet()
        return true
    }

    override fun isCurrent(): Boolean = false

    override fun quit() = Unit
}

/**
 * A fake codec: callbacks go through [thread], as MediaCodec posts them to its handler. On
 * [start] it frees [inputBuffers] input buffers; after a [queueInput] it frees that buffer again
 * only when [recycleInputs]. Outputs happen only when the test calls [output].
 */
class FakeCodecHandle(
    private val thread: DecoderThread,
    override val name: String = "fake.hevc",
    private val inputBuffers: Int = 4,
    private val bufferCapacity: Int = 1 shl 20,
) : CodecHandle {
    @Volatile private var callback: CodecHandle.Callback? = null
    @Volatile var configuredFormat: CodecFormat? = null
    @Volatile var configuredSurface: Surface? = null
    @Volatile var started = false
    @Volatile var released = false
    @Volatile var recycleInputs = true
    @Volatile var throwOnConfigure = false
    @Volatile var throwOnQueueInput = false
    @Volatile var throwOnStopAndRelease = false

    /** Presentation times queued, in order, and the bytes of each input. */
    val queued = CopyOnWriteArrayList<Long>()
    val queuedBytes = CopyOnWriteArrayList<ByteArray>()
    val rendered = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    private val buffers = HashMap<Int, ByteBuffer>()
    private val nextOutput = AtomicInteger()

    override fun setCallback(callback: CodecHandle.Callback) {
        this.callback = callback
    }

    override fun configure(format: CodecFormat, surface: Surface) {
        if (throwOnConfigure) throw IllegalStateException("injected configure failure")
        configuredFormat = format
        configuredSurface = surface
    }

    override fun start() {
        started = true
        repeat(inputBuffers) { index -> post { onInputAvailable(index) } }
    }

    override fun inputBuffer(index: Int): ByteBuffer? {
        check(!released) { "released" }
        return buffers.getOrPut(index) { ByteBuffer.allocate(bufferCapacity) }
    }

    override fun queueInput(index: Int, size: Int, presentationTimeUs: Long) {
        check(!released) { "released" }
        if (throwOnQueueInput) throw IllegalStateException("injected queueInput failure")
        val bytes = ByteArray(size)
        buffers.getValue(index).duplicate().apply { position(0) }.get(bytes)
        queued += presentationTimeUs
        queuedBytes += bytes
        if (recycleInputs) post { onInputAvailable(index) }
    }

    override fun releaseOutput(index: Int, render: Boolean) {
        check(!released) { "released" }
        rendered += index to render
    }

    override fun stop() {
        started = false
        if (throwOnStopAndRelease) throw IllegalStateException("injected stop failure")
    }

    override fun release() {
        released = true
        if (throwOnStopAndRelease) throw IllegalStateException("injected release failure")
    }

    /** The codec outputs the frame queued with [presentationTimeUs]. */
    fun output(presentationTimeUs: Long) = post { onOutputAvailable(nextOutput.getAndIncrement(), presentationTimeUs) }

    /** The codec reports an error through its callback. */
    fun error() = post { onError(IllegalStateException("injected callback error")) }

    private fun post(block: CodecHandle.Callback.() -> Unit) {
        thread.post { callback?.block() }
    }
}

/** Builds [FakeCodecHandle]s and keeps them, for [HevcVideoOutput]'s `codecFactory`. */
class FakeCodecFactory(private val thread: DecoderThread, private val configure: FakeCodecHandle.(index: Int) -> Unit = {}) {
    val made = CopyOnWriteArrayList<FakeCodecHandle>()
    val requested = CopyOnWriteArrayList<DecoderInfo>()

    @Volatile var throwOnCreate = false

    fun create(info: DecoderInfo): CodecHandle {
        requested += info
        if (throwOnCreate) throw java.io.IOException("injected create failure")
        val index = made.size
        return FakeCodecHandle(thread, info.name).apply { configure(index) }.also { made += it }
    }

    val last: FakeCodecHandle get() = made.last()
}

class FixedCatalog(private val list: List<DecoderInfo>) : HevcDecoderCatalog {
    override fun decoders(): List<DecoderInfo> = list
}

fun decoder(
    name: String,
    hardware: Boolean = true,
    lowLatency: Boolean = true,
    maxWidth: Int = 4096,
    maxHeight: Int = 2304,
): DecoderInfo = DecoderInfo(name, hardware, lowLatency, maxWidth, maxHeight) { w, h -> w <= maxWidth && h <= maxHeight }

/**
 * A [Surface] for the JVM: the android.jar stub cannot be constructed, so the instance is
 * allocated without a constructor. The fakes only compare it by identity.
 */
fun fakeSurface(): Surface {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(Surface::class.java) as Surface
}

/** Parameter sets with one VPS (NAL type 32), SPS (33) and PPS (34). */
fun completeExtradata(tag: Int = 0): ByteArray = extradataOf(
    byteArrayOf(0x40, 0x01, 0x0C, tag.toByte()),
    byteArrayOf(0x42, 0x01, 0x01, tag.toByte()),
    byteArrayOf(0x44, 0x01, 0xC1.toByte(), tag.toByte()),
)

/** Parameter sets with a VPS and SPS and no PPS. */
fun incompleteExtradata(): ByteArray = extradataOf(byteArrayOf(0x40, 0x01, 0x0C), byteArrayOf(0x42, 0x01, 0x01))

fun extradataOf(vararg sets: ByteArray): ByteArray {
    val buffer = ByteBuffer.allocate(2 + sets.sumOf { 4 + it.size })
    buffer.putShort(sets.size.toShort())
    for (set in sets) buffer.putInt(set.size).put(set)
    return buffer.array()
}

fun streamConfig(
    extradata: ByteArray = completeExtradata(),
    width: Int = 1280,
    height: Int = 800,
    generation: Long = 1,
): StreamConfig = StreamConfig(
    streamID = 1, generationId = generation, codecExtradata = extradata, pixelWidth = width, pixelHeight = height,
    scale = 2.0, columns = 80, rows = 24, cellGeometry = null, firstAbsLine = 0, totalLines = 24, canResize = true,
    altScreen = false, scrollWheelReporting = false, isBrowser = false,
)

private val sequences = AtomicLong()

/** A frame whose payload is one NAL of [nalBytes] bytes (AVCC: a 4-byte length, then the NAL). */
fun frame(ptsMillis: Long, keyframe: Boolean, nalBytes: Int = 16): MediaFrame {
    val payload = ByteBuffer.allocate(4 + nalBytes).putInt(nalBytes).array()
    return MediaFrame(
        version = 2, flags = if (keyframe) 1 else 0, streamID = 1, sequence = sequences.getAndIncrement(),
        ptsMilliseconds = ptsMillis, generationId = 1, liveTop = 0, payload = payload,
    )
}

/** A frame of exactly [payloadBytes] payload bytes, for byte budgets (the payload need not parse). */
fun sizedFrame(ptsMillis: Long, keyframe: Boolean, payloadBytes: Int): MediaFrame = MediaFrame(
    version = 2, flags = if (keyframe) 1 else 0, streamID = 1, sequence = sequences.getAndIncrement(),
    ptsMilliseconds = ptsMillis, generationId = 1, liveTop = 0, payload = ByteArray(payloadBytes),
)
