package dev.bugobliterator.itermbuddy.core.input

/**
 * A non-text key the phone can inject: upstream `CompanionSpecialKey`
 * (`sources/Companion/Shared/CompanionMessages.swift`), with [raw] its wire value.
 *
 * Exactly upstream's 25 cases. Never add one: a special key the Mac does not know
 * fails the decode of the whole `sendKey`, and the Mac drops it. Return is
 * `Key.Text("\n")`, ^C is `Key.Text("c")` with `control`.
 */
enum class SpecialKey(val raw: String) {
    ESCAPE("escape"),
    TAB("tab"),
    BACKSPACE("backspace"),
    FORWARD_DELETE("forwardDelete"),
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    HOME("home"),
    END("end"),
    PAGE_UP("pageUp"),
    PAGE_DOWN("pageDown"),
    INSERT("insert"),
    F1("f1"),
    F2("f2"),
    F3("f3"),
    F4("f4"),
    F5("f5"),
    F6("f6"),
    F7("f7"),
    F8("f8"),
    F9("f9"),
    F10("f10"),
    F11("f11"),
    F12("f12"),
}

/**
 * Upstream `CompanionKeyModifiers`. All four are always written on the wire (the Mac's
 * synthesized decode requires them); the app always sends [shift] false, as iOS does.
 */
data class KeyModifiers(
    val control: Boolean = false,
    val shift: Boolean = false,
    val leftOption: Boolean = false,
    val rightOption: Boolean = false,
)

/** Upstream `CompanionKeyEvent.Key`: a run of literal characters or one special key. */
sealed interface Key {
    /** Typed text: never logged, so [toString] shows only its length. */
    data class Text(val text: String) : Key {
        override fun toString(): String = "Text(len=${text.length})"
    }

    data class Special(val key: SpecialKey) : Key
}

/** Upstream `CompanionKeyEvent`: [modifiers] apply to the whole event. */
data class KeyEvent(val key: Key, val modifiers: KeyModifiers = KeyModifiers()) {
    override fun toString(): String = "KeyEvent(key=$key, modifiers=$modifiers)"
}
