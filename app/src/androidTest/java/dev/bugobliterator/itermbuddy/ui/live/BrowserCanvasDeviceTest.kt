package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.input.isInputTarget
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.ui.browser.BrowserSessionScreen
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import dev.bugobliterator.itermbuddy.video.MediaCodecCatalog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The browser page's real touch path on a device: [VideoCanvas] as the browser screen composes it
 * (no double-tap zoom, a [BrowserDriver]), under real Compose pointer dispatch (its pan and press
 * detectors together), with a real [HevcVideoOutput] that never gets a config (no picture), the
 * driver and [InputSender] on a recording link. Swipes at fit and at the fill base, fast and
 * slow, with and without the keyboard's inset, must reach the link as `browserScroll` with the
 * right sign; a tap must still click the right page pixel. The driver's 40 ms ticker runs in
 * virtual time ([scheduler]), advanced by hand once a gesture is over.
 */
@RunWith(AndroidJUnit4::class)
class BrowserCanvasDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler))
    private val link = RecordingLink()
    private val active = MutableStateFlow<ActiveStream?>(ActiveStream(1, STREAM_ID, GUID, 1, browserConfig(), null))
    private val input = InputSender(link, scope, isCurrent = { isInputTarget(active.value, it) }, log = {})
    private val driver = BrowserDriver(
        input,
        active,
        MutableStateFlow(false),
        MutableStateFlow<BrowserNavigationState?>(null),
        macRevision = MutableStateFlow(15),
        scope = scope,
        nowMillis = { scheduler.currentTime },
    )
    private val output = HevcVideoOutput(MediaCodecCatalog())
    private val source = LiveVideoSource(output, MutableStateFlow<LiveViewState>(LiveViewState.Closed))

    @Volatile private var canvas = IntSize.Zero

    @After
    fun tearDown() {
        scope.cancel()
        output.close()
    }

    /** The browser canvas on a 360×640 dp box, filling the page or at fit; [keyboard] is the bottom inset as a fraction of the height. */
    private fun show(fill: Boolean, keyboard: Float = 0f) {
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp).testTag(TAG)) {
                VideoCanvas(
                    source = source,
                    imageWidth = IMAGE_W,
                    imageHeight = IMAGE_H,
                    sessionGuid = GUID,
                    onCanvasSize = { canvas = it },
                    bottomInset = { canvas.height * keyboard },
                    doubleTapZoom = false,
                    browser = driver,
                    fillPage = fill,
                )
            }
        }
        rule.waitForIdle()
        assertTrue("canvas laid out", canvas.width > 0 && canvas.height > 0)
    }

    /** Runs the driver's ticker (and any fling) to the end; the messages sent so far. */
    private fun sent(): List<PhoneMessage> {
        rule.waitForIdle()
        scheduler.advanceUntilIdle()
        return link.sent.toList()
    }

    private fun scrolls() = sent().filterIsInstance<PhoneMessage.BrowserScroll>()

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, millis: Long) {
        rule.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), millis)
        }
    }

    /** Image px per view px at fit: the page is drawn as wide as the canvas. */
    private fun fitDensity(): Float = IMAGE_W.toFloat() / canvas.width

    @Test
    fun atFitASwipeUpScrollsThePageDown() {
        show(fill = false)
        swipe(0.5f, 0.8f, 0.5f, 0.2f, 400)
        val scrolls = scrolls()
        val sum = scrolls.sumOf { it.deltaY }
        // The finger travelled 60 % of the height (less the touch slop); a fling may add more.
        val travel = 0.6 * canvas.height * fitDensity()
        assertTrue("${scrolls.size} scrolls, deltaY sum $sum, travel $travel", sum <= -0.5 * travel)
        assertTrue(scrolls.all { it.deltaY <= 0.0 })
    }

    @Test
    fun atFitASwipeDownScrollsThePageUp() {
        show(fill = false)
        swipe(0.5f, 0.2f, 0.5f, 0.7f, 400)
        val sum = scrolls().sumOf { it.deltaY }
        assertTrue("deltaY sum $sum", sum >= 0.5 * 0.5 * canvas.height * fitDensity())
    }

    @Test
    fun aSlowSwipeStillScrolls() {
        show(fill = false)
        swipe(0.5f, 0.6f, 0.5f, 0.4f, 3_000)
        val scrolls = scrolls()
        val sum = scrolls.sumOf { it.deltaY }
        assertTrue("${scrolls.size} scrolls, deltaY sum $sum", scrolls.size >= 5 && sum <= -0.5 * 0.2 * canvas.height * fitDensity())
    }

    @Test
    fun atTheFillVerticalSwipesScrollAndSideSwipesOnlyPan() {
        show(fill = true)
        swipe(0.5f, 0.8f, 0.5f, 0.2f, 400)
        val up = scrolls()
        assertTrue("deltaY sum ${up.sumOf { it.deltaY }}", up.sumOf { it.deltaY } < 0.0)
        assertTrue("never sideways at the fill", up.all { it.deltaX == 0.0 })
        val before = up.size
        swipe(0.7f, 0.5f, 0.3f, 0.5f, 300)
        assertEquals("a side swipe pans the cropped page and scrolls nothing", before, scrolls().size)
    }

    @Test
    fun withTheKeyboardUpASwipeStillScrolls() {
        show(fill = true, keyboard = 0.4f)
        swipe(0.5f, 0.55f, 0.5f, 0.05f, 400)
        val sum = scrolls().sumOf { it.deltaY }
        assertTrue("deltaY sum $sum", sum < 0.0)
    }

    @Test
    fun aTapStillClicksThePagePixelUnderIt() {
        show(fill = false)
        // At fit the page is the top half of the width; a quarter of the width down is its middle row.
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(width / 2f, width / 4f)) }
        val tap = sent().filterIsInstance<PhoneMessage.BrowserTap>().single()
        assertEquals(1000.0, tap.x, 10.0)
        assertEquals(500.0, tap.y, 10.0)
    }

    @Test
    fun atTheFillATapAtTheCentreClicksThePageCentre() {
        show(fill = true)
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        val tap = sent().filterIsInstance<PhoneMessage.BrowserTap>().single()
        assertEquals(1000.0, tap.x, 10.0)
        assertEquals(500.0, tap.y, 10.0)
        assertTrue("a tap scrolls nothing", sent().none { it is PhoneMessage.BrowserScroll })
    }

    /**
     * The whole browser screen ([BrowserSessionScreen]: the bar, the overlay, the hidden text
     * input and the key bar slot over the canvas), as the app shows a live revision-15 page.
     */
    @Test
    fun onTheWholeBrowserScreenASwipeScrollsAndATapClicks() {
        val session = SessionSummary(GUID, "page", "", isBrowser = true)
        val stream = active.value
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp).testTag(TAG)) {
                BrowserSessionScreen(
                    title = "page",
                    session = session,
                    live = LiveViewState.Live(session, browserConfig()),
                    activeStream = stream,
                    source = source,
                    browser = driver,
                    debugBuild = false,
                    videoStatsShown = false,
                    actions = NoActions,
                )
            }
        }
        rule.waitForIdle()
        swipe(0.5f, 0.8f, 0.5f, 0.3f, 400)
        val scrolls = scrolls()
        assertTrue("${scrolls.size} scrolls, deltaY sum ${scrolls.sumOf { it.deltaY }}", scrolls.sumOf { it.deltaY } < 0.0)
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(width / 2f, height * 0.6f)) }
        assertEquals(1, sent().count { it is PhoneMessage.BrowserTap })
    }

    /** The screen's actions, none of which these gestures use. */
    private object NoActions : LiveSessionActions {
        override fun back() = Unit
        override fun retry() = Unit
        override fun fitToPhone(sessionGuid: String, viewportWidthPx: Int, viewportHeightPx: Int) = Unit
        override fun setVideoStatsShown(shown: Boolean) = Unit
        override fun key(target: InputTarget, event: KeyEvent) = Unit
        override fun paste(target: InputTarget, text: String) = Unit
        override fun typeThenReturn(target: InputTarget, text: String) = Unit
        override fun scrollWheel(target: InputTarget, streamID: Long, up: Boolean, lines: Int) = Unit
        override fun composerDraft(sessionGuid: String): String = ""
        override fun setComposerDraft(sessionGuid: String, draft: String) = Unit
        override fun inputMemory(sessionGuid: String): LiveInputMemory = LiveInputMemory()
    }

    /** Records every message sent; requests are not expected. */
    private class RecordingLink : StreamLink {
        val sent = CopyOnWriteArrayList<PhoneMessage>()

        override suspend fun send(message: PhoneMessage, onLink: Long?) {
            sent += message
        }

        override suspend fun request(message: PhoneMessage, onLink: Long?, timeout: Duration?, onSent: (requestID: Long) -> Unit): MacMessage =
            throw UnsupportedOperationException("no requests in this test")
    }

    private companion object {
        const val TAG = "browser-canvas"
        const val GUID = "guid-browser"
        const val STREAM_ID = 7L
        const val IMAGE_W = 2000
        const val IMAGE_H = 1000

        fun browserConfig() = StreamConfig(
            streamID = STREAM_ID,
            generationId = 1,
            codecExtradata = ByteArray(0),
            pixelWidth = IMAGE_W,
            pixelHeight = IMAGE_H,
            scale = 2.0,
            columns = 0,
            rows = 0,
            cellGeometry = null,
            firstAbsLine = 0,
            totalLines = 0,
            canResize = false,
            altScreen = false,
            scrollWheelReporting = false,
            isBrowser = true,
        )
    }
}
