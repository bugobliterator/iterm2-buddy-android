package dev.bugobliterator.itermbuddy.ui.live

import android.view.Surface
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.ui.settings.decoderRowText
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The app-scoped pieces the live view reads directly, outside the home model, so the decoder's
 * per-frame stats recompose only what shows them: the process's [output] and the stream
 * controller's [state].
 */
class LiveVideoSource(val output: HevcVideoOutput, val state: StateFlow<LiveViewState>) {
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
    // Read in the layer and the gesture handlers only (see bottomInset).
    var kept by remember(zoomKey) { mutableStateOf(KeptZoom(ZoomState.FIT, null)) }
    val visible by remember(source, sessionGuid) { source.visibility(sessionGuid) }
        .collectAsStateWithLifecycle(initialValue = source.visible(sessionGuid))
    val density = LocalDensity.current
    val currentOnTap by rememberUpdatedState(onTap)
    val geometry by rememberUpdatedState(GestureGeometry(fit, viewW, viewH, max, topInset, restBottomInset, leftInset, rightInset))
    val currentInset by rememberUpdatedState(bottomInset)
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
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val g = geometry
                    val inset = currentInset()
                    val shown = shownZoom(kept, g, inset)
                    kept = KeptZoom(applyGesture(shown, centroid.x, centroid.y, gestureZoom, pan.x, pan.y, g.fit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right), inset)
                }
            }
            .pointerInput(zoomKey) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val g = geometry
                        val inset = currentInset()
                        kept = KeptZoom(toggleZoom(shownZoom(kept, g, inset), tap.x, tap.y, g.fit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right), inset)
                    },
                    onTap = { currentOnTap() },
                )
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
                VideoSurface(
                    output = source.output,
                    modifier = Modifier
                        .offset { IntOffset(surfaceRect.left.roundToInt(), surfaceRect.top.roundToInt()) }
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

/** What the gesture detectors read at each event: the current fit, view size and maximum scale. */
private class GestureGeometry(
    val fit: FitRect,
    val viewW: Float,
    val viewH: Float,
    val max: Float,
    val top: Float,
    val restBottom: Float,
    val left: Float,
    val right: Float,
)

/** A zoom as the owner left it, with the bottom inset (px) it was made for; null: the resting one. */
internal data class KeptZoom(val state: ZoomState, val inset: Float?)

/** What is shown: [kept] lifted from its inset to [inset] ([liftForKeyboard]), clamped for [geometry]. */
private fun shownZoom(kept: KeptZoom, geometry: GestureGeometry, inset: Float): ZoomState =
    liftForKeyboard(kept.state, geometry.fit, geometry.viewW, geometry.viewH, kept.inset ?: geometry.restBottom, inset, geometry.top, geometry.left, geometry.right)
