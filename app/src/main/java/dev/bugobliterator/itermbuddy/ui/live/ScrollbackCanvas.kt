package dev.bugobliterator.itermbuddy.ui.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.stream.DecodedTileCache
import dev.bugobliterator.itermbuddy.core.stream.HISTORY_SLOT_LINES
import dev.bugobliterator.itermbuddy.core.stream.HistoryGeometry
import dev.bugobliterator.itermbuddy.core.stream.HistoryTileStore
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.TileState
import dev.bugobliterator.itermbuddy.core.stream.wheelStepPx
import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/** iOS's wheel step before any layout: 24 pt (`IOS/Screens/SessionView.swift:2444`). */
private val WHEEL_FALLBACK_STEP = 24.dp

/** A history slot not drawn yet. */
private val LOADING_TILE = Color(0xFF0D0D0D)

/** A history slot whose fetch failed. */
private val FAILED_TILE = Color(0xFF1C0C0C)

/**
 * What the live view's chrome needs from the scrollback: whether to offer **↓ Live** (scrolled up
 * over history), and a way to ask for the bottom again. Snapshot state, written by
 * [ScrollbackCanvas] only when it changes.
 */
@Stable
class ScrollbackControl {
    /** History is showing and the live band's bottom is not in view. */
    var offerLive: Boolean by mutableStateOf(false)
        internal set

    /** Bumped by [jumpToLive]; the canvas scrolls to the bottom on each change. */
    internal var jumps: Int by mutableIntStateOf(0)
        private set

    /** Back to the live band, at the bottom. */
    fun jumpToLive() {
        jumps++
    }

    /** Bumped by [stopCoast]; the canvas stops a coasting fling on each change. */
    internal var coastStops: Int by mutableIntStateOf(0)
        private set

    /** Stops a coasting fling (a key was typed). */
    fun stopCoast() {
        coastStops++
    }
}

/**
 * A terminal session's live view as a scrollable document (iOS `SessionView.swift:2041-2112,
 * 2466-2620`): the [VideoCanvas] with the history tiles of [HistoryTileStore] drawn above the live
 * band, opening at the bottom.
 *
 * **Layout** (no letterbox gap). Each line is `fit.height / config.rows` tall; the history and
 * the band share the fit rect's horizontal span; the band (the video surface) stays at the fit
 * rect, and the history's height is the store's [HistoryGeometry] laid out with that line height
 * ([ScrollbackLayout.liveBandTopPx]). A tile is drawn at its own first line, its fetched
 * `lineCount` lines tall; a slot still loading or failed is a dark placeholder.
 *
 * **Tiles.** What is shown ([DocumentView]) gives the wanted lines ([wantedLines]: the visible ones
 * and one viewport each way), passed to [HistoryTileStore.want] and, with every change of the
 * tiles or the geometry, to a [ScrollbackTiles] that decodes PNGs on a background dispatcher, at
 * most two at once, through a [DecodedTileCache] (96 MiB, displayed bitmaps included). Only the
 * slots on screen ([visibleSlots], derived so the draw reruns only when that set changes) are
 * drawn, and every time the history is drawn exactly the pictures drawn are reported
 * ([ScrollbackTiles.displayed]), so pictures off screen can be evicted for room. When no history
 * is drawn (the alternate screen, a cleared or ended stream) nothing is reported displayed; when
 * the app stops the holder is reset, and leaving the view closes it, dropping every bitmap.
 *
 * **↓ Live.** [control] offers it while history shows and the band's bottom is out of view;
 * growth keeps the band pinned only while it is at the bottom ([followDocument]).
 *
 * **Wheel.** With `altScreen && scrollWheelReporting` there is no history and a one-finger drag
 * sends notches through [onWheel] (see [VideoCanvas]), one step per displayed line (24 dp before
 * any layout).
 *
 * **Momentum.** A flick coasts (see [VideoCanvas]); the lines between the view and where a
 * scrollback coast will rest are asked for at once ([prefetchLines]). [control]'s
 * [ScrollbackControl.stopCoast] and a change of stream stop a coast.
 *
 * **Selection** ([onSelection] non-null, a terminal with cell geometry and the store's geometry
 * for it). A long press (see [VideoCanvas]) maps each phase's screen point to a terminal point
 * with [selectionPointAt]: over the live band through the video's cells from the live top, over
 * history in the tile's own space (tiles have no margins), clamped to the document. The point is
 * null only when nothing can be mapped (no layout yet).
 *
 * The geometry is used only while it is [config]'s stream. Never logs tile content.
 */
