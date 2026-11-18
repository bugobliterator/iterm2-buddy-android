package dev.bugobliterator.itermbuddy.ui.live

import android.view.Surface
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.stream.HistoryTileStore
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.ui.settings.decoderRowText
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app-scoped pieces the live view reads directly, outside the home model, so the decoder's
 * per-frame stats and the live top recompose only what shows them: the process's [output], the
 * stream controller's [state] and its [historyTiles] (null: no scrollback), and the app's
 * [selection] driver (null: no selection).
 */
class LiveVideoSource(
    val output: HevcVideoOutput,
    val state: StateFlow<LiveViewState>,
    val historyTiles: HistoryTileStore? = null,
    val selection: SelectionDriver? = null,
) {
    /** [videoVisible] now, for the view of [sessionGuid]. */
    fun visible(sessionGuid: String): Boolean = videoVisible(state, output.stats, sessionGuid)

    /** [videoVisible] for [sessionGuid] on every change of either flow, re-read in [videoVisible]'s order. */
    fun visibility(sessionGuid: String): Flow<Boolean> =
        combine(state, output.stats) { _, _ -> visible(sessionGuid) }.distinctUntilChanged()

    /** Settings' Decoder row now ([decoderRowText]), the initial value before its flow is collected. */
    fun decoderRowNow(): String = decoderRowText(output.stats.value)
}

/**
 * The live picture: a TextureView-backed [AndroidEmbeddedExternalSurface] sized to the image's
 * [visibleFitRect] in the canvas (the whole window; the insets are the bars over it), zoomed and
 * panned through `graphicsLayer` ([ZoomState]): pinch and drag with [detectTransformGestures],
 * double tap toggles fit ↔ [DOUBLE_TAP_SCALE] at the tap. With no image size yet ([imageWidth]
 * or [imageHeight] 0) the surface fills the visible area inside the insets, so a surface exists
 * before the first config and the decoder can start at once.
 *
 * **Zoom.** Keyed by [nextZoomKey]: the session and the last non-empty image size. A reconnect,
 * retry or lock (no config for a while: 0×0) keeps the zoom and the fit; a real size change or
 * another session resets it to fit. A canvas size change keeps it, clamped ([clampZoom]).
 *
 * **Surface lifecycle.** The surface is attached to [LiveVideoSource.output] when it is created,
 * never on a size change, and detached when it is destroyed or this leaves composition. Nothing
 * here opens or closes the stream: a rotation destroys the surface (the decoder is released, the
 * controller acks on as WaitingForSurface) and the new activity's surface attaches again, which
 * asks for a keyframe.
 *
 * **Stale frames.** The surface stays composed (so the decoder has somewhere to render its first
 * frame) but is invisible unless [LiveVideoSource.visibility] says otherwise; a black scrim
 * covers it too.
 *
 * @param sessionGuid the session shown: the zoom's key and the stale-frame rule's owner.
 * @param onCanvasSize the canvas size in px, on every change.
 * @param onTap a single tap anywhere on the canvas (after the double-tap timeout passes).
 * @param topInset the status bar and top bar drawn over the canvas's top, in px.
 * @param leftInset the system bars and display cutout over the canvas's left edge, in px.
 * @param rightInset the same over its right edge, in px.
 * @param restBottomInset what covers the canvas's bottom with the keyboard hidden (the navigation
 *   bar), in px. Fit is placed between the two insets, top-aligned ([visibleFitRect]).
 * @param bottomInset what covers the canvas's bottom now, in px: [restBottomInset], or the
 *   keyboard and key bar while typing. Read
 *   only while laying out the layer and at each gesture event, never in composition, so the
 *   picture follows the IME animation frame by frame without recomposing or restarting the
 *   gesture detectors.
 *
 * **Keyboard lift.** The canvas never shrinks for the keyboard. The zoom kept here remembers the
 * inset it was made for; what is shown is [liftForKeyboard] of it to the current inset, so a
 * picture whose bottom was in view stays pinned just above the key bar while the keyboard opens
 * (sliding up, its top may leave the screen), the owner can pan up to the top, and when the
 * keyboard closes the picture returns to where it was (fit at scale 1). A tap maps to the image
 * with [viewToImage] of the shown state ([shownZoom]), so it accounts for the lift.
 *
 * **Scrollback document** ([ScrollbackCanvas]). With [historyHeightPx] above 0 the content is the
 * [documentFit]: [drawHistory] paints the history above the band (given the band's left, top and
 * width as the surface is placed, so the last history line meets the band's first), and zoom,
 * pan, the clamp and the keyboard lift all treat the document as the content. The kept zoom
 * remembers the history height it was made for and is rebased on every change of it
 * ([rebaseKept]), so growth keeps the live band pinned only when it was at the bottom at that
 * change. [onDocumentView] hears what is shown
 * ([documentView]) on every change, outside composition; each change of [jumpToLive] scrolls the
 * band back to the bottom ([scrollToLive]).
 *
 * **Wheel** ([wheelStepPx] non-null: the alternate screen with mouse-wheel reporting). A
 * one-finger drag pans a zoomed picture first, and the travel past the pan edge ([wheelDrag])
 * becomes notches, [onWheel] once per drag step that reaches one, with the notch count of that
 * step (iOS `accrueWheel`). A gesture that has had two fingers down only zooms and pans.
 *
 * **Selection** ([onSelect] non-null). One finger held still for [SELECTION_HOLD_MILLIS]
 * ([holdCancelled] otherwise: it moved, a second finger came down, or it lifted) begins a
 * selection; the same press then drags it and lifting ends it, each phase given with the screen
 * point and the zoom and fit as shown. From the hold on, every change is consumed in the initial
 * pass, so the pan, wheel and tap detectors see the gesture cancelled.
 */
