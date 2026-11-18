package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
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
    fun flingDecaysAndStops() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 3000f, imagePxPerViewPx = 1f, zoom = 1f, nowMillis = 0)
        assertTrue(scroll.isFlinging)
        var travelled = 0L
        var t = 0L
        // 3000 × 0.998^t < 30 first at t = 2301 ms (ln 0.01 / ln 0.998 ≈ 2300.95).
        while (t < 2300) {
            t++
            scroll.flush(t)?.let { travelled += it.deltaY }
        }
        assertTrue("still coasting at 2300 ms", scroll.isFlinging)
        scroll.flush(++t)?.let { travelled += it.deltaY }
        assertTrue("stopped at 2301 ms", !scroll.isFlinging)
        // Integral of 3000·0.998^t over 2301 ms ≈ (3000 − 30) / (1000·−ln 0.998) ≈ 1484 px.
        assertTrue("travelled $travelled", abs(travelled - 1484) <= 3)
        repeat(10) { scroll.flush(++t)?.let { travelled += it.deltaY } }
        assertTrue("nothing more than the leftover fraction", abs(travelled - 1484) <= 3)
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
        // About 1000 px/s × 40 ms: −39 px (truncated toward zero), with the 0.5 drag still short of a pixel.
        assertTrue("deltaX ${step.deltaX}", step.deltaX in -40..-38)
        assertEquals(0, step.deltaY)
    }

    @Test
    fun flingElapsedIsCappedPerFlush() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 1000f, 1f, 1f, nowMillis = 0)
        // A late tick counts at most 100 ms, as iOS caps its display-link step.
        val step = scroll.flush(5_000)
        assertNotNull(step)
        assertTrue("deltaY ${step!!.deltaY}", step.deltaY in 99..100)
        assertTrue(scroll.isFlinging)
    }

    @Test
    fun flingVelocityIsScaledToImagePixels() {
        // Density 2 at zoom 2: 3000 view px/s is 3000 image px/s, stopping after ≈ 2301 ms.
        val same = BrowserScrollAccumulator()
        same.fling(3000f, 0f, imagePxPerViewPx = 2f, zoom = 2f, nowMillis = 0)
        assertEquals(stopTime(same), 2301L)
        // Density 1 at zoom 4: 750 image px/s, stopping after ≈ 1608 ms (ln(30/750) / ln 0.998).
        val quarter = BrowserScrollAccumulator()
        quarter.fling(3000f, 0f, imagePxPerViewPx = 1f, zoom = 4f, nowMillis = 0)
        assertEquals(stopTime(quarter), 1608L)
    }

    @Test
    fun slowFlingDoesNotCoast() {
        val scroll = BrowserScrollAccumulator()
        scroll.fling(0f, 29f, 1f, 1f, nowMillis = 0)
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

    /** The first millisecond at which [scroll] has stopped coasting, flushing every millisecond. */
    private fun stopTime(scroll: BrowserScrollAccumulator): Long {
        var t = 0L
        while (scroll.isFlinging) scroll.flush(++t)
        return t
    }
}
