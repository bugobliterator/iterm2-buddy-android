package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.core.input.ModifierKey.CONTROL
import dev.bugobliterator.itermbuddy.core.input.ModifierKey.LEFT_OPTION
import dev.bugobliterator.itermbuddy.core.input.ModifierKey.RIGHT_OPTION
import dev.bugobliterator.itermbuddy.core.input.ModifierState.LOCKED
import dev.bugobliterator.itermbuddy.core.input.ModifierState.OFF
import dev.bugobliterator.itermbuddy.core.input.ModifierState.ONE_SHOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyModifiersTest {
    private var changes = 0
    private val modifiers = StickyModifiers(onChange = { changes++ })

    @Test
    fun tapIsOneShotDoubleTapLocks() {
        modifiers.tap(CONTROL, nowMillis = 1_000)
        assertEquals(ONE_SHOT, modifiers.state(CONTROL))
        modifiers.tap(CONTROL, nowMillis = 1_349)
        assertEquals(LOCKED, modifiers.state(CONTROL))
        modifiers.tap(CONTROL, nowMillis = 1_400)
        assertEquals("a tap on a locked modifier turns it off", OFF, modifiers.state(CONTROL))

        // A second tap after 350 ms is a tap on a one-shot: off, not locked.
        modifiers.tap(CONTROL, nowMillis = 5_000)
        modifiers.tap(CONTROL, nowMillis = 5_350)
        assertEquals(OFF, modifiers.state(CONTROL))
        assertTrue("every tap reports a change", changes >= 5)
    }

    @Test
    fun optionsAreExclusive() {
        modifiers.tap(CONTROL, nowMillis = 0)
        modifiers.tap(LEFT_OPTION, nowMillis = 1_000)
        assertEquals(ONE_SHOT, modifiers.state(LEFT_OPTION))
        modifiers.tap(RIGHT_OPTION, nowMillis = 2_000)
        assertEquals(ONE_SHOT, modifiers.state(RIGHT_OPTION))
        assertEquals("right Option clears left", OFF, modifiers.state(LEFT_OPTION))
        assertEquals("control is independent", ONE_SHOT, modifiers.state(CONTROL))

        // Locking one side clears the other too.
        modifiers.tap(LEFT_OPTION, nowMillis = 3_000)
        modifiers.tap(LEFT_OPTION, nowMillis = 3_100)
        assertEquals(LOCKED, modifiers.state(LEFT_OPTION))
        assertEquals(OFF, modifiers.state(RIGHT_OPTION))
        assertEquals(KeyModifiers(control = true, leftOption = true), modifiers.consume())
    }

    @Test
    fun consumeClearsOnlyOneShots() {
        modifiers.tap(CONTROL, nowMillis = 0)
        modifiers.tap(CONTROL, nowMillis = 100)
        modifiers.tap(RIGHT_OPTION, nowMillis = 1_000)
        assertTrue(modifiers.anyOneShot)
        assertEquals(KeyModifiers(control = true, rightOption = true), modifiers.consume())
        assertEquals(LOCKED, modifiers.state(CONTROL))
        assertEquals(OFF, modifiers.state(RIGHT_OPTION))
        assertFalse(modifiers.anyOneShot)
        assertEquals(KeyModifiers(control = true), modifiers.consume())
        assertEquals(KeyModifiers(control = true), modifiers.consume())

        modifiers.reset()
        assertEquals(KeyModifiers(), modifiers.consume())
        assertEquals(OFF, modifiers.state(CONTROL))
    }

    @Test
    fun shiftIsAlwaysFalse() {
        assertEquals(KeyModifiers(), modifiers.consume())
        for (key in ModifierKey.entries) {
            modifiers.tap(key, nowMillis = 0)
            modifiers.tap(key, nowMillis = 10)
        }
        val consumed = modifiers.consume()
        assertFalse(consumed.shift)
        assertTrue(consumed.control)
    }

    @Test
    fun doubleTapOnLockedKeepsItLocked() {
        modifiers.tap(CONTROL, nowMillis = 0)
        modifiers.tap(CONTROL, nowMillis = 100)
        assertEquals(LOCKED, modifiers.state(CONTROL))
        modifiers.tap(CONTROL, nowMillis = 2_000)
        modifiers.tap(CONTROL, nowMillis = 2_200)
        assertEquals(LOCKED, modifiers.state(CONTROL))
        // A third quick tap is a single tap again.
        modifiers.tap(CONTROL, nowMillis = 2_300)
        assertEquals(OFF, modifiers.state(CONTROL))
    }
}
