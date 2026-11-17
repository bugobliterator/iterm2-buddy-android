package dev.bugobliterator.itermbuddy.core.stream

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * A [VideoOutput] for controller tests, following the interface's contract: [configure] keeps the
 * decoder (no rebuild) when the extradata and pixel size are unchanged; [readiness] is current;
 * [outputOverdueSinceMillis] is the accept time of the oldest frame not yet output.
 *
 * Output is modelled, not decoded: with [autoOutput] every accepted frame is output at once; with
 * [decodeMillisPerFrame] each is output that long after the previous one (a serial decoder slower
 * than the stream); otherwise frames stay pending until [outputOldest] or [outputAll].
 * Thread-safe, so a consumer on a real thread may block in [submit] on [submitLatch].
 */
class FakeVideoOutput(private val nowMillis: () -> Long) : VideoOutput {
    val configures = CopyOnWriteArrayList<StreamConfig>()
    val submits = CopyOnWriteArrayList<MediaFrame>()

    @Volatile var resets = 0
        private set

    /** Decoders built: one per [configure] whose extradata or size differs from the current one. */
    @Volatile var builds = 0
        private set

    /** What [configure] returns (and [readiness] then reports) for a config; Ready by default. */
    @Volatile var configureResult: (StreamConfig) -> VideoReadiness = { VideoReadiness.Ready }

    /** When set, [submit] returns this instead of its own answer (decoder and surface ready). */
    @Volatile var submitResult: ((MediaFrame) -> Boolean)? = null

    /** When true, each [submit] also calls the keyframe requester, as a codec error callback would. */
    @Volatile var requestOnSubmit = false

    @Volatile var autoOutput = true

    @Volatile var decodeMillisPerFrame: Long? = null

    /** When set, [submit] blocks on it before doing anything: a decoder whose input is stuck. */
    @Volatile var submitLatch: CountDownLatch? = null

    /** When true, [reset] throws (after counting), as a codec released twice might. */
    @Volatile var throwOnReset = false

    /** When true, [readiness] throws. */
    @Volatile var throwOnReadiness = false

    @Volatile var maxDecoderSize: Pair<Int, Int>? = 4096 to 4096

    private val lock = Any()
    private var current: StreamConfig? = null
    private var readinessValue: VideoReadiness = VideoReadiness.Ready
    private var surfaceAttached = true
    private var requester: (() -> Unit)? = null

    /** Accept time and scheduled output time (null: until told) of each frame not yet output. */
    private val pending = ArrayDeque<Pair<Long, Long?>>()
    private var lastOutputAt = Long.MIN_VALUE

    override fun configure(config: StreamConfig): VideoReadiness = synchronized(lock) {
        configures += config
        val old = current
        if (old == null || !old.codecExtradata.contentEquals(config.codecExtradata) ||
            old.pixelWidth != config.pixelWidth || old.pixelHeight != config.pixelHeight
        ) {
            builds++
            pending.clear()
        }
        current = config
        readinessValue = configureResult(config)
        readiness()
    }

    override fun readiness(): VideoReadiness = synchronized(lock) {
        if (throwOnReadiness) throw IllegalStateException("readiness failed")
        if (readinessValue == VideoReadiness.Ready && !surfaceAttached) VideoReadiness.WaitingForSurface else readinessValue
    }

    override fun submit(frame: MediaFrame): Boolean {
        submitLatch?.await()
        val accepted = synchronized(lock) {
            submits += frame
            val forced = submitResult
            val ok = forced?.invoke(frame) ?: (current != null && readiness() == VideoReadiness.Ready)
            if (ok) {
                val now = nowMillis()
                val decode = decodeMillisPerFrame
                when {
                    decode != null -> {
                        val at = maxOf(lastOutputAt, now) + decode
                        lastOutputAt = at
                        pending.addLast(now to at)
                    }
                    !autoOutput -> pending.addLast(now to null)
                }
            }
            ok
        }
        if (requestOnSubmit) requester?.invoke()
        return accepted
    }

    override fun reset() {
        synchronized(lock) {
            resets++
            current = null
            readinessValue = VideoReadiness.Ready
            pending.clear()
            lastOutputAt = Long.MIN_VALUE
        }
        if (throwOnReset) throw IllegalStateException("reset failed")
    }

    override fun setKeyframeRequester(request: () -> Unit) {
        requester = request
    }

    override fun outputOverdueSinceMillis(): Long? = synchronized(lock) {
        val now = nowMillis()
        while (pending.isNotEmpty()) {
            val at = pending.first().second
            if (at != null && at <= now) pending.removeFirst() else break
        }
        pending.firstOrNull()?.first
    }

    override fun maxSize(): Pair<Int, Int>? = maxDecoderSize

    /** Outputs the [count] oldest pending frames. */
    fun outputOldest(count: Int) = synchronized(lock) { repeat(count) { pending.removeFirstOrNull() } }

    fun outputAll() = synchronized(lock) { pending.clear() }

    /** The view's surface went away: readiness becomes WaitingForSurface. */
    fun detachSurface() = synchronized(lock) { surfaceAttached = false }

    /** The surface is back: as the real output does, it asks for a keyframe. */
    fun attachSurface() {
        synchronized(lock) { surfaceAttached = true }
        requester?.invoke()
    }

    /** The decoder asks for a keyframe (a codec error, say). */
    fun requestKeyframe() {
        requester?.invoke()
    }
}
