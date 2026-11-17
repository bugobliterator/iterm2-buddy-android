package dev.bugobliterator.itermbuddy.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer

/**
 * What [HevcVideoOutput] configures a decoder with. [csd0] is `parameterSetsToCsd(extradata)`;
 * [lowLatency] sets `KEY_LOW_LATENCY = 1`; [maxInputSize] is `KEY_MAX_INPUT_SIZE`. Priority is
 * always 0 (real time).
 */
class CodecFormat(
    val width: Int,
    val height: Int,
    val csd0: ByteArray,
    val lowLatency: Boolean,
    val maxInputSize: Int,
) {
    override fun toString(): String =
        "CodecFormat(${width}x$height, csd0=${csd0.size} bytes, lowLatency=$lowLatency, maxInputSize=$maxInputSize)"
}

/**
 * A thin wrapper over one async `MediaCodec` decoder, so tests can inject failures and fakes.
 * Every call is made on the decoder thread; [setCallback] is called there first, and the
 * callbacks must be delivered on that same thread (the real handle posts them to its looper).
 * Any call may throw a [RuntimeException] (`CodecException`, `IllegalStateException`, ...).
 */
interface CodecHandle {
    val name: String

    fun setCallback(callback: Callback)

    fun configure(format: CodecFormat, surface: Surface)

    fun start()

    /** The input buffer for [index], as `getInputBuffer`. */
    fun inputBuffer(index: Int): ByteBuffer?

    fun queueInput(index: Int, size: Int, presentationTimeUs: Long)

    fun releaseOutput(index: Int, render: Boolean)

    fun stop()

    fun release()

    /** `MediaCodec.Callback`, delivered on the decoder thread. */
    interface Callback {
        fun onInputAvailable(index: Int)

        fun onOutputAvailable(index: Int, presentationTimeUs: Long)

        fun onError(error: RuntimeException)

        fun onOutputFormatChanged(width: Int, height: Int)
    }
}

/** The real [CodecHandle]: `MediaCodec.createByCodecName(info.name)`. */
fun mediaCodecHandle(info: DecoderInfo): CodecHandle = MediaCodecHandle(MediaCodec.createByCodecName(info.name))

private class MediaCodecHandle(private val codec: MediaCodec) : CodecHandle {
    override val name: String get() = codec.name

    override fun setCallback(callback: CodecHandle.Callback) {
        // Called on the decoder thread, so its looper delivers the callbacks there.
        val looper = checkNotNull(Looper.myLooper()) { "setCallback off a looper thread" }
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = callback.onInputAvailable(index)

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                    callback.onOutputAvailable(index, info.presentationTimeUs)

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = callback.onError(e)

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = callback.onOutputFormatChanged(
                    format.getInteger(MediaFormat.KEY_WIDTH, 0),
                    format.getInteger(MediaFormat.KEY_HEIGHT, 0),
                )
            },
            Handler(looper),
        )
    }

    override fun configure(format: CodecFormat, surface: Surface) {
        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, format.width, format.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(format.csd0))
            if (format.lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize)
        }
        codec.configure(mediaFormat, surface, null, 0)
    }

    override fun start() = codec.start()

    override fun inputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInput(index: Int, size: Int, presentationTimeUs: Long) =
        codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)

    override fun releaseOutput(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)

    override fun stop() = codec.stop()

    override fun release() = codec.release()
}

/**
 * The one thread every codec call and callback of an [HevcVideoOutput] runs on. [post] returns
 * false once the thread has quit.
 */
interface DecoderThread {
    fun post(task: Runnable): Boolean

    /** True when called on this thread. */
    fun isCurrent(): Boolean

    /** Ends the thread after the tasks already posted. */
    fun quit()
}

/** The production [DecoderThread]: a started `HandlerThread` at display priority. */
class HandlerDecoderThread(name: String = "HevcDecoder") : DecoderThread {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    override fun post(task: Runnable): Boolean = handler.post(task)

    override fun isCurrent(): Boolean = Looper.myLooper() == thread.looper

    override fun quit() {
        thread.quitSafely()
    }
}
