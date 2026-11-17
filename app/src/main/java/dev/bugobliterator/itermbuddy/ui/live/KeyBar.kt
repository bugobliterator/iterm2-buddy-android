package dev.bugobliterator.itermbuddy.ui.live

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.input.ModifierKey
import dev.bugobliterator.itermbuddy.core.input.ModifierState
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.core.input.StickyModifiers

/** What the key bar's keys do; every key goes through `ImeTranslator.keyBar` so held composing text goes first. */
interface KeyBarActions {
    fun key(event: KeyEvent)

    fun modifier(key: ModifierKey)

    fun compose()

    fun toggleExpanded()

    fun hideKeyboard()
}

/** ^C: `text "c"` with `control`, exactly (iOS `sendControlC`). */
val CONTROL_C = KeyEvent(Key.Text("c"), KeyModifiers(control = true))

/**
 * The key bar above the soft keyboard, exactly iOS's set (`SessionKeyboardAccessory.swift`):
 * the compact row `esc`, `^C`, `tab`, ← ↓ ↑ →, compose, expand/collapse, hide keyboard; the tray
 * `ctrl`, `⌥ Left`, `⌥ Right` (sticky: armed keys are tinted, locked ones also underlined),
 * `home`, `end`, `del` (forward delete), `PgUp`, `PgDn` and F1–F12. Special keys carry the sticky
 * modifiers, applied by the translator.
 *
 * @param modifierVersion changes whenever [modifiers] does, so the tray redraws.
 */
@Composable
fun KeyBar(
    modifiers: StickyModifiers,
    modifierVersion: Int,
    expanded: Boolean,
    actions: KeyBarActions,
    modifier: Modifier = Modifier,
) {
    fun special(key: SpecialKey) = { actions.key(KeyEvent(Key.Special(key))) }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            if (expanded) {
                KeyRow {
                    ModifierCap("⌃ctrl", "Control", modifiers.state(ModifierKey.CONTROL)) { actions.modifier(ModifierKey.CONTROL) }
                    ModifierCap("⌥Left", "Left Option", modifiers.state(ModifierKey.LEFT_OPTION)) { actions.modifier(ModifierKey.LEFT_OPTION) }
                    ModifierCap("⌥Right", "Right Option", modifiers.state(ModifierKey.RIGHT_OPTION)) { actions.modifier(ModifierKey.RIGHT_OPTION) }
                    KeyCap("home", "Home", special(SpecialKey.HOME))
                    KeyCap("end", "End", special(SpecialKey.END))
                    KeyCap("del", "Forward delete", special(SpecialKey.FORWARD_DELETE))
                }
                KeyRow {
                    KeyCap("PgUp", "Page up", special(SpecialKey.PAGE_UP))
                    KeyCap("PgDn", "Page down", special(SpecialKey.PAGE_DOWN))
                }
                KeyRow { for (key in FUNCTION_KEYS.take(6)) KeyCap(key.label, key.label, special(key.key)) }
                KeyRow { for (key in FUNCTION_KEYS.drop(6)) KeyCap(key.label, key.label, special(key.key)) }
            }
            KeyRow {
                KeyCap("esc", "Escape", special(SpecialKey.ESCAPE))
                KeyCap("^C", "Control C", { actions.key(CONTROL_C) })
                KeyCap("tab", "Tab", special(SpecialKey.TAB))
                Divider()
                KeyCap("←", "Left", special(SpecialKey.LEFT))
                KeyCap("↓", "Down", special(SpecialKey.DOWN))
                KeyCap("↑", "Up", special(SpecialKey.UP))
                KeyCap("→", "Right", special(SpecialKey.RIGHT))
                Divider()
                KeyCap("✎", "Compose", actions::compose)
                KeyCap(if (expanded) "⌄" else "⌃", if (expanded) "Fewer keys" else "More keys", actions::toggleExpanded)
                KeyCap("⌨↓", "Hide keyboard", actions::hideKeyboard)
            }
        }
    }
}

/** The modifier tap with the clock the double tap is timed against. */
fun StickyModifiers.tapNow(key: ModifierKey) = tap(key, SystemClock.uptimeMillis())

private class FunctionKey(val label: String, val key: SpecialKey)

private val FUNCTION_KEYS = listOf(
    SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4, SpecialKey.F5, SpecialKey.F6,
    SpecialKey.F7, SpecialKey.F8, SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12,
).map { FunctionKey(it.raw.uppercase(), it) }

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.Divider() {
    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun RowScope.KeyCap(label: String, description: String, onClick: () -> Unit) {
    Cap(label, description, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, null, onClick)
}

@Composable
private fun RowScope.ModifierCap(label: String, description: String, state: ModifierState, onClick: () -> Unit) {
    val on = state != ModifierState.OFF
    Cap(
        label = label,
        description = description,
        background = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        foreground = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        state = when (state) {
            ModifierState.OFF -> "off"
            ModifierState.ONE_SHOT -> "on for the next key"
            ModifierState.LOCKED -> "locked"
        },
        onClick = onClick,
        underline = state == ModifierState.LOCKED,
    )
}

@Composable
private fun RowScope.Cap(
    label: String,
    description: String,
    background: Color,
    foreground: Color,
    state: String?,
    onClick: () -> Unit,
    underline: Boolean = false,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (underline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .width(16.dp)
                    .height(2.dp)
                    .background(foreground),
            )
        }
    }
}
