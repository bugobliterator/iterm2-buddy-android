package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [detectPresses] under real Compose pointer dispatch, on a device: cancels, holds, pans and
 * double taps. Virtual time: the rule's main clock, advanced by hand.
 */
@RunWith(AndroidJUnit4::class)
class PressGestureDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = CopyOnWriteArrayList<PressAction>()

    private fun setUp() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag(TAG)
                    .pointerInput(Unit) { detectPresses(SELECTION_HOLD_MILLIS, selectable = { true }) { actions += it } },
            )
        }
        rule.waitForIdle()
    }

    /** Lets the double-tap window and any hold run out. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    @Test
    fun downThenCancelGivesNoTap() {
        setUp()
        rule.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(50)
            cancel()
        }
        settle()
        assertEquals(emptyList<PressAction>(), actions.toList())
    }

    @Test
    fun holdDragAndLiftSelects() {
        setUp()
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(SELECTION_HOLD_MILLIS + 100)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG).performTouchInput {
            moveBy(Offset(60f, 40f))
            up()
        }
        settle()
        assertTrue(actions.first() is PressAction.SelectBegin)
        assertTrue(actions.any { it is PressAction.SelectMove })
        assertTrue(actions.last() is PressAction.SelectEnd)
        assertTrue(actions.none { it is PressAction.Tap || it is PressAction.DoubleTap })
    }

    @Test
    fun aPanNeverSelects() {
        setUp()
        rule.onNodeWithTag(TAG).performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
            advanceEventTime(SELECTION_HOLD_MILLIS + 200)
            moveBy(Offset(40f, 0f))
            up()
        }
        settle()
        assertEquals(emptyList<PressAction>(), actions.toList())
    }

    @Test
    fun aDoubleTapIsADoubleTap() {
        setUp()
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        settle()
        assertEquals(1, actions.size)
        assertTrue(actions.single() is PressAction.DoubleTap)
    }

    private companion object {
        const val TAG = "press-surface"
    }
}
