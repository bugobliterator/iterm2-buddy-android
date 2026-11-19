package dev.bugobliterator.itermbuddy.core.sessions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Sessions list's poll of the session tree: its gates, its interval and one request at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTreePollerTest {
    /** A poll that counts its calls and the ones out; [hold] keeps each one out until completed. */
    private class FakePoll {
        var calls = 0
        var out = 0
        var mostOut = 0
        var hold: CompletableDeferred<Unit>? = null

        suspend fun poll() {
            calls++
            out++
            mostOut = maxOf(mostOut, out)
            try {
                hold?.await()
            } finally {
                out--
            }
        }
    }

    private fun TestScope.poller(fake: FakePoll) =
        SessionTreePoller(backgroundScope, fake::poll, interval = 4.seconds)

    @Test
    fun pollsEveryIntervalWhileVisibleForegroundAndConnected() = runTest {
        val fake = FakePoll()
        val poller = poller(fake)
        poller.setConnected(true)
        runCurrent()
        assertEquals("not visible yet", 0, fake.calls)
        poller.setVisible(true)
        runCurrent()
        assertEquals("the list appeared on a live link: one poll at once", 1, fake.calls)
        assertTrue(poller.polling)
        advanceTimeBy(4.seconds - 1.milliseconds)
        assertEquals(1, fake.calls)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, fake.calls)
        advanceTimeBy(8.seconds)
        assertEquals(4, fake.calls)
    }

    @Test
    fun leavingTheListStopsAtOnce() = runTest {
        val fake = FakePoll()
        val poller = poller(fake)
        poller.setConnected(true)
        poller.setVisible(true)
        runCurrent()
        advanceTimeBy(5.seconds)
        assertEquals(2, fake.calls)
        poller.setVisible(false)
        assertFalse(poller.polling)
        advanceTimeBy(60.seconds)
        assertEquals(2, fake.calls)
        // Back on the list: polls again, at once.
        poller.setVisible(true)
        runCurrent()
        assertEquals(3, fake.calls)
    }

    @Test
    fun backgroundStopsAndForegroundResumes() = runTest {
        val fake = FakePoll()
        val poller = poller(fake)
        poller.setConnected(true)
        poller.setVisible(true)
        runCurrent()
        poller.setForeground(false)
        assertFalse(poller.polling)
        advanceTimeBy(60.seconds)
        assertEquals(1, fake.calls)
        poller.setForeground(true)
        runCurrent()
        assertEquals("back in the foreground: one poll at once", 2, fake.calls)
    }

    @Test
    fun disconnectStopsAndAReconnectWaitsAnInterval() = runTest {
        val fake = FakePoll()
        val poller = poller(fake)
        poller.setConnected(true)
        poller.setVisible(true)
        runCurrent()
        poller.setConnected(false)
        assertFalse(poller.polling)
        advanceTimeBy(60.seconds)
        assertEquals(1, fake.calls)
        // The repository fetches on each connect itself: the poll waits an interval, not doubling it.
        poller.setConnected(true)
        runCurrent()
        assertEquals(1, fake.calls)
        advanceTimeBy(4.seconds + 1.milliseconds)
        assertEquals(2, fake.calls)
    }

    @Test
    fun appearingWhileDisconnectedWaitsForTheLinkAndThenAnInterval() = runTest {
        val fake = FakePoll()
        val poller = poller(fake)
        poller.setVisible(true)
        advanceTimeBy(60.seconds)
        assertEquals(0, fake.calls)
        poller.setConnected(true)
        runCurrent()
        assertEquals(0, fake.calls)
        advanceTimeBy(4.seconds + 1.milliseconds)
        assertEquals(1, fake.calls)
    }

    @Test
    fun neverMoreThanOnePollOutAndTheIntervalCountsFromItsEnd() = runTest {
        val fake = FakePoll()
        val hold = CompletableDeferred<Unit>()
        fake.hold = hold
        val poller = poller(fake)
        poller.setConnected(true)
        poller.setVisible(true)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals("the first poll is still out", 1, fake.calls)
        fake.hold = null
        hold.complete(Unit)
        runCurrent()
        assertEquals(1, fake.calls)
        advanceTimeBy(4.seconds - 1.milliseconds)
        assertEquals(1, fake.calls)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, fake.calls)
        assertEquals(1, fake.mostOut)
    }

    @Test
    fun flappingGatesNeverStartASecondPoll() = runTest {
        val fake = FakePoll()
        fake.hold = CompletableDeferred()
        val poller = poller(fake)
        poller.setConnected(true)
        repeat(5) {
            poller.setVisible(true)
            runCurrent()
            poller.setForeground(false)
            poller.setForeground(true)
            runCurrent()
            poller.setVisible(false)
            runCurrent()
        }
        assertEquals(1, fake.mostOut)
    }

    @Test
    fun toStringShowsGatesOnly() = runTest {
        val poller = poller(FakePoll())
        poller.setConnected(true)
        assertEquals(
            "SessionTreePoller(visible=false, foreground=true, connected=true, polling=false)",
            poller.toString(),
        )
    }
}
