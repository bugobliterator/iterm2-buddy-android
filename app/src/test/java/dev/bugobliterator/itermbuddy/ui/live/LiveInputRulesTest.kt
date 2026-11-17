package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveProblem
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the live view offers the keyboard, and where its input goes. */
class LiveInputRulesTest {
    private val session = SessionSummary("guid-alpha", "alpha", "subtitle", isBrowser = false)
    private val browser = SessionSummary("guid-web", "web", "subtitle", isBrowser = true)

    private fun config(isBrowser: Boolean = false): StreamConfig = StreamConfig(
        streamID = 3, generationId = 1, codecExtradata = byteArrayOf(0, 0), pixelWidth = 1600, pixelHeight = 900,
        scale = 2.0, columns = 100, rows = 30, cellGeometry = null, firstAbsLine = 0, totalLines = 30,
        canResize = true, altScreen = false, scrollWheelReporting = false, isBrowser = isBrowser,
    )

    private fun active(guid: String = session.guid) =
        ActiveStream(linkId = 4, streamID = 3, sessionGuid = guid, watchEpoch = 9, config = config(), liveTop = 120)

    @Test
    fun liveTerminalTakesInputForItsActiveStream() {
        assertEquals(
            InputTarget(linkId = 4, watchEpoch = 9, sessionGuid = session.guid),
            inputTarget(session, LiveViewState.Live(session, config()), active()),
        )
        assertEquals(
            "a stalled picture is still the live session",
            InputTarget(4, 9, session.guid),
            inputTarget(session, LiveViewState.Live(session, config(), stalled = true), active()),
        )
    }

    @Test
    fun noInputUnlessLive() {
        val states = listOf(
            LiveViewState.Closed,
            LiveViewState.Starting(session),
            LiveViewState.Reconnecting(session),
            LiveViewState.Paused(session),
            LiveViewState.Ended(session, StreamEndReason.SESSION_CLOSED),
            LiveViewState.Failed(session, LiveProblem.NoDecoder),
        )
        for (state in states) assertNull("$state", inputTarget(session, state, active()))
    }

    @Test
    fun noInputForAnotherSessionOrWithoutTheStream() {
        val other = SessionSummary("guid-beta", "beta", "subtitle", isBrowser = false)
        assertNull(inputTarget(session, LiveViewState.Live(other, config()), active()))
        assertNull(inputTarget(session, LiveViewState.Live(session, config()), null))
        assertNull(inputTarget(session, LiveViewState.Live(session, config()), active(guid = other.guid)))
    }

    @Test
    fun browserSessionsGetNoKeyboard() {
        assertNull(inputTarget(browser, LiveViewState.Live(browser, config(isBrowser = true)), active(guid = browser.guid)))
        assertNull(
            "a browser config on a session listed as a terminal is a browser too",
            inputTarget(session, LiveViewState.Live(session, config(isBrowser = true)), active()),
        )
    }

    /** The button reads "Paste ⏎" either way; its accessibility label says what it will do. */
    @Test
    fun pasteReturnLabelFollowsTheDraft() {
        assertEquals("Type and Return", pasteReturnLabel("git status"))
        assertEquals("Paste", pasteReturnLabel("make test\ngit status"))
        assertEquals("Paste", pasteReturnLabel("echo a\r\necho b"))
    }
}
