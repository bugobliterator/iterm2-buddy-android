package dev.bugobliterator.itermbuddy.core.stream

/** Whether a [VideoOutput] can decode frames for its current config right now. */
sealed interface VideoReadiness {
    /** A decoder is configured (or can be) and a surface is attached. */
    data object Ready : VideoReadiness

    /** No surface is attached (the view is detached, say mid-rotation); frames cannot be shown now. */
    data object WaitingForSurface : VideoReadiness

    /** No decoder on this device can decode the stream. */
    data object NoDecoder : VideoReadiness

    /** The stream's [width] x [height] is over the chosen decoder's [maxWidth] x [maxHeight]. */
    data class TooLarge(val width: Int, val height: Int, val maxWidth: Int, val maxHeight: Int) : VideoReadiness
}

/**
 * Where the stream controller sends decodable video: the Android counterpart of iOS
 * `CompanionVideoView` (`configure`, `enqueue`, `reset`, `onNeedsKeyframe`,
 * `IOS/Screens/CompanionVideoView.swift:65-185`). Calls come from the controller's single
 * consumer; the keyframe requester may be called from any thread.
 *
 * Implementations should not throw: a codec failure is reported through the return values, the
 * readiness and the keyframe requester. The controller still guards every call: a throwing
 * [configure] reads as [VideoReadiness.NoDecoder], a throwing [submit] as false, a throwing
 * [readiness] as not ready, and a throwing [reset] is logged and skipped.
 */
interface VideoOutput {
    /**
     * Applies [config]. Keeps the current decoder when the extradata bytes and the pixel size are
     * unchanged; otherwise rebuilds it. Returns the readiness for the new config.
     */
    fun configure(config: StreamConfig): VideoReadiness

    /** The current readiness: detaching the surface makes it [VideoReadiness.WaitingForSurface]. */
    fun readiness(): VideoReadiness

    /** Queues [frame] for decoding; false when it cannot be decoded now (no decoder, no surface, a codec error). */
    fun submit(frame: MediaFrame): Boolean

    /** Clears the picture and forgets the decoder (iOS `CompanionVideoView.reset`). */
    fun reset()

    /** [request] asks the controller for a keyframe (iOS `onNeedsKeyframe`); it may be called from any thread. */
    fun setKeyframeRequester(request: () -> Unit)

    /** The accept time (`nowMillis`) of the oldest submitted frame not yet output, or null when every one has been. */
    fun outputOverdueSinceMillis(): Long?

    /** The chosen decoder's size limit, width to height, or null when none is chosen. */
    fun maxSize(): Pair<Int, Int>?
}