@Composable
fun ScrollbackCanvas(
    source: LiveVideoSource,
    store: HistoryTileStore,
    config: StreamConfig?,
    sessionGuid: String,
    control: ScrollbackControl,
    onCanvasSize: (IntSize) -> Unit,
    onWheel: (up: Boolean, lines: Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    topInset: Float = 0f,
    restBottomInset: Float = 0f,
    bottomInset: () -> Float = { restBottomInset },
    leftInset: Float = 0f,
    rightInset: Float = 0f,
    onSelection: ((phase: SelectionPhase, point: SelectionPoint?, x: Float, y: Float) -> Unit)? = null,
) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val streamGeometry by store.geometry.collectAsStateWithLifecycle()
    val tiles by store.tiles.collectAsStateWithLifecycle()
    val terminal = config?.takeIf { !it.isBrowser && it.rows > 0 && it.pixelWidth > 0 && it.pixelHeight > 0 }
    val geometry = streamGeometry?.takeIf { terminal != null && it.streamID == terminal.streamID }
    val fit = if (terminal == null) {
        FitRect.EMPTY
    } else {
        visibleFitRect(terminal.pixelWidth, terminal.pixelHeight, canvas.width.toFloat(), canvas.height.toFloat(), topInset, restBottomInset, leftInset, rightInset)
    }
    val lineHeightPx = if (terminal == null || fit.isEmpty) 0f else fit.height / terminal.rows
    val layout = if (geometry == null || lineHeightPx <= 0f) null else geometry.layout(lineHeightPx)
    val historyPx = layout?.liveBandTopPx ?: 0f
    val wheelStep = if (terminal != null && terminal.altScreen && terminal.scrollWheelReporting) {
        wheelStepPx(lineHeightPx, with(LocalDensity.current) { WHEEL_FALLBACK_STEP.toPx() })
    } else {
        null
    }

    val scope = rememberCoroutineScope()
    val holder = remember(store) {
        ScrollbackTiles(DecodedTileCache<ImageBitmap>(), scope, Dispatchers.Default, ::decodedPngBytes, { tile -> decodePng(tile) })
    }
    DisposableEffect(holder) { onDispose { holder.close() } }
    // In the background no bitmap is kept; back in front, what is wanted decodes again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { holder.reset() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { holder.refresh() }
    // Read in the draw only to redraw on a change; the draw takes the holder's current list.
    val drawnSignal = holder.drawn.collectAsStateWithLifecycle()
    // What is shown, written per frame while scrolling and read only by the flow and the derived slots below.
    var view by remember { mutableStateOf<DocumentView?>(null) }
    // Where a coasting fling will rest, while one coasts.
    var coastTo by remember { mutableStateOf<DocumentView?>(null) }
    val currentLayout by rememberUpdatedState(layout)
    val currentTiles by rememberUpdatedState(tiles)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentTerminal by rememberUpdatedState(terminal)
    val currentOnSelection by rememberUpdatedState(onSelection)
    val selectable = onSelection != null && terminal?.cellGeometry != null
    // The slots on screen: the draw is invalidated only when this set changes, not per frame.
    val visibleSlots by remember {
        derivedStateOf {
            val shown = currentLayout
            val now = view
            if (shown == null || now == null || shown.historyLines == 0L) {
                emptySet()
            } else {
                visibleSlots(shown, now, currentTiles.keys)
            }
        }
    }
    LaunchedEffect(holder) {
        snapshotFlow {
            currentLayout?.takeIf { it.historyLines > 0 }?.let { shown -> view?.let { wantedLines(shown, it) to prefetchLines(shown, it, coastTo) } }
        }
            .combine(snapshotFlow { currentTiles to currentGeometry }) { wanted, (tiles, geometry) -> Triple(wanted, tiles, geometry) }
            .collect { (wanted, tiles, geometry) ->
                if (wanted != null) store.want(wanted.second.first, wanted.second.last)
                holder.update(tiles, geometry, wanted?.first ?: LongRange.EMPTY)
            }
    }
    LaunchedEffect(holder) {
        // No history drawn (the alternate screen, a clear): nothing is displayed any more.
        snapshotFlow { currentLayout?.historyLines ?: 0L }.collect { if (it == 0L) holder.displayed(emptyList()) }
    }

    VideoCanvas(
        source = source,
        imageWidth = config?.pixelWidth ?: 0,
        imageHeight = config?.pixelHeight ?: 0,
        sessionGuid = sessionGuid,
        onCanvasSize = {
            canvas = it
            onCanvasSize(it)
        },
        modifier = modifier,
        onTap = onTap,
        topInset = topInset,
        restBottomInset = restBottomInset,
        bottomInset = bottomInset,
        leftInset = leftInset,
        rightInset = rightInset,
        historyHeightPx = historyPx,
        drawHistory = { bandLeft, bandTop, bandWidth ->
            drawnSignal.value
            val shown = currentLayout
            val slots = visibleSlots
            // Only what is on screen is drawn and reported, so the rest can be evicted for room.
            val pictures = if (shown == null) emptyList() else holder.drawn.value.filter { it.slot in slots }
            if (shown != null) drawHistory(shown, slots, currentTiles, pictures, bandLeft, bandTop, bandWidth)
            holder.displayed(pictures.map { it.tile })
        },
        onDocumentView = {
            view = it
            val offer = historyPx > 0f && !it.atLiveBottom
            if (control.offerLive != offer) control.offerLive = offer
        },
        jumpToLive = control.jumps,
        wheelStepPx = wheelStep,
        onWheel = onWheel,
        onCoastTarget = { coastTo = it },
        stopCoast = { control.coastStops to currentTerminal?.streamID },
        onSelect = if (!selectable) {
            null
        } else {
            { phase, x, y, shown, bandFit ->
                val shownLayout = currentLayout
                val shownConfig = currentTerminal
                val point = if (shownLayout == null || shownConfig == null) null else selectionPointAt(x, y, shown, bandFit, shownLayout, shownConfig)
                currentOnSelection?.invoke(phase, point, x, y)
            }
        },
    )
}

