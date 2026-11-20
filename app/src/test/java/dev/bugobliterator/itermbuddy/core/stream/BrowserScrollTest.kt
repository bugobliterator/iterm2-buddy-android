package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The browser scroll accumulator: plain arithmetic on a caller-supplied clock. */
class BrowserScrollTest {
    @Test
    fun dragIsScaledByZoomAndImageDensity() {
        val scroll = BrowserScrollAccumulator()
        // 10 view px at 2 image px per view px, zoomed 2x: 10 image px.
        scroll.drag(10f, -10f, imagePxPerViewPx = 2f, zoom = 2f, atX = 300f, atY = 400f)
        assertEquals(ScrollStep(300.0, 400.0, 10, -10), scroll.flush(0))
        // Density 3 at zoom 1: 30 image px, at the newest point.
        scroll.drag(0f, 10f, imagePxPerViewPx = 3f, zoom = 1f, atX = 5f, atY = 6f)
        assertEquals(ScrollStep(5.0, 6.0, 0, 30), scroll.flush(40))
        assertNull("nothing left", scroll.flush(80))
    }

    @Test
    fun fractionsCarryOver() {
        val scroll = BrowserScrollAccumulator()
        scroll.drag(0.6f, -0.6f, 1f, 1f, 0f, 0f)
        assertNull("under a whole pixel each way", scroll.flush(0))
        scroll.drag(0.6f, -0.6f, 1f, 1f, 0f, 0f)
        // 1.2 each way: one whole pixel goes (toward zero), 0.2 stays.
        assertEquals(ScrollStep(0.0, 0.0, 1, -1), scroll.flush(40))
        repeat(3) { scroll.drag(0.3f, -0.3f, 1f, 1f, 0f, 0f) }
        // 0.2 + 0.9 = 1.1.
        assertEquals(ScrollStep(0.0, 0.0, 1, -1), scroll.flush(80))
        assertNull(scroll.flush(120))
    }

    @Test
    fun nonFiniteOrDegenerateDragsAreIgnored() {
        val scroll = BrowserScrollAccumulator()
        scroll.drag(Float.NaN, 10f, 1f, 1f, 0f, 0f)
        scroll.drag(10f, 10f, 1f, 0f, 0f, 0f)
        scroll.drag(10f, 10f, Float.POSITIVE_INFINITY, 1f, 0f, 0f)
        scroll.drag(10f, 10f, 1f, 1f, Float.NaN, 0f)
        assertNull(scroll.flush(0))
    }

