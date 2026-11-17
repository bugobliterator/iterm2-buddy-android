package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.effectiveCellGeometry
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The live view's zoom and pan, pure so it is tested on the JVM. Coordinates:
 *
 * - **view**: the canvas, in px, origin top-left; the image is drawn aspect-fit into [FitRect].
 * - **screen**: what the finger touches, `screen = offset + scale × view` ([ZoomState], applied
 *   by `graphicsLayer` with its origin at the canvas's top-left).
 * - **image**: encoded pixels of the stream, origin top-left (`browserTap` coordinates, and
 *   [StreamConfig.cellGeometry] units).
 *
 * No function here divides by a size it has not checked: a 0×0 image, an empty view or a browser
 * config's 0×0 grid give an empty fit, scale 1 or null, never an exception or a NaN.
 */

/** Double tap zooms to this scale at the tap (or the maximum, if lower). */
const val DOUBLE_TAP_SCALE = 2.5f

/** Above this, a double tap goes back to fit. */
private const val ZOOMED_THRESHOLD = 1.01f

/** Zoom stops when one encoded pixel covers this many view pixels (iOS `maxiPhonePixelsPerMacPixel`). */
private const val VIEW_PIXELS_PER_IMAGE_PIXEL = 4f

/** `screen = offset + scale × view`; scale 1 with offset 0 is fit. */
data class ZoomState(val scale: Float, val offsetX: Float, val offsetY: Float) {
    companion object {
        val FIT = ZoomState(1f, 0f, 0f)
    }
}

/** Where the image is drawn at fit, in view px. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val isEmpty: Boolean get() = !(width > 0f && height > 0f)

    companion object {
        val EMPTY = FitRect(0f, 0f, 0f, 0f)
    }
}

/** An encoded image size; never 0 on either side. */
data class ImageSize(val width: Int, val height: Int)

/**
 * What the live view's zoom is keyed on: the session and the last non-empty image size. The zoom
 * resets to fit only when the key changes.
 */
data class ZoomKey(val sessionGuid: String, val image: ImageSize?)

/**
 * The next [ZoomKey] for an image of [width]×[height]: the size when it is non-empty; otherwise
 * (no config: a reconnect, retry, lock or restart in progress) the [previous] key's size for the
 * same session, so the transient 0×0 keeps the zoom and only a real size change resets it.
 */
fun nextZoomKey(previous: ZoomKey?, sessionGuid: String, width: Int, height: Int): ZoomKey {
    if (width > 0 && height > 0) {
        val image = ImageSize(width, height)
        return if (previous?.sessionGuid == sessionGuid && previous.image == image) previous else ZoomKey(sessionGuid, image)
    }
    return if (previous?.sessionGuid == sessionGuid) previous else ZoomKey(sessionGuid, null)
}

/**
 * [state] made valid for [fit] in a [viewW]×[viewH] view (content kept covering it, or centred),
 * scale unchanged: a kept zoom after the canvas changes size.
 */
fun clampZoom(state: ZoomState, fit: FitRect, viewW: Float, viewH: Float): ZoomState = clamp(sane(state), fit, viewW, viewH)

/** [imageW]×[imageH] aspect-fit into [viewW]×[viewH] and centred; [FitRect.EMPTY] when either is empty. */
fun fitRect(imageW: Int, imageH: Int, viewW: Float, viewH: Float): FitRect {
    if (imageW <= 0 || imageH <= 0 || !(viewW > 0f) || !(viewH > 0f) || !viewW.isFinite() || !viewH.isFinite()) {
        return FitRect.EMPTY
    }
    val scale = min(viewW / imageW, viewH / imageH)
    val width = imageW * scale
    val height = imageH * scale
    return FitRect((viewW - width) / 2, (viewH - height) / 2, width, height)
}

/** `max(1, 4 × imageW / fit.width)`: zoom until one image pixel spans four view pixels; 1 for an empty fit. */
fun maxScale(imageW: Int, fit: FitRect): Float {
    if (imageW <= 0 || fit.isEmpty) return 1f
    val scale = VIEW_PIXELS_PER_IMAGE_PIXEL * imageW / fit.width
    return if (scale.isFinite()) max(1f, scale) else 1f
}

/**
 * One step of a pinch or drag: scale by [zoom] about the centroid ([centroidX], [centroidY]) so the
 * content under it stays under it, then move by ([panX], [panY]). The scale is clamped to
 * [1, [maxScale]]; then, on each axis, content larger than the view is kept covering it and
 * content smaller than the view is centred.
 */
fun applyGesture(
    state: ZoomState,
    centroidX: Float,
    centroidY: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    fit: FitRect,
    viewW: Float,
    viewH: Float,
    maxScale: Float,
): ZoomState {
    val current = sane(state)
    val upper = if (maxScale.isFinite()) max(1f, maxScale) else 1f
    val wanted = current.scale * zoom
    val scale = (if (wanted.isFinite()) wanted else current.scale).coerceIn(1f, upper)
    val ratio = scale / current.scale
    val offsetX = centroidX - (centroidX - current.offsetX) * ratio + finite(panX)
    val offsetY = centroidY - (centroidY - current.offsetY) * ratio + finite(panY)
    return clamp(ZoomState(scale, finite(offsetX), finite(offsetY)), fit, viewW, viewH)
}

/** Double tap: back to fit when zoomed in, else [DOUBLE_TAP_SCALE] (at most [maxScale]) about the tap. */
fun toggleZoom(state: ZoomState, tapX: Float, tapY: Float, fit: FitRect, viewW: Float, viewH: Float, maxScale: Float): ZoomState {
    val current = sane(state)
    if (current.scale > ZOOMED_THRESHOLD) return clamp(ZoomState.FIT, fit, viewW, viewH)
    return applyGesture(current, tapX, tapY, DOUBLE_TAP_SCALE / current.scale, 0f, 0f, fit, viewW, viewH, maxScale)
}

/** The image pixel under screen point ([x], [y]), or null outside the image (or for an empty image or fit). */
fun viewToImage(x: Float, y: Float, state: ZoomState, fit: FitRect, imageW: Int, imageH: Int): Pair<Float, Float>? {
    if (!(state.scale > 0f) || fit.isEmpty || imageW <= 0 || imageH <= 0) return null
    val viewX = (x - state.offsetX) / state.scale
    val viewY = (y - state.offsetY) / state.scale
    val imageX = (viewX - fit.left) * imageW / fit.width
    val imageY = (viewY - fit.top) * imageH / fit.height
    // NaN fails both comparisons, so it is outside too.
    if (!(imageX >= 0f && imageX < imageW && imageY >= 0f && imageY < imageH)) return null
    return imageX to imageY
}

/**
 * The cell (column to row) under image pixel ([x], [y]), upstream `CompanionCellGeometry`:
 * `column = floor((x − leftMargin) / cellWidth)`, `row = floor((y − topMargin) / cellHeight)`.
 * Null for a browser config, a grid with no usable cell size, or a point outside the grid.
 */
fun imageToCell(x: Float, y: Float, config: StreamConfig): Pair<Int, Int>? {
    if (config.isBrowser || config.columns <= 0 || config.rows <= 0 || !x.isFinite() || !y.isFinite()) return null
    val cell = effectiveCellGeometry(config) ?: return null
    val column = floor((x - cell.leftMargin) / cell.cellWidth)
    val row = floor((y - cell.topMargin) / cell.cellHeight)
    if (!(column >= 0 && column < config.columns && row >= 0 && row < config.rows)) return null
    return column.toInt() to row.toInt()
}

/** Keeps content larger than the view covering it, and centres content smaller than it, per axis. */
private fun clamp(state: ZoomState, fit: FitRect, viewW: Float, viewH: Float): ZoomState = ZoomState(
    state.scale,
    clampAxis(state.offsetX, state.scale, fit.left, fit.width, finite(viewW)),
    clampAxis(state.offsetY, state.scale, fit.top, fit.height, finite(viewH)),
)

private fun clampAxis(offset: Float, scale: Float, start: Float, length: Float, view: Float): Float {
    val scaledStart = scale * start
    val scaledLength = scale * length
    val clamped = if (scaledLength >= view) {
        // min/max rather than coerceIn: rounding must never make the bounds cross and throw.
        val high = -scaledStart
        val low = min(view - scaledStart - scaledLength, high)
        max(low, min(offset, high))
    } else {
        (view - scaledLength) / 2 - scaledStart
    }
    // + 0f turns -0f into 0f, so fit is always exactly ZoomState.FIT.
    return finite(clamped) + 0f
}

private fun sane(state: ZoomState): ZoomState =
    if (state.scale > 0f && state.scale.isFinite()) state else ZoomState.FIT

private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
