package dev.bugobliterator.itermbuddy.video

import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.VisibleForTesting
import dev.bugobliterator.itermbuddy.applog.AppLog
import dev.bugobliterator.itermbuddy.core.stream.HevcFramingException
import dev.bugobliterator.itermbuddy.core.stream.MediaFrame
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.VideoOutput
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.stream.parameterSetsToCsd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Decoder counters for diagnostics. [lastLatencyMillis] is accept (by [HevcVideoOutput.submit])
 * to output of the latest output frame, matched by presentation time: the frame's own accept
 * time, never one carried over from dropped frames, and no sample when the output matches no
 * accepted frame. [medianLatencyMillis] is the median of the last [LATENCY_WINDOW] such samples
 * of the running decoder (null until its first). [lowLatency] is how the running (or last)
 * decoder was configured ([LowLatencyState]). [decoderName] is the running decoder (null while
 * there is none); [lastDecoderName] the most recent one started, kept after it is released
 * (Settings shows it).
 * [framesOutSinceReset] counts frames rendered since the last [HevcVideoOutput.reset] or decoder
 * rebuild for a new config: the live view's stale-frame signal. It is zeroed under the output's
 * lock in the same step that retires the old decoder's frames, so an output of a decoder that is
 * no longer wanted never counts.
 */
data class VideoStats(
    val decoderName: String?,
    val framesIn: Long,
    val framesOut: Long,
    val lastLatencyMillis: Long?,
    val framesOutSinceReset: Long = 0,
    val lastDecoderName: String? = null,
    val medianLatencyMillis: Long? = null,
    val lowLatency: LowLatencyState? = null,
)

/** How many recent accept-to-output samples [VideoStats.medianLatencyMillis] is the median of. */
const val LATENCY_WINDOW = 31

/** How long [HevcVideoOutput.detachSurface] and [HevcVideoOutput.close] wait for the decoder thread. */
const val DEFAULT_SURFACE_CALL_TIMEOUT_MILLIS: Long = 1_000

/** `KEY_MAX_INPUT_SIZE` floor: 4 MiB. */
private const val MIN_MAX_INPUT_SIZE: Long = 4L shl 20

/** The `KEY_MAX_INPUT_SIZE` a decoder is rebuilt with, once, for a keyframe over the first one: 16 MiB. */
private const val ENLARGED_MAX_INPUT_SIZE: Int = 16 shl 20

/**
 * How long a decoder configured with the low-latency keys may go without any output after its
 * first keyframe went in before it is rebuilt with the minimal format.
 */
const val DEFAULT_LOW_LATENCY_OUTPUT_TIMEOUT_MILLIS: Long = 3_000

/** Failures of one decoder, with no output in between, before the next candidate is tried. */
private const val FAILURES_BEFORE_FALLBACK = 2

private const val TAG = "HevcVideoOutput"

