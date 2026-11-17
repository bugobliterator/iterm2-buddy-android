package dev.bugobliterator.itermbuddy.core.sessions

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unpairing, from this phone or from the Mac, leaves nothing of the old pairing behind: the
 * tree, the navigation and the watch, through the same wiring `CompanionApp` uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnpairCleanupTest {
    @Test
    fun localAndRemoteUnpairClearEverything() {
        unpairClearsEverything(fromMac = false)
        unpairClearsEverything(fromMac = true)
    }

    private fun unpairClearsEverything(fromMac: Boolean) = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        val loaded = rig.repository.state.value as TreeState.Loaded
        val session = loaded.tree.windows.first().tabs.last().panes.single().session
        rig.navigator.openSession(session)
        runCurrent()
        assertEquals(HomeDestination.LiveSession(session), rig.navigator.destination.value)
        assertTrue(rig.controller.state.value is LiveViewState.Live)
        assertNotNull(rig.controller.activeStream.value)

        if (fromMac) {
            rig.mac.sendUnpaired()
            withTimeout(1.minutes) { rig.client.state.first { it is ClientState.Failed } }
        } else {
            rig.client.unpair()
        }
        runCurrent()
        assertEquals(false, rig.client.paired.value)
        assertEquals(TreeState.Loading, rig.repository.state.value)
        assertEquals(HomeDestination.SessionList, rig.navigator.destination.value)
        assertEquals(LiveViewState.Closed, rig.controller.state.value)
        assertNull(rig.controller.activeStream.value)

        // Pair again, with another Mac: it connects, and nothing of the old watch starts.
        val keys = newStaticKeyPair()
        val code = PairingCode(keys.second, "sessions-rig-second-pid", SessionsRig.ORIGIN)
        val second = FakeMac(rig.relay, code, keys.first, backgroundScope)
        rig.answerFetches(second, SessionsRig.PUSH)
        second.start()
        rig.client.pair(code)
        withTimeout(1.minutes) { second.awaitingCode.first { it != null } }
        second.enterCode(accept = true)
        withTimeout(1.minutes) { rig.client.state.first { it is ClientState.Connected } }
        advanceTimeBy(30.seconds)
        assertEquals(true, rig.client.paired.value)
        assertEquals(emptyList<String>(), second.receivedCases().filter { it == "startSessionStream" })
        assertEquals(LiveViewState.Closed, rig.controller.state.value)
        assertEquals(TreeState.Loaded(SessionsRig.treeOf(SessionsRig.PUSH), refreshing = false), rig.repository.state.value)
    }

    @Test
    fun everyCleanupRunsEachTimePairedTurnsFalse() = runTest {
        val paired = MutableStateFlow<Boolean?>(null)
        val ran = CopyOnWriteArrayList<String>()
        clearOnUnpair(paired, backgroundScope, listOf({ ran += "navigator" }, { ran += "repository" }))
        runCurrent()
        assertEquals(emptyList<String>(), ran)
        paired.value = true
        runCurrent()
        assertEquals(emptyList<String>(), ran)
        paired.value = false
        runCurrent()
        assertEquals(listOf("navigator", "repository"), ran)
        paired.value = true
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals(listOf("navigator", "repository", "navigator", "repository"), ran)
    }

    @Test
    fun aThrowingCleanupDoesNotSkipTheOthers() = runTest {
        val paired = MutableStateFlow<Boolean?>(true)
        val ran = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        clearOnUnpair(paired, backgroundScope, listOf({ error("boom") }, { ran += "repository" }), log = { logs += it })
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals(listOf("repository"), ran)
        assertEquals(1, logs.size)
        paired.value = true
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals("still collecting after a failure", listOf("repository", "repository"), ran)
    }
}
