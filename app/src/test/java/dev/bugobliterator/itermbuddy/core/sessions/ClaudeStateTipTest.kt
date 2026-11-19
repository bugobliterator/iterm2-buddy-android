package dev.bugobliterator.itermbuddy.core.sessions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The Sessions list's one-line tip about `\(status)`: when it shows, and what is remembered. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeStateTipTest {
    /** Counts its reads, so a test can tell the read happens later, on the tip's own context. */
    private class CountingStore(initial: Boolean = false) : ClaudeTipStore {
        @Volatile var value = initial
        @Volatile var reads = 0

        override fun dismissed(): Boolean {
            reads++
            return value
        }

        override fun setDismissed(dismissed: Boolean) {
            value = dismissed
        }
    }

    /** A tip whose store work runs on the test's scheduler (the app passes Dispatchers.IO). */
    private fun TestScope.tip(store: ClaudeTipStore) = ClaudeStateTip(store, backgroundScope, EmptyCoroutineContext)

    @Test
    fun theStoreIsNotReadByTheCaller() = runTest {
        val store = CountingStore()
        val tip = tip(store)
        tip.offer()
        assertEquals("nothing read while constructing", 0, store.reads)
        assertFalse("not shown until the dismissal is loaded", tip.shown.value)
        runCurrent()
        assertEquals(1, store.reads)
        assertTrue(tip.shown.value)
    }

    @Test
    fun showsOnlyOnceOffered() = runTest {
        val tip = tip(CountingStore())
        runCurrent()
        assertFalse(tip.shown.value)
        tip.offer()
        assertTrue(tip.shown.value)
    }

    @Test
    fun aDismissalIsPersisted() = runTest {
        val store = CountingStore()
        val tip = tip(store)
        runCurrent()
        tip.offer()
        tip.dismiss()
        assertFalse(tip.shown.value)
        runCurrent()
        assertTrue(store.value)

        // The next process reads it back.
        val next = tip(store)
        next.offer()
        runCurrent()
        assertFalse(next.shown.value)
    }

    @Test
    fun aSeenStatusDismissesForGood() = runTest {
        val store = CountingStore()
        val tip = tip(store)
        tip.statusSeen()
        tip.offer()
        runCurrent()
        assertTrue(store.value)
        assertFalse(tip.shown.value)
    }

    @Test
    fun aChangeBeforeTheLoadWinsOverTheStoredValue() = runTest {
        val store = CountingStore(initial = true)
        val tip = tip(store)
        tip.clear()
        tip.offer()
        runCurrent()
        assertFalse("clear wrote over the stored dismissal", store.value)
        assertTrue(tip.shown.value)
    }

    @Test
    fun clearForgetsTheDismissalAndTheOffer() = runTest {
        val store = CountingStore()
        val tip = tip(store)
        runCurrent()
        tip.offer()
        tip.dismiss()
        runCurrent()
        tip.clear()
        runCurrent()
        assertFalse(store.value)
        assertFalse("not offered on the new pairing yet", tip.shown.value)
        tip.offer()
        assertTrue(tip.shown.value)
    }

    @Test
    fun toStringShowsFlagsOnly() = runTest {
        val tip = tip(CountingStore())
        tip.offer()
        assertEquals("ClaudeStateTip(loaded=false, offered=true, dismissed=false)", tip.toString())
    }
}