/**
 * The live view's [VideoOutput]: an async MediaCodec HEVC decoder rendering to the view's
 * [Surface], mirroring iOS `CompanionVideoView` (`IOS/Screens/CompanionVideoView.swift`):
 * configure from parameter sets, decode each access unit, render it at once, ask for a keyframe
 * when a decode fails, forget everything on [reset].
 *
 * **Threading.** Every codec call and codec callback runs on one [DecoderThread] (a
 * `HandlerThread` in production). The public methods change the wanted state under a lock on the
 * caller's thread and post a reconcile to the decoder thread, which releases a codec that is no
 * longer wanted and builds one that is. [submit] offers into a [PendingInputQueue] on the caller's
 * thread; the decoder thread polls it whenever an input buffer is free. Only [detachSurface] and
 * [close] wait for the decoder thread (so the codec has let go of the surface before the view
 * destroys it), and at most [surfaceCallTimeoutMillis]: a wedged codec cannot hang the UI thread
 * past that; the work still runs when the thread frees up. [attachSurface] never waits for the
 * build. No method throws. The decoder list is read once per output, on the decoder thread when
 * it gets there first (the constructor posts it), else by the first [configure].
 *
 * **Decoder lifetime.** A decoder exists only with a surface and a config whose parameter sets are
 * complete ([hasCompleteHevcParameterSets]); for an incomplete set the readiness is
 * [VideoReadiness.WaitingForConfig] and MediaCodec is never configured from it. A config with the
 * same extradata and pixel size keeps the decoder; anything else rebuilds it, releasing the old
 * codec first. A keyframe never rebuilds a working decoder, with one exception: a keyframe over
 * `KEY_MAX_INPUT_SIZE` (`max(w × h, 4 MiB)`) rebuilds it once with 16 MiB; past that, frames over
 * the limit are refused. Attaching a new surface builds one and requests a keyframe; attaching the
 * same surface again does nothing. Until a codec's first keyframe, [submit] refuses non-keyframes.
 *
 * **Low latency.** Each decoder is first configured with [LowLatencyRequest.REQUESTED]
 * ([decoderIntegerKeys]: `KEY_LOW_LATENCY` whether or not advertised, plus its vendor's keys;
 * Qualcomm's picture-order key only when the SPS says the stream never reorders,
 * [spsMaxNumReorderPics]). When that format differs from [LowLatencyRequest.MINIMAL] (the earlier
 * format):
 * - If configure or start throws (other than [InsufficientResourceException]), the same codec is
 *   reset and configured once more with the minimal format. When that works, a configure-time
 *   rejection (`IllegalArgumentException` or `CodecException`) remembers the decoder in
 *   [lowLatencyFallbacks] at once; any other rejection counts, and the decoder is remembered at
 *   the second ([LowLatencyFallbacks.reject]).
 * - If the started codec fails (a codec call or callback throws) before its first output, or
 *   gives no output within [lowLatencyOutputTimeoutMillis] of its first keyframe going in, the
 *   decoder is remembered and the same decoder rebuilt with the minimal format at once (the queue
 *   empties and a keyframe is requested); this does not count toward moving to the next decoder.
 *
 * A remembered decoder gets the minimal format straight away for the rest of the process. Which
 * path ran is logged, key names only. Callbacks of the first attempt are ignored once the retry
 * starts (an attempt counter). [setLowLatencyKeys] (the debug switch) turns the requested format
 * off and on, rebuilding the decoder.
 *
 * **Failures and fallback.** Any exception from a codec call or callback releases the codec,
 * empties the pending queue and requests a keyframe; the next keyframe [submit] accepts rebuilds
 * the codec. The candidates are [decoderCandidates] for the size; after
 * [FAILURES_BEFORE_FALLBACK] failures of one candidate with no output in between, or at once on
 * [InsufficientResourceException], the next is used. An output resets the count. When every
 * candidate has failed, the readiness is [VideoReadiness.NoDecoder] until a new config or [reset].
 * An access unit that does not parse is dropped (with the queue behind it) and a keyframe
 * requested, without counting against the decoder.
 *
 * **Overdue clock.** A FIFO of accept times, one per frame accepted into the queue. An output pops
 * the entries up to its presentation time (at least one; the Mac's encoder never reorders), so a
 * partial output leaves the next frame's time at the head. Frames dropped or replaced in the queue,
 * or lost with a released codec, take their entries with them, but when that empties the FIFO the
 * earliest removed time is carried onto the next accepted frame, and reported meanwhile, so neither
 * a replacing keyframe nor a codec rebuild while attached resets the clock. Only [reset], [close]
 * and [detachSurface] empty it: with no surface nothing is overdue, and a rotation with a frame in
 * flight must not show "Picture stalled" once the surface is back.
 *
 * **Ownership.** The output owns its decoder thread. Whoever constructs it (the app-scoped live
 * view owner) calls [close] when it is done with it; until then the thread lives, idle, with the
 * process. Process death needs nothing: the codec and thread go with the process.
 */