@Composable
fun VideoCanvas(
    source: LiveVideoSource,
    imageWidth: Int,
    imageHeight: Int,
    sessionGuid: String,
    onCanvasSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    topInset: Float = 0f,
    restBottomInset: Float = 0f,
    bottomInset: () -> Float = { restBottomInset },
    leftInset: Float = 0f,
    rightInset: Float = 0f,
    historyHeightPx: Float = 0f,
    drawHistory: (DrawScope.(bandLeft: Float, bandTop: Float, bandWidth: Float) -> Unit)? = null,
    onDocumentView: (DocumentView) -> Unit = {},
    jumpToLive: Int = 0,
    wheelStepPx: Float? = null,
    onWheel: (up: Boolean, lines: Int) -> Unit = { _, _ -> },
    onSelect: ((phase: SelectionPhase, x: Float, y: Float, shown: ZoomState, fit: FitRect) -> Unit)? = null,
) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val viewW = canvas.width.toFloat()
    val viewH = canvas.height.toFloat()
    // Not snapshot state: the key is a pure function of its inputs and the previous key.
    val keyHolder = remember { arrayOfNulls<ZoomKey>(1) }
    val zoomKey = nextZoomKey(keyHolder[0], sessionGuid, imageWidth, imageHeight).also { keyHolder[0] = it }
    val image = zoomKey.image
    val fit = if (image == null) FitRect.EMPTY else visibleFitRect(image.width, image.height, viewW, viewH, topInset, restBottomInset, leftInset, rightInset)
    // No image size yet: the surface fills the visible area, so the decoder has one at once.
    val surfaceRect = if (fit.isEmpty) FitRect(leftInset, topInset, viewW - leftInset - rightInset, viewH - topInset - restBottomInset) else fit
    val max = maxScale(image?.width ?: 0, fit)
    val history = if (fit.isEmpty || !historyHeightPx.isFinite()) 0f else maxOf(0f, historyHeightPx)
    // Read in the layer and the gesture handlers only (see bottomInset).
    var kept by remember(zoomKey) { mutableStateOf(KeptZoom(ZoomState.FIT, null)) }
    val visible by remember(source, sessionGuid) { source.visibility(sessionGuid) }
        .collectAsStateWithLifecycle(initialValue = source.visible(sessionGuid))
    val density = LocalDensity.current
    val currentOnTap by rememberUpdatedState(onTap)
    val geometry by rememberUpdatedState(GestureGeometry(fit, viewW, viewH, max, topInset, restBottomInset, leftInset, rightInset, history))
    val currentInset by rememberUpdatedState(bottomInset)
    val wheel = remember(wheelStepPx) { wheelStepPx?.let { ScrollWheelAccumulator(it) } }
    val currentWheel by rememberUpdatedState(wheel)
    val currentOnWheel by rememberUpdatedState(onWheel)
    val currentOnDocumentView by rememberUpdatedState(onDocumentView)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectable = onSelect != null
    LaunchedEffect(zoomKey) {
        snapshotFlow {
            val g = geometry
            val inset = currentInset()
            documentView(shownZoom(kept, g, inset), g.fit, g.history, g.viewH, inset)
        }.collect { currentOnDocumentView(it) }
    }
    LaunchedEffect(zoomKey) {
        // Rebase the kept zoom on every change of the history height, so whether the band was at
        // the bottom is judged at each change, not only at the last gesture (see rebaseKept).
        snapshotFlow { geometry.history }.collect { kept = rebaseKept(kept, geometry) }
    }
    LaunchedEffect(zoomKey, jumpToLive) {
        if (jumpToLive == 0) return@LaunchedEffect
        val g = geometry
        val inset = currentInset()
        kept = KeptZoom(scrollToLive(shownZoom(kept, g, inset), g.fit, g.history, g.viewW, g.viewH, inset, g.top, g.left, g.right), inset, g.history)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged {
                canvas = it
                onCanvasSize(it)
            }
            // Keyed on the zoom key only: the canvas changes every frame while the keyboard
            // animates, and restarting the detectors then would drop a gesture in progress. The
            // geometry is read at each event instead.
            .pointerInput(zoomKey) {
                var multiTouch = false
                detectZoomPan(
                    onStart = {
                        multiTouch = false
                        currentWheel?.reset()
                    },
                    onEnd = { currentWheel?.reset() },
                ) { centroid, pan, gestureZoom, pointers ->
                    val step = gestureStep(kept, geometry, currentInset(), centroid.x, centroid.y, pan.x, pan.y, gestureZoom, pointers, multiTouch, currentWheel)
                    multiTouch = step.multiTouch
                    kept = step.kept
                    if (step.notches.isNotEmpty()) currentOnWheel(step.notches.first(), step.notches.size)
                }
            }
            .pointerInput(zoomKey) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val g = geometry
                        val inset = currentInset()
                        val shown = shownZoom(kept, g, inset)
                        // Zooming out over history keeps the line under the tap rather than jump to the live band.
                        val next = if (g.history > 0f && shown.scale > 1f) {
                            applyGesture(shown, tap.x, tap.y, 1f / shown.scale, 0f, 0f, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
                        } else {
                            toggleZoom(shown, tap.x, tap.y, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
                        }
                        kept = KeptZoom(next, inset, g.history)
                    },
                    onTap = { currentOnTap() },
                )
            }
            .pointerInput(zoomKey, selectable) {
                if (!selectable) return@pointerInput
                detectSelectionHold { phase, position ->
                    val g = geometry
                    currentOnSelect?.invoke(phase, position.x, position.y, shownZoom(kept, g, currentInset()), g.fit)
                }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val zoom = shownZoom(kept, geometry, currentInset())
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offsetX
                translationY = zoom.offsetY
            },
        ) {
            if (surfaceRect.width >= 1f && surfaceRect.height >= 1f) {
                val left = surfaceRect.left.roundToInt()
                val top = surfaceRect.top.roundToInt()
                if (drawHistory != null && history > 0f) {
                    // Drawn above the box's top (the layer does not clip); placed from the surface's
                    // rounded corner so the history's last line meets the band's first.
                    Spacer(Modifier.fillMaxSize().drawBehind { drawHistory(left.toFloat(), top.toFloat(), surfaceRect.width) })
                }
                VideoSurface(
                    output = source.output,
                    modifier = Modifier
                        .offset { IntOffset(left, top) }
                        .size(with(density) { surfaceRect.width.toDp() }, with(density) { surfaceRect.height.toDp() })
                        .graphicsLayer { alpha = if (visible) 1f else 0f },
                )
            }
        }
        if (!visible) Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * The TextureView surface for [output]: [HevcVideoOutput.attachSurface] on creation only (not on
 * size changes), [HevcVideoOutput.detachSurface] on destruction, or on leaving composition if the
 * destruction callback has not run by then. Never more than one detach per attach. On destruction
 * the surface is released once the decoder has let go of it (detach waits for that).
 */
