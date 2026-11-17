package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.input.ModifierKey
import dev.bugobliterator.itermbuddy.core.input.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-scoped keyboard state the live view reads back after rotation recreates it. */
class LiveInputMemoryTest {
    @Test
    fun armedCtrlTrayAndRestoreFlagSurviveARecreatedView() {
        val memories = LiveInputMemories()
        // The first view arms ctrl, expands the tray and loses input with the keyboard up.
        val before = memories.of("guid-alpha")
        var redraws = 0
        before.modifiers.onChange = { redraws++ }
        before.modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        before.expanded = true
        before.restoreKeyboard = true
        assertEquals(1, redraws)
        // Rotation: the new view asks again and gets the same state; it takes over the redraws.
        before.modifiers.onChange = {}
        val after = memories.of("guid-alpha")
        assertSame(before, after)
        var newRedraws = 0
        after.modifiers.onChange = { newRedraws++ }
        assertEquals(ModifierState.ONE_SHOT, after.modifiers.state(ModifierKey.CONTROL))
        assertTrue(after.expanded)
        assertTrue(after.restoreKeyboard)
        assertTrue("the armed ctrl applies to the next key", after.modifiers.consume().control)
        assertEquals(1, newRedraws)
        assertEquals(1, redraws)
    }

    @Test
    fun sessionsAreSeparateAndUnpairClearsThem() {
        val memories = LiveInputMemories()
        memories.of("guid-alpha").expanded = true
        assertFalse(memories.of("guid-bravo").expanded)
        val old = memories.of("guid-alpha")
        memories.clear()
        assertNotSame(old, memories.of("guid-alpha"))
        assertFalse(memories.of("guid-alpha").expanded)
    }
}