class HevcVideoOutput(
    private val catalog: HevcDecoderCatalog,
    private val codecFactory: (DecoderInfo) -> CodecHandle = ::mediaCodecHandle,
    maxPendingInputBytes: Long = DEFAULT_MAX_PENDING_INPUT_BYTES,
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
    private val decoderThread: DecoderThread = HandlerDecoderThread(),
    private val surfaceCallTimeoutMillis: Long = DEFAULT_SURFACE_CALL_TIMEOUT_MILLIS,
    private val log: (String) -> Unit = { AppLog.i(TAG, it) },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val lowLatencyFallbacks: LowLatencyFallbacks = LowLatencyFallbacks.process,
    private val lowLatencyOutputTimeoutMillis: Long = DEFAULT_LOW_LATENCY_OUTPUT_TIMEOUT_MILLIS,
    /** Device tests only: added to every [LowLatencyRequest.REQUESTED] format, so it differs from the minimal one on any decoder. */
    private val extraRequestedKeys: Map<String, Int> = emptyMap(),
) : VideoOutput, AutoCloseable {

    /**
     * What the current config asks for. [candidates] are the decoders that take the size, in
     * preference order; [candidate], [failures], [maxInputSize] and [enlarged] change under lock.
     */
    private class Plan(
        val extradata: ByteArray,
        val width: Int,
        val height: Int,
        val complete: Boolean,
        val choice: DecoderChoice?,
        val candidates: List<DecoderInfo>,
        val csd: ByteArray?,
        var maxInputSize: Int,
        /** The SPS may reorder pictures (`sps_max_num_reorder_pics > 0`, or unknown). */
        val reorders: Boolean = true,
    ) {
        var candidate = 0
        var failures = 0
        var enlarged = false

        fun current(): DecoderInfo? = if (complete && choice is DecoderChoice.Use) candidates.getOrNull(candidate) else null
    }

    /**
     * One accepted frame: [acceptedAt] is the overdue clock's time (possibly carried from frames
     * dropped before it), [ownAcceptedAt] when this frame itself was accepted (the latency's start).
     */
    private class Accepted(val presentationTimeUs: Long, val acceptedAt: Long, val ownAcceptedAt: Long)

    /** What the decoder thread builds: [info] for the plan's size, with the debug switch's [lowLatencyKeys]. */
    private class Wanted(
        val info: DecoderInfo,
        val width: Int,
        val height: Int,
        val csd: ByteArray,
        val maxInputSize: Int,
        val lowLatencyKeys: Boolean,
        val reorders: Boolean,
    )

    // ---- Guarded by lock: the wanted state, the pending queue and the overdue clock ----
    private val lock = Any()
    private var closed = false
    private var plan: Plan? = null
    private var surface: Surface? = null

    /** Bumped whenever the running codec (if any) stops being the wanted one. */
    private var epoch = 0L

    /** After a codec failure: no codec until a keyframe is accepted. */
    private var rebuildOnKeyframe = false

    /** The debug switch "Decoder low-latency keys"; always true in release builds. */
    private var lowLatencyKeys = true

    private val queue = PendingInputQueue(maxPendingInputBytes)
    private val accepted = ArrayDeque<Accepted>()
    private var carriedSince: Long? = null

    // ---- Decoder thread only ----
    private var codec: CodecHandle? = null
    private var codecEpoch = -1L
    private var codecHasOutput = false
    private val freeInputs = ArrayDeque<Int>()

    /** Bumped for every configure attempt; callbacks of an earlier attempt are ignored. */
    private var codecAttempt = 0L

    /** The running codec runs [LowLatencyRequest.REQUESTED] and that differs from the minimal format. */
    private var codecRequested = false
    private var codecDecoderName: String? = null

    /** The attempt the no-output check is armed for. */
    private var watchdogAttempt = -1L

    /** Set while configure (not start) runs, so a rejection knows where it came from. */
    private var inConfigure = false

    /** The running decoder's recent accept-to-output samples, oldest first (at most [LATENCY_WINDOW]). */
    private val latencies = ArrayDeque<Long>()

    @Volatile private var keyframeRequester: (() -> Unit)? = null

    private val statsFlow = MutableStateFlow(VideoStats(null, 0, 0, null))

    /** Decoder name and frame counters. */
    val stats: StateFlow<VideoStats> = statsFlow.asStateFlow()

    private val reconcileTask = Runnable { guarded("reconcile") { reconcile() } }

    /** The device's HEVC decoders, read once; a failing catalog reads as none. */
    private val decoderList: List<DecoderInfo> by lazy {
        try {
            catalog.decoders()
        } catch (e: RuntimeException) {
            log("listing HEVC decoders failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    init {
        // Read the decoder list on the decoder thread, off the controller's, before the first config.
        decoderThread.post { guarded("decoder list") { decoderList } }
    }

    // ---- VideoOutput ----

    override fun configure(config: StreamConfig): VideoReadiness = guarded("configure", VideoReadiness.NoDecoder) {
        val extradata = config.codecExtradata
        val unchanged = synchronized(lock) {
            if (closed) return@guarded VideoReadiness.NoDecoder
            val current = plan
            current != null && current.extradata.contentEquals(extradata) &&
                current.width == config.pixelWidth && current.height == config.pixelHeight
        }
        if (unchanged) return@guarded readiness()
        val next = makePlan(extradata, config.pixelWidth, config.pixelHeight)
        synchronized(lock) {
            if (closed) return@guarded VideoReadiness.NoDecoder
            plan = next
            discardWanted()
            rebuildOnKeyframe = false
            // A new decoder is a new picture: the surface's last buffer is the old one's.
            statsFlow.update { it.copy(framesOutSinceReset = 0) }
        }
        decoderThread.post(reconcileTask)
        if (!next.complete) log("config of ${extradata.size} bytes lacks a VPS, SPS or PPS; waiting for a complete one")
        readiness()
    }

    override fun readiness(): VideoReadiness = guarded("readiness", VideoReadiness.NoDecoder) {
        synchronized(lock) {
            val current = plan
            when {
                closed -> VideoReadiness.NoDecoder
                current == null -> if (surface == null) VideoReadiness.WaitingForSurface else VideoReadiness.Ready
                !current.complete -> VideoReadiness.WaitingForConfig
                else -> when (val choice = current.choice) {
                    is DecoderChoice.TooLarge ->
                        VideoReadiness.TooLarge(current.width, current.height, choice.maxWidth, choice.maxHeight)
                    is DecoderChoice.Use -> when {
                        current.current() == null -> VideoReadiness.NoDecoder
                        surface == null -> VideoReadiness.WaitingForSurface
                        else -> VideoReadiness.Ready
                    }
                    DecoderChoice.None, DecoderChoice.UnsupportedSize, null -> VideoReadiness.NoDecoder
                }
            }
        }
    }

    override fun submit(frame: MediaFrame): Boolean = guarded("submit", false) {
        val accept = synchronized(lock) {
            val current = plan
            if (closed || surface == null || current?.current() == null) return@guarded false
            if (rebuildOnKeyframe && !frame.isKeyframe) return@guarded false
            if (frame.payloadSize > current.maxInputSize) {
                if (!frame.isKeyframe || current.enlarged || frame.payloadSize > ENLARGED_MAX_INPUT_SIZE) {
                    // A refused frame breaks the reference chain: what follows waits for a keyframe.
                    queue.waitForKeyframe()
                    return@guarded false
                }
                log("keyframe of ${frame.payloadSize} bytes over the ${current.maxInputSize}-byte input size; rebuilding with $ENLARGED_MAX_INPUT_SIZE")
                current.enlarged = true
                current.maxInputSize = ENLARGED_MAX_INPUT_SIZE
                discardWanted()
            }
            val before = queue.size
            val ok = queue.offer(frame)
            removeNewest(before + (if (ok) 1 else 0) - queue.size)
            if (ok) {
                val now = nowMillis()
                val since = carriedSince?.let { minOf(it, now) } ?: now
                carriedSince = null
                accepted.addLast(Accepted(presentationTimeUs(frame), since, now))
                rebuildOnKeyframe = false
            }
            ok
        }
        if (accept) decoderThread.post(reconcileTask)
        accept
    }

    override fun reset() = guarded("reset") {
        synchronized(lock) {
            plan = null
            epoch++
            rebuildOnKeyframe = false
            queue.clear()
            accepted.clear()
            carriedSince = null
            statsFlow.update { it.copy(framesOutSinceReset = 0) }
        }
        decoderThread.post(reconcileTask)
    }

    override fun setKeyframeRequester(request: () -> Unit) {
        keyframeRequester = request
    }

    override fun outputOverdueSinceMillis(): Long? = guarded("outputOverdueSinceMillis", null) {
        synchronized(lock) { accepted.firstOrNull()?.acceptedAt ?: carriedSince }
    }

    override fun maxSize(): Pair<Int, Int>? = guarded("maxSize", null) {
        when (val choice = synchronized(lock) { plan?.choice }) {
            is DecoderChoice.Use -> choice.info.maxWidth to choice.info.maxHeight
            is DecoderChoice.TooLarge -> choice.maxWidth to choice.maxHeight
            DecoderChoice.None, DecoderChoice.UnsupportedSize, null -> null
        }
    }

    override fun sizeSupported(width: Int, height: Int): Boolean = guarded("sizeSupported", false) {
        chooseDecoder(decoderList, width, height) is DecoderChoice.Use
    }

    // ---- Surface and lifetime ----

    /**
     * The view's surface is ready: builds a decoder (when the config allows) on the decoder thread
     * and requests a keyframe. Does not wait for the build. The surface already attached is a no-op.
     */
    fun attachSurface(surface: Surface) = guarded("attachSurface") {
        synchronized(lock) {
            if (closed || this.surface === surface) return@guarded
            this.surface = surface
            discardWanted()
            rebuildOnKeyframe = false
        }
        decoderThread.post(reconcileTask)
        keyframeRequester?.invoke()
    }

    /**
     * The debug switch "Decoder low-latency keys": off configures decoders with
     * [LowLatencyRequest.MINIMAL] (the earlier format), on with [LowLatencyRequest.REQUESTED].
     * A change rebuilds a running decoder (the queue empties and a keyframe is requested, as for a
     * new surface). On by default.
     */
    fun setLowLatencyKeys(enabled: Boolean) = guarded("setLowLatencyKeys") {
        val rebuild = synchronized(lock) {
            if (closed || lowLatencyKeys == enabled) return@guarded
            lowLatencyKeys = enabled
            val attached = surface != null
            if (attached) {
                discardWanted()
                rebuildOnKeyframe = false
            }
            attached
        }
        log("decoder low-latency keys turned ${if (enabled) "on" else "off"}")
        if (rebuild) {
            decoderThread.post(reconcileTask)
            keyframeRequester?.invoke()
        }
    }

    /**
     * The view's surface is going away: releases the decoder before returning (or the timeout).
     * The overdue clock is emptied, not carried: frames in flight are lost with the surface, not
     * held by a stuck decoder, so a rotation must not count them as a stall once the surface is back.
     */
    fun detachSurface() = guarded("detachSurface") {
        synchronized(lock) {
            if (closed) return@guarded
            surface = null
            discardWanted()
            accepted.clear()
            carriedSince = null
            rebuildOnKeyframe = false
        }
        runOnDecoderAndWait("detachSurface") { reconcile() }
    }

    /** Releases the codec and ends the decoder thread. Idempotent; afterwards every method is a no-op. */
    override fun close() = guarded("close") {
        synchronized(lock) {
            if (closed) return@guarded
            closed = true
            plan = null
            surface = null
            epoch++
            queue.clear()
            accepted.clear()
            carriedSince = null
            statsFlow.update { it.copy(framesOutSinceReset = 0) }
        }
        runOnDecoderAndWait("close") { reconcile() }
        decoderThread.quit()
    }

    @VisibleForTesting
    internal fun pendingInputCount(): Int = queue.size

    // ---- Wanted-state helpers (hold lock) ----

    /** The running codec is no longer wanted: its frames and the queue's go, the clock carries. */
    private fun discardWanted() {
        epoch++
        queue.clear()
        removeNewest(accepted.size)
    }

    /** Removes the [count] newest clock entries; when that empties the FIFO, the earliest removed time carries. */
    private fun removeNewest(count: Int) {
        if (count <= 0) return
        var earliest = Long.MAX_VALUE
        repeat(minOf(count, accepted.size)) { earliest = minOf(earliest, accepted.removeLast().acceptedAt) }
        if (accepted.isEmpty() && earliest != Long.MAX_VALUE) {
            carriedSince = minOf(carriedSince ?: earliest, earliest)
        }
    }

    private fun makePlan(extradata: ByteArray, width: Int, height: Int): Plan {
        val maxInput = maxOf(width.toLong() * height, MIN_MAX_INPUT_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!hasCompleteHevcParameterSets(extradata)) return Plan(extradata, width, height, false, null, emptyList(), null, maxInput)
        val decoders = decoderList
        val choice = chooseDecoder(decoders, width, height)
        val candidates = if (choice is DecoderChoice.Use) decoderCandidates(decoders, width, height) else emptyList()
        val reorders = spsMaxNumReorderPics(extradata)?.let { it > 0 } ?: true
        return Plan(extradata, width, height, true, choice, candidates, parameterSetsToCsd(extradata), maxInput, reorders)
    }

    // ---- Decoder thread ----

    private fun reconcile() {
        var wanted: Wanted? = null
        var wantedSurface: Surface? = null
        var wantedEpoch = 0L
        synchronized(lock) {
            val current = plan
            val info = current?.current()
            val csd = current?.csd
            if (!closed && !rebuildOnKeyframe && surface != null && current != null && info != null && csd != null) {
                wanted = Wanted(info, current.width, current.height, csd, current.maxInputSize, lowLatencyKeys, current.reorders)
                wantedSurface = surface
            }
            wantedEpoch = epoch
        }
        if (codec != null && (codecEpoch != wantedEpoch || wanted == null)) releaseCodec()
        val build = wanted
        val target = wantedSurface
        if (codec == null && build != null && target != null) buildCodec(build, target, wantedEpoch)
        feed()
    }

    private fun formatFor(wanted: Wanted, request: LowLatencyRequest): CodecFormat {
        var keys = decoderIntegerKeys(wanted.info.name, wanted.info.supportsLowLatency, request, sdkInt, wanted.maxInputSize, wanted.reorders)
        if (request == LowLatencyRequest.REQUESTED && extraRequestedKeys.isNotEmpty()) keys = keys + extraRequestedKeys
        return CodecFormat(wanted.width, wanted.height, wanted.csd, request, keys)
    }

    private fun buildCodec(wanted: Wanted, target: Surface, forEpoch: Long) {
        val info = wanted.info
        val handle = try {
            codecFactory(info)
        } catch (e: Exception) {
            fail(null, forEpoch, e)
            return
        }
        codec = handle
        codecEpoch = forEpoch
        codecHasOutput = false
        codecRequested = false
        codecDecoderName = info.name
        freeInputs.clear()
        val minimal = formatFor(wanted, LowLatencyRequest.MINIMAL)
        val first = if (!wanted.lowLatencyKeys || info.name in lowLatencyFallbacks) minimal else formatFor(wanted, LowLatencyRequest.REQUESTED)
        try {
            var used = first
            try {
                startCodec(handle, first, target)
            } catch (e: RuntimeException) {
                if (first === minimal || e is InsufficientResourceException || first.integerKeys == minimal.integerKeys) throw e
                val atConfigure = inConfigure
                log(
                    "decoder ${info.name} rejected the low-latency keys at ${if (atConfigure) "configure" else "start"} " +
                        "(${e.javaClass.simpleName}); retrying with the minimal format",
                )
                handle.reset()
                startCodec(handle, minimal, target)
                used = minimal
                // Remembered only once the minimal format worked: a decoder failing both is not the keys' fault.
                val remembered = if (atConfigure && (e is IllegalArgumentException || e is MediaCodec.CodecException)) {
                    lowLatencyFallbacks.add(info.name)
                    true
                } else {
                    lowLatencyFallbacks.reject(info.name)
                }
                if (remembered) log("decoder ${info.name} gets the minimal format from now on")
            }
            codecRequested = used.request == LowLatencyRequest.REQUESTED && used.integerKeys != minimal.integerKeys
            val state = when {
                used.request == LowLatencyRequest.REQUESTED -> LowLatencyState.ON
                !wanted.lowLatencyKeys -> LowLatencyState.OFF
                else -> LowLatencyState.FALLBACK
            }
            log(
                "decoder ${info.name} started for ${used.width}x${used.height}, low latency ${lowLatencyLabel(state)}: " +
                    used.integerKeys.keys.joinToString(),
            )
            latencies.clear()
            statsFlow.update {
                it.copy(decoderName = info.name, lastDecoderName = info.name, lowLatency = state, medianLatencyMillis = null)
            }
        } catch (e: RuntimeException) {
            fail(handle, forEpoch, e)
        }
    }

    private fun startCodec(handle: CodecHandle, format: CodecFormat, target: Surface) {
        freeInputs.clear()
        handle.setCallback(Events(handle, ++codecAttempt))
        // Left true when configure throws, so the caller knows where the rejection came from.
        inConfigure = true
        handle.configure(format, target)
        inConfigure = false
        handle.start()
    }

    /**
     * Arms the no-output check once per attempt, when the first keyframe goes into a codec running
     * the low-latency keys and it has not output yet.
     */
    private fun armOutputWatchdog() {
        if (!codecRequested || codecHasOutput || watchdogAttempt == codecAttempt) return
        val attempt = codecAttempt
        watchdogAttempt = attempt
        val since = nowMillis()
        decoderThread.postDelayed({ guarded("low-latency output check") { checkOutput(attempt, since) } }, lowLatencyOutputTimeoutMillis)
    }

    private fun checkOutput(attempt: Long, since: Long) {
        val handle = codec ?: return
        if (attempt != codecAttempt || codecHasOutput || !codecRequested) return
        val waited = nowMillis() - since
        if (waited < lowLatencyOutputTimeoutMillis) {
            decoderThread.postDelayed({ guarded("low-latency output check") { checkOutput(attempt, since) } }, lowLatencyOutputTimeoutMillis - waited)
            return
        }
        fallBackToMinimal(handle, codecEpoch, "no output within $lowLatencyOutputTimeoutMillis ms of a keyframe")
    }

    /**
     * The codec running the low-latency keys failed or stayed silent before its first output: the
     * decoder is remembered and the same decoder rebuilt at once with the minimal format, without
     * counting a failure against it. Its frames and the queue go (the clock carries) and a
     * keyframe is requested.
     */
    private fun fallBackToMinimal(handle: CodecHandle, forEpoch: Long, reason: String) {
        val name = codecDecoderName ?: handle.name
        val wanted = synchronized(lock) {
            if (forEpoch == epoch && !closed) {
                discardWanted()
                rebuildOnKeyframe = false
                true
            } else {
                false
            }
        }
        releaseCodec()
        if (!wanted) return
        lowLatencyFallbacks.add(name)
        log("decoder $name failed before its first output with the low-latency keys ($reason); rebuilding it with the minimal format")
        decoderThread.post(reconcileTask)
        keyframeRequester?.invoke()
    }

    /** Moves pending frames into free input buffers, in order. */
    private fun feed() {
        val handle = codec ?: return
        while (freeInputs.isNotEmpty()) {
            val frame = synchronized(lock) { if (codecEpoch == epoch) queue.poll() else null } ?: return
            val index = freeInputs.first()
            try {
                val buffer = handle.inputBuffer(index) ?: throw IllegalStateException("no input buffer $index")
                buffer.clear()
                if (frame.payloadSize > buffer.remaining()) {
                    throw IllegalStateException("frame of ${frame.payloadSize} bytes over the ${buffer.remaining()}-byte input buffer")
                }
                frame.writeAnnexB(buffer)
                handle.queueInput(index, frame.payloadSize, presentationTimeUs(frame))
                freeInputs.removeFirst()
                statsFlow.update { it.copy(framesIn = it.framesIn + 1) }
                if (frame.isKeyframe) armOutputWatchdog()
            } catch (e: HevcFramingException) {
                dropUnparseable(e)
                return
            } catch (e: Exception) {
                fail(handle, codecEpoch, e)
                return
            }
        }
    }

    /**
     * An access unit that does not parse: not the decoder's fault. The frame and everything queued
     * behind it go (the clock carries), the input buffer stays free, and a keyframe is requested.
     */
    private fun dropUnparseable(error: HevcFramingException) {
        log("dropped an access unit that does not parse: ${error.message}")
        val request = synchronized(lock) {
            if (codecEpoch != epoch) return
            removeNewest(queue.size + 1)
            queue.clear()
            true
        }
        if (request) keyframeRequester?.invoke()
    }

    /**
     * One output buffer. A buffer of a codec that is no longer wanted (a reset or rebuild is on its
     * way) is released without rendering. A wanted one is rendered first and counted after, and
     * only if the codec is still wanted then: `releaseOutputBuffer(index, true)` queues the buffer
     * to the surface's BufferQueue before it returns (CCodec renders through the output surface's
     * `queueBuffer`), and the TextureView's SurfaceTexture latches the newest queued buffer on its
     * next draw, so once [VideoStats.framesOutSinceReset] says a frame is out, any draw shows that
     * frame or a newer one, never the buffer left from before the reset. A reset between the two
     * steps leaves the frame uncounted (it is retired with its codec).
     */
    private fun onOutput(handle: CodecHandle, index: Int, presentationTimeUs: Long) {
        val wanted = synchronized(lock) { codecEpoch == epoch }
        handle.releaseOutput(index, wanted)
        if (!wanted) return
        val now = nowMillis()
        val first = !codecHasOutput
        codecHasOutput = true
        var latency: Long? = null
        synchronized(lock) {
            if (codecEpoch != epoch) return
            if (first) plan?.failures = 0
            var popped: Accepted? = null
            while (accepted.isNotEmpty() && accepted.first().presentationTimeUs <= presentationTimeUs) {
                popped = accepted.removeFirst()
            }
            if (popped == null) {
                // No accepted frame at or before this output: pop one for the clock, but no sample.
                accepted.removeFirstOrNull()
            } else if (popped.presentationTimeUs == presentationTimeUs) {
                latency = now - popped.ownAcceptedAt
            }
            val median = latency?.let { addLatencySample(it) }
            // Under the lock, so a reset racing this output either sees it counted and zeroes it,
            // or retires this codec first and it does not count.
            statsFlow.update {
                it.copy(
                    framesOut = it.framesOut + 1,
                    framesOutSinceReset = it.framesOutSinceReset + 1,
                    lastLatencyMillis = latency ?: it.lastLatencyMillis,
                    medianLatencyMillis = median ?: it.medianLatencyMillis,
                )
            }
        }
    }

    /** Adds a sample to the window and returns the window's median (the upper one for an even count). */
    private fun addLatencySample(sample: Long): Long {
        latencies.addLast(sample)
        while (latencies.size > LATENCY_WINDOW) latencies.removeFirst()
        return latencies.sorted()[latencies.size / 2]
    }

    /**
     * A codec call or callback failed (or [handle] could not be created, when null). When it was
     * the wanted codec: drop its frames and the queue (the clock carries), count the failure
     * against the decoder (moving to the next candidate when due), wait for a keyframe to rebuild,
     * release it and ask for a keyframe (unless no candidate is left).
     */
    private fun fail(handle: CodecHandle?, forEpoch: Long, error: Exception) {
        if (handle != null && handle !== codec) {
            releaseQuietly(handle)
            return
        }
        if (handle != null && codecRequested && !codecHasOutput && error !is InsufficientResourceException) {
            fallBackToMinimal(handle, forEpoch, error.javaClass.simpleName)
            return
        }
        log("decoder failed: ${error.javaClass.simpleName}; releasing it and requesting a keyframe")
        val request = synchronized(lock) {
            if (forEpoch == epoch && !closed) {
                discardWanted()
                rebuildOnKeyframe = true
                val current = plan
                if (current != null) {
                    current.failures++
                    if (current.failures >= FAILURES_BEFORE_FALLBACK || error is InsufficientResourceException) {
                        val from = current.current()?.name
                        current.candidate++
                        current.failures = 0
                        log("decoder $from failed; falling back to ${current.current()?.name ?: "none"}")
                    }
                }
                current?.current() != null
            } else {
                false
            }
        }
        if (handle != null) releaseCodec()
        if (request) keyframeRequester?.invoke()
    }

    private fun releaseCodec() {
        val handle = codec ?: return
        codec = null
        codecEpoch = -1
        codecRequested = false
        freeInputs.clear()
        releaseQuietly(handle)
        statsFlow.update { it.copy(decoderName = null) }
    }

    private fun releaseQuietly(handle: CodecHandle) {
        try {
            handle.stop()
        } catch (_: RuntimeException) {
            // Stopping a codec in the error state throws; release below still frees it.
        }
        try {
            handle.release()
        } catch (e: RuntimeException) {
            log("releasing decoder failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Callbacks of one configure [attempt] of a codec; anything for a codec that is no longer
     * current, or for an attempt before its minimal-format retry, is ignored.
     */
    private inner class Events(private val handle: CodecHandle, private val attempt: Long) : CodecHandle.Callback {
        override fun onInputAvailable(index: Int) = onCurrent {
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputAvailable(index: Int, presentationTimeUs: Long) = onCurrent {
            onOutput(handle, index, presentationTimeUs)
        }

        override fun onError(error: RuntimeException) = onCurrent { fail(handle, codecEpoch, error) }

        override fun onOutputFormatChanged(width: Int, height: Int) = onCurrent {
            log("decoder output format ${width}x$height")
        }

        private inline fun onCurrent(block: () -> Unit) {
            if (handle !== codec || attempt != codecAttempt) return
            try {
                block()
            } catch (e: RuntimeException) {
                fail(handle, codecEpoch, e)
            }
        }
    }

    // ---- Plumbing ----

    private fun runOnDecoderAndWait(what: String, task: () -> Unit) {
        if (decoderThread.isCurrent()) {
            task()
            return
        }
        val done = CountDownLatch(1)
        val posted = decoderThread.post {
            try {
                guarded(what) { task() }
            } finally {
                done.countDown()
            }
        }
        if (!posted) return
        try {
            if (!done.await(surfaceCallTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log("$what did not finish on the decoder thread within $surfaceCallTimeoutMillis ms; not waiting longer")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        log("$what failed: ${e.javaClass.simpleName}")
        fallback
    }

    private inline fun guarded(what: String, block: () -> Unit) = guarded(what, Unit, block)

    private fun presentationTimeUs(frame: MediaFrame): Long {
        val pts = frame.ptsMilliseconds
        return if (pts >= Long.MAX_VALUE / 1000) Long.MAX_VALUE else pts * 1000
    }
}
