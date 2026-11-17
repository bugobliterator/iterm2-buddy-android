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
 * The canvas is edge to edge. Insets bound the visible area that fit and the clamp use: top (the
 * status bar and the top bar drawn over the picture), bottom (the navigation bar, or the keyboard
 * and key bar while typing), left and right (system bars and display cutouts in landscape), as
 * iOS's safe-area content insets. Content larger than the visible area pans until its edge meets
 * an inset, so what lies behind the bars is reachable, and never further; smaller content is
 * centred across and top-aligned down.
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
 * [state] made valid for [fit] in a [viewW]×[viewH] view with the given insets (content kept
 * covering the visible area, or centred across and top-aligned down), scale unchanged: a kept zoom
 * after the canvas or an inset changes.
 */
fun clampZoom(
    state: ZoomState,
    fit: FitRect,
    viewW: Float,
    viewH: Float,
    bottomInset: Float = 0f,
    topInset: Float = 0f,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): ZoomState = clamp(sane(state), fit, viewW, viewH, Edges(leftInset, topInset, rightInset, bottomInset))

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
 * [1, [maxScale]]; then the result is clamped to the visible area inside the insets (see the
 * file comment).
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
    bottomInset: Float = 0f,
    topInset: Float = 0f,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): ZoomState {
    val current = sane(state)
    val upper = if (maxScale.isFinite()) max(1f, maxScale) else 1f
    val wanted = current.scale * zoom
    val scale = (if (wanted.isFinite()) wanted else current.scale).coerceIn(1f, upper)
    val ratio = scale / current.scale
    val offsetX = centroidX - (centroidX - current.offsetX) * ratio + finite(panX)
    val offsetY = centroidY - (centroidY - current.offsetY) * ratio + finite(panY)
    return clamp(ZoomState(scale, finite(offsetX), finite(offsetY)), fit, viewW, viewH, Edges(leftInset, topInset, rightInset, bottomInset))
}

/** Double tap: back to fit when zoomed in, else [DOUBLE_TAP_SCALE] (at most [maxScale]) about the tap. */
fun toggleZoom(
    state: ZoomState,
    tapX: Float,
    tapY: Float,
    fit: FitRect,
    viewW: Float,
    viewH: Float,
    maxScale: Float,
    bottomInset: Float = 0f,
    topInset: Float = 0f,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): ZoomState {
    val current = sane(state)
    if (current.scale > ZOOMED_THRESHOLD) return restingFit(fit, viewW, viewH, Edges(leftInset, topInset, rightInset, bottomInset))
    return applyGesture(current, tapX, tapY, DOUBLE_TAP_SCALE / current.scale, 0f, 0f, fit, viewW, viewH, maxScale, bottomInset, topInset, leftInset, rightInset)
}

/**
 * The image pixel under screen point ([x], [y]) for the zoom as shown (lifted and inset, as the
 * layer draws it), or null outside the image (or for an empty image or fit). For the input that
 * maps taps to cells (selection, browser taps); nothing calls it yet.
 */
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

/** The insets around the visible area, each finite and at least 0. */
private class Edges(left: Float, top: Float, right: Float, bottom: Float) {
    val left = inset(left)
    val top = inset(top)
    val right = inset(right)
    val bottom = inset(bottom)

    fun withBottom(value: Float) = Edges(left, top, right, value)
}

/**
 * Clamps [state] to the visible area inside [edges] (see the file comment): content larger than
 * it on an axis keeps covering it; narrower content is centred across, shorter content sits at the
 * top. An empty fit (no image) gives [ZoomState.FIT].
 */
private fun clamp(state: ZoomState, fit: FitRect, viewW: Float, viewH: Float, edges: Edges): ZoomState {
    if (fit.isEmpty) return ZoomState.FIT
    return ZoomState(
        state.scale,
        clampAxis(state.offsetX, state.scale, fit.left, fit.width, finite(viewW), edges.left, edges.right, centre = true),
        clampAxis(state.offsetY, state.scale, fit.top, fit.height, finite(viewH), edges.top, edges.bottom, centre = false),
    )
}

/**
 * One axis of [clamp]: the visible span is [lead]…`view − trail`. Content at least that long stays
 * covering it; shorter content is centred in it ([centre]) or put at its start.
 */
private fun clampAxis(offset: Float, scale: Float, start: Float, length: Float, view: Float, lead: Float, trail: Float, centre: Boolean): Float {
    val scaledStart = scale * start
    val scaledLength = scale * length
    val visible = view - lead - trail
    val clamped = if (scaledLength >= visible) {
        // min/max rather than coerceIn: rounding must never make the bounds cross and throw.
        val high = lead - scaledStart
        val low = min(view - trail - scaledStart - scaledLength, high)
        max(low, min(offset, high))
    } else if (centre) {
        lead + (visible - scaledLength) / 2 - scaledStart
    } else {
        lead - scaledStart
    }
    // + 0f turns -0f into 0f, so fit is always exactly ZoomState.FIT.
    return finite(clamped) + 0f
}