@Composable
private fun VideoSurface(output: HevcVideoOutput, modifier: Modifier) {
    val attached = remember { AtomicReference<Surface?>(null) }
    AndroidEmbeddedExternalSurface(modifier = modifier) {
        onSurface { surface, _, _ ->
            attached.set(surface)
            output.attachSurface(surface)
            surface.onDestroyed {
                if (attached.compareAndSet(this, null)) output.detachSurface()
                release()
            }
        }
    }
    DisposableEffect(output) {
        onDispose {
            if (attached.getAndSet(null) != null) output.detachSurface()
        }
    }
}

/**
 * A selection press: one finger held still for [SELECTION_HOLD_MILLIS] ([holdCancelled] decides
 * when it is something else), then [onPhase] `BEGIN` where it is, `MOVE` on each move and `END`
 * where it lifts (or where it last was, if the gesture is cancelled). From the hold on, every
 * change is consumed in the initial pass, before the pan and tap detectors see it.
 */
private suspend fun PointerInputScope.detectSelectionHold(onPhase: (SelectionPhase, Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var cancelled = false
        withTimeoutOrNull(SELECTION_HOLD_MILLIS) {
            while (!cancelled) {
                val event = awaitPointerEvent()
                val finger = event.changes.firstOrNull { it.id == down.id }
                val travel = finger?.let { (it.position - down.position).getDistance() } ?: Float.NaN
                val released = finger == null || !finger.pressed
                cancelled = holdCancelled(travel, event.changes.count { it.pressed }, released, slop)
            }
        }
        if (cancelled) return@awaitEachGesture
        var last = currentEvent.changes.firstOrNull { it.id == down.id }?.position ?: down.position
        onPhase(SelectionPhase.BEGIN, last)
        var ended = false
        try {
            while (!ended) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val finger = event.changes.firstOrNull { it.id == down.id }
                if (finger == null || !finger.pressed) {
                    ended = true
                    onPhase(SelectionPhase.END, finger?.position ?: last)
                } else if (finger.position != last) {
                    last = finger.position
                    onPhase(SelectionPhase.MOVE, last)
                }
            }
        } finally {
            // Cancelled mid-drag (the detector restarted): finish where the finger last was.
            if (!ended) onPhase(SelectionPhase.END, last)
        }
    }
}

/**
 * Pinch and drag, as `detectTransformGestures` (touch slop, then centroid, pan and zoom per event,
 * no rotation), also giving how many pointers are down and when a gesture past the slop starts and
 * ends (the wheel's accumulator resets then).
 */
private suspend fun PointerInputScope.detectZoomPan(
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, pointers: Int) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        try {
            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                if (!canceled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange
                        val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                        if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                            pastTouchSlop = true
                            onStart()
                        }
                    }
                    if (pastTouchSlop) {
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            onGesture(event.calculateCentroid(useCurrent = false), panChange, zoomChange, event.changes.count { it.pressed })
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (!canceled && event.changes.any { it.pressed })
        } finally {
            if (pastTouchSlop) onEnd()
        }
    }
}
