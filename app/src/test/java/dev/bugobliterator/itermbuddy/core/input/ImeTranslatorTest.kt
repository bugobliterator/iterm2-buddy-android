package dev.bugobliterator.itermbuddy.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.view.KeyEvent as AndroidKeyEvent

class ImeTranslatorTest {
    private val events = mutableListOf<KeyEvent>()
    private var flushes = 0
    private val modifiers = StickyModifiers()
    private val ime = ImeTranslator(modifiers, emit = { events += it }, onComposingFlushed = { flushes++ })

    private fun text(s: String, modifiers: KeyModifiers = KeyModifiers()) = KeyEvent(Key.Text(s), modifiers)

    private fun special(key: SpecialKey, modifiers: KeyModifiers = KeyModifiers()) = KeyEvent(Key.Special(key), modifiers)

    private val ctrl = KeyModifiers(control = true)

    private val controlC = KeyEvent(Key.Text("c"), ctrl)

    private fun down(keyCode: Int) = ime.sendKeyEvent(keyCode, AndroidKeyEvent.ACTION_DOWN)

    private fun up(keyCode: Int) = ime.sendKeyEvent(keyCode, AndroidKeyEvent.ACTION_UP)

    @Test
    fun typedLettersAreOneEventEach() {
        ime.commitText("l")
        ime.commitText("s")
        ime.performEditorAction()
        assertEquals(listOf(text("l"), text("s"), text("\n")), events)
        assertEquals("nothing was held, so nothing was flushed", 0, flushes)
    }

    @Test
    fun composingTextIsSentOnceOnCommit() {
        ime.setComposingText("h")
        ime.setComposingText("he")
        ime.setComposingText("hel")
        assertEquals(emptyList<KeyEvent>(), events)
        ime.commitText("help ")
        assertEquals(listOf(text("help ")), events)
    }

    @Test
    fun finishComposingSendsHeldText() {
        ime.setComposingText("o")
        ime.setComposingText("ok")
        ime.finishComposingText()
        ime.finishComposingText()
        assertEquals(listOf(text("ok")), events)
        assertEquals("finish is the IME's own end of composing", 0, flushes)
    }

    @Test
    fun backspaceWhileComposingEditsHeldText() {
        ime.setComposingText("help")
        ime.deleteSurroundingText(1, 0)
        assertEquals(emptyList<KeyEvent>(), events)
        ime.commitText("hel")
        assertEquals(listOf(text("hel")), events)

        events.clear()
        ime.setComposingText("ab")
        repeat(3) { down(AndroidKeyEvent.KEYCODE_DEL) }
        assertEquals("DEL shortens the held text and does not flush it", listOf(special(SpecialKey.BACKSPACE)), events)
        ime.finishComposingText()
        assertEquals(listOf(special(SpecialKey.BACKSPACE)), events)

        // Deleting more than is held: only the excess reaches the Mac.
        events.clear()
        ime.setComposingText("xy")
        ime.deleteSurroundingText(4, 0)
        assertEquals(listOf(special(SpecialKey.BACKSPACE), special(SpecialKey.BACKSPACE)), events)
        assertEquals(0, flushes)
    }

    @Test
    fun editorActionFlushesComposingFirst() {
        ime.setComposingText("ls")
        ime.performEditorAction()
        assertEquals(listOf(text("ls"), text("\n")), events)
        assertEquals(1, flushes)

        events.clear()
        ime.setComposingText("cd")
        down(AndroidKeyEvent.KEYCODE_TAB)
        assertEquals(listOf(text("cd"), special(SpecialKey.TAB)), events)
        assertEquals(2, flushes)

        events.clear()
        ime.setComposingText("sleep")
        ime.keyBar(controlC)
        assertEquals(listOf(text("sleep"), controlC), events)
        assertEquals(3, flushes)

        // With nothing held there is nothing to flush.
        events.clear()
        ime.keyBar(special(SpecialKey.ESCAPE))
        assertEquals(listOf(special(SpecialKey.ESCAPE)), events)
        assertEquals(3, flushes)
    }

    @Test
    fun heldBackspaceSendsOneBackspacePerCharacter() {
        repeat(5) { ime.deleteSurroundingText(1, 0) }
        assertEquals(List(5) { special(SpecialKey.BACKSPACE) }, events)

        events.clear()
        ime.deleteSurroundingText(3, 0)
        assertEquals(List(3) { special(SpecialKey.BACKSPACE) }, events)

        events.clear()
        repeat(4) { down(AndroidKeyEvent.KEYCODE_DEL) }
        assertEquals(List(4) { special(SpecialKey.BACKSPACE) }, events)
    }

    @Test
    fun oneShotControlAppliesToFirstCharacterOnly() {
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        ime.commitText("cd")
        assertEquals(listOf(text("c", ctrl), text("d")), events)
        assertEquals(ModifierState.OFF, modifiers.state(ModifierKey.CONTROL))

        events.clear()
        ime.commitText("x")
        assertEquals(listOf(text("x")), events)
    }

    @Test
    fun lockedModifierStaysOnTheWholeCommit() {
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 100)
        modifiers.tap(ModifierKey.LEFT_OPTION, nowMillis = 1_000)
        ime.commitText("ab")
        assertEquals(listOf(text("a", KeyModifiers(control = true, leftOption = true)), text("b", ctrl)), events)

