package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid

/** Why ⤢ is greyed when the Mac reports the window cannot resize (full screen, maximised, edge-attached). */
internal const val FIT_REASON_CANNOT_RESIZE =
    "The Mac can't resize this window. Leave full screen, un-maximise it, or detach it from the screen edge."

/** Why ⤢ is greyed before there is a picture (no config, no grid, or no measured canvas yet). */
internal const val FIT_REASON_WAITING = "Waiting for the picture."

/** Why ⤢ is greyed when no grid's frame is a size this phone's decoder takes. */
internal const val FIT_REASON_DECODER = "This phone's decoder can't show a resized window."

/** Why ⤢ is greyed after the Mac ended the stream (iOS disables it then too). */
internal const val FIT_REASON_ENDED = "The stream has ended."

/** The live bar's ⤢ Fit to phone button. */
sealed interface FitButton {
    /** Not shown: a browser session has no grid to resize. */
    data object Hidden : FitButton

    /** Shown and tappable: [fitGrid] gives a grid. */
    data object Enabled : FitButton

    /** Shown greyed; a tap or long press shows [reason]. */
    data class Disabled(val reason: String) : FitButton
}

/**
 * The ⤢ button for [session], as iOS's resize button (`SessionView.swift:923-937`): hidden only
 * for a browser session (or a browser config), otherwise shown, and enabled exactly when the stream
 * has not ended and [fitGrid] gives a grid for the viewport (the visible area with the keyboard
 * hidden), [config] (`canResize == true`, a grid) and the decoder. When it is disabled, the reason,
 * checked in this order: the stream ended; no config, grid or measured viewport yet; the Mac
 * cannot resize the window; no size the decoder takes.
 */
internal fun fitButton(
    session: SessionSummary,
    live: LiveViewState,
    config: StreamConfig?,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    decoderMax: Pair<Int, Int>?,
    sizeSupported: ((Int, Int) -> Boolean)?,
): FitButton {
    if (session.isBrowser || config?.isBrowser == true) return FitButton.Hidden
    if (live is LiveViewState.Ended) return FitButton.Disabled(FIT_REASON_ENDED)
    if (config == null || config.columns <= 0 || config.rows <= 0 || viewportWidthPx <= 0 || viewportHeightPx <= 0) {
        return FitButton.Disabled(FIT_REASON_WAITING)
    }
    if (config.canResize != true) return FitButton.Disabled(FIT_REASON_CANNOT_RESIZE)
    if (fitGrid(viewportWidthPx, viewportHeightPx, config, decoderMax, sizeSupported) != null) return FitButton.Enabled
    // Without the decoder's limits a grid exists: the decoder is what refuses it.
    val anyGrid = fitGrid(viewportWidthPx, viewportHeightPx, config, null, null) != null
    return FitButton.Disabled(if (anyGrid) FIT_REASON_DECODER else FIT_REASON_WAITING)
}
