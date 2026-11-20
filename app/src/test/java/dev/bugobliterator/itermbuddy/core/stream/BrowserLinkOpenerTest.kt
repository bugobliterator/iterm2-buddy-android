package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Mac-local links into a Browser session, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserLinkOpenerTest {
    private class Rig(test: TestScope) {
        val active = MutableStateFlow<ActiveStream?>(ActiveStream(1, 1, "guid-terminal", 1, null, 0))
        val inputActive = MutableStateFlow(false)
        val loads = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val opener = BrowserLinkOpener(active, inputActive, { loads += it }, test.backgroundScope, waitBound = 10.seconds, log = { logs += it })

        fun browserLive(guid: String, streamID: Long = 2) {
            active.value = ActiveStream(1, streamID, guid, 2, browserConfig(streamID), null)
            inputActive.value = true
        }
    }

    @Test
    fun theLinkLoadsOnceTheBrowserStreamTakesInput() = runTest {
        val rig = Rig(this)
        rig.opener.open("guid-browser", URL)
        runCurrent()
        assertTrue("not before its stream", rig.loads.isEmpty())
        // Another browser session's stream does not take it.
        rig.browserLive("guid-other")
        runCurrent()
        assertTrue(rig.loads.isEmpty())
        // The stream is up but its config not yet a browser's / input not yet active.
        rig.inputActive.value = false
        rig.active.value = ActiveStream(1, 3, "guid-browser", 3, null, null)
        runCurrent()
        assertTrue(rig.loads.isEmpty())
        rig.browserLive("guid-browser", streamID = 3)
        runCurrent()
        assertEquals(listOf(URL), rig.loads.toList())
        // Once only.
        rig.inputActive.value = false
        runCurrent()
        rig.inputActive.value = true
        runCurrent()
        assertEquals(1, rig.loads.size)
        assertTrue("never the URL in the log: ${rig.logs}", rig.logs.none { "localhost" in it })
    }

    @Test
    fun aLinkNoStreamTakesIsDroppedAfterTheBound() = runTest {
        val rig = Rig(this)
        rig.opener.open("guid-browser", URL)
        advanceTimeBy(10_001)
        rig.browserLive("guid-browser")
        runCurrent()
        assertTrue(rig.loads.isEmpty())
        assertTrue(rig.logs.any { "dropped" in it })
    }

    @Test
    fun aNewerLinkReplacesTheWaitingOneAndCancelDropsIt() = runTest {
        val rig = Rig(this)
        rig.opener.open("guid-browser", URL)
        rig.opener.open("guid-browser", "http://127.0.0.1:9000/")
        rig.browserLive("guid-browser")
        runCurrent()
        assertEquals(listOf("http://127.0.0.1:9000/"), rig.loads.toList())
        rig.inputActive.value = false
        rig.opener.open("guid-browser", URL)
        rig.opener.cancel()
        rig.inputActive.value = true
        runCurrent()
        assertEquals(1, rig.loads.size)
    }

    @Test
    fun theMostRecentlyViewedBrowserSessionIsChosen() = runTest {
        val rig = Rig(this)
        val sessions = listOf(terminal("t1"), browser("b1"), terminal("t2"), browser("b2"), browser("b3"))
        assertEquals("none viewed: the first in the tree", "b1", rig.opener.target(sessions)?.guid)
        rig.opener.shown("b2")
        rig.opener.shown("b3")
        assertEquals("b3", rig.opener.target(sessions)?.guid)
        rig.opener.shown("b2")
        assertEquals("b2", rig.opener.target(sessions)?.guid)
        // Gone from the tree: the next most recent.
        assertEquals("b3", rig.opener.target(sessions.filter { it.guid != "b2" })?.guid)
        // A recent GUID that is not a browser (or not there) is skipped.
        assertEquals("b1", chooseLinkBrowserSession(sessions, listOf("t1", "gone"))?.guid)
        assertNull("no browser session", rig.opener.target(listOf(terminal("t1"))))
        assertNull(chooseLinkBrowserSession(emptyList(), listOf("b1")))
    }

    private companion object {
        const val URL = "http://localhost:3000/"

        fun terminal(guid: String) = SessionSummary(guid, "name-$guid", "", isBrowser = false)

        fun browser(guid: String) = SessionSummary(guid, "name-$guid", "", isBrowser = true)

        fun browserConfig(streamID: Long) = StreamConfig(streamID, 1, ByteArray(0), 800, 600, 2.0, 0, 0, null, 0, 0, null, false, false, isBrowser = true)
    }
}
