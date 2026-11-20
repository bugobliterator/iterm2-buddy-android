package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.TappedLink
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import dev.bugobliterator.itermbuddy.video.MediaCodecCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The link bubble under real Compose pointer dispatch, on a device: a single tap on the terminal
 * canvas asks for the link under it (a fake answer standing in for the Mac's smart selection) and
 * the bubble shows by the tap with the link and Open and Copy; Copy and a tap elsewhere hide it; a
 * double tap and a tap that stops a coast ask nothing. Virtual time: the rule's main clock.
 */
@RunWith(AndroidJUnit4::class)
class LinkBubbleDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val output = HevcVideoOutput(MediaCodecCatalog())
    private val source = LiveVideoSource(output, MutableStateFlow<LiveViewState>(LiveViewState.Closed))

    /** Each lookup asked for (the screen y of its tap). */
    private val asked = CopyOnWriteArrayList<Float>()
    private val opened = CopyOnWriteArrayList<String>()
    private val copied = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        output.close()
    }

    /** A terminal canvas with scrollback whose every tap finds [answer] after [answerMillis]. */
    private fun show(answer: TappedLink?, answerMillis: Long = 120) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scope = rememberCoroutineScope()
            val taps = remember {
                LinkTaps(scope) {
                    delay(answerMillis)
                    answer
                }
            }
            val bubble by taps.bubble.collectAsState()
            Box(Modifier.size(360.dp, 640.dp).testTag(TAG)) {
                VideoCanvas(
                    source = source,
                    imageWidth = 1000,
                    imageHeight = 500,
                    sessionGuid = "guid-terminal",
                    onCanvasSize = {},
                    historyHeightPx = 40_000f,
                    onTapAt = { x, y, _, _ ->
                        if (!taps.dismiss()) {
                            asked += y
                            taps.tap(SelectionPoint(0, 0), x, y)
                        }
                    },
                    onPanStart = { taps.dismiss() },
                )
                bubble?.let { shown ->
                    LinkBubble(
                        anchor = Offset(shown.x, shown.y),
                        bounds = { Rect4(0, 0, 1000, 2000) },
                        link = shown.link,
                        browserSession = null,
                        onOpen = {
                            taps.dismiss()
                            opened += shown.link.url
                        },
                        onCopy = {
                            taps.dismiss()
                            copied += shown.link.url
                        },
                    )
                }
            }
        }
        settle(100)
    }

    private fun settle(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    @Test
    fun aTapOnALinkShowsTheBubbleAndOpenOpensIt() {
        show(TappedLink("https://example.com/path", macLocal = false))
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        // Past the double-tap window and the answer.
        settle(1_000)
        assertEquals(1, asked.size)
        rule.onNodeWithText("https://example.com/path").assertIsDisplayed()
        rule.onNodeWithText("Open link").assertIsDisplayed().performClick()
        settle(100)
        assertEquals(listOf("https://example.com/path"), opened.toList())
        assertEquals(0, count("Open link"))
    }

    @Test
    fun copyAndATapElsewhereHideIt() {
        show(TappedLink("http://localhost:3000/", macLocal = true))
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        settle(1_000)
        rule.onNodeWithText("Mac-only link — no Browser session open").assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        settle(100)
        assertEquals(listOf("http://localhost:3000/"), copied.toList())
        assertEquals(0, count("Copy"))
        // Again, then a tap elsewhere only hides it (it looks nothing up).
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        settle(1_000)
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(width / 4f, height / 4f)) }
        settle(1_000)
        assertEquals(0, count("Copy"))
        assertEquals(2, asked.size)
    }

    @Test
    fun noLinkNoBubble() {
        show(answer = null)
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        settle(1_000)
        assertEquals(1, asked.size)
        assertEquals(0, count("Copy"))
    }

    @Test
    fun aDoubleTapAndATapThatStopsACoastAskNothing() {
        show(TappedLink("https://example.com/path", macLocal = false))
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        settle(1_000)
        assertTrue("a double tap zooms only: $asked", asked.isEmpty())
        rule.onNodeWithTag(TAG).performTouchInput { swipe(Offset(width / 2f, height * 0.3f), Offset(width / 2f, height * 0.7f), 80) }
        rule.waitForIdle()
        settle(48)
        rule.onNodeWithTag(TAG).performTouchInput { click(center) }
        settle(1_000)
        assertTrue("the tap only stopped the coast: $asked", asked.isEmpty())
        assertEquals(0, count("Copy"))
    }

    private fun count(text: String): Int = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val TAG = "terminal-canvas"
    }
}
