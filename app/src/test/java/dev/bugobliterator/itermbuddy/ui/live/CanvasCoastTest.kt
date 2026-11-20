package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.FlingCurve
import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.WHEEL_COAST_MAX_NOTCHES
import dev.bugobliterator.itermbuddy.core.stream.WHEEL_COAST_NOTCHES_PER_FRAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The terminal canvas's momentum, frame by frame in virtual time. */
class CanvasCoastTest {
    private val eps = 1e-2f

    /** 1000×500 under a 100 px top bar in a 1000×2000 view: the live band at (0, 100, 1000, 500). */
    private val bandFit = visibleFitRect(1000, 500, 1000f, 2000f, topInset = 100f, bottomInset = 0f)

    private fun geometry(history: Float) = GestureGeometry(bandFit, 1000f, 2000f, 4f, 100f, 0f, 0f, 0f, history)

    /*
     * With 3000 px of history the document is 3500 px tall in a 1900 px visible span: its offset
     * runs from 1400 (the live band's bottom on the screen's) to 3000 (its top under the bar).
     */
    private val history = 3000f
    private val liveBottom = KeptZoom(ZoomState(1f, 0f, 1400f), 0f, history)
    private val scrolledUp = KeptZoom(ZoomState(1f, 0f, 2000f), 0f, history)

    /** Runs [coast] from [from] at 60 Hz until it ends; every frame's kept zoom and notches. */
    private fun run(coast: CanvasCoast, from: KeptZoom, geometry: GestureGeometry): List<CoastFrame> {
        val frames = mutableListOf<CoastFrame>()
        var kept = from
        var t = 10_000L
        coast.start(t)
        do {
            t += 16
            val frame = coast.step(kept, geometry, 0f, t)
            kept = frame.kept
            frames += frame
            check(frames.size < 1_000) { "never ended" }
        } while (!frame.finished)
        return frames
    }

