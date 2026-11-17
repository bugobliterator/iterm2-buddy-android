package dev.bugobliterator.itermbuddy.core.input

import android.view.KeyEvent as AndroidKeyEvent

/**
 * Turns the soft keyboard's `InputConnection` calls, hardware keys and the key bar's keys into the
 * Mac's key events, in order. Pure (only Android's key and meta constants), so every IME sequence
 * is a JVM test.
 *
 * **Committed text.** Each [commitText] is one `text` event, with `\r\n`, `\r` and `\n` all
 * written `\n` (the Mac's Return). A one-shot modifier applies to the first code point only: with
 * one armed and more than one code point committed, the first code point is sent with every
 * modifier on and the rest with only the locked ones (iOS `sendText`; "armed Ctrl + the" is ^T,
 * then "he"). Otherwise the whole commit carries the modifiers that are on.
 *
 * **Text that is not typing goes to the composer.** A commit of more than one code point that
 * contains a newline, or is longer than [COMPOSER_THRESHOLD] UTF-16 units (dictation, a clipboard
 * suggestion, a long swipe), is never typed as keystrokes: it goes to `toComposer`, which opens
 * the composer seeded with it so the owner confirms it (iOS `CompanionKeyboardInputView.insertText`
 * sends every multi-character insert there; Android IMEs commit whole words, so only these do).
 * Nothing is sent and no modifier is consumed for it.
 *
 * **Composing text** is held, never sent while it changes. It is sent once: by [commitText]
 * (which replaces it, so only the committed text is sent), or by [finishComposingText].
 *
 * **Deletions.** The translator keeps a bounded tail of the text it sent since the last special
 * key, Return, IME restart or target change (the IME's idea of what is before the cursor).
 * [deleteSurroundingText] (`before` UTF-16 units; `after` is ignored, nothing is after the cursor)
 * deletes from that tail and sends one `backspace` per **code point** removed (a split surrogate
 * pair counts as its whole code point), plus one per unit beyond the tail; so 😀 (two units) is one
 * backspace and 👍🏽 (two code points) is two. The Mac deletes one character per keystroke as the
 * shell's line editor counts them, which is per code point (readline, zsh), not per grapheme as
 * iOS's `deleteBackward` does. It never touches held composing text: as the framework's
 * `BaseInputConnection` does, it deletes the text before the composing span. A `KEYCODE_DEL`
 * key-down shortens held text (never splitting a surrogate pair) and sends nothing; with nothing
 * held it is one `backspace` (and removes one code point from the tail). Neither flushes.
 *
 * **Everything else flushes first.** Before an editor action, any other mapped key-down, a typed
 * character key, or any key bar key (the compose key too: [flushComposing]), held text is sent and
 * [onComposingFlushed] runs (the view restarts the IME so it drops its composing region), so `ls`
 * then Return is `ls`, Return, never the reverse.
 *
 * **Keys.** Key-ups are ignored. `KEYCODE_ENTER`/`NUMPAD_ENTER` and the editor action are
 * `text "\n"`; DEL is `backspace`, FORWARD_DEL `forwardDelete`; ESCAPE, TAB, the D-pad arrows,
 * MOVE_HOME/END, PAGE_UP/DOWN, INSERT and F1–F12 are their special keys. An unmapped key-down
 * with a character (`unicodeChar`, computed without Ctrl and Alt) is typed as that character. A
 * hardware Ctrl adds `control` (a letter is sent lower-case, as ^C is `text "c"`), Alt adds
 * `leftOption`. Every key from the IME or a hardware key consumes the sticky modifiers. A key bar
 * event with no modifiers of its own is sent with the consumed sticky modifiers; one that names
 * its own (^C: `text "c"` with `control`) is sent exactly as given and still consumes any armed
 * one-shot, as iOS.
 *
 * Logs nothing but the opt-in [InputTrace] (each emitted event). Main thread only.
 *
 * @param emit receives each key event, in order.
 * @param onComposingFlushed runs after held text is sent ahead of a key or editor action.
 * @param toComposer receives committed text that is not typed (see above).
 */
