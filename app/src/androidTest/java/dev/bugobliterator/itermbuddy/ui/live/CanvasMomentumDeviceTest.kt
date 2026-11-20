package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.WHEEL_COAST_NOTCHES_PER_FRAME
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import dev.bugobliterator.itermbuddy.video.MediaCodecCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The terminal canvas's momentum under real Compose pointer dispatch, on a device: a flick over
 * the scrollback coasts on after the finger lifts and stops at the history's top, a touch stops
 * it, and in wheel mode a flick keeps sending notches that thin out. A real [HevcVideoOutput]
 * that never gets a config (no picture). Virtual time: the rule's main clock, advanced by hand.
 */
@RunWith(AndroidJUnit4::class)
class CanvasMomentumDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val output = HevcVideoOutput(MediaCodecCatalog())
    private val source = LiveVideoSource(output, MutableStateFlow<LiveViewState>(LiveViewState.Closed))
    private val views = CopyOnWriteArrayList<DocumentView>()

    /** Each wheel send: (main clock ms, up, lines). */
    private val wheel = CopyOnWriteArrayList<Triple<Long, Boolean, Int>>()
    private val keyTyped = mutableIntStateOf(0)

    @Volatile private var canvas = IntSize.Zero

    @After
    fun tearDown() {
        output.close()
    }

    /** A 1000×500 terminal on a 360×640 dp box, with [history] px of scrollback, or in wheel mode. */
    private fun show(history: Float, wheelStep: Float? = null) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp).testTag(TAG)) {
                VideoCanvas(
                    source = source,
                    imageWidth = 1000,
                    imageHeight = 500,
                    sessionGuid = "guid-terminal",
                    onCanvasSize = { canvas = it },
                    historyHeightPx = history,
                    onDocumentView = { views += it },
                    wheelStepPx = wheelStep,
                    onWheel = { up, lines -> wheel += Triple(rule.mainClock.currentTime, up, lines) },
                    stopCoast = { keyTyped.intValue },
                )
            }
        }
        settle(100)
        assertTrue("canvas laid out", canvas.width > 0 && canvas.height > 0)
    }

    private fun settle(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    /** A quick vertical flick from [fromY] to [toY] (fractions of the height) in [millis]. */
    private fun flick(fromY: Float, toY: Float, millis: Long = 80) {
        rule.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(width / 2f, height * fromY), Offset(width / 2f, height * toY), millis)
        }
        rule.waitForIdle()
    }

    @Test
    fun aFlickOverTheScrollbackCoastsOnAfterTheLift() {
        show(history = 40_000f)
        val start = views.last()
        assertTrue("opens at the live bottom", start.atLiveBottom)
        flick(0.3f, 0.6f)
        val atLift = views.last()
        settle(3_000)
        val rest = views.last()
        val dragged = start.topPx - atLift.topPx
        val coasted = atLift.topPx - rest.topPx
        // The finger went 30 % of the height; the coast carries on well past that, up the history.
        assertTrue("dragged $dragged px, then coasted $coasted px", dragged > 0f && coasted > dragged)
        settle(1_000)
        assertEquals("at rest", rest.topPx, views.last().topPx, 0.01f)
    }

    @Test
    fun aTouchStopsTheCoast() {
        show(history = 40_000f)
        flick(0.3f, 0.7f)
        settle(48)
        val atTouch = views.last().topPx
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        settle(1_000)
        assertEquals("held where the finger caught it", atTouch, views.last().topPx, 1f)
        rule.onNodeWithTag(TAG).performTouchInput { up() }
    }

    @Test
    fun aKeyTypedStopsTheCoast() {
        show(history = 40_000f)
        flick(0.3f, 0.7f)
        settle(48)
        val atKey = views.last().topPx
        keyTyped.intValue++
        settle(1_000)
        assertEquals(atKey, views.last().topPx, 1f)
    }

    @Test
    fun aHardFlickStopsAtTheTopWithNoBounce() {
        show(history = 1_500f)
        flick(0.2f, 0.8f, millis = 60)
        settle(3_000)
        val top = views.minOf { it.topPx }
        // The document's top meets the canvas's (no top inset here) and never goes past it.
        assertEquals(0f, views.last().topPx, 0.5f)
        assertTrue("never past the top: $top", top >= -0.5f)
    }

    @Test
    fun inWheelModeAFlickKeepsSendingNotchesThatThinOut() {
        show(history = 0f, wheelStep = 20f)
        flick(0.7f, 0.4f)
        val atLift = wheel.sumOf { it.third }
        val lift = rule.mainClock.currentTime
        settle(3_000)
        val coast = wheel.filter { it.first > lift }
        assertTrue("the drag sent notches", atLift > 0)
        assertTrue("then the coast sent more: $coast", coast.sumOf { it.third } > 0)
        // Finger up: newer content, down notches; never more than the per-frame cap at once.
        assertTrue(wheel.all { !it.second })
        assertTrue(coast.all { it.third in 1..WHEEL_COAST_NOTCHES_PER_FRAME })
        // Busier at first than at the end.
        val half = (coast.first().first + coast.last().first) / 2
        assertTrue("$coast", coast.filter { it.first <= half }.sumOf { it.third } > coast.filter { it.first > half }.sumOf { it.third })
        val total = wheel.sumOf { it.third }
        settle(1_000)
        assertEquals("it stops", total, wheel.sumOf { it.third })
    }

    @Test
    fun inWheelModeATouchStopsTheNotches() {
        show(history = 0f, wheelStep = 20f)
        flick(0.7f, 0.3f)
        settle(48)
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        val atTouch = wheel.sumOf { it.third }
        settle(1_000)
        assertEquals(atTouch, wheel.sumOf { it.third })
        rule.onNodeWithTag(TAG).performTouchInput { up() }
    }

    private companion object {
        const val TAG = "terminal-canvas"
    }
}
