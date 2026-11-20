package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.stream.TappedLink
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The bubble for a link found under a tap at ([x], [y]) on the canvas (px); [id] tells repeats apart. */
data class LinkBubbleState(val id: Long, val link: TappedLink, val x: Float, val y: Float)

/**
 * The live view's link taps: [tap] looks up the link under a single tap ([lookup], the app's
 * [LinkLookup][dev.bugobliterator.itermbuddy.core.stream.LinkLookup]) in [scope] and shows
 * [bubble] for it; no link, no bubble. One lookup at a time: a newer tap, [cancel] or [dismiss]
 * cancels the one under way (its clear still goes, the lookup's own `finally`), so a late answer
 * never shows over a newer gesture. Main thread.
 */
class LinkTaps(private val scope: CoroutineScope, private val lookup: suspend (SelectionPoint) -> TappedLink?) {
    private val state = MutableStateFlow<LinkBubbleState?>(null)
    private var job: Job? = null
    private var nextId = 1L

    /** The bubble shown now, or null. */
    val bubble: StateFlow<LinkBubbleState?> = state.asStateFlow()

    /** A single tap on terminal [point], at ([x], [y]) on the canvas: looks it up (see the class comment). */
    fun tap(point: SelectionPoint, x: Float, y: Float) {
        dismiss()
        job = scope.launch {
            val link = lookup(point) ?: return@launch
            state.value = LinkBubbleState(nextId++, link, x, y)
        }
    }

    /** Cancels a lookup under way (a drag, a long press), leaving a bubble shown. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Hides the bubble and cancels a lookup under way; true when a bubble was shown. */
    fun dismiss(): Boolean {
        cancel()
        val shown = state.value != null
        state.value = null
        return shown
    }
}

/** What the bubble offers: [note] above the buttons (null: none), and [openLabel] (null: no Open). */
internal data class LinkBubbleContent(val note: String?, val openLabel: String?)

/**
 * The bubble for [link]: a phone link opens in the phone's browser; a Mac-local one opens in the
 * browser session [browserSession] names, and with none says so and offers Copy only.
 */
internal fun linkBubbleContent(link: TappedLink, browserSession: String?): LinkBubbleContent = when {
    !link.macLocal -> LinkBubbleContent(note = null, openLabel = "Open link")
    browserSession != null -> LinkBubbleContent(note = "Mac-only link", openLabel = "Open in $browserSession")
    else -> LinkBubbleContent(note = "Mac-only link — no Browser session open", openLabel = null)
}

/** [url] in at most [max] characters: its middle replaced by `…` when longer. */
internal fun elideLink(url: String, max: Int = LINK_ELIDE_CHARS): String {
    if (url.length <= max || max < 3) return url
    val keep = max - 1
    val head = (keep * 2 + 2) / 3
    return url.take(head) + "…" + url.takeLast(keep - head)
}

private const val LINK_ELIDE_CHARS = 44

/** The clip label for a copied link. */
internal const val LINK_CLIP_LABEL = "Link"

/**
 * The small bubble for a tapped link: the link elided ([elideLink]) on one line, a note when it
 * is Mac-only, then **Open** (as [linkBubbleContent] labels it) and **Copy**. Placed as the
 * selection menu ([menuPosition]) for the tap at [anchor] inside [bounds] (read at layout).
 */
@Composable
internal fun LinkBubble(
    anchor: Offset,
    bounds: () -> Rect4,
    link: TappedLink,
    browserSession: String?,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = linkBubbleContent(link, browserSession)
    Surface(
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(menuPosition(anchor, IntSize(placeable.width, placeable.height), bounds(), BUBBLE_GAP.roundToPx()))
            }
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(
                text = elideLink(link.url),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
            content.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row {
                content.openLabel?.let { label ->
                    TextButton(onClick = onOpen) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        }
    }
}

private val BUBBLE_GAP = 24.dp
