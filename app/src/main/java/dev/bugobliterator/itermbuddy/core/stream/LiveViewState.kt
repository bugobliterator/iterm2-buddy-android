package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason

/**
 * What the live view shows for the watched session. Every case that names a session prints it
 * through [SessionSummary.toString], which shows the GUID and lengths only, never the name.
 */
sealed interface LiveViewState {
    /** Nothing is watched. */
    data object Closed : LiveViewState

    /** A stream is being started, or has started and waits for its first config (or for one with complete parameter sets). */
    data class Starting(val session: SessionSummary) : LiveViewState

    /** Frames are flowing for [config]. [stalled]: the decoder holds a frame it has not output for over `stallAfter`. */
    data class Live(val session: SessionSummary, val config: StreamConfig, val stalled: Boolean = false) : LiveViewState

    /** The link is down while foreground; the stream restarts when it is back. */
    data class Reconnecting(val session: SessionSummary) : LiveViewState

    /** The app is in the background; the stream was stopped and restarts on resume. */
    data class Paused(val session: SessionSummary) : LiveViewState

    /** The Mac ended the stream ([reason]); there is no automatic restart. */
    data class Ended(val session: SessionSummary, val reason: StreamEndReason) : LiveViewState

    /** The stream cannot be shown: [problem] says why. */
    data class Failed(val session: SessionSummary, val problem: LiveProblem) : LiveViewState
}

/** Why a live view is [LiveViewState.Failed]. */
sealed interface LiveProblem {
    /** No decoder on this device can decode the stream. Acks continue. */
    data object NoDecoder : LiveProblem

    /** The stream is larger than the decoder allows. Acks continue and the config is kept for Fit. */
    data class TooLarge(val width: Int, val height: Int, val maxWidth: Int, val maxHeight: Int) : LiveProblem

    /** The start failed: [retryable] when a later link, resume or Retry may succeed. */
    data class StartFailed(val retryable: Boolean) : LiveProblem
}
