package dev.bugobliterator.itermbuddy.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.bugobliterator.itermbuddy.core.input.ImeTranslator

/**
 * The soft keyboard's target in the live view: an Android [View] (hosted in Compose through
 * `AndroidView`) whose [InputConnection] forwards every IME call to [translator] and keeps its own
 * editable empty, so the IME never sees, suggests on or edits terminal text.
 *
 * The editor is a single-line visible-password field with no suggestions
 * (`TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`), no
 * fullscreen or extract UI and no action key (`IME_ACTION_NONE`): no autocorrect, prediction or
 * capitalisation, as iOS's key-input view, and no personalised learning (it may be a password).
 * Hardware keys reach [onKeyDown] and go the same way, Ctrl and Alt included.
 *
 * **Held Backspace (device check).** With nothing before the cursor, Gboard sends
 * `KEYCODE_DEL` key events instead of `deleteSurroundingText`, and repeats them while the key is
 * held. If the owner's check shows held Backspace does *not* repeat on the Pixel's Gboard, the
 * specified fallback is to keep one sentinel character before the cursor (report it from
 * `getTextBeforeCursor`, with the selection after it) and restore it after each delete, so Gboard
 * deletes "text" and repeats; each `deleteSurroundingText(1, 0)` then becomes one `backspace`
 * through [ImeTranslator.deleteSurroundingText] exactly as now.
 *
 * Never logs anything.
 */
class TerminalInputView(context: Context) : View(context) {
    /** Where IME calls go; null drops them (the view is not offering input). */
    var translator: ImeTranslator? = null

    private val imm: InputMethodManager get() = context.getSystemService(InputMethodManager::class.java)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        // A new connection sees an empty editor: what was typed before is no longer "before the cursor".
        translator?.inputRestarted()
        return TerminalInputConnection(this)
    }

    /** Takes focus and raises the soft keyboard. */
    fun showKeyboard() {
        if (!hasFocus()) requestFocus()
        val window = activityWindow()
        if (window != null) {
            WindowCompat.getInsetsController(window, this).show(WindowInsetsCompat.Type.ime())
        } else {
            imm.showSoftInput(this, 0)
        }
    }

    private fun activityWindow(): Window? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.window
            current = current.baseContext
        }
        return null
    }

    /** Lowers the soft keyboard. */
    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Whether the soft keyboard is showing in this window. */
    fun keyboardVisible(): Boolean = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /** Makes the IME drop its composing region after held text was sent ahead of a key. */
    fun restartInput() {
        imm.restartInput(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyUp(keyCode, event)

    /**
     * A key event from the IME or a hardware keyboard, for [ImeTranslator.sendKeyEvent] with the
     * character it types (some IMEs send digits as key events; a dead key types none). Anything else is left to the caller.
     */
    internal fun handleKey(event: KeyEvent): Boolean {
        val target = translator ?: return false
        // The character without Ctrl, Alt or Meta (shift kept); the translator adds Ctrl and Alt itself.
        val plain = event.getUnicodeChar(event.metaState and MODIFIER_META.inv())
        val char = if (plain and KeyCharacterMap.COMBINING_ACCENT != 0) 0 else plain
        return target.sendKeyEvent(event.keyCode, event.action, char, event.metaState)
    }

    private companion object {
        const val MODIFIER_META = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
    }
}

/**
 * [TerminalInputView]'s connection: a [BaseInputConnection] with `fullEditor = false` whose
 * every text call goes to the translator, so its editable stays empty (no composing region, no
 * text before or after the cursor).
 */
private class TerminalInputConnection(private val view: TerminalInputView) : BaseInputConnection(view, false) {
    private val translator: ImeTranslator? get() = view.translator

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        translator?.commitText(text)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        translator?.setComposingText(text)
        return true
    }

    override fun finishComposingText(): Boolean {
        translator?.finishComposingText()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        translator?.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        // Held composing text is shortened by UTF-16 units; an IME that counts code points here
        // deletes the same number of characters from anything not held (one backspace each).
        translator?.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = view.handleKey(event) || super.sendKeyEvent(event)

    override fun performEditorAction(actionCode: Int): Boolean {
        translator?.performEditorAction()
        return true
    }

    // The editor is always empty: there is no region or selection to set.
    override fun setComposingRegion(start: Int, end: Int): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean = false

    override fun commitCompletion(text: CompletionInfo?): Boolean = true

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun closeConnection() {
        // The IME let go (hidden, or restarted after a flush): anything still held is sent.
        translator?.finishComposingText()
        super.closeConnection()
    }
}
