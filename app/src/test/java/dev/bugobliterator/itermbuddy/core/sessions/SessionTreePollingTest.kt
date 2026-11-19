package dev.bugobliterator.itermbuddy.core.sessions

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.REPLY
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.treeOf
import dev.bugobliterator.itermbuddy.ui.HomeTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The Sessions list's poll through the app's wiring: iTerm2 pushes no tree when a subtitle
 * changes, so while the list is the destination shown, the app is in the foreground and the
 * client is connected, the tree is fetched again every interval (4 s), through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTreePollingTest {
    @Test
    fun pollsOnlyWhileTheSessionListIsShown() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        assertEquals("the connect's own fetch", 1, rig.fetches())
        advanceTimeBy(20.seconds)
        assertEquals("the Chats tab shows: no poll", 1, rig.fetches())

        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        assertEquals("the list appeared: one fetch at once", 2, rig.fetches())
        advanceTimeBy(4.5.seconds)
        assertEquals(3, rig.fetches())
        advanceTimeBy(4.seconds)
        assertEquals(4, rig.fetches())

        // A session view pushed over the list: the list is not shown.
        val session = (rig.repository.state.value as TreeState.Loaded).tree.windows.first().tabs.last().panes.single().session
        rig.navigator.openSession(session, session.name)
        runCurrent()
        advanceTimeBy(20.seconds)
        assertEquals(4, rig.fetches())

        rig.navigator.back()
        runCurrent()
        assertEquals(5, rig.fetches())

        rig.graph.setForeground(false)
        advanceTimeBy(20.seconds)
        assertEquals("backgrounded: no poll", 5, rig.fetches())
        rig.graph.setForeground(true)
        runCurrent()
        assertEquals(6, rig.fetches())

        rig.navigator.selectTab(HomeTab.CHATS)
        advanceTimeBy(20.seconds)
        assertEquals("left the list", 6, rig.fetches())
    }

    @Test
    fun aPolledSubtitleChangeReachesTheTree() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        rig.mac.onRequest("fetchSessionTree") { payloadWithPlannerSubtitle("working") }
        advanceTimeBy(4.5.seconds)
        val tree = (rig.repository.state.value as TreeState.Loaded).tree
        val planner = tree.windows.first().tabs[1].panes.single().peers.first().session
        assertEquals(ClaudeSessionState.WORKING, claudeStateFromSubtitle(planner.subtitle))
    }

    @Test
    fun anUnchangedTreeNeitherFlashesLoadingNorEmitsAgain() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        val seen = CopyOnWriteArrayList<TreeState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.repository.state.collect { seen += it } }
        rig.connect()
        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertTrue(rig.fetches() >= 8)
        assertEquals(listOf(TreeState.Loading, TreeState.Loaded(treeOf(REPLY), refreshing = false)), seen.toList())
    }

    @Test
    fun neverMoreThanOneFetchOut() = runTest {
        val rig = SessionsRig(this)
        // The Mac never answers: each fetch times out after 15 s.
        rig.mac.onRequest("fetchSessionTree") { null }
        rig.connect()
        runCurrent()
        assertEquals(1, rig.fetches())
        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        assertEquals("the connect's fetch is still out: the poll waits for it", 1, rig.fetches())
        // Flapping the list and the foreground while it is out sends nothing more.
        repeat(3) {
            rig.navigator.selectTab(HomeTab.CHATS)
            runCurrent()
            rig.navigator.selectTab(HomeTab.SESSIONS)
            runCurrent()
            rig.graph.setForeground(false)
            rig.graph.setForeground(true)
            runCurrent()
        }
        advanceTimeBy(14.seconds)
        assertEquals(1, rig.fetches())
        // It times out at 15 s; the next goes an interval later.
        advanceTimeBy(1.seconds + 4.5.seconds)
        assertEquals(2, rig.fetches())
        assertEquals(TreeState.Failed(retryable = true), rig.repository.state.value)
        advanceTimeBy(14.seconds)
        assertEquals(2, rig.fetches())
        assertEquals("a failing poll shows no Loading", TreeState.Failed(retryable = true), rig.repository.state.value)
    }

    @Test
    fun theIntervalIsInjectable() = runTest {
        val rig = SessionsRig(this, sessionPollInterval = 10.seconds)
        rig.answerFetches()
        rig.connect()
        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        assertEquals(2, rig.fetches())
        advanceTimeBy(9.seconds)
        assertEquals(2, rig.fetches())
        advanceTimeBy(1.5.seconds)
        assertEquals(3, rig.fetches())
    }

    @Test
    fun theTipShowsAfterAConnectUntilDismissedOrAStatusIsSeen() = runTest {
        val store = InMemoryClaudeTipStore()
        val rig = SessionsRig(this, claudeTipStore = store)
        rig.answerFetches()
        assertFalse("never connected", rig.graph.claudeTip.shown.value)
        rig.connect()
        runCurrent()
        assertTrue(rig.graph.claudeTip.shown.value)

        // A status in a polled tree: Claude Code's state is set up, the tip goes for good.
        rig.mac.onRequest("fetchSessionTree") { payloadWithPlannerSubtitle("idle") }
        rig.navigator.selectTab(HomeTab.SESSIONS)
        runCurrent()
        assertFalse(rig.graph.claudeTip.shown.value)
        assertTrue(store.dismissed())

        // Unpair clears the dismissal; the next pairing shows the tip once it connects.
        rig.client.unpair()
        runCurrent()
        assertFalse(store.dismissed())
        assertFalse(rig.graph.claudeTip.shown.value)
    }

    private fun payloadWithPlannerSubtitle(subtitle: String): JsonObject {
        val text = Fixtures.read(REPLY).replace("\"subtitle\":\"planning\"", "\"subtitle\":\"$subtitle\"")
        check(text != Fixtures.read(REPLY)) { "the fixture's planner subtitle moved" }
        return Json.parseToJsonElement(text).jsonObject.getValue("json").jsonObject.getValue("payload").jsonObject
    }
}