class ImeTranslator(
    private val modifiers: StickyModifiers,
    private val emit: (KeyEvent) -> Unit,
    private val onComposingFlushed: () -> Unit,
    private val toComposer: (String) -> Unit,
) {
    /** The composing text the IME holds, not yet sent. */
    private val held = StringBuilder()

    /** The end of what was sent since the last special key, Return, restart or reset; at most [TAIL_LIMIT] units. */
    private val tail = StringBuilder()

    fun commitText(text: CharSequence) {
        held.setLength(0)
        sendText(text.toString())
    }

    fun setComposingText(text: CharSequence) {
        held.setLength(0)
        held.append(text)
    }

    fun finishComposingText() {
        val text = held.toString()
        held.setLength(0)
        sendText(text)
    }

    fun deleteSurroundingText(before: Int, after: Int) {
        if (before <= 0) return
        repeat(deleteFromTail(before)) { sendSpecial(SpecialKey.BACKSPACE, clearTail = false) }
    }

    /**
     * A key event from the IME or a hardware keyboard. [unicodeChar] is the character the key
     * types without Ctrl and Alt (0 for none); [metaState] its `KeyEvent.getMetaState()`. Returns
     * whether it was handled: false for any action of a key with no mapping and no character
     * (left to the caller, and nothing is flushed for it).
     */
    fun sendKeyEvent(keyCode: Int, action: Int, unicodeChar: Int = 0, metaState: Int = 0): Boolean {
        // A mapped key's other actions are taken and ignored; an unmapped one's (Back) are not.
        if (action != AndroidKeyEvent.ACTION_DOWN) return keyCode in HANDLED_KEYS
        val hardware = KeyModifiers(
            control = metaState and AndroidKeyEvent.META_CTRL_ON != 0,
            leftOption = metaState and AndroidKeyEvent.META_ALT_ON != 0,
        )
        if (keyCode == AndroidKeyEvent.KEYCODE_DEL) {
            if (held.isNotEmpty()) {
                shortenHeld()
            } else {
                deleteFromTail(1)
                sendSpecial(SpecialKey.BACKSPACE, clearTail = false, extra = hardware)
            }
            return true
        }
        if (keyCode == AndroidKeyEvent.KEYCODE_ENTER || keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) {
            flushHeld()
            sendText(RETURN, hardware)
            return true
        }
        val special = SPECIAL_KEYS[keyCode]
        if (special != null) {
            flushHeld()
            sendSpecial(special, extra = hardware)
            return true
        }
        if (unicodeChar <= 0 || !Character.isValidCodePoint(unicodeChar)) return false
        flushHeld()
        val typed = if (hardware.control) Character.toLowerCase(unicodeChar) else unicodeChar
        sendText(String(Character.toChars(typed)), hardware)
        return true
    }

    fun performEditorAction() {
        flushHeld()
        sendText(RETURN)
    }

    /** A key bar key: see the class comment for its modifiers. */
    fun keyBar(event: KeyEvent) {
        flushHeld()
        val sticky = modifiers.consume()
        tail.setLength(0)
        out(if (event.modifiers == KeyModifiers()) event.copy(modifiers = sticky) else event)
    }

    /** The key bar's compose key: held text is sent first, as for any key bar key. */
    fun flushComposing() = flushHeld()

    /** The IME connection was recreated: it now sees an empty editor, so the tail is forgotten. */
    fun inputRestarted() {
        tail.setLength(0)
    }

    /** Input now goes elsewhere (another link, watch or session): held text and the tail are dropped, unsent. */
    fun reset() {
        held.setLength(0)
        tail.setLength(0)
    }

    /** Emits [event], with a line in the opt-in [InputTrace]. */
    private fun out(event: KeyEvent) {
        InputTrace.current.log { "emit ${traceKeyEvent(event)}" }
        emit(event)
    }

    /** Sends held text ahead of a key, then [onComposingFlushed]; nothing when none is held. */
    private fun flushHeld() {
        if (held.isEmpty()) return
        val text = held.toString()
        held.setLength(0)
        sendText(text)
        onComposingFlushed()
    }

    /** Removes the last character of the held text, never leaving half a surrogate pair. */
    private fun shortenHeld() {
        var end = held.length - 1
        if (end > 0 && Character.isHighSurrogate(held[end - 1]) && Character.isLowSurrogate(held[end])) end--
        held.setLength(end)
    }

    /**
     * Deletes [units] UTF-16 units from the end of the tail and returns the backspaces that
     * takes: one per code point removed (a pair split by the count goes whole), one per unit
     * beyond the tail.
     */
    private fun deleteFromTail(units: Int): Int {
        var remaining = units
        var backspaces = 0
        while (remaining > 0 && tail.isNotEmpty()) {
            val width = Character.charCount(Character.codePointBefore(tail, tail.length))
            tail.setLength(tail.length - width)
            remaining -= width
            backspaces++
        }
        return backspaces + maxOf(remaining, 0)
    }

    private fun sendText(raw: String, extra: KeyModifiers = KeyModifiers()) {
        if (raw.isEmpty()) return
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints > 1 && ('\n' in text || text.length > COMPOSER_THRESHOLD)) {
            toComposer(text)
            return
        }
        if (modifiers.anyOneShot && codePoints > 1) {
            val split = text.offsetByCodePoints(0, 1)
            out(KeyEvent(Key.Text(text.substring(0, split)), modifiers.consume() + extra))
            out(KeyEvent(Key.Text(text.substring(split)), modifiers.consume() + extra))
        } else {
            out(KeyEvent(Key.Text(text), modifiers.consume() + extra))
        }
        remember(text)
    }

    /** Adds sent [text] to the tail: a Return starts it afresh; it keeps the last [TAIL_LIMIT] units. */
    private fun remember(text: String) {
        val newline = text.lastIndexOf('\n')
        if (newline >= 0) tail.setLength(0)
        tail.append(text, newline + 1, text.length)
        if (tail.length > TAIL_LIMIT) {
            var start = tail.length - TAIL_LIMIT
            if (Character.isLowSurrogate(tail[start])) start++
            tail.delete(0, start)
        }
    }

    private fun sendSpecial(key: SpecialKey, clearTail: Boolean = true, extra: KeyModifiers = KeyModifiers()) {
        if (clearTail) tail.setLength(0)
        out(KeyEvent(Key.Special(key), modifiers.consume() + extra))
    }

    private operator fun KeyModifiers.plus(other: KeyModifiers) = KeyModifiers(
        control = control || other.control,
        shift = false,
        leftOption = leftOption || other.leftOption,
        rightOption = rightOption || other.rightOption,
    )

    companion object {
        /** Committed text longer than this (UTF-16 units) goes to the composer instead of being typed. */
        const val COMPOSER_THRESHOLD = 64

        /** How much sent text the tail keeps for deletions (UTF-16 units). */
        const val TAIL_LIMIT = 256

        private const val RETURN = "\n"

        private val SPECIAL_KEYS: Map<Int, SpecialKey> = mapOf(
            AndroidKeyEvent.KEYCODE_ESCAPE to SpecialKey.ESCAPE,
            AndroidKeyEvent.KEYCODE_TAB to SpecialKey.TAB,
            AndroidKeyEvent.KEYCODE_FORWARD_DEL to SpecialKey.FORWARD_DELETE,
            AndroidKeyEvent.KEYCODE_DPAD_UP to SpecialKey.UP,
            AndroidKeyEvent.KEYCODE_DPAD_DOWN to SpecialKey.DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT to SpecialKey.LEFT,
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT to SpecialKey.RIGHT,
            AndroidKeyEvent.KEYCODE_MOVE_HOME to SpecialKey.HOME,
            AndroidKeyEvent.KEYCODE_MOVE_END to SpecialKey.END,
            AndroidKeyEvent.KEYCODE_PAGE_UP to SpecialKey.PAGE_UP,
            AndroidKeyEvent.KEYCODE_PAGE_DOWN to SpecialKey.PAGE_DOWN,
            AndroidKeyEvent.KEYCODE_INSERT to SpecialKey.INSERT,
            AndroidKeyEvent.KEYCODE_F1 to SpecialKey.F1,
            AndroidKeyEvent.KEYCODE_F2 to SpecialKey.F2,
            AndroidKeyEvent.KEYCODE_F3 to SpecialKey.F3,
            AndroidKeyEvent.KEYCODE_F4 to SpecialKey.F4,
            AndroidKeyEvent.KEYCODE_F5 to SpecialKey.F5,
            AndroidKeyEvent.KEYCODE_F6 to SpecialKey.F6,
            AndroidKeyEvent.KEYCODE_F7 to SpecialKey.F7,
            AndroidKeyEvent.KEYCODE_F8 to SpecialKey.F8,
            AndroidKeyEvent.KEYCODE_F9 to SpecialKey.F9,
            AndroidKeyEvent.KEYCODE_F10 to SpecialKey.F10,
            AndroidKeyEvent.KEYCODE_F11 to SpecialKey.F11,
            AndroidKeyEvent.KEYCODE_F12 to SpecialKey.F12,
        )

        private val HANDLED_KEYS: Set<Int> = SPECIAL_KEYS.keys +
            setOf(AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