        events.clear()
        ime.commitText("cd")
        assertEquals(listOf(text("cd", ctrl)), events)
    }

    @Test
    fun newlineVariantsAreOneReturn() {
        ime.commitText("\r\n")
        ime.commitText("\r")
        ime.commitText("\n")
        down(AndroidKeyEvent.KEYCODE_ENTER)
        assertEquals(List(4) { text("\n") }, events)

        events.clear()
        ime.commitText("a\r\nb")
        assertEquals(listOf(text("a\nb")), events)
    }

    @Test
    fun emojiIsOneTextEvent() {
        ime.commitText("😀")
        assertEquals(listOf(text("😀")), events)

        events.clear()
        ime.setComposingText("😀")
        ime.deleteSurroundingText(1, 0)
        ime.finishComposingText()
        assertEquals("a held surrogate pair is never split", emptyList<KeyEvent>(), events)
    }

    @Test
    fun oneShotControlSplitsByCodePoint() {
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        ime.commitText("😀a")
        assertEquals(listOf(text("😀", ctrl), text("a")), events)
    }

    @Test
    fun hardwareArrowsMapToSpecials() {
        val expected = listOf(
            AndroidKeyEvent.KEYCODE_DPAD_UP to SpecialKey.UP,
            AndroidKeyEvent.KEYCODE_DPAD_DOWN to SpecialKey.DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT to SpecialKey.LEFT,
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT to SpecialKey.RIGHT,
            AndroidKeyEvent.KEYCODE_ESCAPE to SpecialKey.ESCAPE,
            AndroidKeyEvent.KEYCODE_TAB to SpecialKey.TAB,
            AndroidKeyEvent.KEYCODE_DEL to SpecialKey.BACKSPACE,
            AndroidKeyEvent.KEYCODE_FORWARD_DEL to SpecialKey.FORWARD_DELETE,
            AndroidKeyEvent.KEYCODE_MOVE_HOME to SpecialKey.HOME,
            AndroidKeyEvent.KEYCODE_MOVE_END to SpecialKey.END,
            AndroidKeyEvent.KEYCODE_PAGE_UP to SpecialKey.PAGE_UP,
            AndroidKeyEvent.KEYCODE_PAGE_DOWN to SpecialKey.PAGE_DOWN,
            AndroidKeyEvent.KEYCODE_INSERT to SpecialKey.INSERT,
            AndroidKeyEvent.KEYCODE_F1 to SpecialKey.F1,
            AndroidKeyEvent.KEYCODE_F7 to SpecialKey.F7,
            AndroidKeyEvent.KEYCODE_F12 to SpecialKey.F12,
        )
        for ((keyCode, _) in expected) assertTrue("handled $keyCode", down(keyCode))
        assertEquals(expected.map { special(it.second) }, events)

        events.clear()
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        down(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        down(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(special(SpecialKey.LEFT, ctrl), special(SpecialKey.LEFT)), events)

        events.clear()
        assertEquals("an unmapped key is left to the caller", false, down(AndroidKeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(emptyList<KeyEvent>(), events)
    }

    @Test
    fun keyUpIgnored() {
        ime.setComposingText("ab")
        for (keyCode in listOf(AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_TAB)) up(keyCode)
        assertEquals(emptyList<KeyEvent>(), events)
        assertEquals(0, flushes)
        assertTrue("a mapped key's key-up is taken", up(AndroidKeyEvent.KEYCODE_DEL))
        assertEquals("Back is left to the system", false, up(AndroidKeyEvent.KEYCODE_BACK))
        ime.finishComposingText()
        assertEquals(listOf(text("ab")), events)
    }

    @Test
    fun keyBarSpecialsCarryConsumedModifiers() {
        modifiers.tap(ModifierKey.RIGHT_OPTION, nowMillis = 0)
        ime.keyBar(special(SpecialKey.RIGHT))
        ime.keyBar(special(SpecialKey.RIGHT))
        assertEquals(listOf(special(SpecialKey.RIGHT, KeyModifiers(rightOption = true)), special(SpecialKey.RIGHT)), events)
    }

    @Test
    fun controlCIsExactAndConsumesArmedModifiers() {
        modifiers.tap(ModifierKey.LEFT_OPTION, nowMillis = 0)
        ime.keyBar(controlC)
        ime.commitText("a")
        assertEquals(listOf(controlC, text("a")), events)
        assertEquals(ModifierState.OFF, modifiers.state(ModifierKey.LEFT_OPTION))
    }

    @Test
    fun commitReplacingComposingSendsOnlyTheCommit() {
        ime.setComposingText("teh")
        ime.commitText("the")
        ime.setComposingText("x")
        ime.commitText("")
        ime.finishComposingText()
        assertEquals("an empty commit drops the held text", listOf(text("the")), events)
    }

    @Test
    fun composeKeyFlushesHeldText() {
        ime.flushComposing()
        assertEquals(0, flushes)
        ime.setComposingText("git")
        ime.flushComposing()
        ime.finishComposingText()
        assertEquals(listOf(text("git")), events)
        assertEquals(1, flushes)
    }
}
