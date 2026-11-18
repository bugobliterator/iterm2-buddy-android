package dev.bugobliterator.itermbuddy.ui.browser

import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionRulesTest {
    @Test
    fun editingRaisesAndLowersTheKeyboardOnChangeOnly() {
        val rule = BrowserKeyboardRule()
        assertEquals(KeyboardMove.SHOW, rule.onEditing(editing = true, active = true))
        assertNull("no change, no move", rule.onEditing(editing = true, active = true))
        assertEquals(KeyboardMove.HIDE, rule.onEditing(editing = false, active = true))
        assertNull(rule.onEditing(editing = false, active = true))
    }

    @Test
    fun editingBeforeBrowserInputIsAppliedOnceItIsActive() {
        // The Mac reports editing before the first config says browser (IOS/Screens/SessionView.swift:2208-2213).
        val rule = BrowserKeyboardRule()
        assertNull(rule.onEditing(editing = true, active = false))
        assertEquals(KeyboardMove.SHOW, rule.onEditing(editing = true, active = true))
    }

    @Test
    fun tapReshowsTheKeyboardOnlyWhileEditing() {
        // Tapping the focused field again makes no editing change (IOS/Screens/SessionView.swift:2200-2205).
        val rule = BrowserKeyboardRule()
        assertTrue(rule.onTap(editing = true, active = true))
        assertFalse(rule.onTap(editing = false, active = true))
        assertFalse(rule.onTap(editing = true, active = false))
    }

    @Test
    fun inputGoesToTheLiveBrowserStreamOnly() {
        val stream = ActiveStream(linkId = 3, streamID = 9, sessionGuid = BROWSER.guid, watchEpoch = 4, config = BROWSER_CONFIG, liveTop = null)
        val live = LiveViewState.Live(BROWSER, BROWSER_CONFIG)
        assertEquals(InputTarget(3, 4, BROWSER.guid), browserInputTarget(BROWSER, live, stream, inputActive = true))
        assertNull("below revision 15", browserInputTarget(BROWSER, live, stream, inputActive = false))
        assertNull("not live", browserInputTarget(BROWSER, LiveViewState.Starting(BROWSER), stream, inputActive = true))
        assertNull("another session's stream", browserInputTarget(BROWSER, live, stream.copy(sessionGuid = "other"), inputActive = true))
        assertNull("a terminal config", browserInputTarget(BROWSER, LiveViewState.Live(BROWSER, BROWSER_CONFIG.copy(isBrowser = false)), stream, inputActive = true))
    }

    private companion object {
        val BROWSER = SessionSummary("ptys_browser", "Docs", "", isBrowser = true)
        val BROWSER_CONFIG = StreamConfig(
            streamID = 9, generationId = 1, codecExtradata = byteArrayOf(0, 0), pixelWidth = 1600, pixelHeight = 1200,
            scale = 2.0, columns = 0, rows = 0, cellGeometry = null, firstAbsLine = 0, totalLines = 0,
            canResize = false, altScreen = false, scrollWheelReporting = false, isBrowser = true,
        )
    }
}
