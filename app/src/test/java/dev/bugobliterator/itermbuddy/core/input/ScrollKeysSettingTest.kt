package dev.bugobliterator.itermbuddy.core.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** Settings' "Scroll web pages with keys" switch: on by default, persisted, forgotten on unpair (the shared [StoredSwitch]). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollKeysSettingTest {
    @Test
    fun onByDefaultPersistedAndClearedOnUnpair() = runTest {
        val store = InMemoryScrollKeysStore()
        val setting = ScrollKeysSetting(store, backgroundScope, EmptyCoroutineContext)
        assertTrue(ScrollKeysSetting.DEFAULT)
        runCurrent()
        assertTrue(setting.enabled.value)
        assertNull("the default is not written", store.enabled())

        setting.set(false)
        runCurrent()
        assertEquals(false, store.enabled())
        val reopened = ScrollKeysSetting(store, backgroundScope, EmptyCoroutineContext)
        runCurrent()
        assertFalse(reopened.enabled.value)

        reopened.clear()
        runCurrent()
        assertTrue(reopened.enabled.value)
        assertNull(store.enabled())
    }

    @Test
    fun theSwitchesKeepTheirOwnValues() = runTest {
        // Both live in the same preferences file under different keys; in memory, separate stores.
        val paste = PasteKeysSetting(InMemoryPasteKeysStore(), backgroundScope, EmptyCoroutineContext)
        val scroll = ScrollKeysSetting(InMemoryScrollKeysStore(), backgroundScope, EmptyCoroutineContext)
        runCurrent()
        scroll.set(false)
        assertTrue(paste.enabled.value)
        assertFalse(scroll.enabled.value)
        assertEquals("ScrollKeysSetting(enabled=false, loaded=true)", scroll.toString())
    }
}
