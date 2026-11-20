package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.isInputTarget
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeStreamConfig
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds

/**
 * A one-finger drag on a browser page, from the canvas's per-event decisions to the wire, as
 * [VideoCanvas] wires them (the scroll point fixed at the drag's start, [browserGestureStep] per
 * event, the density at fit over the zoom as shown, [BrowserDriver.scroll], its 40 ms ticker,
 * the [InputSender] queue), against a [ScriptedStreamLink], in virtual time.
 *
 * It also works out what the Mac makes of each `browserScroll` (iTerm2 3.7.7,
 * `CompanionHostBridge.handleBrowserScroll` and `CompanionBrowserInput.scroll`): the deltas
 * divided by the frame scale (encoded pixels per web view point), each rounded to whole points
 * (Swift `rounded()`, half away from zero), a step of 0 dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserDragPipelineTest {
    /** The owner's case: a 2000×1000 page (a 2× Mac's 1000×500 pt pane) under a 100 px bar on a 1000×700 canvas. */
    private val fit = visibleFitRect(IMAGE_W, IMAGE_H, 1000f, 700f, topInset = 100f, bottomInset = 0f)

    private inner class Canvas(val test: TestScope, fillPage: Boolean) {
        val link = ScriptedStreamLink()
        val active = MutableStateFlow<ActiveStream?>(browserStream())
        val input = InputSender(link, test.backgroundScope, isCurrent = { isInputTarget(active.value, it) }, log = {})
        val driver = BrowserDriver(
            input,
            active,
            MutableStateFlow(false),
            MutableStateFlow<BrowserNavigationState?>(null),
            macRevision = MutableStateFlow(15),
            scope = test.backgroundScope,
            nowMillis = { test.testScheduler.currentTime },
        )
        val base = if (fillPage) browserFillZoom(fit, 1000f, 700f, 100f, 0f) else null
        val geometry = GestureGeometry(fit, 1000f, 700f, maxOf(maxScale(IMAGE_W, fit), base?.scale ?: 1f), 100f, 0f, 0f, 0f, 0f, base)
        var kept = KeptZoom(ZoomState.FIT, null, atBase = fillPage)

        /** A drag from ([x], [y]) by ([dx], [dy]) view px over [millis], one event every 16 ms, as VideoCanvas handles it. */
        fun drag(x: Float, y: Float, dx: Float, dy: Float, millis: Long) {
            val at = browserScrollPoint(x, y, shownZoom(kept, geometry, 0f), fit, IMAGE_W, IMAGE_H)!!
            val events = (millis / EVENT_MILLIS).toInt()
            repeat(events) {
                val step = browserGestureStep(kept, geometry, 0f, x, y, dx / events, dy / events, 1f, 1, false)
                kept = step.kept
                val density = browserImagePxPerViewPx(IMAGE_W, geometry.fit)!!
                if (step.scrollX != 0f || step.scrollY != 0f) {
                    driver.scroll(step.scrollX, step.scrollY, density, shownZoom(step.kept, geometry, 0f).scale, at.first, at.second)
                }
                test.advanceTimeBy(EVENT_MILLIS.milliseconds)
            }
            test.advanceTimeBy(200.milliseconds)
            test.runCurrent()
        }

        fun scrolls(): List<PhoneMessage.BrowserScroll> = link.sent.map { it.message }.filterIsInstance<PhoneMessage.BrowserScroll>()
    }

    @Test
    fun aDragAtFitSendsTheWholeTravelAsBrowserScrolls() = runTest {
        val canvas = Canvas(this, fillPage = false)
        // The finger moves up 300 view px in 300 ms: the content follows it up, the page scrolls down.
        canvas.drag(500f, 300f, 0f, -300f, 300)
        val scrolls = canvas.scrolls()
        assertTrue("one step per 40 ms flush, got ${scrolls.size}", scrolls.size in 7..9)
        // Two image px per view px at fit: 600 image px (less a fraction of float travel still held),
        // all negative (content up), none sideways.
        assertEquals(-600.0, scrolls.sumOf { it.deltaY }, 1.0)
        assertTrue(scrolls.all { it.deltaY < 0 && it.deltaX == 0.0 })
        // At the page pixel under the finger's start: (500, 200) view → (1000, 400) image px.
        assertTrue(scrolls.all { it.x == 1000.0 && it.y == 400.0 })
        // What a 2× Mac scrolls by: every step reaches the web view, 300 points in all.
        assertTrue(scrolls.sumOf { macPoints(it.deltaY, 2.0) } in -301..-299)
        assertTrue(scrolls.all { macPoints(it.deltaY, 2.0) != 0 })
    }

    @Test
    fun aDragAtTheFillSendsItsVerticalTravelAndPansSideways() = runTest {
        val canvas = Canvas(this, fillPage = true)
        canvas.drag(500f, 400f, 120f, -240f, 320)
        val scrolls = canvas.scrolls()
        // 240 view px at 2 / 1.2 image px per view px: 400 image px up; the 120 px across only pans.
        assertEquals(-400.0, scrolls.sumOf { it.deltaY }, 1.0)
        assertTrue(scrolls.all { it.deltaX == 0.0 })
        assertEquals(0f, canvas.kept.state.offsetX, 1e-3f)
        assertTrue(canvas.kept.atBase)
    }

    @Test
    fun aSlowDragStillReachesTheMac() = runTest {
        // 20 view px per second for 2 s: 1.6 image px per flush, sent as steps of 1 and 2.
        val canvas = Canvas(this, fillPage = false)
        canvas.drag(500f, 300f, 0f, -40f, 2000)
        val scrolls = canvas.scrolls()
        assertEquals(-80.0, scrolls.sumOf { it.deltaY }, 1.0)
        assertTrue(scrolls.all { abs(it.deltaY) in 1.0..2.0 })
        // On a 2× Mac a 1 px step is half a point and rounds away from zero to a whole point, so
        // nothing is lost: a slow drag scrolls a little further than the finger, never not at all.
        val points = scrolls.map { macPoints(it.deltaY, 2.0) }
        assertTrue(points.none { it == 0 })
        assertTrue("${points.sum()} points", points.sum() <= -40)
    }

    private companion object {
        const val IMAGE_W = 2000
        const val IMAGE_H = 1000
        const val EVENT_MILLIS = 16L

        /** The points iTerm2 3.7.7 scrolls a web view by for one step of [delta] encoded px at [frameScale]. */
        fun macPoints(delta: Double, frameScale: Double): Int {
            val points = delta / frameScale
            return (sign(points) * floor(abs(points) + 0.5)).toInt()
        }

        fun browserStream(): ActiveStream {
            val config = (decodeStreamConfig(FakeMac.streamConfigPayload(1, emptyMap()).getValue("streamConfig")) as MacMessage.StreamConfigMessage).config
            return ActiveStream(1, 1, "guid-browser", 1, config.copy(streamID = 1, isBrowser = true, pixelWidth = IMAGE_W, pixelHeight = IMAGE_H), null)
        }
    }
}