private fun sane(state: ZoomState): ZoomState =
    if (state.scale > 0f && state.scale.isFinite()) state else ZoomState.FIT

private fun finite(value: Float): Float = if (value.isFinite()) value else 0f

/**
 * [state], made for a bottom inset of [fromInset], moved to [toInset]: the keyboard (and the key
 * bar above it) opening, closing or animating over the bottom of the canvas, as iOS grows its
 * scroll view's bottom inset by the keyboard's overlap (`SessionView.swift:1995-2005`). If the
 * image's bottom was in view (at or above the old inset's edge), it moves with the inset's edge,
 * so at fit a tall picture slides up by the change and its top may leave the screen; but a rising
 * bottom never goes above [topInset] plus a quarter of the height below it ([LIFT_FLOOR]), so a
 * keyboard taller than that hides the image's bottom rather than push it off the top. An image
 * whose bottom was hidden stays put. Then the result is clamped for [toInset], so with the
 * keyboard gone a picture at scale 1 is back at fit.
 */
fun liftForKeyboard(
    state: ZoomState,
    fit: FitRect,
    viewW: Float,
    viewH: Float,
    fromInset: Float,
    toInset: Float,
    topInset: Float = 0f,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): ZoomState {
    val fromEdges = Edges(leftInset, topInset, rightInset, fromInset)
    val toEdges = fromEdges.withBottom(toInset)
    val from = clamp(sane(state), fit, viewW, viewH, fromEdges)
    if (fit.isEmpty) return ZoomState.FIT
    val scaledEnd = from.scale * (fit.top + fit.height)
    val imageBottom = from.offsetY + scaledEnd
    val pinned = imageBottom <= finite(viewH) - fromEdges.bottom + PINNED_SLACK
    if (!pinned) return clamp(from, fit, viewW, viewH, toEdges)
    val wanted = imageBottom - (toEdges.bottom - fromEdges.bottom)
    val bottom = max(wanted, min(imageBottom, liftFloor(viewH, toEdges)))
    return clamp(ZoomState(from.scale, from.offsetX, finite(bottom - scaledEnd)), fit, viewW, viewH, toEdges)
}

/** Scale 1 at fit, with the image's bottom lifted just above the bottom inset if that covers it (down to [liftFloor]). */
private fun restingFit(fit: FitRect, viewW: Float, viewH: Float, edges: Edges): ZoomState {
    if (fit.isEmpty) return ZoomState.FIT
    val fitBottom = fit.top + fit.height
    val visibleBottom = finite(viewH) - edges.bottom
    if (fitBottom <= visibleBottom) return clamp(ZoomState.FIT, fit, viewW, viewH, edges)
    val bottom = max(visibleBottom, min(fitBottom, liftFloor(viewH, edges)))
    return clamp(ZoomState(1f, 0f, finite(bottom - fitBottom)), fit, viewW, viewH, edges)
}

/** The highest a lifted image's bottom goes: [LIFT_FLOOR] of the height below the top inset, below it. */
private fun liftFloor(viewH: Float, edges: Edges): Float = edges.top + LIFT_FLOOR * max(0f, finite(viewH) - edges.top)

/** A lifted image keeps at least this fraction of the height below the top bar. */
private const val LIFT_FLOOR = 0.25f

/** Within this of the visible bottom, the image's bottom counts as in view (rounding). */
private const val PINNED_SLACK = 0.5f

private fun inset(value: Float): Float = if (value.isFinite() && value > 0f) value else 0f

/**
 * Where the image is drawn at fit on an edge-to-edge canvas: aspect-fit into the area inside the
 * insets ([topInset]: the status bar and the top bar; [bottomInset]: the navigation bar; the side
 * insets: system bars and cutouts), centred across and **top-aligned** just below the bar, so any
 * height left over is at the bottom. [FitRect.EMPTY] when that area or the image is empty.
 */
fun visibleFitRect(
    imageW: Int,
    imageH: Int,
    viewW: Float,
    viewH: Float,
    topInset: Float,
    bottomInset: Float,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
): FitRect {
    val edges = Edges(leftInset, topInset, rightInset, bottomInset)
    val inner = fitRect(imageW, imageH, finite(viewW) - edges.left - edges.right, finite(viewH) - edges.top - edges.bottom)
    return if (inner.isEmpty) FitRect.EMPTY else FitRect(edges.left + inner.left, edges.top, inner.width, inner.height)
}

/**
 * The Fit to phone viewport (width to height, px): the canvas inside its insets with the keyboard
 * hidden, so a fitted grid fills the visible area.
 */
fun fitViewport(canvasW: Int, canvasH: Int, topInset: Int, bottomInset: Int, leftInset: Int = 0, rightInset: Int = 0): Pair<Int, Int> =
    maxOf(0, canvasW - maxOf(0, leftInset) - maxOf(0, rightInset)) to maxOf(0, canvasH - maxOf(0, topInset) - maxOf(0, bottomInset))
