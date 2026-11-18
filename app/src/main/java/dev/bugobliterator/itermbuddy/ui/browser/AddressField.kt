package dev.bugobliterator.itermbuddy.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.ui.live.NoPersonalizedLearning

/**
 * The browser bar's address field, as iOS's `BrowserControlsBar` keeps it
 * (`IOS/Screens/SessionView.swift:1150-1214`): it [shown]s the page's URL unless focused, and
 * then what is typed, whatever the page reports meanwhile; focusing it puts the URL in and
 * selects it all, so typing replaces it, as in Safari ([onFocus]); leaving it without going
 * anywhere restores the URL ([onBlur]); Go opens the trimmed text ([go]). A URL that is null or
 * empty reads as an empty field (upstream decodes an absent URL as "").
 *
 * [selection] and [composition] (the IME's composing span) are the field's while focused, so the
 * value given back to the field keeps the IME's composition and it never restarts input. The tap
 * that focuses a field places a cursor straight after the focus change; the first selection-only
 * change after [onFocus] is ignored so the select-all survives it, and later ones (a second tap)
 * move the cursor. A focus with no tap after it (TalkBack, a hardware keyboard) lets the latch go
 * a frame later ([settled]), so it never eats the owner's first real tap.
 *
 * [toString] redacts [text] (its length only): a URL or what is typed is never logged.
 */
data class AddressFieldState(
    val text: String,
    val focused: Boolean,
    val selection: TextRange = TextRange(text.length),
    val composition: TextRange? = null,
    private val keepSelectAll: Boolean = false,
) {
    /** What the field shows for the page's [url]: the URL, or what is typed while focused. */
    fun shown(url: String?): String = if (focused) text else url.orEmpty()

    /** The field got focus: the page's [url], all selected. */
    fun onFocus(url: String?): AddressFieldState {
        val address = url.orEmpty()
        return AddressFieldState(address, focused = true, selection = TextRange(0, address.length), keepSelectAll = true)
    }

    /** The field lost focus: back to the page's [url]. */
    fun onBlur(url: String?): AddressFieldState = AddressFieldState(url.orEmpty(), focused = false)

    /** The owner typed or moved the cursor: [newText] with [newSelection] and the IME's [newComposition]. Ignored while not focused. */
    fun onEdit(newText: String, newSelection: TextRange, newComposition: TextRange? = null): AddressFieldState {
        if (!focused) return this
        if (keepSelectAll && newText == text) return copy(composition = newComposition, keepSelectAll = false)
        return AddressFieldState(newText, focused = true, selection = newSelection, composition = newComposition)
    }

    /** A frame after the focus: the select-all latch is let go (see the class comment). */
    fun settled(): AddressFieldState = if (keepSelectAll) copy(keepSelectAll = false) else this

    /** What Go opens: the text trimmed of whitespace and newlines; null when nothing is left. */
    fun go(): String? = text.trim().ifEmpty { null }

    override fun toString(): String =
        "AddressFieldState(text(len=${text.length}), focused=$focused, selection=$selection, composition=$composition)"
}

/**
 * The address field: one line in a capsule, "Search or enter website" when empty, a URI keyboard
 * with Go, no autocorrect, no capitals, and no personalised learning (an address is as private as
 * what is typed into a terminal; iOS turns autocorrection off, `SessionView.swift:1150-1156`).
 *
 * @param url the page's URL ([BrowserNavigationState.url][dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState.url]).
 * @param onUpdate applies a change (focus, typing) to the caller's current state, which may be
 *   newer than [state] (a focus and the focusing tap's cursor arrive in one frame).
 * @param onGo Go pressed: the caller loads [AddressFieldState.go] and clears the focus.
 */
@Composable
internal fun AddressField(
    state: AddressFieldState,
    url: String?,
    enabled: Boolean,
    onUpdate: ((AddressFieldState) -> AddressFieldState) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) = NoPersonalizedLearning {
    val colors = MaterialTheme.colorScheme
    val shown = state.shown(url)
    val value = if (state.focused) TextFieldValue(shown, state.selection, state.composition) else TextFieldValue(shown, TextRange(shown.length))
    // The focusing tap's cursor lands in the same frame as the focus; after that the latch goes.
    LaunchedEffect(state.focused) {
        if (state.focused) {
            withFrameNanos { }
            onUpdate { it.settled() }
        }
    }
    val style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    BasicTextField(
        value = value,
        onValueChange = { edit -> onUpdate { it.onEdit(edit.text, edit.selection, edit.composition) } },
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .onFocusChanged { focus ->
                onUpdate { now ->
                    when {
                        focus.isFocused == now.focused -> now
                        focus.isFocused -> now.onFocus(url)
                        else -> now.onBlur(url)
                    }
                }
            }
            .semantics { contentDescription = "Address" },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = FIELD_ALPHA), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = "Search or enter website",
                        style = style.copy(color = colors.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** The capsule's white over the dark bar (iOS `Color.white.opacity(0.14)`). */
private const val FIELD_ALPHA = 0.14f
