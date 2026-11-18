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

    /** Hold 300 ms, double-tap window 300 ms, slop 8 px. */
    private fun arbiter() = PressArbiter(holdMillis = 300, doubleTapMillis = 300, slop = 8f)

    @Test
    fun tapThenHoldAndDragGivesNoTap() {
        val press = arbiter()
        val actions = mutableListOf<PressAction>()
        actions += press.down(100f, 100f, 0, selectable = true)
        actions += press.up(100f, 100f, 60)
        // A long press inside the double-tap window: no deferred tap may land in its selection.
        actions += press.down(200f, 300f, 200, selectable = true)
        assertEquals(500L, press.deadline)
        actions += press.tick(500)
        assertTrue(press.selecting)
        actions += press.move(260f, 340f, pointers = 1, time = 550)
        actions += press.up(260f, 340f, 600)
        actions += press.tick(5000)
        assertEquals(
            listOf(PressAction.SelectBegin(200f, 300f), PressAction.SelectMove(260f, 340f), PressAction.SelectEnd(260f, 340f)),
            actions,
        )
    }

    @Test
    fun holdAndLiftAfterATapKeepsTheSelection() {
        val press = arbiter()
        val actions = press.down(100f, 100f, 0, true) + press.up(100f, 100f, 50) +
            press.down(100f, 100f, 150, true) + press.up(102f, 101f, 700) + press.tick(10_000)
        assertEquals(listOf(PressAction.SelectBegin(100f, 100f), PressAction.SelectEnd(102f, 101f)), actions)
    }

    @Test
    fun quickTapStillTaps() {
        val press = arbiter()
        assertEquals(emptyList<PressAction>(), press.down(10f, 20f, 0, true) + press.up(11f, 21f, 80))
        assertEquals(380L, press.deadline)
        assertEquals(emptyList<PressAction>(), press.tick(379))
        assertEquals(listOf(PressAction.Tap(11f, 21f)), press.tick(380))
        assertEquals(null, press.deadline)
        // Unselectable, a long press lifted is a tap too.
        val plain = arbiter()
        assertEquals(null, plain.down(10f, 20f, 0, selectable = false).let { plain.deadline })
        assertEquals(listOf(PressAction.Tap(10f, 20f)), plain.up(10f, 20f, 2000) + plain.tick(2300))
    }

    @Test
    fun doubleTapStillZooms() {
        val press = arbiter()
        val actions = press.down(10f, 20f, 0, true) + press.up(10f, 20f, 50) +
            press.down(12f, 22f, 150, true) + press.up(12f, 22f, 200) + press.tick(10_000)
        assertEquals(listOf(PressAction.DoubleTap(12f, 22f)), actions)
        assertFalse(press.selecting)
    }

    @Test
    fun dragAndPinchStillPanAndNeverSelect() {
        val press = arbiter()
        val drag = press.down(10f, 20f, 0, true) + press.move(14f, 20f, 1, 50) + press.move(30f, 20f, 1, 100) +
            press.tick(1000) + press.move(300f, 20f, 1, 1100) + press.up(300f, 20f, 1200) + press.tick(5000)
        assertEquals("a drag is the pan or wheel detector's", emptyList<PressAction>(), drag)
        val pinch = press.down(10f, 20f, 0, true) + press.move(10f, 20f, pointers = 2, time = 40) + press.tick(1000) + press.up(10f, 20f, 1100)
        assertEquals(emptyList<PressAction>(), pinch)
        // A tap then a drag: the tap still happens.
        val tapThenDrag = press.down(10f, 20f, 0, true) + press.up(10f, 20f, 40) + press.down(10f, 20f, 100, true) + press.move(60f, 20f, 1, 150)
        assertEquals(listOf(PressAction.Tap(10f, 20f)), tapThenDrag)
    }

    @Test
    fun cancelEndsASelectionUnderWay() {
        val press = arbiter()
        press.down(10f, 20f, 0, true)
        press.tick(300)
        press.move(40f, 20f, 1, 350)
        assertEquals(listOf(PressAction.SelectEnd(40f, 20f)), press.cancel())
        assertEquals(emptyList<PressAction>(), press.cancel())
    }
}
