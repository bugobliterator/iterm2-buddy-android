package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.BROWSER_INPUT_REVISION
import dev.bugobliterator.itermbuddy.core.stream.LiveProblem
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.wire.ProtocolRevision
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import dev.bugobliterator.itermbuddy.video.VideoStats
import kotlinx.coroutines.flow.StateFlow

/** Shown after the too-large text when Fit to phone is not offered. */
const val MAC_SIDE_ADVICE = "Make the iTerm2 window smaller on the Mac (leave full screen) to watch it here."

/** Shown over a browser session's video until browser control exists on both sides. */
const val BROWSER_NOTE = "Browser control comes later"

/** What an overlay's one button does. */
enum class OverlayAction {
    /** The navigator's Back (closes the stream). */
    BACK,

    /** The controller's `retry` (restarts the stream). */
    RETRY,

    /** The controller's `retry` after the Mac ended the stream: watches the session again. */
    RESTART,

    /** Fit to phone. */
    FIT,
}

/** The live view's message over (or instead of) the video, and its one button, if any. */
data class Overlay(val text: String, val action: OverlayAction?)

/**
 * The design's overlay for [state] (`docs/superpowers/specs/2026-11-16-sessions-design.md`,
 * Live view) for the view of session [sessionGuid]. [canFit] is `fitGrid(…) != null` for the
 * active stream's config: only a too-large window offers Fit, and only then; otherwise it gets the
 * Mac-side advice. Closed, Paused, a healthy Live and the ends the phone caused (stopped by
 * client, superseded) show none. A state that is another session's (the moment between a row tap
 * and the controller taking the open) reads as "Starting…": this view's stream is on its way.
 * A start that cannot succeed by retrying (`StartFailed(retryable = false)`) offers Back.
 */
fun liveOverlay(state: LiveViewState, canFit: Boolean, sessionGuid: String): Overlay? {
    val owner = state.sessionOrNull() ?: return null
    if (owner.guid != sessionGuid) return Overlay("Starting…", null)
    return overlayFor(state, canFit)
}

private fun overlayFor(state: LiveViewState, canFit: Boolean): Overlay? = when (state) {
    LiveViewState.Closed -> null
    is LiveViewState.Starting -> Overlay("Starting…", null)
    is LiveViewState.Live -> if (state.stalled) Overlay("Picture stalled", OverlayAction.RETRY) else null
    is LiveViewState.Reconnecting -> Overlay("Reconnecting…", null)
    is LiveViewState.Paused -> null
    is LiveViewState.Ended -> when (state.reason) {
        StreamEndReason.SESSION_CLOSED -> Overlay("Session closed on the Mac", OverlayAction.BACK)
        StreamEndReason.DATA_LIMIT_REACHED -> Overlay("Stream ended: daily data limit reached", OverlayAction.RESTART)
        StreamEndReason.ERROR, StreamEndReason.UNKNOWN -> Overlay("Stream ended", OverlayAction.RESTART)
        StreamEndReason.SUPERSEDED, StreamEndReason.STOPPED_BY_CLIENT -> null
    }
    is LiveViewState.Failed -> when (val problem = state.problem) {
        LiveProblem.NoDecoder -> Overlay("This phone has no HEVC video decoder", null)
        is LiveProblem.TooLarge -> {
            val text = "The Mac window (${problem.width}×${problem.height}) is too large for this phone's decoder " +
                "(max ${problem.maxWidth}×${problem.maxHeight})."
            if (canFit) Overlay(text, OverlayAction.FIT) else Overlay("$text $MAC_SIDE_ADVICE", null)
        }
        is LiveProblem.StartFailed -> Overlay("Can't show session", if (problem.retryable) OverlayAction.RETRY else OverlayAction.BACK)
    }
}

/** The session [this] state is about; null for [LiveViewState.Closed]. */
fun LiveViewState.sessionOrNull(): SessionSummary? = when (this) {
    LiveViewState.Closed -> null
    is LiveViewState.Starting -> session
    is LiveViewState.Live -> session
    is LiveViewState.Reconnecting -> session
    is LiveViewState.Paused -> session
    is LiveViewState.Ended -> session
    is LiveViewState.Failed -> session
}

/**
 * The stale-frame rule: the video surface may be visible only while [state] is Live for the
 * view's own session ([sessionGuid]) and the output has rendered at least one frame since its last reset or decoder rebuild
 * ([VideoStats.framesOutSinceReset]). The TextureView keeps its last buffer through a reset, so
 * without this a previous session's (or stream's) terminal would show under Starting, Ended,
 * Failed, Reconnecting, or a new stream's first moments. Every stream change resets the output
 * (the controller resets on open, close, retry, pause, resume, link up and down, and end).
 */
fun showsVideo(state: LiveViewState, framesOutSinceReset: Long, sessionGuid: String): Boolean =
    state is LiveViewState.Live && state.session.guid == sessionGuid && framesOutSinceReset > 0

/**
 * [showsVideo] read from the flows as the live screen reads them: the controller's [state]
 * first, then the output's [stats]. The controller resets the output before it publishes the
 * next stream's state, and the output zeroes the count under its own lock in that reset, so a
 * state read first is never paired with a count from before its reset.
 */
fun videoVisible(state: StateFlow<LiveViewState>, stats: StateFlow<VideoStats>, sessionGuid: String): Boolean {
    val current = state.value
    return showsVideo(current, stats.value.framesOutSinceReset, sessionGuid)
}

/**
 * The note over a browser session's video while browser input is not available: until both
 * this phone ([phoneRevision]) and the Mac ([macRevision], null when unknown) speak revision 15.
 * Null for a terminal session.
 */
fun browserNote(session: SessionSummary, macRevision: Int?, phoneRevision: Int = ProtocolRevision.REVISION): String? {
    if (!session.isBrowser) return null
    val both = phoneRevision >= BROWSER_INPUT_REVISION && macRevision != null && macRevision >= BROWSER_INPUT_REVISION
    return if (both) null else BROWSER_NOTE
}
