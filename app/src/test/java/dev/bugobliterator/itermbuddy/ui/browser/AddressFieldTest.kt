package dev.bugobliterator.itermbuddy.ui.browser

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address field as iOS's `BrowserControlsBar` keeps it (`IOS/Screens/SessionView.swift:1150-1214`). */
class AddressFieldTest {
    @Test
    fun showsUrlUnlessFocused() {
        val idle = AddressFieldState("typed", focused = false)
        assertEquals(URL, idle.shown(URL))
        assertEquals("", idle.shown(null))
        // While focused, what was typed stays, whatever the page reports.
        val typing = AddressFieldState("typed", focused = true)
        assertEquals("typed", typing.shown(URL))
        assertEquals("typed", typing.shown(null))
    }

    @Test
    fun focusSelectsAll() {
        val focused = AddressFieldState("", focused = false).onFocus(URL)
        assertTrue(focused.focused)
        assertEquals(URL, focused.text)
        assertEquals(TextRange(0, URL.length), focused.selection)
        // The tap that focused the field then places a cursor: the select-all stays, once.
        val afterTap = focused.onEdit(URL, TextRange(5))
        assertEquals(TextRange(0, URL.length), afterTap.selection)
        // A later tap moves the cursor.
        assertEquals(TextRange(7), afterTap.onEdit(URL, TextRange(7)).selection)
        // Typing replaces the selection, as in Safari.
        val typed = focused.onEdit("w", TextRange(1))
        assertEquals("w", typed.text)
        assertEquals(TextRange(1), typed.selection)
        // No page address yet: an empty field.
        assertEquals("", AddressFieldState("", false).onFocus(null).text)
    }

    @Test
    fun compositionSurvivesEdits() {
        // Gboard's composing span must come back in the field's value, or it restarts input per key.
        val focused = AddressFieldState("", false).onFocus(URL)
        val composing = focused.onEdit("exa", TextRange(3), TextRange(0, 3))
        assertEquals(TextRange(0, 3), composing.composition)
        val more = composing.onEdit("exam", TextRange(4), TextRange(0, 4))
        assertEquals(TextRange(0, 4), more.composition)
        assertEquals(null, more.onEdit("exam ", TextRange(5), null).composition)
        // A composition change with the same text is kept even while the select-all is held.
        assertEquals(TextRange(0, URL.length), focused.onEdit(URL, TextRange(2), TextRange(0, URL.length)).composition)
        // Leaving the field drops it.
        assertNull(more.onBlur(URL).composition)
    }

    @Test
    fun aFocusWithoutATapDoesNotEatTheFirstTap() {
        // TalkBack or a hardware keyboard focuses with no cursor placement after it; one frame later
        // the select-all latch is let go, so the owner's first tap moves the cursor.
        val settled = AddressFieldState("", false).onFocus(URL).settled()
        assertEquals(TextRange(0, URL.length), settled.selection)
        assertEquals(TextRange(4), settled.onEdit(URL, TextRange(4)).selection)
    }

    @Test
    fun blurRestoresUrl() {
        val typing = AddressFieldState("", false).onFocus(URL).onEdit("half typ", TextRange(8))
        val left = typing.onBlur(OTHER_URL)
        assertFalse(left.focused)
        assertEquals(OTHER_URL, left.text)
        assertEquals(OTHER_URL, left.shown(OTHER_URL))
        assertEquals("", typing.onBlur(null).text)
        // An edit while not focused (none should come) changes nothing.
        assertEquals(left, left.onEdit("stray", TextRange(5)))
    }

    @Test
    fun goSendsTrimmedText() {
        assertEquals("example.com/a b", AddressFieldState("  example.com/a b \n\t", true).go())
        assertNull(AddressFieldState(" \n ", true).go())
        assertNull(AddressFieldState("", true).go())
    }

    @Test
    fun toStringHidesText() {
        val state = AddressFieldState(SENTINEL, focused = true).onFocus(SENTINEL)
        val text = state.toString()
        assertFalse(text, text.contains(SENTINEL))
        assertTrue(text, text.contains("len=${SENTINEL.length}"))
    }

    private companion object {
        const val URL = "https://example.com/docs"
        const val OTHER_URL = "https://example.com/other"
        const val SENTINEL = "SENTINEL-https://secret.example/path"
    }
}
