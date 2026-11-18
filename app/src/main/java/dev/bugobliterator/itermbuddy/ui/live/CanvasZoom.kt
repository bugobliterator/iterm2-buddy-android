package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.cellAt
import dev.bugobliterator.itermbuddy.core.stream.historyCellAt
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint

/*
 * The live canvas's zoom state and per-event decisions, pure so they are tested on the JVM;
 * [VideoCanvas] keeps a [KeptZoom] in snapshot state and calls these.
 */

/** What the gesture detectors read at each event: the current fit, view size, maximum scale, insets and history height. */
internal class GestureGeometry(
    val fit: FitRect,
    val viewW: Float,
    val viewH: Float,
    val max: Float,
    val top: Float,
    val restBottom: Float,
    val left: Float,
    val right: Float,
    val history: Float,
) {
    /** The content zoom and pan work on: the band with the history above it ([documentFit]). */
    val docFit: FitRect = documentFit(fit, history)
}

/**
 * A zoom as the owner left it, with the bottom inset (px) it was made for (null: the resting one)
 * and the history height (px) it was made for (null: whatever it is now).
 */
internal data class KeptZoom(val state: ZoomState, val inset: Float?, val history: Float? = null)

/**
 * What is shown: [kept] moved with the document from its history height to the current one
 * ([followDocument]; normally [rebaseKept] has done that already), then lifted from its inset to
 * [inset] ([liftForKeyboard]), clamped for [geometry].
 */
internal fun shownZoom(kept: KeptZoom, geometry: GestureGeometry, inset: Float): ZoomState {
    val keptInset = kept.inset ?: geometry.restBottom
    val keptHistory = kept.history ?: geometry.history
    val followed = if (keptHistory == geometry.history) {
        kept.state
    } else {
        followDocument(kept.state, geometry.fit, geometry.viewW, geometry.viewH, keptHistory, geometry.history, keptInset, geometry.top, geometry.left, geometry.right)
    }
    return liftForKeyboard(followed, geometry.docFit, geometry.viewW, geometry.viewH, keptInset, inset, geometry.top, geometry.left, geometry.right)
}

/**
 * [kept] rebased onto [geometry]'s history height, on every change of it: whether the live band
 * was at the bottom is judged at each change, as iOS judges `wasAtBottom` at every layout
 * (`IOS/Screens/SessionView.swift:2060-2061, 2101-2111`), so a document that shrank (a clear, the
 * alternate screen) and reads as pinned follows the live band when it grows again.
 */
internal fun rebaseKept(kept: KeptZoom, geometry: GestureGeometry): KeptZoom {
    val from = kept.history ?: return kept.copy(history = geometry.history)
    if (from == geometry.history) return kept
    val inset = kept.inset ?: geometry.restBottom
    val followed = followDocument(kept.state, geometry.fit, geometry.viewW, geometry.viewH, from, geometry.history, inset, geometry.top, geometry.left, geometry.right)
    return KeptZoom(followed, kept.inset, geometry.history)
}

/** The outcome of one pan or pinch event: the new [kept] zoom, the wheel notches to send, and the gesture's two-finger latch. */
internal class GestureStep(val kept: KeptZoom, val notches: List<Boolean>, val multiTouch: Boolean)

/**
 * One event of a pan or pinch ([centroidX], [centroidY], [panX], [panY], [zoom]) with [pointers]
 * down, the gesture so far having had two fingers if [multiTouch]. With a [wheel] (wheel mode) and
 * only ever one finger, the drag pans first and the travel past the pan edge becomes notches
 * ([wheelDrag]); otherwise it zooms and pans ([applyGesture]). A gesture latches to zoom and pan
 * once two fingers are down, until it ends.
 */
internal fun gestureStep(
    kept: KeptZoom,
    geometry: GestureGeometry,
    inset: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    pointers: Int,
    multiTouch: Boolean,
    wheel: ScrollWheelAccumulator?,
): GestureStep {
    val g = geometry
    val shown = shownZoom(kept, g, inset)
    val latched = multiTouch || pointers > 1
    if (wheel != null && !latched) {
        val drag = wheelDrag(shown, panX, panY, g.docFit, g.viewW, g.viewH, inset, g.top, g.left, g.right)
        return GestureStep(KeptZoom(drag.state, inset, g.history), wheel.drag(drag.pastEdgeY), latched)
    }
    val next = applyGesture(shown, centroidX, centroidY, zoom, panX, panY, g.docFit, g.viewW, g.viewH, g.max, inset, g.top, g.left, g.right)
    return GestureStep(KeptZoom(next, inset, g.history), emptyList(), latched)
}

/** How long a still finger is held before a selection begins: iOS's 0.3 s (`IOS/Screens/SessionView.swift:1928-1933`). */
internal const val SELECTION_HOLD_MILLIS = 300L

/**
 * Whether a press waiting out [SELECTION_HOLD_MILLIS] is no longer a selection hold: a second
 * finger came down (zoom), the finger moved more than [slop] from where it went down (a pan,
 * a wheel drag or a scroll, decided by [gestureStep]), or it was lifted (a tap). As UIKit's long
 * press with its default allowable movement, so a drag that moves before the hold scrolls instead.
 * Once the hold passes, the selection owns the rest of the gesture and the pan and tap detectors
 * see it consumed.
 */
internal fun holdCancelled(travel: Float, pointers: Int, released: Boolean, slop: Float): Boolean =
    released || pointers > 1 || !(travel <= slop)

/**
 * The selection point under screen point ([x], [y]) of a terminal shown with zoom [shown], the
 * live band at [fit] and the document [layout] above it ([ScrollbackLayout.liveBandTopPx] of
 * history): over the band, the video's cell ([cellAt], margins included, from the layout's live
 * top); over history, the tile's cell on that line ([historyCellAt], tile space, no margins).
 * Clamped to the document and the grid, as iOS (`IOS/Screens/SessionView.swift:2710-2717`), so
 * a drag past an edge selects up to it. Null without a usable zoom, fit or image, or for a config
 * that cannot select (a browser, no cell geometry).
 */
internal fun selectionPointAt(x: Float, y: Float, shown: ZoomState, fit: FitRect, layout: ScrollbackLayout, config: StreamConfig): SelectionPoint? {
    if (fit.isEmpty || config.pixelWidth <= 0 || config.pixelHeight <= 0) return null
    val history = layout.liveBandTopPx
    val (docX, docY) = screenToDocument(x, y, shown, fit, history) ?: return null
    val imageX = (docX - fit.left) * config.pixelWidth / fit.width
    return if (docY >= history) {
        cellAt(imageX, (docY - history) * config.pixelHeight / fit.height, config, layout.liveTop)
    } else {
        historyCellAt(layout.absLineAt(docY), imageX, config)
    }
}
