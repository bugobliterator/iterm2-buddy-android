package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Navigation, not composition, drives the stream: only navigator calls open and close it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionNavigatorTest {
    private val opened = CopyOnWriteArrayList<SessionSummary>()
    private var closes = 0
    private val navigator = SessionNavigator(open = { opened += it }, close = { closes++ })

    @Test
    fun openAndBackDriveController() {
        navigator.openSession(TERMINAL)
        assertEquals(listOf(TERMINAL), opened)
        assertEquals(0, closes)
        assertEquals(HomeDestination.LiveSession(TERMINAL), navigator.destination.value)
        navigator.back()
        assertEquals(1, closes)
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // Back on the list does nothing more.
        navigator.back()
        assertEquals(1, closes)
        assertEquals(listOf(TERMINAL), opened)
    }

    @Test
    fun browserRowsOpenTheLiveView() {
        navigator.openSession(BROWSER)
        assertEquals(listOf(BROWSER), opened)
        assertEquals(HomeDestination.LiveSession(BROWSER), navigator.destination.value)
    }

    @Test
    fun settingsAndBackNeverTouchTheStream() {
        navigator.openSettings()
        assertEquals(HomeDestination.Settings, navigator.destination.value)
        navigator.back()
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        assertEquals(0, closes)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun recreatingTheUiDoesNotRestart() = runTest {
        navigator.openSession(TERMINAL)
        val seen = CopyOnWriteArrayList<HomeDestination>()
        val first = backgroundScope.launch { navigator.destination.collect { seen += it } }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = backgroundScope.launch { navigator.destination.collect { seen += it } }
        runCurrent()
        second.cancel()
        assertEquals(listOf<HomeDestination>(HomeDestination.LiveSession(TERMINAL), HomeDestination.LiveSession(TERMINAL)), seen.toList())
        assertEquals(listOf(TERMINAL), opened)
        assertEquals(0, closes)
    }

    @Test
    fun resetClosesStream() {
        navigator.openSession(TERMINAL)
        navigator.reset()
        assertEquals(1, closes)
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        navigator.openSettings()
        navigator.reset()
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
    }

    @Test
    fun screenKeptOnOnlyInSessionViews() {
        assertTrue(keepsScreenOn(HomeDestination.LiveSession(TERMINAL)))
        assertTrue(keepsScreenOn(HomeDestination.BrowserSession(BROWSER)))
        assertFalse(keepsScreenOn(HomeDestination.SessionList))
        assertFalse(keepsScreenOn(HomeDestination.Settings))
    }

    @Test
    fun destinationToStringHidesNames() {
        val text = HomeDestination.LiveSession(SessionSummary("ptys_1", SENTINEL, SENTINEL, false)).toString()
        assertFalse(text, text.contains(SENTINEL))
    }

    private companion object {
        const val SENTINEL = "SENTINEL-secret-name"
        val TERMINAL = SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget", false)
        val BROWSER = SessionSummary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", true)
    }
}
