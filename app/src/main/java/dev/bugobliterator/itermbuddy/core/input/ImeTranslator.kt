package dev.bugobliterator.itermbuddy.core.input

import android.view.KeyEvent as AndroidKeyEvent

/**
 * Turns the soft keyboard's `InputConnection` calls and the key bar's keys into the Mac's key
 * events, in order. Pure (only Android's key code constants), so every IME sequence is a JVM test.
 *
 * **Committed text.** Each [commitText] is one `text` event, with `\r\n`, `\r` and `\n` all
 * written `\n` (the Mac's Return). A one-shot modifier applies to the first code point only: with
 * one armed and more than one code point committed, the first code point is sent with every
 * modifier on and the rest with only the locked ones (iOS `sendText`; "armed Ctrl + the" is ^T,
 * then "he"). Otherwise the whole commit carries the modifiers that are on.
 *
 * **Composing text** is held, never sent while it changes. It is sent once: by [commitText]
 * (which replaces it, so only the committed text is sent), or by [finishComposingText].
 *
 * **Deletions come first.** [deleteSurroundingText] (`before` characters) and a `KEYCODE_DEL`
 * key-down never send held text: while text is held they shorten it (never splitting a surrogate
 * pair) and send nothing for those characters; only the excess beyond the held length becomes
 * `backspace` events. With nothing held, each character is one `backspace`. `after` is ignored:
 * the editor is always empty, so there is nothing after the cursor.
 *
 * **Everything else flushes first.** Before an editor action, any other mapped key-down, or any
 * key bar key (the compose key too: [flushComposing]), held text is sent and [onComposingFlushed]
 * runs (the view restarts the IME so it drops its composing region), so `ls` then Return is `ls`,
 * Return, never the reverse.
 *
 * **Keys.** Key-ups are ignored. `KEYCODE_ENTER`/`NUMPAD_ENTER` and the editor action are
 * `text "\n"`; DEL is `backspace`, FORWARD_DEL `forwardDelete`; ESCAPE, TAB, the D-pad arrows,
 * MOVE_HOME/END, PAGE_UP/DOWN, INSERT and F1–F12 are their special keys. Every key sent from the
 * IME or a hardware key consumes the sticky modifiers. A key bar event with no modifiers of its
 * own is sent with the consumed sticky modifiers; one that names its own (^C: `text "c"` with
 * `control`) is sent exactly as given and still consumes any armed one-shot, as iOS.
 *
 * Never logs anything.
 *
 * @param emit receives each key event, in order.
 * @param onComposingFlushed runs after held text is sent ahead of a key or editor action.
 */
class ImeTranslator(
    private val modifiers: StickyModifiers,
    private val emit: (KeyEvent) -> Unit,
    private val onComposingFlushed: () -> Unit,
) {
    /** The composing text the IME holds, not yet sent. */
    private val held = StringBuilder()

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
        val excess = shortenHeld(before)
        repeat(excess) { sendSpecial(SpecialKey.BACKSPACE) }
    }

    /**
     * A key event from the IME or a hardware keyboard. Returns whether it was handled: false for
     * any action of a key with no mapping (left to the caller, and nothing is flushed for it).
     */
    fun sendKeyEvent(keyCode: Int, action: Int): Boolean {
        // A mapped key's other actions are taken and ignored; an unmapped one's (Back) are not.
        if (action != AndroidKeyEvent.ACTION_DOWN) return keyCode in HANDLED_KEYS
        if (keyCode == AndroidKeyEvent.KEYCODE_DEL) {
            if (shortenHeld(1) > 0) sendSpecial(SpecialKey.BACKSPACE)
            return true
        }
        if (keyCode == AndroidKeyEvent.KEYCODE_ENTER || keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) {
            flushHeld()
            sendText(RETURN)
            return true
        }
        val special = SPECIAL_KEYS[keyCode] ?: return false
        flushHeld()
        sendSpecial(special)
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
        emit(if (event.modifiers == KeyModifiers()) event.copy(modifiers = sticky) else event)
    }

    /** The key bar's compose key: held text is sent first, as for any key bar key. */
    fun flushComposing() = flushHeld()

    /** Sends held text ahead of a key, then [onComposingFlushed]; nothing when none is held. */
    private fun flushHeld() {
        if (held.isEmpty()) return
        val text = held.toString()
        held.setLength(0)
        sendText(text)
        onComposingFlushed()
    }

    /**
     * Removes up to [count] characters from the end of the held text, never leaving half a
     * surrogate pair, and returns how many of [count] were left over.
     */
    private fun shortenHeld(count: Int): Int {
        val removed = minOf(count, held.length)
        var end = held.length - removed
        if (end > 0 && Character.isHighSurrogate(held[end - 1])) end--
        held.setLength(end)
        return count - removed
    }

    private fun sendText(raw: String) {
        if (raw.isEmpty()) return
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (modifiers.anyOneShot && text.codePointCount(0, text.length) > 1) {
            val split = text.offsetByCodePoints(0, 1)
            emit(KeyEvent(Key.Text(text.substring(0, split)), modifiers.consume()))
            emit(KeyEvent(Key.Text(text.substring(split)), modifiers.consume()))
        } else {
            emit(KeyEvent(Key.Text(text), modifiers.consume()))
        }
    }

    private fun sendSpecial(key: SpecialKey) {
        emit(KeyEvent(Key.Special(key), modifiers.consume()))
    }

    private companion object {
        const val RETURN = "\n"

        val SPECIAL_KEYS: Map<Int, SpecialKey> = mapOf(
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

        val HANDLED_KEYS: Set<Int> = SPECIAL_KEYS.keys +
            setOf(AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
