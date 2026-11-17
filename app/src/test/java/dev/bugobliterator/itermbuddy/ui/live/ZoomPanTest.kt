package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.stream.CellGeometry
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPanTest {
    private val eps = 1e-3f

    /** The image point under view point ([x], [y]), forced non-null. */
    private fun under(x: Float, y: Float, state: ZoomState, fit: FitRect, w: Int, h: Int): Pair<Float, Float> =
        checkNotNull(viewToImage(x, y, state, fit, w, h)) { "($x, $y) is outside the image" }

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, eps)
        assertEquals(expected.second, actual.second, eps)
    }

    @Test
    fun fitRectIsAspectFitAndCentred() {
        // 1280×800 in a 1080×2000 portrait view: full width, 675 tall, centred vertically.
        assertEquals(FitRect(0f, 662.5f, 1080f, 675f), fitRect(1280, 800, 1080f, 2000f))
        // In a wide view it is full height and centred horizontally.
        assertEquals(FitRect(160f, 0f, 1280f, 800f), fitRect(1280, 800, 1600f, 800f))
    }

    @Test
    fun pinchKeepsCentroidFixed() {
        val fit = fitRect(1280, 800, 1280f, 800f)
        val max = maxScale(1280, fit)
        val start = ZoomState(1f, 0f, 0f)
        val before = under(300f, 400f, start, fit, 1280, 800)
        val zoomed = applyGesture(start, 300f, 400f, 2f, 0f, 0f, fit, 1280f, 800f, max)
        assertEquals(2f, zoomed.scale, eps)
        assertPoint(before, under(300f, 400f, zoomed, fit, 1280, 800))
        // Again from a zoomed, panned state at another centroid.
        val panned = applyGesture(zoomed, 640f, 400f, 1f, -200f, -100f, fit, 1280f, 800f, max)
        val mid = under(900f, 500f, panned, fit, 1280, 800)
        val more = applyGesture(panned, 900f, 500f, 1.5f, 0f, 0f, fit, 1280f, 800f, max)
        assertEquals(3f, more.scale, eps)
        assertPoint(mid, under(900f, 500f, more, fit, 1280, 800))
    }

    @Test
    fun scaleClampedToMax() {
        // Fitted 640 px wide: max = 4 × 1280 / 640 = 8.
        val fit = fitRect(1280, 800, 640f, 400f)
        val max = maxScale(1280, fit)
        assertEquals(8f, max, eps)
        val start = ZoomState(1f, 0f, 0f)
        assertEquals(8f, applyGesture(start, 320f, 200f, 100f, 0f, 0f, fit, 640f, 400f, max).scale, eps)
        assertEquals(1f, applyGesture(start, 320f, 200f, 0.01f, 0f, 0f, fit, 640f, 400f, max).scale, eps)
        // An image smaller than four times the view still zooms no further than fit allows: at least 1.
        val bigFit = fitRect(100, 100, 1000f, 1000f)
        assertEquals(1f, maxScale(100, bigFit), eps)
    }

    @Test
    fun panClampedToImage() {
        val fit = fitRect(1280, 800, 1280f, 800f)
        val max = maxScale(1280, fit)
        val start = ZoomState(1f, 0f, 0f)
        // At fit there is nothing to pan.
        assertEquals(start, applyGesture(start, 640f, 400f, 1f, 500f, -300f, fit, 1280f, 800f, max))
        val zoomed = applyGesture(start, 640f, 400f, 2f, 0f, 0f, fit, 1280f, 800f, max)
        // Dragging far right and down stops with the image's top-left corner at the view's.
        val topLeft = applyGesture(zoomed, 640f, 400f, 1f, 10_000f, 10_000f, fit, 1280f, 800f, max)
        assertEquals(0f, topLeft.offsetX, eps)
        assertEquals(0f, topLeft.offsetY, eps)
        // And far left and up with its bottom-right at the view's.
        val bottomRight = applyGesture(zoomed, 640f, 400f, 1f, -10_000f, -10_000f, fit, 1280f, 800f, max)
        assertEquals(1280f - 2 * 1280f, bottomRight.offsetX, eps)
        assertEquals(800f - 2 * 800f, bottomRight.offsetY, eps)
        assertPoint(1280f to 800f, under(1279.999f, 799.999f, bottomRight, fit, 1280, 800))
        // Letterboxed axis (portrait): content shorter than the view sits at its top (the space
        // left over goes to the bottom), whatever the pan; horizontally it is centred.
        val portraitFit = fitRect(1280, 800, 1080f, 2000f)
        val portraitMax = maxScale(1280, portraitFit)
        val z = applyGesture(ZoomState(1f, 0f, 0f), 540f, 1000f, 2f, 0f, 5_000f, portraitFit, 1080f, 2000f, portraitMax)
        // 675 × 2 = 1350 < 2000: top-aligned.
        val top = z.offsetY + z.scale * portraitFit.top
        assertEquals(0f, top, eps)
    }

    @Test
    fun viewToImageUndoesFitAndZoom() {
        val fit = fitRect(1280, 800, 1080f, 2000f)
        // At fit: the fit rect's top-left is the image origin, its centre the image centre.
        assertPoint(0f to 0f, under(0f, 662.5f, ZoomState(1f, 0f, 0f), fit, 1280, 800))
        assertPoint(640f to 400f, under(540f, 1000f, ZoomState(1f, 0f, 0f), fit, 1280, 800))
        // Zoomed and panned: screen = offset + scale × view point, view point → image by the fit.
        val state = ZoomState(3f, -1200f, -2300f)
        val imageX = 1000f
        val imageY = 700f
        val viewX = fit.left + imageX * fit.width / 1280
        val viewY = fit.top + imageY * fit.height / 800
        val screenX = state.offsetX + state.scale * viewX
        val screenY = state.offsetY + state.scale * viewY
        assertPoint(imageX to imageY, under(screenX, screenY, state, fit, 1280, 800))
    }

    @Test
    fun pointOutsideImageIsNull() {
        val fit = fitRect(1280, 800, 1080f, 2000f)
        val state = ZoomState(1f, 0f, 0f)
        assertNull(viewToImage(540f, 100f, state, fit, 1280, 800)) // letterbox above
        assertNull(viewToImage(540f, 1900f, state, fit, 1280, 800)) // letterbox below
        assertNull(viewToImage(-1f, 1000f, state, fit, 1280, 800))
        assertNull(viewToImage(1080f, 1000f, state, fit, 1280, 800)) // right edge is outside
        assertNotNull(viewToImage(0f, 1000f, state, fit, 1280, 800))
    }

    @Test
    fun doubleTapTogglesFitAndTwoAndAHalfAtTheTap() {
        val fit = fitRect(1280, 800, 1280f, 800f)
        val max = maxScale(1280, fit)
        val start = ZoomState(1f, 0f, 0f)
        val tapped = under(400f, 300f, start, fit, 1280, 800)
        val zoomed = toggleZoom(start, 400f, 300f, fit, 1280f, 800f, max)
        assertEquals(DOUBLE_TAP_SCALE, zoomed.scale, eps)
        assertPoint(tapped, under(400f, 300f, zoomed, fit, 1280, 800))
        assertEquals(ZoomState(1f, 0f, 0f), toggleZoom(zoomed, 10f, 10f, fit, 1280f, 800f, max))
        // Never past the maximum.
        val small = fitRect(1280, 800, 5120f, 3200f)
        assertEquals(1f, toggleZoom(start, 10f, 10f, small, 5120f, 3200f, maxScale(1280, small)).scale, eps)
    }

    @Test
    fun imageToCellUsesTheGeometry() {
        val config = terminalConfig()
        // cellWidth 15.75, cellHeight 32, leftMargin 10, topMargin 0.
        assertEquals(0 to 0, imageToCell(10f, 0f, config))
        assertEquals(1 to 2, imageToCell(10f + 15.75f * 1.5f, 64.5f, config))
        assertNull(imageToCell(5f, 0f, config)) // in the left margin
        assertNull(imageToCell(10f + 15.75f * 80, 0f, config)) // past the last column
        // Without geometry: pixels over the grid, no margins.
        val noGeometry = config.copy(cellGeometry = null)
        assertEquals(79 to 24, imageToCell(1279f, 799f, noGeometry))
    }

    @Test
    fun browserConfigHasNoFitAndNoCellMapping() {
        val config = fixtureConfig("stream-config-browser")
        assertTrue(config.isBrowser)
        assertEquals(0, config.columns)
        assertEquals(0, config.rows)
        assertNull(fitGrid(1080, 2000, config, null))
        assertNull(fitGrid(2400, 1080, config, 4096 to 2304))
        assertNull(fitGrid(1080, 2000, config.copy(canResize = true), 4096 to 2304))
        assertNull(imageToCell(100f, 100f, config))
        assertNull(imageToCell(100f, 100f, config.copy(isBrowser = false)))
        // Every function here on the browser image, and on empty images and views: no exception, finite results.
        val fit = fitRect(config.pixelWidth, config.pixelHeight, 1080f, 2000f)
        val max = maxScale(config.pixelWidth, fit)
        val state = applyGesture(ZoomState(1f, 0f, 0f), 540f, 1000f, 2f, 10f, 10f, fit, 1080f, 2000f, max)
        val point = under(540f, 1000f, state, fit, config.pixelWidth, config.pixelHeight)
        assertTrue(point.first.isFinite() && point.second.isFinite())
        val empty = fitRect(0, 0, 1080f, 2000f)
        val emptyView = fitRect(1280, 800, 0f, 0f)
        for (rect in listOf(empty, emptyView)) {
            listOf(rect.left, rect.top, rect.width, rect.height).forEach { assertTrue("$rect", it.isFinite()) }
            val m = maxScale(0, rect)
            assertTrue(m.isFinite() && m >= 1f)
            val s = applyGesture(ZoomState(1f, 0f, 0f), 0f, 0f, 2f, 5f, 5f, rect, 0f, 0f, m)
            listOf(s.scale, s.offsetX, s.offsetY).forEach { assertTrue("$s", it.isFinite()) }
            val t = toggleZoom(ZoomState(1f, 0f, 0f), 0f, 0f, rect, 0f, 0f, m)
            listOf(t.scale, t.offsetX, t.offsetY).forEach { assertTrue("$t", it.isFinite()) }
            assertNull(viewToImage(0f, 0f, ZoomState(1f, 0f, 0f), rect, 0, 0))
        }
        assertNull(viewToImage(0f, 0f, ZoomState(0f, 0f, 0f), fit, config.pixelWidth, config.pixelHeight))
    }

    @Test
    fun zoomKeyKeepsZoomThroughTheTransientEmptyImageAndResetsOnARealSizeChange() {
        val first = nextZoomKey(null, "guid-alpha", 0, 0)
        assertEquals(ZoomKey("guid-alpha", null), first)
        val live = nextZoomKey(first, "guid-alpha", 1280, 800)
        assertEquals(ZoomKey("guid-alpha", ImageSize(1280, 800)), live)
        // A reconnect, retry or lock: no config (0×0) for a while, then the same size again.
        val reconnecting = nextZoomKey(live, "guid-alpha", 0, 0)
        assertEquals("the transient 0×0 keeps the key, so the zoom stays", live, reconnecting)
        assertEquals(live, nextZoomKey(reconnecting, "guid-alpha", 1280, 800))
        // A real size change resets it.
        val resized = nextZoomKey(reconnecting, "guid-alpha", 1440, 900)
        assertNotEquals(live, resized)
        assertEquals(ImageSize(1440, 900), resized.image)
        // So does another session, even at the same size.
        assertNotEquals(resized, nextZoomKey(resized, "guid-bravo", 1440, 900))
        assertEquals(ZoomKey("guid-bravo", null), nextZoomKey(resized, "guid-bravo", 0, 0))
    }

    @Test
    fun clampZoomKeepsTheScaleAndRefitsThePan() {
        val wide = fitRect(1280, 800, 1280f, 800f)
        val zoomed = applyGesture(ZoomState.FIT, 1280f, 800f, 3f, 0f, 0f, wide, 1280f, 800f, maxScale(1280, wide))
        // The canvas shrinks: the scale stays and the pan is clamped to the new fit.
        val small = fitRect(1280, 800, 640f, 400f)
        val clamped = clampZoom(zoomed, small, 640f, 400f)
        assertEquals(3f, clamped.scale, eps)
        assertEquals(640f - 3 * 640f, clamped.offsetX, eps)
        assertEquals(400f - 3 * 400f, clamped.offsetY, eps)
        assertEquals(ZoomState.FIT, clampZoom(ZoomState.FIT, small, 640f, 400f))
    }

    private fun terminalConfig(): StreamConfig {
        val config = fixtureConfig("stream-config-terminal")
        assertEquals(CellGeometry(15.75, 32.0, 10.0, 0.0), config.cellGeometry)
        return config
    }

    private fun fixtureConfig(name: String): StreamConfig {
        val file = Json.parseToJsonElement(Fixtures.read("messages/live-session/$name.json")).jsonObject
        val envelope = file.getValue("json").toString().toByteArray()
        return (decodeEnvelope(envelope).second as MacMessage.StreamConfigMessage).config
    }

    // ---- Keyboard lift: a bottom inset (IME plus key bar overlapping the canvas) ----

    /** A tall image filling a 500×1000 view at fit: fit rect (0, 0, 500, 1000). */
    private val tallFit = fitRect(1000, 2000, 500f, 1000f)

    private fun bottomOf(state: ZoomState, fit: FitRect) = state.offsetY + state.scale * (fit.top + fit.height)

    @Test
    fun liftedFitSitsAboveTheKeyboard() {
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, fromInset = 0f, toInset = 300f)
        assertEquals(1f, lifted.scale, eps)
        assertEquals(-300f, lifted.offsetY, eps)
        assertEquals("the image bottom is at viewH - inset", 700f, bottomOf(lifted, tallFit), eps)
        // Part way through the IME animation it follows the inset.
        assertEquals(-150f, liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 150f).offsetY, eps)
        // Clamping with the inset keeps the lift.
        assertEquals(lifted, clampZoom(lifted, tallFit, 500f, 1000f, bottomInset = 300f))
    }

    @Test
    fun panningUpShowsTheTopWhileLifted() {
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 300f)
        val top = applyGesture(lifted, 250f, 500f, 1f, 0f, 500f, tallFit, 500f, 1000f, maxScale = 4f, bottomInset = 300f)
        assertEquals("the top is reachable, and no further", 0f, top.offsetY, eps)
        val back = applyGesture(top, 250f, 500f, 1f, 0f, -1000f, tallFit, 500f, 1000f, maxScale = 4f, bottomInset = 300f)
        assertEquals("and back down only to the keyboard's edge", 700f, bottomOf(back, tallFit), eps)
        // Without the inset the bottom cannot go above the view's bottom.
        val noInset = applyGesture(ZoomState.FIT, 250f, 500f, 1f, 0f, -300f, tallFit, 500f, 1000f, maxScale = 4f)
        assertEquals(ZoomState.FIT, noInset)
    }

    @Test
    fun closingTheKeyboardReturnsToFit() {
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 300f)
        assertEquals(ZoomState.FIT, liftForKeyboard(lifted, tallFit, 500f, 1000f, 300f, 0f))
        val pannedUp = lifted.copy(offsetY = 0f)
        assertEquals(ZoomState.FIT, liftForKeyboard(pannedUp, tallFit, 500f, 1000f, 300f, 0f))
    }

    @Test
    fun zoomedOnTheBottomStaysPinned() {
        val atBottom = ZoomState(2f, -250f, -1000f)
        assertEquals(atBottom, clampZoom(atBottom, tallFit, 500f, 1000f))
        val lifted = liftForKeyboard(atBottom, tallFit, 500f, 1000f, 0f, 300f)
        assertEquals(700f, bottomOf(lifted, tallFit), eps)
        assertEquals(atBottom.offsetX, lifted.offsetX, eps)
        // Viewing the middle: the keyboard covers what it covers, nothing moves.
        val middle = ZoomState(2f, -250f, -500f)
        assertEquals(middle, liftForKeyboard(middle, tallFit, 500f, 1000f, 0f, 300f))
    }

    @Test
    fun shortImageStaysAtTheTopAboveTheKeyboard() {
        val wide = visibleFitRect(2000, 1000, 500f, 1000f, topInset = 0f, bottomInset = 0f) // (0, 0, 500, 250)
        val lifted = liftForKeyboard(ZoomState.FIT, wide, 500f, 1000f, 0f, 400f)
        assertEquals("already above the keyboard: nothing moves", ZoomState.FIT, lifted)
        assertTrue(bottomOf(lifted, wide) <= 600f + eps)
        assertEquals(ZoomState.FIT, liftForKeyboard(lifted, wide, 500f, 1000f, 400f, 0f))
    }

    @Test
    fun aKeyboardTallerThanTheViewNeverThrows() {
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 1500f)
        assertTrue(lifted.offsetY.isFinite())
        assertTrue(liftForKeyboard(ZoomState.FIT, FitRect.EMPTY, 0f, 0f, 0f, 300f).offsetY.isFinite())
    }

    @Test
    fun toggleZoomBackToFitStaysLifted() {
        val zoomed = toggleZoom(ZoomState.FIT, 250f, 600f, tallFit, 500f, 1000f, maxScale = 4f, bottomInset = 300f)
        assertTrue(zoomed.scale > 1f)
        val back = toggleZoom(zoomed, 250f, 600f, tallFit, 500f, 1000f, maxScale = 4f, bottomInset = 300f)
        assertEquals(1f, back.scale, eps)
        assertEquals(700f, bottomOf(back, tallFit), eps)
    }

    @Test
    fun tapsMapThroughTheLift() {
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 300f)
        assertPoint(500f to 600f, under(250f, 0f, lifted, tallFit, 1000, 2000))
        assertPoint(500f to 1998f, under(250f, 699f, lifted, tallFit, 1000, 2000))
    }

    // ---- Edge to edge: a top bar over the picture and the navigation bar below it ----

    @Test
    fun fitSitsJustBelowTheBarAndAboveTheNavigationBar() {
        // A 1080×2400 window, a 200 px status bar plus top bar, a 60 px gesture bar.
        val wide = visibleFitRect(1600, 1000, 1080f, 2400f, topInset = 200f, bottomInset = 60f)
        assertEquals(0f, wide.left, eps)
        assertEquals("top-aligned just below the bar", 200f, wide.top, eps)
        assertEquals(675f, wide.height, eps)
        assertEquals("clamping keeps fit where it is", ZoomState.FIT, clampZoom(ZoomState.FIT, wide, 1080f, 2400f, bottomInset = 60f, topInset = 200f))
        // A tall image fills the visible height exactly and is centred across.
        val tall = visibleFitRect(1000, 4280, 1080f, 2400f, topInset = 200f, bottomInset = 60f)
        assertEquals(200f, tall.top, eps)
        assertEquals(2140f, tall.height, eps)
        assertEquals(2340f, tall.top + tall.height, eps)
        assertEquals((1080f - tall.width) / 2, tall.left, eps)
        // Never a NaN or a throw for a view smaller than its insets.
        assertTrue(visibleFitRect(1000, 1000, 100f, 100f, 80f, 80f).isEmpty)
    }

    @Test
    fun clampKeepsZoomedContentBetweenTheInsets() {
        val fit = visibleFitRect(1000, 2000, 500f, 1260f, topInset = 200f, bottomInset = 60f) // (0, 200, 500, 1000)
        val zoomed = ZoomState(2f, -250f, 0f)
        // Dragged far down: the image's top stops at the bar's bottom edge.
        val down = applyGesture(zoomed, 250f, 600f, 1f, 0f, 10_000f, fit, 500f, 1260f, maxScale = 4f, bottomInset = 60f, topInset = 200f)
        assertEquals(200f, down.offsetY + down.scale * fit.top, eps)
        // Dragged far up: its bottom stops at the navigation bar's top edge.
        val up = applyGesture(zoomed, 250f, 600f, 1f, 0f, -10_000f, fit, 500f, 1260f, maxScale = 4f, bottomInset = 60f, topInset = 200f)
        assertEquals(1200f, up.offsetY + up.scale * (fit.top + fit.height), eps)
        // With the keyboard up the bottom inset takes over, the top one stays.
        val lifted = liftForKeyboard(ZoomState.FIT, fit, 500f, 1260f, fromInset = 60f, toInset = 560f, topInset = 200f)
        assertEquals(700f, bottomOf(lifted, fit), eps)
        val top = applyGesture(lifted, 250f, 400f, 1f, 0f, 10_000f, fit, 500f, 1260f, maxScale = 4f, bottomInset = 560f, topInset = 200f)
        assertEquals("panning up reaches the image's top, below the bar", 200f, top.offsetY + fit.top, eps)
        assertEquals(ZoomState.FIT, liftForKeyboard(lifted, fit, 500f, 1260f, 560f, 60f, topInset = 200f))
    }

    @Test
    fun tapsMapBelowTheBar() {
        val fit = visibleFitRect(1600, 1000, 1080f, 2400f, topInset = 200f, bottomInset = 60f)
        assertNull("the bar's area is above the image", viewToImage(540f, 199f, ZoomState.FIT, fit, 1600, 1000))
        assertPoint(0f to 0f, under(0f, 200f, ZoomState.FIT, fit, 1600, 1000))
        assertPoint(800f to 500f, under(540f, 200f + 337.5f, ZoomState.FIT, fit, 1600, 1000))
    }

    @Test
    fun fitViewportIsTheAreaBetweenTheInsets() {
        assertEquals(1080 to 2140, fitViewport(1080, 2400, topInset = 200, bottomInset = 60))
        assertEquals(0 to 0, fitViewport(0, 0, 200, 60))
        assertEquals(500 to 0, fitViewport(500, 100, 80, 80))
    }

    // ---- Side insets (landscape) and the lift's floor ----

    @Test
    fun landscapeSideNavigationBarIsAnInset() {
        // A 2400×1080 landscape window: 150 px of bar on top, a 130 px navigation bar on the right.
        val fit = visibleFitRect(1600, 1000, 2400f, 1080f, topInset = 150f, bottomInset = 0f, leftInset = 0f, rightInset = 130f)
        assertEquals(930f, fit.height, eps)
        assertEquals(1488f, fit.width, eps)
        assertEquals("centred in the 2270 px left of the bar", (2270f - 1488f) / 2, fit.left, eps)
        assertTrue(fit.left + fit.width <= 2270f + eps)
        assertEquals(ZoomState.FIT, clampZoom(ZoomState.FIT, fit, 2400f, 1080f, 0f, 150f, 0f, 130f))
        // Zoomed in and dragged far left: the image's right edge stops at the navigation bar.
        val zoomed = ZoomState(2f, 0f, 0f)
        val left = applyGesture(zoomed, 1200f, 540f, 1f, -10_000f, 0f, fit, 2400f, 1080f, maxScale = 4f, bottomInset = 0f, topInset = 150f, leftInset = 0f, rightInset = 130f)
        assertEquals(2270f, left.offsetX + left.scale * (fit.left + fit.width), eps)
        // And far right: its left edge stops at the left inset (a cutout here).
        val cutoutFit = visibleFitRect(1600, 1000, 2400f, 1080f, 150f, 0f, leftInset = 100f, rightInset = 0f)
        val right = applyGesture(zoomed, 1200f, 540f, 1f, 10_000f, 0f, cutoutFit, 2400f, 1080f, maxScale = 4f, bottomInset = 0f, topInset = 150f, leftInset = 100f)
        assertEquals(100f, right.offsetX + right.scale * cutoutFit.left, eps)
        assertTrue(cutoutFit.left >= 100f)
    }

    @Test
    fun fitViewportExcludesSideInsets() {
        assertEquals(2270 to 930, fitViewport(2400, 1080, topInset = 150, bottomInset = 0, leftInset = 0, rightInset = 130))
        assertEquals(2200 to 930, fitViewport(2400, 1080, 150, 0, leftInset = 100, rightInset = 100))
    }

    @Test
    fun liftNeverPushesTheBottomAboveAQuarterBelowTheBar() {
        // A keyboard covering 900 of 1000 px: unfloored the bottom would be at 100.
        val lifted = liftForKeyboard(ZoomState.FIT, tallFit, 500f, 1000f, 0f, 900f)
        assertEquals(250f, bottomOf(lifted, tallFit), eps)
        assertEquals(ZoomState.FIT, liftForKeyboard(lifted, tallFit, 500f, 1000f, 900f, 0f))
        // With a top bar the quarter is of the height below it.
        val belowBar = visibleFitRect(1000, 2000, 500f, 1200f, topInset = 200f, bottomInset = 0f)
        val liftedBelow = liftForKeyboard(ZoomState.FIT, belowBar, 500f, 1200f, 0f, 1100f, topInset = 200f)
        assertEquals(450f, bottomOf(liftedBelow, belowBar), eps)
        // Double tap back to fit honours the floor too.
        val zoomed = ZoomState(2f, -250f, -1000f)
        assertEquals(250f, bottomOf(toggleZoom(zoomed, 1f, 1f, tallFit, 500f, 1000f, 4f, bottomInset = 900f), tallFit), eps)
    }

    @Test
    fun anEmptyFitClampsToIdentity() {
        assertEquals(ZoomState.FIT, clampZoom(ZoomState(2f, 37f, -12f), FitRect.EMPTY, 500f, 1000f, 300f, 100f))
        assertEquals(ZoomState.FIT, liftForKeyboard(ZoomState(2f, 5f, 5f), FitRect.EMPTY, 500f, 1000f, 0f, 300f))
    }
}
