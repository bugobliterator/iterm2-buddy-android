package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.CellGeometry
import dev.bugobliterator.itermbuddy.core.stream.LiveProblem
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live bar's ⤢ Fit to phone button: shown, enabled or disabled with a reason (iOS `SessionView.swift:923-937`). */
class FitButtonTest {
    private val session = SessionSummary("guid-alpha", "alpha", "subtitle", isBrowser = false)
    private val browser = SessionSummary("guid-web", "web", "subtitle", isBrowser = true)

    private fun config(canResize: Boolean? = true, isBrowser: Boolean = false, columns: Int = 200, rows: Int = 50) = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = byteArrayOf(0, 0), pixelWidth = 3200, pixelHeight = 1600,
        scale = 2.0, columns = columns, rows = rows, cellGeometry = CellGeometry(16.0, 32.0, 0.0, 0.0), firstAbsLine = 0,
        totalLines = rows, canResize = canResize, altScreen = false, scrollWheelReporting = false, isBrowser = isBrowser,
    )

    private fun button(
        live: LiveViewState = LiveViewState.Live(session, config()),
        config: StreamConfig? = config(),
        width: Int = 1080,
        height: Int = 2000,
        decoderMax: Pair<Int, Int>? = 4096 to 2304,
        sizeSupported: ((Int, Int) -> Boolean)? = { _, _ -> true },
        on: SessionSummary = session,
    ) = fitButton(on, live, config, width, height, decoderMax, sizeSupported)

    @Test
    fun enabledWhenAGridFits() {
        assertEquals(FitButton.Enabled, button())
        // A window too large for the decoder is exactly when Fit helps.
        val tooLarge = LiveViewState.Failed(session, LiveProblem.TooLarge(5120, 2880, 4096, 2304))
        assertEquals(FitButton.Enabled, button(live = tooLarge))
    }

    @Test
    fun hiddenOnlyForBrowserSessions() {
        assertEquals(FitButton.Hidden, button(on = browser, live = LiveViewState.Live(browser, config(isBrowser = true)), config = config(isBrowser = true)))
        assertEquals(FitButton.Hidden, button(config = config(isBrowser = true)))
        // Every terminal state shows it.
        for (state in listOf(LiveViewState.Starting(session), LiveViewState.Reconnecting(session), LiveViewState.Closed)) {
            assertEquals(true, button(live = state, config = null) is FitButton.Disabled)
        }
    }

    @Test
    fun cannotResizeExplainsTheMacSide() {
        val reason = FitButton.Disabled(FIT_REASON_CANNOT_RESIZE)
        assertEquals(reason, button(config = config(canResize = false)))
        assertEquals(reason, button(config = config(canResize = null)))
        assertEquals(
            "The Mac can't resize this window. Leave full screen, un-maximise it, or detach it from the screen edge.",
            FIT_REASON_CANNOT_RESIZE,
        )
    }

    @Test
    fun waitingForThePicture() {
        val waiting = FitButton.Disabled("Waiting for the picture.")
        assertEquals(waiting, button(live = LiveViewState.Starting(session), config = null))
        assertEquals("the canvas not measured yet", waiting, button(width = 0, height = 0))
        assertEquals("no grid in the config yet", waiting, button(config = config(columns = 0, rows = 0)))
    }

    @Test
    fun decoderThatTakesNoSize() {
        val reason = FitButton.Disabled("This phone's decoder can't show a resized window.")
        assertEquals(reason, button(sizeSupported = { _, _ -> false }))
    }

    @Test
    fun endedStreamIsDisabled() {
        val ended = LiveViewState.Ended(session, StreamEndReason.SESSION_CLOSED)
        assertEquals(FitButton.Disabled(FIT_REASON_ENDED), button(live = ended))
    }
}
