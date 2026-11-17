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
        // Letterboxed axis (portrait): content narrower than the view stays centred.
        val portraitFit = fitRect(1280, 800, 1080f, 2000f)
        val portraitMax = maxScale(1280, portraitFit)
        val z = applyGesture(ZoomState(1f, 0f, 0f), 540f, 1000f, 2f, 0f, 5_000f, portraitFit, 1080f, 2000f, portraitMax)
        // 675 × 2 = 1350 < 2000: centred vertically whatever the pan.
        val top = z.offsetY + z.scale * portraitFit.top
        assertEquals((2000f - 1350f) / 2, top, eps)
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
}
