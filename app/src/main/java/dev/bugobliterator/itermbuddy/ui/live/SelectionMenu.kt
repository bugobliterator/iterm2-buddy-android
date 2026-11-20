package dev.bugobliterator.itermbuddy.ui.live

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.stream.CopyOutcome
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlin.math.roundToInt

/** The clip label for copied terminal text. */
internal const val SELECTION_CLIP_LABEL = "Terminal selection"

/** The snackbar for [outcome], or null when the system's own confirmation is enough. */
internal fun copyMessage(outcome: CopyOutcome): String? = when (outcome) {
    CopyOutcome.COPIED -> null
    CopyOutcome.NOTHING_SELECTED -> "Nothing selected"
    CopyOutcome.FAILED -> "Couldn't copy the selection"
}

/**
 * Where the menu, [menu] in size, goes for a press at [anchor]: centred over it, [gap] above, or
 * [gap] below when there is no room above; kept inside [bounds] (left, top, right, bottom, px) as
 * far as it fits. All in the canvas's pixels.
 */
internal fun menuPosition(anchor: Offset, menu: IntSize, bounds: Rect4, gap: Int): IntOffset {
    val x = (anchor.x - menu.width / 2f).roundToInt()
    val above = (anchor.y - gap - menu.height).roundToInt()
    val y = if (above >= bounds.top) above else (anchor.y + gap).roundToInt()
    val maxX = maxOf(bounds.left, bounds.right - menu.width)
    val maxY = maxOf(bounds.top, bounds.bottom - menu.height)
    return IntOffset(x.coerceIn(bounds.left, maxX), y.coerceIn(bounds.top, maxY))
}

/** A pixel rectangle by its edges. */
internal data class Rect4(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The selection's floating toolbar, as iOS's edit menu (`IOS/Screens/SessionView.swift:2935-2948`):
 * **Copy**, **Select All** and **Paste** (Paste only while input is offered and the clipboard
 * holds text), placed by [menuPosition] for a press at [anchor] inside [bounds] (read at layout,
 * so the keyboard moving them does not recompose).
 */
@Composable
internal fun SelectionMenu(
    anchor: Offset,
    bounds: () -> Rect4,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val position = menuPosition(anchor, IntSize(placeable.width, placeable.height), bounds(), MENU_GAP.roundToPx())
                placeable.place(position)
            }
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Row {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onSelectAll) { Text("Select All") }
            if (canPaste) TextButton(onClick = onPaste) { Text("Paste") }
        }
    }
}

private val MENU_GAP = 24.dp

/**
 * Puts [text] on the clipboard as [label], marked sensitive
 * ([ClipDescription.EXTRA_IS_SENSITIVE]) so the system's confirmation and keyboard suggestions
 * do not show it. Never logged.
 */
internal fun copyToClipboard(context: Context, text: String, label: String = SELECTION_CLIP_LABEL) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** Whether the clipboard holds text, from its description alone (no read, so no access notice). */
internal fun clipboardHasText(context: Context): Boolean =
    context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.hasMimeType("text/*") == true

/** The clipboard's first item as text, or null when there is none or it is empty. */
internal fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
}

/**
 * One long press's selection, for the live view: [begin] reports whether a begin was sent, and
 * [move] and [end] act only after one was (iOS `guard selecting`, `SessionView.swift:2826, 2840`);
 * [end] uses the last mapped point when the lift cannot be mapped (iOS `lastDragLivePoint`) and
 * says whether to show the menu.
 */
internal class SelectionPress(private val driver: SelectionDriver) {
    private var began = false
    private var last: SelectionPoint? = null

    fun begin(point: SelectionPoint?): Boolean {
        last = point
        began = point != null && driver.begin(point)
        return began
    }

    fun move(point: SelectionPoint?) {
        if (!began || point == null) return
        last = point
        driver.move(point)
    }

    /** True when a selection was begun: the menu shows. */
    fun end(point: SelectionPoint?): Boolean {
        if (!began) return false
        began = false
        (point ?: last)?.let(driver::end)
        last = null
        return true
    }
}
