package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.FIT_NOTICE_CANNOT_RESIZE
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid

/** Why ⤢ is greyed when the Mac reports the window cannot resize (full screen, maximised, edge-attached, width-locked). */
internal const val FIT_REASON_CANNOT_RESIZE = FIT_NOTICE_CANNOT_RESIZE

/** Why ⤢ is greyed before there is a picture (no config, no grid, or no measured canvas yet). */
internal const val FIT_REASON_WAITING = "Waiting for the picture."

/** Why ⤢ is greyed when no grid's frame is a size this phone's decoder takes. */
internal const val FIT_REASON_DECODER = "This phone's decoder can't show a resized window."

/** Why ⤢ is greyed after the Mac ended the stream (iOS disables it then too). */
internal const val FIT_REASON_ENDED = "The stream has ended."

/** Long-press hint for ⤢ while the toggle is on and it can fit this session. */
internal const val FIT_HINT_ON = "Fit to phone is on: every terminal session is resized to fit this phone as you open it or rotate."

/** Long-press hint for ⤢ while the toggle is off. */
internal const val FIT_HINT_OFF = "Fit to phone is off: sessions keep their size on the Mac."

/**
 * The live bar's ⤢ Fit to phone toggle (one global on/off, [FitToPhoneSetting][dev.bugobliterator.itermbuddy.core.stream.FitToPhoneSetting]).
 * Every shown state toggles on a tap; this says whether it can fit the session shown.
 */
sealed interface FitButton {
    /** Not shown: a browser session has no grid to resize. */
    data object Hidden : FitButton

    /** Shown at full strength: [fitGrid] gives a grid, so while on it fits this session. */
    data object Enabled : FitButton

    /** Shown greyed: while on it does nothing for this session now, because of [reason]. */
    data class Disabled(val reason: String) : FitButton
}

/**
 * What a tap or long press on ⤢ shows, if anything. A tap that turns it on ([turnedOn]) shows the
 * [FitButton.Disabled] reason (but not [FIT_REASON_WAITING]: it fits once the picture is there); a
 * tap that turns it off shows nothing. A long press ([turnedOn] null) shows that reason, else
 * [FIT_HINT_ON] or [FIT_HINT_OFF] for [on].
 */
internal fun fitToggleMessage(button: FitButton, on: Boolean, turnedOn: Boolean?): String? {
    val reason = (button as? FitButton.Disabled)?.reason?.takeIf { it != FIT_REASON_WAITING }
    return when (turnedOn) {
        true -> reason
        false -> null
        null -> reason ?: if (on) FIT_HINT_ON else FIT_HINT_OFF
    }
}

/**
 * The ⤢ toggle for [session], shown as iOS's resize button (`SessionView.swift:923-937`): hidden only
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
