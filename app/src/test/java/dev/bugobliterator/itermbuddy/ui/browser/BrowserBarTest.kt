package dev.bugobliterator.itermbuddy.ui.browser

import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The browser controls' enabled states, as iOS's `BrowserControlsBar` (`IOS/Screens/SessionView.swift:1130-1186`). */
class BrowserBarTest {
    private fun navigation(back: Boolean = false, forward: Boolean = false, loading: Boolean = false) =
        BrowserNavigationState("https://example.com", "Example", back, forward, loading)

    @Test
    fun backAndForwardFollowTheHistory() {
        assertEquals(
            BrowserControls(back = true, forward = false, showsStop = false, reloadOrStop = true, address = true),
            browserControls(navigation(back = true), active = true),
        )
        assertEquals(
            BrowserControls(back = false, forward = true, showsStop = false, reloadOrStop = true, address = true),
            browserControls(navigation(forward = true), active = true),
        )
    }

    @Test
    fun loadingOffersStopInsteadOfReload() {
        assertEquals(
            BrowserControls(back = false, forward = false, showsStop = true, reloadOrStop = true, address = true),
            browserControls(navigation(loading = true), active = true),
        )
    }

    @Test
    fun noNavigationYetDisablesReload() {
        assertEquals(
            BrowserControls(back = false, forward = false, showsStop = false, reloadOrStop = false, address = true),
            browserControls(null, active = true),
        )
    }

    @Test
    fun inactiveDisablesEverything() {
        // Ended, reconnecting or not yet a browser stream: the bar stays (no layout jump) but does nothing.
        assertEquals(
            BrowserControls(back = false, forward = false, showsStop = true, reloadOrStop = false, address = false),
            browserControls(navigation(back = true, forward = true, loading = true), active = false),
        )
        assertEquals(
            BrowserControls(back = false, forward = false, showsStop = false, reloadOrStop = false, address = false),
            browserControls(navigation(back = true, forward = true), active = false),
        )
    }
}
