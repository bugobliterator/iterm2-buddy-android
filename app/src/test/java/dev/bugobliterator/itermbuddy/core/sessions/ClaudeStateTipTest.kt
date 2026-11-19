package dev.bugobliterator.itermbuddy.core.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sessions list's one-line tip about `\(status)`: when it shows, and what is remembered. */
class ClaudeStateTipTest {
    @Test
    fun showsOnlyAfterAConnect() {
        val tip = ClaudeStateTip(InMemoryClaudeTipStore())
        assertFalse(tip.shown.value)
        tip.connected()
        assertTrue(tip.shown.value)
    }

    @Test
    fun aDismissalIsPersisted() {
        val store = InMemoryClaudeTipStore()
        val tip = ClaudeStateTip(store)
        tip.connected()
        tip.dismiss()
        assertFalse(tip.shown.value)
        assertTrue(store.dismissed())

        // The next process reads it back.
        val next = ClaudeStateTip(store)
        next.connected()
        assertFalse(next.shown.value)
    }

    @Test
    fun aSeenStatusDismissesForGood() {
        val store = InMemoryClaudeTipStore()
        val tip = ClaudeStateTip(store)
        tip.statusSeen()
        assertTrue(store.dismissed())
        tip.connected()
        assertFalse(tip.shown.value)
    }

    @Test
    fun clearForgetsTheDismissalAndTheConnect() {
        val store = InMemoryClaudeTipStore()
        val tip = ClaudeStateTip(store)
        tip.connected()
        tip.dismiss()
        tip.clear()
        assertFalse(store.dismissed())
        assertFalse("not connected on the new pairing yet", tip.shown.value)
        tip.connected()
        assertTrue(tip.shown.value)
    }

    @Test
    fun toStringShowsFlagsOnly() {
        val tip = ClaudeStateTip(InMemoryClaudeTipStore())
        tip.connected()
        assertEquals("ClaudeStateTip(connectedOnce=true, dismissed=false)", tip.toString())
    }
}