/**
 * The history above a band at ([bandLeft], [bandTop]), [bandWidth] wide, for the visible [slots]:
 * first each slot's expected span as a placeholder (dark; reddish when it failed), so a refetching
 * tail or a tile not decoded yet never shows a hole, then the [pictures] over it, each at its own
 * lines. Rows are rounded from line numbers, so neighbouring tiles share an edge and the last
 * meets the band.
 */
private fun DrawScope.drawHistory(
    layout: ScrollbackLayout,
    slots: Set<Long>,
    tiles: Map<Long, TileState>,
    pictures: List<DrawnTile<ImageBitmap>>,
    bandLeft: Float,
    bandTop: Float,
    bandWidth: Float,
) {
    val top = bandTop - layout.liveBandTopPx
    val historyEnd = layout.firstAbsLine + layout.historyLines
    for (slot in slots) {
        val first = maxOf(slot, layout.firstAbsLine)
        val end = minOf(slot + HISTORY_SLOT_LINES, historyEnd)
        if (end <= first) continue
        val y = top + layout.tileTopPx(first)
        drawRect(
            color = if (tiles[slot] == TileState.Failed) FAILED_TILE else LOADING_TILE,
            topLeft = Offset(bandLeft, y),
            size = Size(bandWidth, top + layout.tileTopPx(end) - y),
        )
    }
    val left = bandLeft.roundToInt()
    val width = bandWidth.roundToInt()
    for (picture in pictures) {
        val tile = picture.tile
        val y = (top + layout.tileTopPx(tile.firstAbsLine)).roundToInt()
        val bottom = (top + layout.tileBottomPx(tile.firstAbsLine, tile.lineCount)).roundToInt()
        if (bottom <= y || width <= 0) continue
        val image = picture.handle
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left, y),
            dstSize = IntSize(width, bottom - y),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** A tile PNG's decoded size (ARGB_8888, as [decodePng] decodes it), from its header alone; null when it is not an image. */
private fun decodedPngBytes(tile: HistoryTile): Long? = tile.withPng { bytes, offset, length ->
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toLong() * bounds.outHeight * 4 else null
}

/** A tile's PNG as an ARGB_8888 bitmap, or null when it does not decode. */
private fun decodePng(tile: HistoryTile): ImageBitmap? = tile.withPng { bytes, offset, length ->
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    BitmapFactory.decodeByteArray(bytes, offset, length, options)?.asImageBitmap()
}
