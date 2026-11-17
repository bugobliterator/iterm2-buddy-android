package dev.bugobliterator.itermbuddy.core.input

/** The key bar's sticky modifier keys (iOS `SessionKeyboardController.Modifier`). Shift has none: the IME sends shifted text. */
enum class ModifierKey { CONTROL, LEFT_OPTION, RIGHT_OPTION }

/** A sticky modifier: [ONE_SHOT] applies to the next key only, [LOCKED] until tapped off. */
enum class ModifierState { OFF, ONE_SHOT, LOCKED }

/**
 * The key bar's sticky modifiers, as iOS (`SessionKeyboardAccessory.swift`), with the double tap
 * timed here rather than by the gesture system:
 *
 * - a tap on an off modifier makes it [ModifierState.ONE_SHOT];
 * - a second tap less than [DOUBLE_TAP_MILLIS] after turning it one-shot locks it;
 * - any other tap on a one-shot or locked modifier turns it off;
 * - left and right Option are exclusive (the Mac's key mapper reads one side only): turning one
 *   on turns the other off. Control is independent.
 *
 * Not thread-safe: the live view uses it from the main thread only.
 *
 * @param onChange called after every change of state, so the key bar can redraw.
 */
class StickyModifiers(private val onChange: () -> Unit = {}) {
    private val states = mutableMapOf<ModifierKey, ModifierState>()
    private val armedAt = mutableMapOf<ModifierKey, Long>()

    fun tap(key: ModifierKey, nowMillis: Long) {
        val next = when (state(key)) {
            ModifierState.OFF -> ModifierState.ONE_SHOT
            ModifierState.ONE_SHOT -> {
                val armed = armedAt[key]
                if (armed != null && nowMillis - armed in 0 until DOUBLE_TAP_MILLIS) ModifierState.LOCKED else ModifierState.OFF
            }
            ModifierState.LOCKED -> ModifierState.OFF
        }
        states[key] = next
        if (next == ModifierState.ONE_SHOT) armedAt[key] = nowMillis else armedAt.remove(key)
        if (next != ModifierState.OFF) {
            when (key) {
                ModifierKey.LEFT_OPTION -> turnOff(ModifierKey.RIGHT_OPTION)
                ModifierKey.RIGHT_OPTION -> turnOff(ModifierKey.LEFT_OPTION)
                ModifierKey.CONTROL -> Unit
            }
        }
        onChange()
    }

    fun state(key: ModifierKey): ModifierState = states[key] ?: ModifierState.OFF

    /** Whether any modifier is [ModifierState.ONE_SHOT]. */
    val anyOneShot: Boolean get() = states.values.any { it == ModifierState.ONE_SHOT }

    /**
     * The modifiers for the next key: every modifier that is on. One-shots are turned off; locked
     * ones stay. `shift` is always false (iOS: the keyboard already sends shifted characters).
     */
    fun consume(): KeyModifiers {
        val modifiers = KeyModifiers(
            control = state(ModifierKey.CONTROL) != ModifierState.OFF,
            shift = false,
            leftOption = state(ModifierKey.LEFT_OPTION) != ModifierState.OFF,
            rightOption = state(ModifierKey.RIGHT_OPTION) != ModifierState.OFF,
        )
        if (anyOneShot) {
            for (key in ModifierKey.entries) if (state(key) == ModifierState.ONE_SHOT) turnOff(key)
            onChange()
        }
        return modifiers
    }

    /** Everything off (iOS `reset`, for another session). */
    fun reset() {
        states.clear()
        armedAt.clear()
        onChange()
    }

    private fun turnOff(key: ModifierKey) {
        states[key] = ModifierState.OFF
        armedAt.remove(key)
    }

    companion object {
        /** A second tap sooner than this after the first locks the modifier. */
        const val DOUBLE_TAP_MILLIS = 350L
    }
}
