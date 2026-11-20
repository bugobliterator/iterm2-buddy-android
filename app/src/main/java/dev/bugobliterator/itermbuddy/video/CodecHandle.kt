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
 * [integerKeys] are the MediaFormat integer keys, in order ([decoderIntegerKeys]: low latency,
 * vendor keys, `KEY_PRIORITY = 0`, `KEY_MAX_INPUT_SIZE`); [request] is what they ask for.
 */
class CodecFormat(
    val width: Int,
    val height: Int,
    val csd0: ByteArray,
    val request: LowLatencyRequest,
    val integerKeys: Map<String, Int>,
) {
    /** `KEY_LOW_LATENCY = 1` is set. */
    val lowLatency: Boolean get() = integerKeys[MediaFormat.KEY_LOW_LATENCY] == 1

    /** `KEY_MAX_INPUT_SIZE`. */
    val maxInputSize: Int get() = integerKeys.getValue(MediaFormat.KEY_MAX_INPUT_SIZE)

    override fun toString(): String =
        "CodecFormat(${width}x$height, csd0=${csd0.size} bytes, $request, keys=$integerKeys)"
}

/**
 * A thin wrapper over one async `MediaCodec` decoder, so tests can inject failures and fakes.
 * Every call is made on the decoder thread; [setCallback] is called there first, and the
 * callbacks must be delivered on that same thread (the real handle posts them to its looper).
 * Any call may throw a [RuntimeException] (`CodecException`, `IllegalStateException`, ...).
 * [configure] and [start] throw [InsufficientResourceException] when the codec reports
 * `ERROR_INSUFFICIENT_RESOURCE`, so the output moves to another decoder at once. [reset] returns
 * the codec to the unconfigured state (from any state, an error one included) so it can be
 * configured again; the callback is set again after it.
 */
interface CodecHandle {
    val name: String

    fun setCallback(callback: Callback)

    fun configure(format: CodecFormat, surface: Surface)

    fun start()

    /** `MediaCodec.reset`: back to unconfigured, ready for [setCallback] and [configure] again. */
    fun reset()

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

/** `CodecException.ERROR_INSUFFICIENT_RESOURCE` from configure or start: this decoder cannot run now. */
class InsufficientResourceException(cause: Throwable) : IllegalStateException("insufficient resource", cause)

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
            for ((key, value) in format.integerKeys) setInteger(key, value)
        }
        resourceChecked { codec.configure(mediaFormat, surface, null, 0) }
    }

    override fun start() = resourceChecked { codec.start() }

    override fun reset() = codec.reset()

    private inline fun resourceChecked(block: () -> Unit) {
        try {
            block()
        } catch (e: MediaCodec.CodecException) {
            if (e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE) throw InsufficientResourceException(e)
            throw e
        }
    }

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
