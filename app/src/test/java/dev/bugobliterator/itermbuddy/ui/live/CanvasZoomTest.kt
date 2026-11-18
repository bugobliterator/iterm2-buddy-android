package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.CellGeometry
import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasZoomTest {
    private val eps = 1e-3f

    /** 1000×500 under a 100 px top bar in a 1000×2000 view: the live band at (0, 100, 1000, 500). */
    private val bandFit = visibleFitRect(1000, 500, 1000f, 2000f, topInset = 100f, bottomInset = 0f)

    private fun geometry(history: Float) = GestureGeometry(bandFit, 1000f, 2000f, 4f, 100f, 0f, 0f, 0f, history)

    private fun atBottom(kept: KeptZoom, history: Float): Boolean =
        documentView(shownZoom(kept, geometry(history), 0f), bandFit, history, 2000f).atLiveBottom

    /** Scrolled up over 3000 px of history: the document's top 900 px above the screen's. */
    private val scrolledUp = KeptZoom(ZoomState(1f, 0f, 2000f), 0f, 3000f)

    @Test
    fun alternateScreenThenBackReturnsToLive() {
        assertFalse(atBottom(scrolledUp, 3000f))
        // The alternate screen: no history, then back to it with no drag in between.
        val alternate = rebaseKept(scrolledUp, geometry(0f))
        val back = rebaseKept(alternate, geometry(3000f))
        assertTrue("an emptied document reads as pinned and follows", atBottom(back, 3000f))
    }

    @Test
    fun clearThenGrowthFollowsTheBottom() {
        // Cmd-K: the history shrinks to 200 px, shorter than the view, so it reads as pinned.
        val cleared = rebaseKept(scrolledUp, geometry(200f))
        assertTrue(atBottom(cleared, 200f))
        var kept = cleared
        for (history in listOf(800f, 1600f, 2400f, 3200f)) {
            kept = rebaseKept(kept, geometry(history))
            assertTrue("following at $history", atBottom(kept, history))
        }
    }

    @Test
    fun growthWhileReadingKeepsTheLines() {
        val grown = rebaseKept(scrolledUp, geometry(3500f))
        assertEquals(2500f, grown.state.offsetY, eps)
        assertEquals(3500f, grown.history)
        assertFalse(atBottom(grown, 3500f))
    }

    @Test
    fun firstRebaseOnlyRecordsTheHeight() {
        val fresh = KeptZoom(ZoomState.FIT, null)
        assertEquals(KeptZoom(ZoomState.FIT, null, 3000f), rebaseKept(fresh, geometry(3000f)))
        assertTrue(atBottom(fresh, 3000f))
    }

    @Test
    fun oneFingerWheelsAndTwoFingersLatchToZoom() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 12.5f)
        val kept = KeptZoom(ZoomState.FIT, null, 0f)
        val g = geometry(0f)
        val one = gestureStep(kept, g, 0f, 500f, 500f, 0f, 30f, 1f, pointers = 1, multiTouch = false, wheel = wheel)
        assertEquals(listOf(true, true), one.notches)
        assertFalse(one.multiTouch)
        val pinch = gestureStep(one.kept, g, 0f, 500f, 500f, 0f, 0f, 2f, pointers = 2, multiTouch = one.multiTouch, wheel = wheel)
        assertTrue(pinch.multiTouch)
        assertEquals(emptyList<Boolean>(), pinch.notches)
        assertEquals(2f, pinch.kept.state.scale, eps)
        // A finger lifted: the gesture stays a pan, never a wheel, until it ends.
        val after = gestureStep(pinch.kept, g, 0f, 500f, 500f, 0f, 300f, 1f, pointers = 1, multiTouch = pinch.multiTouch, wheel = wheel)
        assertTrue(after.multiTouch)
        assertEquals(emptyList<Boolean>(), after.notches)
    }

    @Test
    fun withoutWheelAOneFingerDragScrollsTheHistory() {
        val kept = KeptZoom(ZoomState.FIT, null, 3000f)
        val step = gestureStep(kept, geometry(3000f), 0f, 500f, 500f, 0f, 300f, 1f, pointers = 1, multiTouch = false, wheel = null)
        assertEquals(emptyList<Boolean>(), step.notches)
        // From the bottom (1400) down by 300 px reveals older lines.
        assertEquals(1700f, step.kept.state.offsetY, eps)
        assertEquals(3000f, step.kept.history)
    }

    /** 10×5 cells of 96 px with margins 20 left and 10 top, in a 1000×500 image shown at [bandFit]. */
    private val terminal = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = ByteArray(0), pixelWidth = 1000, pixelHeight = 500, scale = 1.0,
        columns = 10, rows = 5, cellGeometry = CellGeometry(96.0, 96.0, 20.0, 10.0), firstAbsLine = 0, totalLines = 205,
        canResize = true, altScreen = false, scrollWheelReporting = false, isBrowser = false,
    )

    /** 200 history lines of 100 px above the band; the live top is line 200. */
    private val document = ScrollbackLayout(firstAbsLine = 0, liveTop = 200, totalLines = 205, rows = 5, lineHeightPx = 100f, altScreen = false)

    @Test
    fun selectionOverTheBandUsesTheVideoCells() {
        val atBottom = ZoomState.FIT
        assertEquals(SelectionPoint(202, 3), selectionPointAt(20f + 96 * 3 + 1, 100f + 10 + 96 * 2 + 1, atBottom, bandFit, document, terminal))
        assertEquals("the top margin is row 0", SelectionPoint(200, 0), selectionPointAt(5f, 101f, atBottom, bandFit, document, terminal))
        assertEquals("below the band, clamped", SelectionPoint(204, 9), selectionPointAt(999f, 1900f, atBottom, bandFit, document, terminal))
        // Zoomed in 2x on the band's top-left corner.
        val zoomed = ZoomState(2f, 0f, -100f)
        assertEquals(SelectionPoint(200, 1), selectionPointAt(2 * (20f + 96 + 1), 2 * (100f + 11) - 100f, zoomed, bandFit, document, terminal))
    }

    @Test
    fun selectionOverHistoryUsesTileSpace() {
        // Just above the band: the last history line; 105 px is column 1 of a margin-free tile
        // (the video's margins would make it column 0).
        assertEquals(SelectionPoint(199, 1), selectionPointAt(105f, 50f, ZoomState.FIT, bandFit, document, terminal))
        // Scrolled up 10000 px: the band's top edge on screen is line 100.
        assertEquals(SelectionPoint(100, 9), selectionPointAt(999f, 100f, ZoomState(1f, 0f, 10000f), bandFit, document, terminal))
        assertEquals("above the document, clamped", SelectionPoint(0, 0), selectionPointAt(-5f, 0f, ZoomState(1f, 0f, 30000f), bandFit, document, terminal))
    }

    @Test
    fun selectionNeedsCellsAndAnImage() {
        assertNull(selectionPointAt(500f, 300f, ZoomState.FIT, bandFit, document, terminal.copy(cellGeometry = null)))
        assertNull(selectionPointAt(500f, 300f, ZoomState.FIT, bandFit, document, terminal.copy(isBrowser = true)))
        assertNull(selectionPointAt(500f, 300f, ZoomState.FIT, FitRect.EMPTY, document, terminal))
        assertNull(selectionPointAt(500f, 300f, ZoomState(0f, 0f, 0f), bandFit, document, terminal))
    }

    @Test
    fun holdBeginsOnlyForOneStillFinger() {
        assertFalse("still, one finger: wait out the hold", holdCancelled(travel = 3f, pointers = 1, released = false, slop = 8f))
        assertTrue("moved past the slop: a pan, wheel drag or scroll", holdCancelled(travel = 9f, pointers = 1, released = false, slop = 8f))
        assertTrue("two fingers: a pinch", holdCancelled(travel = 0f, pointers = 2, released = false, slop = 8f))
        assertTrue("lifted: a tap", holdCancelled(travel = 0f, pointers = 0, released = true, slop = 8f))
        assertTrue("a lost pointer", holdCancelled(travel = Float.NaN, pointers = 1, released = false, slop = 8f))
    }
}