    @Test
    fun flingFollowsTheSharedCurveAndStops() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 3000f, imagePxPerViewPx = 1f, zoom = 1f, nowMillis = 0, densityPxPerDp = 1f)
        assertTrue(scroll.isFlinging)
        val curve = FlingCurve(3000f, 1f)
        var travelled = 0L
        var t = 0L
        var lastStep = Int.MAX_VALUE
        while (scroll.isFlinging) {
            t += 40
            val step = scroll.flush(t)?.deltaY ?: 0
            // The tail thins out: never a bigger step than the one before (give or take a carried pixel).
            assertTrue("at $t: $step after $lastStep", step.toLong() <= lastStep.toLong() + 1)
            lastStep = step
            travelled += step
        }
        // It stops when the curve does, having travelled its distance (less a carried fraction).
        assertEquals(curve.durationMillis.toDouble(), t.toDouble(), 40.0)
        assertEquals(curve.distancePx.toDouble(), travelled.toDouble(), 1.0)
        assertNull(scroll.flush(t + 1000))
    }

    @Test
    fun flingSendsWholePixelsAtTheFlingPoint() {
        val scroll = BrowserScrollAccumulator()
        scroll.drag(0f, 0.5f, 1f, 1f, 12f, 34f)
        scroll.fling(-1000f, 0f, 1f, 1f, nowMillis = 100)
        val step = scroll.flush(140)!!
        assertEquals(12.0, step.x, 0.0)
        assertEquals(34.0, step.y, 0.0)
        // The curve's first 40 ms, truncated toward zero, with the 0.5 drag still short of a pixel.
        val expected = -FlingCurve(1000f, 1f).distanceAt(40)
        assertEquals(expected.toDouble(), step.deltaX.toDouble(), 1.0)
        assertEquals(0, step.deltaY)
    }

    @Test
    fun flingElapsedIsCappedPerFlush() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 1000f, 1f, 1f, nowMillis = 0)
        // A late tick counts at most 100 ms, as iOS caps its display-link step.
        val step = scroll.flush(5_000)
        assertNotNull(step)
        assertEquals(FlingCurve(1000f, 1f).distanceAt(100).toDouble(), step!!.deltaY.toDouble(), 1.0)
        assertTrue(scroll.isFlinging)
    }

    @Test
    fun flingIsMeasuredInScreenPxAndScaledToImagePixels() {
        // Density 2 at zoom 2: image px are view px, so it travels the curve's distance.
        val same = BrowserScrollAccumulator()
        same.fling(3000f, 0f, imagePxPerViewPx = 2f, zoom = 2f, nowMillis = 0, densityPxPerDp = 2f)
        assertEquals(FlingCurve(3000f, 2f).distancePx.toDouble(), travel(same).toDouble(), 1.0)
        // Density 1 at zoom 4: a quarter of it, over the same time.
        val quarter = BrowserScrollAccumulator()
        quarter.fling(3000f, 0f, imagePxPerViewPx = 1f, zoom = 4f, nowMillis = 0, densityPxPerDp = 2f)
        assertEquals(FlingCurve(3000f, 2f).distancePx / 4.0, travel(quarter).toDouble(), 1.0)
    }

    @Test
    fun slowFlingDoesNotCoast() {
        val scroll = BrowserScrollAccumulator()
        // Under 50 dp/s at 2 px per dp.
        scroll.fling(0f, 99f, 1f, 1f, nowMillis = 0, densityPxPerDp = 2f)
        assertTrue(!scroll.isFlinging)
        assertNull(scroll.flush(40))
    }

    @Test
    fun dragStopsAFling() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 3000f, 1f, 1f, nowMillis = 0)
        scroll.drag(0f, 4f, 1f, 1f, 0f, 0f)
        assertTrue(!scroll.isFlinging)
        assertEquals(ScrollStep(0.0, 0.0, 0, 4), scroll.flush(40))
    }

    @Test
    fun stopFlingKeepsPendingTravel() {
        val scroll = BrowserScrollAccumulator()
        scroll.drag(0f, 7f, 1f, 1f, 0f, 0f)
        scroll.fling(0f, 3000f, 1f, 1f, nowMillis = 0)
        scroll.stopFling()
        assertTrue(!scroll.isFlinging)
        assertEquals(ScrollStep(0.0, 0.0, 0, 7), scroll.flush(40))
    }

    @Test
    fun pinchCancelsScroll() {
        val scroll = BrowserScrollAccumulator()
        scroll.drag(0f, 25f, 1f, 1f, 0f, 0f)
        scroll.fling(0f, 3000f, 1f, 1f, nowMillis = 0)
        scroll.cancel()
        assertTrue(!scroll.isFlinging)
        assertNull("the drag and the fling are both gone", scroll.flush(40))
    }

    /** The whole travel of [scroll]'s fling, flushing every 16 ms. */
    private fun travel(scroll: BrowserScrollAccumulator): Long {
        var t = 0L
        var sum = 0L
        while (scroll.isFlinging) sum += scroll.flush(++t * 16)?.deltaX ?: 0
        return sum
    }

    @Test
    fun keyTravelPagesThenLinesAndNeverReverses() {
        val keys = KeyScrollAccumulator()
        keys.add(-1000.0, 40.0, 900.0)
        assertEquals(ScrollKey.PAGE_DOWN, keys.next(40.0, 900.0))
        // 100 left: lines while at least half a line is pending (100, 60, 20), then the 20 px overshoot is carried, just inside half a line.
        assertEquals(ScrollKey.LINE_DOWN, keys.next(40.0, 900.0))
        assertEquals(ScrollKey.LINE_DOWN, keys.next(40.0, 900.0))
        assertEquals(ScrollKey.LINE_DOWN, keys.next(40.0, 900.0))
        assertEquals(20.0, keys.pendingPx, 1e-3)
        assertEquals(null, keys.next(40.0, 900.0))
        assertFalse(keys.hasWork(40.0))
        keys.cancel()
        // The backlog is capped at two pages.
        keys.add(-10_000.0, 40.0, 900.0)
        assertEquals(ScrollKey.PAGE_DOWN, keys.next(40.0, 900.0))
        assertEquals(ScrollKey.PAGE_DOWN, keys.next(40.0, 900.0))
        assertEquals(null, keys.next(40.0, 900.0))
        keys.add(-30.0, 40.0, 900.0)
        keys.cancel()
        assertEquals(null, keys.next(40.0, 900.0))
    }

    @Test
    fun aSlowDragScrollsAsFarAsTheFingerWithinHalfALine() {
        // A line of 72 px (36 CSS px at scale 2), fed 5 px at a time like a slow drag, each key sent as soon as owed.
        val keys = KeyScrollAccumulator()
        var finger = 0.0
        var scrolled = 0.0
        repeat(400) {
            keys.add(-5.0, 72.0, 1900.0)
            finger -= 5.0
            keys.next(72.0, 1900.0)?.let {
                assertEquals(ScrollKey.LINE_DOWN, it)
                scrolled -= 72.0
            }
            assertTrue("finger $finger, keys $scrolled", abs(scrolled - finger) <= 36.0)
        }
        // 2000 px of finger: 28 lines (2016 px), not twice as many.
        assertEquals(-2016.0, scrolled, 0.0)
    }

    @Test
    fun anOvershootNeverFiresAKeyBack() {
        val keys = KeyScrollAccumulator()
        // 40 px owes a 72 px line (at half a line); the 32 px overshoot is paid back by the next travel.
        keys.add(36.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_UP, keys.next(72.0, 1900.0))
        assertEquals(-36.0 * (1 - 1e-6), keys.pendingPx, 1e-9)
        assertEquals("just inside half a line: nothing back", null, keys.next(72.0, 1900.0))
        assertFalse(keys.hasWork(72.0))
        // The same way again: it takes a whole line more to fire.
        keys.add(71.0, 72.0, 1900.0)
        assertEquals(null, keys.next(72.0, 1900.0))
        keys.add(1.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_UP, keys.next(72.0, 1900.0))
    }

    @Test
    fun aPageGoesOnlyWhenAlmostAWholePageIsOwed() {
        val keys = KeyScrollAccumulator()
        // Half a page used to page; now it is lines until a page less half a line is owed.
        keys.add(-1000.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_DOWN, keys.next(72.0, 1900.0))
        keys.cancel()
        keys.add(-1863.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_DOWN, keys.next(72.0, 1900.0))
        keys.cancel()
        keys.add(-1864.0, 72.0, 1900.0)
        assertEquals(ScrollKey.PAGE_DOWN, keys.next(72.0, 1900.0))
        // The 36 px it went past is carried: nothing more owed.
        assertEquals(36.0, keys.pendingPx, 1e-3)
        assertEquals(null, keys.next(72.0, 1900.0))
        // A page and a half: one page, then the rest as lines.
        keys.cancel()
        keys.add(2850.0, 72.0, 1900.0)
        assertEquals(ScrollKey.PAGE_UP, keys.next(72.0, 1900.0))
        assertEquals(950.0, keys.pendingPx, 1e-9)
        assertEquals(ScrollKey.LINE_UP, keys.next(72.0, 1900.0))
    }

    @Test
    fun travelAgainstOwedKeysDropsThemButNotAnOvershoot() {
        val keys = KeyScrollAccumulator()
        keys.add(-500.0, 72.0, 1900.0)
        assertTrue(keys.hasWork(72.0))
        // The finger reverses: the owed downward lines are dropped, only the new travel counts.
        keys.add(40.0, 72.0, 1900.0)
        assertEquals(40.0, keys.pendingPx, 1e-9)
        assertEquals(ScrollKey.LINE_UP, keys.next(72.0, 1900.0))
        // An overshoot (owed nothing) is not dropped by travel the other way: it is paid back.
        assertEquals(-32.0, keys.pendingPx, 1e-9)
        keys.add(50.0, 72.0, 1900.0)
        assertEquals(18.0, keys.pendingPx, 1e-9)
    }

    @Test
    fun jitterAtTheThresholdSendsNoShake() {
        val keys = KeyScrollAccumulator()
        // A slow drag down that just fired a Down: 36 owed, the 72 px line paid, 36 px ahead.
        keys.add(-36.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_DOWN, keys.next(72.0, 1900.0))
        // The finger trembles up and down by up to 17 px: an Up would need 54 px owed (half a line and a quarter).
        val sent = mutableListOf<ScrollKey>()
        repeat(50) { i ->
            keys.add(if (i % 2 == 0) 17.0 else -17.0, 72.0, 1900.0)
            keys.next(72.0, 1900.0)?.let { sent += it }
        }
        assertEquals("no up/down shake", emptyList<ScrollKey>(), sent)
        // Carrying on down a line fires the next Down at half a line owed, the slow drag's fidelity kept.
        keys.add(-72.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_DOWN, keys.next(72.0, 1900.0))
        // A real reversal fires once three quarters of a line is owed the other way (36 already, then 18 and 1 more).
        keys.cancel()
        keys.add(-36.0, 72.0, 1900.0)
        keys.next(72.0, 1900.0)
        keys.add(18.0, 72.0, 1900.0)
        assertFalse("just short", keys.hasWork(72.0))
        assertEquals(null, keys.next(72.0, 1900.0))
        keys.add(1.0, 72.0, 1900.0)
        assertEquals(ScrollKey.LINE_UP, keys.next(72.0, 1900.0))
    }
}
