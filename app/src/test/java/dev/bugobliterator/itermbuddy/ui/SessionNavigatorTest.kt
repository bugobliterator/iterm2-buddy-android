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
    private var macRevision = 15
    private val navigator = SessionNavigator(open = { opened += it }, close = { closes++ }, macRevision = { macRevision })

    @Test
    fun openAndBackDriveController() {
        navigator.openSession(TERMINAL)
        assertEquals(listOf(TERMINAL), opened)
        assertEquals(0, closes)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.destination.value)
        navigator.back()
        assertEquals(1, closes)
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // Back on the list does nothing more.
        navigator.back()
        assertEquals(1, closes)
        assertEquals(listOf(TERMINAL), opened)
    }

    @Test
    fun browserRowOpensBrowserAtRevisionFifteen() {
        navigator.openSession(BROWSER)
        assertEquals(listOf(BROWSER), opened)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.back()
        assertEquals(1, closes)
        // Below 15 the Mac takes no browser input: the page is watched in the live view.
        macRevision = 14
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.destination.value)
        // A terminal row is always the live view.
        macRevision = 15
        navigator.openSession(TERMINAL)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.destination.value)
        assertEquals(listOf(BROWSER, BROWSER, TERMINAL), opened)
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

    /**
     * A contract test only: reading [SessionNavigator.destination] from a new collector (as a
     * recreated Activity does) opens and closes nothing. It cannot drive real composition or a
     * rotation; the owner's on-device rotation check is the proof that rotation neither stops
     * nor restarts the stream.
     */
    @Test
    fun newCollectorsOfDestinationNeitherOpenNorClose() = runTest {
        navigator.openSession(TERMINAL)
        val seen = CopyOnWriteArrayList<HomeDestination>()
        val first = backgroundScope.launch { navigator.destination.collect { seen += it } }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = backgroundScope.launch { navigator.destination.collect { seen += it } }
        runCurrent()
        second.cancel()
        assertEquals(listOf<HomeDestination>(HomeDestination.LiveSession(TERMINAL, "zsh"), HomeDestination.LiveSession(TERMINAL, "zsh")), seen.toList())
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
        assertTrue(keepsScreenOn(HomeDestination.LiveSession(TERMINAL, "zsh")))
        assertTrue(keepsScreenOn(HomeDestination.BrowserSession(BROWSER, "Widget Docs")))
        assertFalse(keepsScreenOn(HomeDestination.SessionList))
        assertFalse(keepsScreenOn(HomeDestination.Settings))
    }

    @Test
    fun peerRowTitleIsTheViewTitle() {
        navigator.openSession(PLANNER, "Planner: claude")
        assertEquals(HomeDestination.LiveSession(PLANNER, "Planner: claude"), navigator.destination.value)
        assertEquals(listOf(PLANNER), opened)
    }

    @Test
    fun destinationToStringHidesNames() {
        val summary = SessionSummary("ptys_1", SENTINEL, SENTINEL, false)
        for (destination in listOf(HomeDestination.LiveSession(summary, SENTINEL), HomeDestination.BrowserSession(summary, SENTINEL))) {
            val text = destination.toString()
            assertFalse(text, text.contains(SENTINEL))
        }
    }

    private companion object {
        const val SENTINEL = "SENTINEL-secret-name"
        val TERMINAL = SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget", false)
        val PLANNER = SessionSummary("ptys_1F5Y7R3MQ9KD4", "claude", "planning", false)
        val BROWSER = SessionSummary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", true)
    }
}
