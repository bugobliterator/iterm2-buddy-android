package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator

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