    @Test
    fun aFlickUpTheHistoryCoastsTheCurvesDistanceAndSlowsDown() {
        val coast = CanvasCoast.of(0f, 4000f, densityPxPerDp = 2f, maxVelocity = 20_000f, wheel = null)!!
        val frames = run(coast, liveBottom, geometry(history))
        val offsets = frames.map { it.kept.state.offsetY }
        val distance = FlingCurve(4000f, 2f).distancePx
        assertEquals(1400f + distance, offsets.last(), 0.5f)
        // Each frame moves less than the one before, never back.
        val steps = (listOf(1400f) + offsets).zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it >= 0f })
        assertTrue("$steps", steps.zipWithNext().all { (a, b) -> b <= a + eps })
        assertTrue(frames.all { it.notches == 0 })
    }

    @Test
    fun aHardFlickStopsAtTheHistorysTopWithNoBounce() {
        val coast = CanvasCoast.of(0f, 15_000f, 1f, 20_000f, null)!!
        val frames = run(coast, scrolledUp, geometry(history))
        assertEquals(3000f, frames.last().kept.state.offsetY, eps)
        assertTrue("never past the top", frames.all { it.kept.state.offsetY <= 3000f + eps })
        // It ends on meeting the top, long before the curve would.
        assertTrue("${frames.size} frames", frames.size * 16 < FlingCurve(15_000f, 1f).durationMillis / 2)
    }

    @Test
    fun aFlickDownStopsAtTheLiveBottom() {
        val coast = CanvasCoast.of(0f, -15_000f, 1f, 20_000f, null)!!
        val frames = run(coast, scrolledUp, geometry(history))
        val last = frames.last().kept
        assertEquals(1400f, last.state.offsetY, eps)
        assertTrue(frames.all { it.kept.state.offsetY >= 1400f - eps })
        assertTrue(documentView(last.state, bandFit, history, 2000f).atLiveBottom)
    }

    @Test
    fun zoomedInAFlickPansBothWaysAndStopsAtTheEdges() {
        val zoomed = KeptZoom(ZoomState(2f, -500f, 600f), 0f, 0f)
        val coast = CanvasCoast.of(-6000f, -3000f, 1f, 20_000f, null)!!
        val frames = run(coast, zoomed, geometry(0f))
        val last = frames.last().kept.state
        assertEquals(2f, last.scale)
        // Left as far as the band allows (its right edge on the screen's), and up.
        assertEquals(-1000f, last.offsetX, eps)
        assertTrue(last.offsetY < 600f)
    }

    @Test
    fun theTargetIsWhereTheCoastRests() {
        val coast = CanvasCoast.of(0f, 3000f, 2f, 20_000f, null)!!
        val target = coast.target(liveBottom, geometry(history), 0f)
        assertNotNull(target)
        val frames = run(coast, liveBottom, geometry(history))
        val rest = documentView(frames.last().kept.state, bandFit, history, 2000f)
        assertEquals(rest.topPx, target!!.topPx, 0.5f)
        assertEquals(rest.bottomPx, target.bottomPx, 0.5f)
        // Flung hard, the target is the top, clamped: the document's top just under the 100 px bar.
        val hard = CanvasCoast.of(0f, 15_000f, 1f, 20_000f, null)!!.target(liveBottom, geometry(history), 0f)!!
        assertEquals(-100f, hard.topPx, 0.5f)
    }

    @Test
    fun inWheelModeAFlickSendsDecayingNotchesWithinTheBudget() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 40f)
        // The alternate screen: no history, the band at fit; finger flicked up, so the content goes on down.
        val coast = CanvasCoast.of(0f, -6000f, 2.75f, 20_000f, wheel)!!
        assertNull("no history to fetch", coast.target(KeptZoom(ZoomState.FIT, 0f, 0f), geometry(0f), 0f))
        val frames = run(coast, KeptZoom(ZoomState.FIT, 0f, 0f), geometry(0f))
        val counts = frames.map { it.notches }
        assertTrue("$counts", counts.all { it in 0..WHEEL_COAST_NOTCHES_PER_FRAME })
        assertTrue(frames.filter { it.notches > 0 }.all { !it.up })
        val total = counts.sum()
        // The curve covers about 2070 px: 51 lines of 40 px, fewer if the per-frame cap bit early on.
        assertTrue("total $total", total in 40..52)
        // Busy at first, sparse at the end.
        val firstHalf = counts.take(counts.size / 2).sum()
        assertTrue("$counts", firstHalf > total - firstHalf)
        assertTrue(frames.all { it.kept.state == ZoomState.FIT })
    }

    @Test
    fun inWheelModeAHardFlickIsCappedInAll() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 10f)
        val coast = CanvasCoast.of(0f, 20_000f, 1f, 20_000f, wheel)!!
        val frames = run(coast, KeptZoom(ZoomState.FIT, 0f, 0f), geometry(0f))
        assertEquals(WHEEL_COAST_MAX_NOTCHES, frames.sumOf { it.notches })
        assertTrue(frames.filter { it.notches > 0 }.all { it.up })
    }

    @Test
    fun theDragsRemainderCarriesIntoTheCoast() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 100f)
        assertEquals(emptyList<Boolean>(), wheel.drag(-70f))
        // A gentle flick travelling about 40 px: with the 70 left from the drag, one notch.
        val coast = CanvasCoast.of(0f, -400f, 1f, 20_000f, wheel)!!
        assertTrue(abs(FlingCurve(400f, 1f).distancePx) in 30f..99f)
        assertEquals(1, run(coast, KeptZoom(ZoomState.FIT, 0f, 0f), geometry(0f)).sumOf { it.notches })
    }

    @Test
    fun aSlowOrBrokenReleaseDoesNotCoast() {
        assertNull(CanvasCoast.of(0f, 40f, 1f, 20_000f, null))
        assertNull(CanvasCoast.of(0f, 0f, 1f, 20_000f, null))
        assertNull(CanvasCoast.of(Float.NaN, 4000f, 1f, 20_000f, null))
        // The release speed is capped at the platform's maximum.
        val capped = CanvasCoast.of(0f, 100_000f, 1f, 8000f, null)!!
        val target = capped.target(liveBottom, geometry(30_000f), 0f)!!
        val expected = CanvasCoast.of(0f, 8000f, 1f, 8000f, null)!!.target(liveBottom, geometry(30_000f), 0f)!!
        assertEquals(expected.topPx, target.topPx, 0.5f)
        assertFalse(target.atLiveBottom)
    }

    @Test
    fun aReleaseIsCappedAtTheMaximumKeepingItsDirection() {
        assertEquals(3000f to -4000f, capFlingVelocity(3000f, -4000f, 8000f))
        val (x, y) = capFlingVelocity(30_000f, -40_000f, 8000f)
        assertEquals(4800f, x, 0.01f)
        assertEquals(-6400f, y, 0.01f)
        assertEquals(30_000f to 0f, capFlingVelocity(30_000f, 0f, Float.NaN))
        assertEquals(0f to 0f, capFlingVelocity(Float.NaN, 1f, 8000f))
    }
}
