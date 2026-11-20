package dev.bugobliterator.itermbuddy.core.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** Settings' "Send / * + = as paste" switch: its default, what is stored, and unpair. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteKeysSettingTest {
    /** Counts its reads and writes, so a test can tell they happen later, on the setting's own context. */
    private class CountingStore(initial: Boolean? = null) : PasteKeysStore {
        @Volatile var value = initial
        @Volatile var reads = 0
        @Volatile var writes = 0

        override fun enabled(): Boolean? {
            reads++
            return value
        }

        override fun setEnabled(enabled: Boolean?) {
            writes++
            value = enabled
        }
    }

    /** A setting whose store work runs on the test's scheduler (the app passes Dispatchers.IO). */
    private fun TestScope.setting(store: PasteKeysStore) = PasteKeysSetting(store, backgroundScope, EmptyCoroutineContext)

    @Test
    fun onByDefault() = runTest {
        val store = CountingStore()
        val setting = setting(store)
        assertTrue(setting.enabled.value)
        assertEquals("nothing read while constructing", 0, store.reads)
        runCurrent()
        assertEquals(1, store.reads)
        assertTrue(setting.enabled.value)
        assertEquals("the default is not written", 0, store.writes)
        assertTrue(PasteKeysSetting.DEFAULT)
    }

    @Test
    fun aStoredValueIsRead() = runTest {
        val setting = setting(CountingStore(initial = false))
        assertTrue("the default until the read", setting.enabled.value)
        runCurrent()
        assertFalse(setting.enabled.value)
    }

    @Test
    fun aChangeIsPersistedAndReadBack() = runTest {
        val store = CountingStore()
        val setting = setting(store)
        runCurrent()
        setting.set(false)
        assertFalse(setting.enabled.value)
        assertEquals("written off the caller's thread", 0, store.writes)
        runCurrent()
        assertEquals(false, store.value)

        val next = setting(store)
        runCurrent()
        assertFalse(next.enabled.value)
    }

    @Test
    fun aChangeBeforeTheReadWins() = runTest {
        val store = CountingStore(initial = true)
        val setting = setting(store)
        setting.set(false)
        runCurrent()
        assertFalse(setting.enabled.value)
        assertEquals(false, store.value)
    }

    @Test
    fun writesAreInOrder() = runTest {
        val store = CountingStore()
        val setting = setting(store)
        setting.set(false)
        setting.set(true)
        setting.set(false)
        runCurrent()
        assertEquals(false, store.value)
        assertEquals(3, store.writes)
    }

    @Test
    fun unpairForgetsIt() = runTest {
        val store = CountingStore(initial = false)
        val setting = setting(store)
        runCurrent()
        assertFalse(setting.enabled.value)
        setting.clear()
        assertTrue(setting.enabled.value)
        runCurrent()
        assertNull("the stored value is removed", store.value)
    }

    @Test
    fun inMemoryStoreHoldsTheValue() {
        val store = InMemoryPasteKeysStore()
        assertNull(store.enabled())
        store.setEnabled(false)
        assertEquals(false, store.enabled())
        store.setEnabled(null)
        assertNull(store.enabled())
    }
}
