package dev.bugobliterator.itermbuddy.video

import dev.bugobliterator.itermbuddy.core.stream.MediaFrame

/** The decoder's pending-input byte budget (16 MiB). */
const val DEFAULT_MAX_PENDING_INPUT_BYTES: Long = 16L shl 20

/**
 * The hand-off from `HevcVideoOutput.submit` (the controller's thread) to the decoder thread,
 * which polls it whenever MediaCodec frees an input buffer. Thread-safe. Bounded by [maxBytes] of
 * payload; every frame it holds was already acked by the controller, so what it drops is never a
 * silent drop before the ack.
 *
 * Rules:
 * - a keyframe replaces everything pending (the new IDR depends on none of it) and is accepted;
 * - a non-keyframe is refused while the queue waits for a keyframe; it waits from construction
 *   and after [clear] (a decoder starts from a keyframe, and dropped frames break the reference
 *   chain);
 * - a non-keyframe that would take the queue over [maxBytes] is refused, the pending non-keyframes
 *   are dropped (a pending keyframe stays: at most one can be pending, at the head) and the queue
 *   waits for a keyframe;
 * - a single frame larger than [maxBytes] is refused, whatever it is, and the queue waits for a
 *   keyframe; what was pending stays.
 *
 * [bytes] never exceeds [maxBytes].
 */
class PendingInputQueue(private val maxBytes: Long = DEFAULT_MAX_PENDING_INPUT_BYTES) {
    private val lock = Any()
    private val frames = ArrayDeque<MediaFrame>()
    private var total = 0L
    private var waiting = true

    /** Payload bytes pending. */
    val bytes: Long get() = synchronized(lock) { total }

    /** Frames pending. */
    val size: Int get() = synchronized(lock) { frames.size }

    /** True while non-keyframes are refused until the next keyframe. */
    val waitingForKeyframe: Boolean get() = synchronized(lock) { waiting }

    /** Queues [frame] under the rules above; false when it is refused. */
    fun offer(frame: MediaFrame): Boolean = synchronized(lock) {
        val size = frame.payloadSize.toLong()
        when {
            size > maxBytes -> {
                waiting = true
                false
            }
            frame.isKeyframe -> {
                frames.clear()
                frames.addLast(frame)
                total = size
                waiting = false
                true
            }
            waiting -> false
            total + size > maxBytes -> {
                frames.removeAll { !it.isKeyframe }
                total = frames.sumOf { it.payloadSize.toLong() }
                waiting = true
                false
            }
            else -> {
                frames.addLast(frame)
                total += size
                true
            }
        }
    }

    /** The oldest pending frame, removed, or null. */
    fun poll(): MediaFrame? = synchronized(lock) {
        val frame = frames.removeFirstOrNull() ?: return null
        total -= frame.payloadSize
        frame
    }

    /** Keeps what is pending but refuses non-keyframes until the next keyframe (a frame was refused elsewhere). */
    fun waitForKeyframe() = synchronized(lock) { waiting = true }

    /** Drops everything pending; the queue then waits for a keyframe. */
    fun clear() = synchronized(lock) {
        frames.clear()
        total = 0
        waiting = true
    }
}
