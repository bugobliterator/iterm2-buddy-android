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
    /** [videoVisible] now. */
    fun visible(): Boolean = videoVisible(state, output.stats)

    /** [videoVisible] on every change of either flow, re-read in [videoVisible]'s order. */
    val visibility: Flow<Boolean> = combine(state, output.stats) { _, _ -> visible() }.distinctUntilChanged()

    /** Settings' Decoder row now ([decoderRowText]), the initial value before its flow is collected. */
    fun decoderRowNow(): String = decoderRowText(output.stats.value)
}

/**
 * The live picture: a TextureView-backed [AndroidEmbeddedExternalSurface] sized to the image's
 * [fitRect] in the canvas, zoomed and panned through `graphicsLayer` ([ZoomState]): pinch and drag
 * with [detectTransformGestures], double tap toggles fit ↔ [DOUBLE_TAP_SCALE] at the tap. With
 * no image size yet ([imageWidth] or [imageHeight] 0) the surface fills the canvas, so a surface
 * exists before the first config and the decoder can start at once.
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
 * @param zoomKey the zoom resets to fit when this changes (the session), and when the fit does
 *   (a new image size or canvas size).
 * @param onCanvasSize the canvas size in px, on every change.
 */
@Composable
fun VideoCanvas(
    source: LiveVideoSource,
    imageWidth: Int,
    imageHeight: Int,
    zoomKey: Any,
    onCanvasSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val viewW = canvas.width.toFloat()
    val viewH = canvas.height.toFloat()
    val fit = fitRect(imageWidth, imageHeight, viewW, viewH)
    val surfaceRect = if (fit.isEmpty) FitRect(0f, 0f, viewW, viewH) else fit
    val max = maxScale(imageWidth, fit)
    var zoom by remember(zoomKey, fit) { mutableStateOf(ZoomState.FIT) }
    val visible by remember(source) { source.visibility }.collectAsStateWithLifecycle(initialValue = source.visible())
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged {
                canvas = it
                onCanvasSize(it)
            }
            .pointerInput(zoomKey, fit, max, canvas) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    zoom = applyGesture(zoom, centroid.x, centroid.y, gestureZoom, pan.x, pan.y, fit, viewW, viewH, max)
                }
            }
            .pointerInput(zoomKey, fit, max, canvas) {
                detectTapGestures(onDoubleTap = { tap -> zoom = toggleZoom(zoom, tap.x, tap.y, fit, viewW, viewH, max) })
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
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
 * destruction callback has not run by then. Never more than one detach per attach.
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
            }
        }
    }
    DisposableEffect(output) {
        onDispose {
            if (attached.getAndSet(null) != null) output.detachSurface()
        }
    }
}
