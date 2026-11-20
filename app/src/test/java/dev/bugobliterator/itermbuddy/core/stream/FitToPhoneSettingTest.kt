package dev.bugobliterator.itermbuddy.core.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The live bar's Fit to phone toggle: off by default, persisted, off again on unpair (the shared StoredSwitch). */
@OptIn(ExperimentalCoroutinesApi::class)
class FitToPhoneSettingTest {
    @Test
    fun offByDefaultPersistedAndClearedOnUnpair() = runTest {
        val store = InMemoryFitToPhoneStore()
        val setting = FitToPhoneSetting(store, backgroundScope, EmptyCoroutineContext)
        assertFalse(FitToPhoneSetting.DEFAULT)
        runCurrent()
        assertFalse(setting.enabled.value)
        assertNull("the default is not written", store.enabled())

        setting.set(true)
        runCurrent()
        assertEquals(true, store.enabled())
        val reopened = FitToPhoneSetting(store, backgroundScope, EmptyCoroutineContext)
        runCurrent()
        assertTrue(reopened.enabled.value)

        reopened.clear()
        runCurrent()
        assertFalse(reopened.enabled.value)
        assertNull(store.enabled())
    }
}
