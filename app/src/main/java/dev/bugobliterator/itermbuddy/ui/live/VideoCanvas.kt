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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.FlingCurve
import dev.bugobliterator.itermbuddy.core.stream.FitNotice
import dev.bugobliterator.itermbuddy.core.stream.HistoryTileStore
import dev.bugobliterator.itermbuddy.core.stream.LatencyMeter
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.ui.settings.decoderRowText
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app-scoped pieces the live view reads directly, outside the home model, so the decoder's
 * per-frame stats and the live top recompose only what shows them: the process's [output], the
 * stream controller's [state] and its [historyTiles] (null: no scrollback), and the app's
 * [selection] driver (null: no selection) and [browser] driver (null: no browser view).
 * [latency] is the debug overlay's meter (null: no latency lines), and [ping] one diagnostics
 * ping on the active stream's link ([pingActiveLink]).
 */
class LiveVideoSource(
    val output: HevcVideoOutput,
    val state: StateFlow<LiveViewState>,
    val historyTiles: HistoryTileStore? = null,
    val selection: SelectionDriver? = null,
    val browser: BrowserDriver? = null,
    val latency: LatencyMeter? = null,
    val ping: suspend () -> Boolean = { false },
    /** Fit to phone's brief messages ([AutoFitter.notices][dev.bugobliterator.itermbuddy.core.stream.AutoFitter.notices]). */
    val fitNotices: Flow<FitNotice> = emptyFlow(),
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
 * **Taps and selection** ([detectPresses], one [PressArbiter] for both, so a deferred tap never
 * lands inside a selection). A tap is [onTap] once the double-tap window passes; a double tap
 * toggles the zoom. With [onSelect] non-null when the finger goes down, one finger held still for
 * [selectionHoldMillis] begins a selection; the same press then drags it and lifting ends it,
 * each phase given with the screen point and the zoom and fit as shown, all to the callback taken
 * at the begin (so a config change mid-drag still ends it). While selecting, every change is
 * consumed in the initial pass, so the pan and wheel detector sees the gesture cancelled. With
 * [doubleTapZoom] false there is no double-tap zoom and a tap is given at its lift.
 *
 * **Browser** ([browser] non-null when a gesture starts: a browser page taking input). A tap is
 * also a click at its page pixel ([browserTapPoint]; none off the page) and stops any fling. A one-finger drag scrolls
 * the page through the driver ([browserGestureStep]: all of it at fit; zoomed in, it pans and only
 * vertical travel past the pan edge scrolls), at the page pixel under the finger where it went
 * down ([browserScrollPoint]); a pinch zooms locally, cancels the scroll and makes the rest of the
 * gesture zoom and pan only; a release at fit with no pinch flings ([browserFlings]) with the
 * finger's velocity. Leaving composition cancels any scroll or fling under way (iOS `tearDown`).
 *
 * **Fill** ([fillPage], a browser page). The page rests at [browserFillZoom], covering the visible
 * area (the resting insets) with no band: a page wider than the screen fills its height, centred,
 * its sides cropped. That is the base zoom: the first view, and the one a kept zoom left at the
 * base follows when the base changes (rotation, the bars measured); a zoom the owner chose (pinched
 * out to fit, or in) is kept and clamped, as a terminal's. At the base a horizontal drag pans to the
 * sides and vertical travel scrolls the page ([browserGestureStep]), a release flings vertically
 * only ([browserFlings], [browserFlingVelocity]), the keyboard covers the page's bottom without
 * lifting it ([shownZoom]), and taps and scroll density use the zoom as shown. Pinching out reaches fit
 * (the whole page). The zoom key resets as for a terminal (another session or image size).
 *
 * **Momentum** (a terminal: no [browser]). A one-finger drag released fast enough coasts with
 * Android's fling decay ([CanvasCoast], [FlingCurve]), one step per frame: the history and a
 * zoomed picture pan on and stop at the top or the live bottom without a bounce, and in wheel
 * mode the coast keeps sending decaying notches through [onWheel] (one count per frame, capped).
 * [onCoastTarget] hears where a scrollback coast will rest (null when it ends), so its tiles are
 * fetched ahead. Any touch stops it, as do a change of the zoom key, of what [stopCoast] reads
 * (snapshot state, read outside composition: a key typed, another stream), of the wheel step, and
 * [jumpToLive]. A browser page coasts in its driver.
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
    selectionHoldMillis: Long = SELECTION_HOLD_MILLIS,
    doubleTapZoom: Boolean = true,
    browser: BrowserDriver? = null,
    fillPage: Boolean = false,
    onCoastTarget: (DocumentView?) -> Unit = {},
    stopCoast: () -> Any? = { null },
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
    // A browser page rests filling the screen; it can always zoom at least that far.
    val base = if (fillPage) browserFillZoom(fit, viewW, viewH, topInset, restBottomInset, leftInset, rightInset) else null
    val max = maxOf(maxScale(image?.width ?: 0, fit), base?.scale ?: 1f)
    val history = if (fit.isEmpty || !historyHeightPx.isFinite()) 0f else maxOf(0f, historyHeightPx)
    // Read in the layer and the gesture handlers only (see bottomInset).
    var kept by remember(zoomKey) { mutableStateOf(KeptZoom(ZoomState.FIT, null, atBase = fillPage)) }
    val visible by remember(source, sessionGuid) { source.visibility(sessionGuid) }
        .collectAsStateWithLifecycle(initialValue = source.visible(sessionGuid))
    val density = LocalDensity.current
    val currentOnTap by rememberUpdatedState(onTap)
    val geometry by rememberUpdatedState(GestureGeometry(fit, viewW, viewH, max, topInset, restBottomInset, leftInset, rightInset, history, base))
    val currentInset by rememberUpdatedState(bottomInset)
    val wheel = remember(wheelStepPx) { wheelStepPx?.let { ScrollWheelAccumulator(it) } }
    val currentWheel by rememberUpdatedState(wheel)
    val currentOnWheel by rememberUpdatedState(onWheel)
    val currentOnDocumentView by rememberUpdatedState(onDocumentView)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentBrowser by rememberUpdatedState(browser)
    val currentImage by rememberUpdatedState(IntSize(maxOf(0, imageWidth), maxOf(0, imageHeight)))
    DisposableEffect(browser) {
        onDispose { browser?.cancelScroll() }
    }
    // Momentum (see the KDoc): the coast under way, run per frame on the UI thread.
    val coastScope = rememberCoroutineScope()
    val coastJob = remember { arrayOfNulls<Job>(1) }
    val currentOnCoastTarget by rememberUpdatedState(onCoastTarget)
    val stopCoasting = remember { { coastJob[0]?.cancel(); coastJob[0] = null } }
    DisposableEffect(zoomKey, wheel, jumpToLive) {
        onDispose { stopCoasting() }
    }
    val currentStopCoast by rememberUpdatedState(stopCoast)
    LaunchedEffect(zoomKey) {
        // Read outside composition, so a key typed does not recompose the canvas.
        snapshotFlow { currentStopCoast() }.drop(1).collect { stopCoasting() }
    }
    val startCoast = { coast: CanvasCoast ->
        stopCoasting()
        currentOnCoastTarget(coast.target(kept, geometry, currentInset()))
        coastJob[0] = coastScope.launch {
            try {
                coast.start(withFrameMillis { it })
                do {
                    val frame = coast.step(kept, geometry, currentInset(), withFrameMillis { it })
                    kept = frame.kept
                    if (frame.notches > 0) currentOnWheel(frame.up, frame.notches)
                } while (!frame.finished)
            } finally {
                currentOnCoastTarget(null)
            }
        }
    }
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
                // The browser page's gesture: decided at its start, with its pinch latch and the
                // page pixel it scrolls at (kept from an earlier drag when this one has none, as iOS).
                var pageGesture = false
                var sawPinch = false
                var scrollAt: Pair<Float, Float>? = null
                detectZoomPan(
                    onDown = {
                        stopCoasting()
                        currentBrowser?.touchDown()
                    },
                    onStart = { down ->
                        multiTouch = false
                        sawPinch = false
                        currentWheel?.reset()
                        pageGesture = currentBrowser != null
                        if (pageGesture) {
                            val g = geometry
                            val image = currentImage
                            browserScrollPoint(down.x, down.y, shownZoom(kept, g, currentInset()), g.fit, image.width, image.height)?.let { scrollAt = it }
                        }
                    },
                    onEnd = { velocity, completed ->
                        val coast = if (pageGesture || !completed || multiTouch) {
                            null
                        } else {
                            CanvasCoast.of(velocity.x, velocity.y, density.density, viewConfiguration.maximumFlingVelocity, currentWheel)
                        }
                        // A coast carries the wheel's remainder on; otherwise it is dropped.
                        if (coast == null) currentWheel?.reset() else startCoast(coast)
                        val page = currentBrowser
                        val at = scrollAt
                        if (pageGesture && completed && page != null && at != null) {
                            val g = geometry
                            val shown = shownZoom(kept, g, currentInset())
                            val density = browserImagePxPerViewPx(currentImage.width, g.fit)
                            if (density != null && browserFlings(shown.scale, sawPinch, g.base?.scale ?: 1f)) {
                                val (flingX, flingY) = browserFlingVelocity(velocity.x, velocity.y, shown.scale)
                                page.fling(flingX, flingY, density, shown.scale, at.first, at.second, this@pointerInput.density)
                            }
                        }
                    },
                ) { centroid, pan, gestureZoom, pointers ->
                    val page = currentBrowser
                    if (pageGesture && page != null) {
                        val g = geometry
                        val inset = currentInset()
                        val step = browserGestureStep(kept, g, inset, centroid.x, centroid.y, pan.x, pan.y, gestureZoom, pointers, sawPinch)
                        if (step.pinchStarted) page.cancelScroll()
                        sawPinch = step.sawPinch
                        kept = step.kept
                        val at = scrollAt
                        val density = browserImagePxPerViewPx(currentImage.width, g.fit)
                        if ((step.scrollX != 0f || step.scrollY != 0f) && at != null && density != null) {
                            page.scroll(step.scrollX, step.scrollY, density, shownZoom(step.kept, g, inset).scale, at.first, at.second)
                        }
                    } else {
                        val step = gestureStep(kept, geometry, currentInset(), centroid.x, centroid.y, pan.x, pan.y, gestureZoom, pointers, multiTouch, currentWheel)
                        multiTouch = step.multiTouch
                        kept = step.kept
                        if (step.notches.isNotEmpty()) currentOnWheel(step.notches.first(), step.notches.size)
                    }
                }
            }
            .pointerInput(zoomKey, selectionHoldMillis, doubleTapZoom) {
                // The select callback of the gesture under way, taken at its begin.
                var gestureSelect: ((SelectionPhase, Float, Float, ZoomState, FitRect) -> Unit)? = null
                detectPresses(selectionHoldMillis, doubleTapZoom, selectable = { currentOnSelect != null }) { action ->
                    val g = geometry
                    val inset = currentInset()
                    val shown = shownZoom(kept, g, inset)
                    when (action) {
                        is PressAction.Tap -> {
                            val page = currentBrowser
                            if (page != null) {
                                val image = currentImage
                                // Off the page it clicks nothing, but every tap stops a fling (iOS SessionView:2192).
                                val point = browserTapPoint(action.x, action.y, shown, g.fit, image.width, image.height)
                                if (point == null) page.stopFling() else page.tap(point.first.toDouble(), point.second.toDouble())
                            }
                            currentOnTap()
                        }
                        is PressAction.DoubleTap -> {
                            // Zooming out over history keeps the line under the tap rather than jump to the live band.
                            val next = if (g.history > 0f && shown.scale > 1f) {
                                applyGesture(shown, action.x, action.y, 1f / shown.scale, 0f, 0f, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
                            } else {
                                toggleZoom(shown, action.x, action.y, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
                            }
                            kept = keptAfter(next, inset, g)
                        }
                        is PressAction.SelectBegin -> {
                            gestureSelect = currentOnSelect
                            gestureSelect?.invoke(SelectionPhase.BEGIN, action.x, action.y, shown, g.fit)
                        }
                        is PressAction.SelectMove -> gestureSelect?.invoke(SelectionPhase.MOVE, action.x, action.y, shown, g.fit)
                        is PressAction.SelectEnd -> {
                            gestureSelect?.invoke(SelectionPhase.END, action.x, action.y, shown, g.fit)
                            gestureSelect = null
                        }
                    }
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
 * Taps, double taps and selection holds of the primary finger, decided by a [PressArbiter] in
 * event time (the double-tap window from [viewConfiguration], or none without [doubleTaps], the
 * hold [holdMillis], touch slop),
 * each decision given to [onAction]. [selectable] is read as each press goes down. Events are
 * read in the initial pass and consumed only while a selection owns the gesture. A lift that is a
 * cancel ([isCancelledLift]: the system took the press) is [PressArbiter.cancel], never a tap.
 * Cancelled (the detector restarts), a selection under way still ends where it was.
 */
internal suspend fun PointerInputScope.detectPresses(holdMillis: Long, doubleTaps: Boolean = true, selectable: () -> Boolean, onAction: (PressAction) -> Unit) {
    val arbiter = PressArbiter(holdMillis, if (doubleTaps) viewConfiguration.doubleTapTimeoutMillis else 0L, viewConfiguration.touchSlop)
    try {
        awaitPointerEventScope {
            var tracked: PointerId? = null
            var lastTime = 0L
            while (true) {
                val deadline = arbiter.deadline
                val event = if (deadline == null) {
                    awaitPointerEvent(PointerEventPass.Initial)
                } else {
                    withTimeoutOrNull(maxOf(0L, deadline - lastTime)) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                if (event == null) {
                    // Timed out: event time has reached the deadline.
                    lastTime = maxOf(lastTime, deadline ?: lastTime)
                    arbiter.tick(lastTime).forEach(onAction)
                    continue
                }
                val time = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastTime
                lastTime = maxOf(lastTime, time)
                val wasSelecting = arbiter.selecting
                val primary = tracked?.let { id -> event.changes.firstOrNull { it.id == id } }
                val actions = when {
                    tracked == null -> {
                        val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                        if (down == null) {
                            emptyList()
                        } else {
                            tracked = down.id
                            arbiter.down(down.position.x, down.position.y, lastTime, selectable())
                        }
                    }
                    primary == null || !primary.pressed -> {
                        tracked = null
                        if (primary == null || isCancelledLift(primary.isConsumed, wasSelecting)) {
                            arbiter.cancel()
                        } else {
                            arbiter.up(primary.position.x, primary.position.y, lastTime)
                        }
                    }
                    else -> arbiter.move(primary.position.x, primary.position.y, event.changes.count { it.pressed }, lastTime)
                }
                if (wasSelecting || arbiter.selecting) event.changes.forEach { it.consume() }
                actions.forEach(onAction)
            }
        }
    } finally {
        arbiter.cancel().forEach(onAction)
    }
}

/**
 * Pinch and drag, as `detectTransformGestures` (touch slop, then centroid, pan and zoom per event,
 * no rotation), also giving how many pointers are down and when a gesture past the slop starts
 * (with where its first finger went down) and ends (the wheel's accumulator resets then, unless
 * it coasts), with the first finger's velocity in px/s and whether it ended with every finger
 * lifted (not cancelled); [onDown] hears every first finger down (it stops a coast).
 */
private suspend fun PointerInputScope.detectZoomPan(
    onDown: () -> Unit,
    onStart: (down: Offset) -> Unit,
    onEnd: (velocity: Velocity, completed: Boolean) -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, pointers: Int) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        var completed = false
        val touchSlop = viewConfiguration.touchSlop
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown()
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(down)
        try {
            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                event.changes.firstOrNull { it.id == down.id }?.let { if (!canceled) velocity.addPointerInputChange(it) }
                if (!canceled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange
                        val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                        if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                            pastTouchSlop = true
                            onStart(down.position)
                        }
                    }
                    if (pastTouchSlop) {
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            onGesture(event.calculateCentroid(useCurrent = false), panChange, zoomChange, event.changes.count { it.pressed })
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
                completed = !canceled && event.changes.none { it.pressed }
            } while (!canceled && event.changes.any { it.pressed })
        } finally {
            if (pastTouchSlop) onEnd(if (completed) velocity.calculateVelocity() else Velocity.Zero, completed)
        }
    }
}
