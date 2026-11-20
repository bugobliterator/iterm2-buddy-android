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

    @Test
    fun aConsumedLiftIsACancel() {
        assertTrue("ACTION_CANCEL arrives unpressed and consumed", isCancelledLift(consumed = true, wasSelecting = false))
        assertFalse("a real lift is not consumed yet in the initial pass", isCancelledLift(consumed = false, wasSelecting = false))
        assertFalse("a selection ends either way", isCancelledLift(consumed = true, wasSelecting = true))
        assertFalse(isCancelledLift(consumed = false, wasSelecting = true))
    }

    /*
     * Browser drags (iOS `handleBrowserPan`/`accrueBrowserScroll`, `IOS/Screens/SessionView.swift:2256-2328`).
     * A 1000×700 view with a 100 px bar: the page at (0, 100, 1000, 500); at scale 2 it pans
     * x in [-1000, 0] and y in [-500, -100].
     */
    private val pageFit = visibleFitRect(1000, 500, 1000f, 700f, topInset = 100f, bottomInset = 0f)
    private val page = GestureGeometry(pageFit, 1000f, 700f, 4f, 100f, 0f, 0f, 0f, 0f)

    private fun drag(kept: KeptZoom, panX: Float, panY: Float, pointers: Int = 1, sawPinch: Boolean = false) =
        browserGestureStep(kept, page, 0f, 500f, 400f, panX, panY, 1f, pointers, sawPinch)

    @Test
    fun browserDragAtFitScrollsThePageBothWays() {
        val step = drag(KeptZoom(ZoomState.FIT, 0f), 10f, -20f)
        assertEquals(10f, step.scrollX, eps)
        assertEquals(-20f, step.scrollY, eps)
        assertEquals(ZoomState.FIT, step.kept.state)
        assertFalse(step.sawPinch)
    }

    @Test
    fun zoomedHorizontalDragOnlyPans() {
        val step = drag(KeptZoom(ZoomState(2f, -500f, -300f), 0f), 50f, 0f)
        assertEquals(-450f, step.kept.state.offsetX, eps)
        assertEquals(0f, step.scrollX, eps)
        assertEquals(0f, step.scrollY, eps)
        // Past the left edge it still never scrolls the page sideways.
        val atEdge = drag(KeptZoom(ZoomState(2f, 0f, -300f), 0f), 80f, 0f)
        assertEquals(0f, atEdge.kept.state.offsetX, eps)
        assertEquals(0f, atEdge.scrollX, eps)
    }

    @Test
    fun zoomedVerticalDragScrollsThePageOnlyPastTheEdge() {
        val middle = drag(KeptZoom(ZoomState(2f, -500f, -300f), 0f), 0f, 100f)
        assertEquals(-200f, middle.kept.state.offsetY, eps)
        assertEquals(0f, middle.scrollY, eps)
        // 30 px pans to the top edge; the other 20 scroll the page up (finger down).
        val partly = drag(KeptZoom(ZoomState(2f, -500f, -130f), 0f), 0f, 50f)
        assertEquals(-100f, partly.kept.state.offsetY, eps)
        assertEquals(20f, partly.scrollY, eps)
        val atBottom = drag(KeptZoom(ZoomState(2f, -500f, -500f), 0f), 0f, -40f)
        assertEquals(-40f, atBottom.scrollY, eps)
        assertEquals(0f, atBottom.scrollX, eps)
    }

    @Test
    fun aPinchZoomsAndNeverScrollsUntilTheGestureEnds() {
        val pinch = browserGestureStep(KeptZoom(ZoomState.FIT, 0f), page, 0f, 500f, 400f, 5f, 5f, 1.5f, 2, false)
        assertTrue(pinch.sawPinch)
        assertTrue("the first pinch event cancels the scroll", pinch.pinchStarted)
        assertEquals(1.5f, pinch.kept.state.scale, eps)
        assertEquals(0f, pinch.scrollX, eps)
        assertEquals(0f, pinch.scrollY, eps)
        // One finger left after the pinch: still no scroll, and no second cancel.
        val after = drag(pinch.kept, 0f, 30f, pointers = 1, sawPinch = true)
        assertTrue(after.sawPinch)
        assertFalse(after.pinchStarted)
        assertEquals(0f, after.scrollY, eps)
    }

    @Test
    fun flingOnlyAtFitAndNotAfterAPinch() {
        assertTrue(browserFlings(1f, sawPinch = false))
        assertTrue(browserFlings(1.001f, sawPinch = false))
        assertFalse(browserFlings(1.01f, sawPinch = false))
        assertFalse(browserFlings(1f, sawPinch = true))
    }

    @Test
    fun browserPointsAreEncodedImagePixels() {
        // A 2000×1000 page drawn 1000 px wide: two image pixels per view pixel at fit.
        assertEquals(2f, browserImagePxPerViewPx(2000, pageFit)!!, eps)
        assertNull(browserImagePxPerViewPx(2000, FitRect.EMPTY))
        assertNull(browserImagePxPerViewPx(0, pageFit))
        // A tap on the page maps to its pixel; one off the page (the area below it) is dropped.
        val tap = browserTapPoint(250f, 350f, ZoomState.FIT, pageFit, 2000, 1000)!!
        assertEquals(500f, tap.first, eps)
        assertEquals(500f, tap.second, eps)
        assertNull(browserTapPoint(250f, 650f, ZoomState.FIT, pageFit, 2000, 1000))
        // A drag that starts off the page scrolls the page at its nearest edge.
        val scroll = browserScrollPoint(250f, 650f, ZoomState.FIT, pageFit, 2000, 1000)!!
        assertEquals(500f, scroll.first, eps)
        assertEquals(999f, scroll.second, eps)
        // Zoomed: the pan-start point follows the zoom.
        val zoomed = browserScrollPoint(0f, 100f, ZoomState(2f, -500f, -100f), pageFit, 2000, 1000)!!
        assertEquals(500f, zoomed.first, eps)
        assertEquals(0f, zoomed.second, eps)
        assertNull(browserScrollPoint(0f, 0f, ZoomState.FIT, FitRect.EMPTY, 2000, 1000))
    }

    /*
     * Fill the screen: the same page (2000×1000 image, fit at (0, 100, 1000, 500) under the 100 px
     * bar of a 1000×700 view) rests covering the 1000×600 visible area: scale 1.2, 1200 px wide
     * centred (offset x −100, 100 px cropped each side), its top on the bar (offset y −20).
     */
    private val fill = browserFillZoom(pageFit, 1000f, 700f, topInset = 100f, bottomInset = 0f)!!
    private val filled = GestureGeometry(pageFit, 1000f, 700f, 4f, 100f, 0f, 0f, 0f, 0f, fill)

    private fun fillDrag(kept: KeptZoom, panX: Float, panY: Float, zoom: Float = 1f, pointers: Int = 1, sawPinch: Boolean = false) =
        browserGestureStep(kept, filled, 0f, 500f, 400f, panX, panY, zoom, pointers, sawPinch)

    @Test
    fun fillCoversTheVisibleAreaWithNoBand() {
        assertEquals(1.2f, fill.scale, eps)
        assertEquals(-100f, fill.offsetX, eps)
        assertEquals(-20f, fill.offsetY, eps)
        // The page's edges on screen: the height fills top bar to bottom, the sides are cropped evenly.
        assertEquals(100f, fill.offsetY + fill.scale * pageFit.top, eps)
        assertEquals(700f, fill.offsetY + fill.scale * (pageFit.top + pageFit.height), eps)
        assertEquals(-100f, fill.offsetX + fill.scale * pageFit.left, eps)
        assertEquals(1100f, fill.offsetX + fill.scale * (pageFit.left + pageFit.width), eps)
        // A page relatively taller than the area fills its width instead, top-aligned under the bar.
        val tallFit = visibleFitRect(500, 1000, 1000f, 700f, topInset = 100f, bottomInset = 0f)
        val tall = browserFillZoom(tallFit, 1000f, 700f, topInset = 100f, bottomInset = 0f)!!
        assertEquals(1000f / 300f, tall.scale, eps)
        assertEquals(0f, tall.offsetX + tall.scale * tallFit.left, eps)
        assertEquals(1000f, tall.offsetX + tall.scale * (tallFit.left + tallFit.width), eps)
        assertEquals(100f, tall.offsetY + tall.scale * tallFit.top, eps)
        // A page of the area's own shape is already filled at fit; nothing to fill without a page.
        val sameShape = visibleFitRect(2000, 1200, 1000f, 700f, topInset = 100f, bottomInset = 0f)
        assertEquals(ZoomState.FIT, browserFillZoom(sameShape, 1000f, 700f, topInset = 100f, bottomInset = 0f))
        assertNull(browserFillZoom(FitRect.EMPTY, 1000f, 700f, 100f, 0f))
        assertNull(browserFillZoom(pageFit, 1000f, 100f, topInset = 100f, bottomInset = 0f))
    }

    @Test
    fun aPageFirstShowsAtTheFillAndFollowsItsChanges() {
        val first = KeptZoom(ZoomState.FIT, null, atBase = true)
        assertEquals(fill, shownZoom(first, filled, 0f))
        // A rotation, or the bar measured: the base changes and a kept zoom at the base follows it.
        val landscapeFit = visibleFitRect(2000, 1000, 1600f, 500f, topInset = 100f, bottomInset = 0f)
        val landscapeFill = browserFillZoom(landscapeFit, 1600f, 500f, topInset = 100f, bottomInset = 0f)!!
        val landscape = GestureGeometry(landscapeFit, 1600f, 500f, 4f, 100f, 0f, 0f, 0f, 0f, landscapeFill)
        val panned = fillDrag(first, 40f, 0f).kept
        assertTrue(panned.atBase)
        assertEquals(landscapeFill, shownZoom(panned, landscape, 0f))
        // A zoom the owner chose (pinched out to the whole page) is kept, as a terminal's.
        val whole = KeptZoom(ZoomState.FIT, 0f)
        assertEquals(ZoomState.FIT, shownZoom(whole, landscape, 0f))
        // A terminal has no base: nothing changes for it.
        assertEquals(ZoomState.FIT, shownZoom(KeptZoom(ZoomState.FIT, null, atBase = true), page, 0f))
    }

    @Test
    fun atTheFillHorizontalDragsPanAndVerticalOnesScrollThePage() {
        val start = KeptZoom(ZoomState.FIT, null, atBase = true)
        val sideways = fillDrag(start, 60f, 0f)
        assertEquals(-40f, sideways.kept.state.offsetX, eps)
        assertEquals(0f, sideways.scrollX, eps)
        assertEquals(0f, sideways.scrollY, eps)
        assertTrue("still at the base", sideways.kept.atBase)
        // Past the cropped side it stops; the page never scrolls sideways at the fill.
        val edge = fillDrag(sideways.kept, 500f, 0f)
        assertEquals(0f, edge.kept.state.offsetX, eps)
        assertEquals(0f, edge.scrollX, eps)
        // The height fits exactly: all vertical travel goes to the page, none to panning.
        val up = fillDrag(start, 0f, -30f)
        assertEquals(-30f, up.scrollY, eps)
        assertEquals(fill.offsetY, up.kept.state.offsetY, eps)
        val down = fillDrag(start, 5f, 25f)
        assertEquals(25f, down.scrollY, eps)
        assertEquals(-95f, down.kept.state.offsetX, eps)
    }

    @Test
    fun pinchingOutFromTheFillReachesTheWholePage() {
        val pinch = fillDrag(KeptZoom(ZoomState.FIT, null, atBase = true), 0f, 0f, zoom = 0.5f, pointers = 2)
        assertEquals(ZoomState.FIT, pinch.kept.state)
        assertFalse(pinch.kept.atBase)
        // At fit a drag scrolls the page both ways, and a release flings there.
        val drag = fillDrag(pinch.kept, 10f, -20f)
        assertEquals(10f, drag.scrollX, eps)
        assertEquals(-20f, drag.scrollY, eps)
        // Pinching back in to exactly the fill scale is the base again.
        val back = fillDrag(pinch.kept, 0f, 0f, zoom = 1.2f, pointers = 2)
        assertTrue(back.kept.atBase)
    }

    @Test
    fun flingsCoastAtTheFillAndAtFitOnly() {
        assertTrue(browserFlings(1.2f, sawPinch = false, baseScale = 1.2f))
        assertTrue(browserFlings(1.2011f, sawPinch = false, baseScale = 1.2f))
        assertTrue(browserFlings(1f, sawPinch = false, baseScale = 1.2f))
        assertFalse(browserFlings(1.1f, sawPinch = false, baseScale = 1.2f))
        assertFalse(browserFlings(1.5f, sawPinch = false, baseScale = 1.2f))
        assertFalse(browserFlings(1.2f, sawPinch = true, baseScale = 1.2f))
    }

    @Test
    fun tapsAndScrollPointsMapThroughTheFill() {
        // The screen's centre (500, 400) is the page's centre pixel.
        val tap = browserTapPoint(500f, 400f, fill, pageFit, 2000, 1000)!!
        assertEquals(1000f, tap.first, eps)
        assertEquals(500f, tap.second, eps)
        // The top-left of the visible area is 100 cropped view px in: image x 100 × 2 / 1.2.
        val corner = browserTapPoint(0f, 100f, fill, pageFit, 2000, 1000)!!
        assertEquals(2000f * 100f / 1200f, corner.first, 0.01f)
        assertEquals(0f, corner.second, eps)
        // Scroll density at the fill: 2 image px per fit view px over a zoom of 1.2.
        assertEquals(2f / 1.2f, browserImagePxPerViewPx(2000, pageFit)!! / fill.scale, eps)
    }

    @Test
    fun withoutADoubleTapWindowEveryTapIsImmediate() {
        // A browser has no double-tap zoom (iOS LiveCanvas has none): a click is sent at the lift.
        val arbiter = PressArbiter(holdMillis = 300, doubleTapMillis = 0, slop = 8f)
        assertTrue(arbiter.down(10f, 10f, 0, selectable = false).isEmpty())
        assertEquals(listOf(PressAction.Tap(10f, 10f)), arbiter.up(10f, 10f, 50))
        assertTrue(arbiter.down(12f, 10f, 80, selectable = false).isEmpty())
        assertEquals("a quick second tap is a second click", listOf(PressAction.Tap(12f, 10f)), arbiter.up(12f, 10f, 120))
        assertNull(arbiter.deadline)
    }
}
