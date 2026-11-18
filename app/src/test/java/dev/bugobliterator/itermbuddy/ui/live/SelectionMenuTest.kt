package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.CopyOutcome
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.stream.copyOutcome
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionMenuTest {
    /** A 1080×2000 canvas under a 150 px top bar, above a 100 px navigation bar. */
    private val bounds = Rect4(0, 150, 1080, 1900)
    private val menu = IntSize(600, 120)

    @Test
    fun menuSitsCentredAboveThePress() {
        assertEquals(IntOffset(240, 1000 - 60 - 120), menuPosition(Offset(540f, 1000f), menu, bounds, gap = 60))
    }

    @Test
    fun menuGoesBelowWithNoRoomAbove() {
        assertEquals(IntOffset(240, 260), menuPosition(Offset(540f, 200f), menu, bounds, gap = 60))
    }

    @Test
    fun menuStaysInsideTheBounds() {
        assertEquals("left edge", 0, menuPosition(Offset(10f, 1000f), menu, bounds, gap = 60).x)
        assertEquals("right edge", 480, menuPosition(Offset(1070f, 1000f), menu, bounds, gap = 60).x)
        assertEquals("below, kept above the bottom", 280, menuPosition(Offset(540f, 250f), menu, Rect4(0, 150, 1080, 400), gap = 60).y)
        assertEquals("wider than the bounds: from the left", 0, menuPosition(Offset(540f, 1000f), IntSize(2000, 120), bounds, gap = 60).x)
    }

    @Test
    fun copyOutcomes() {
        assertEquals(CopyOutcome.COPIED, copyOutcome("SENTINEL"))
        assertEquals(CopyOutcome.NOTHING_SELECTED, copyOutcome(""))
        assertEquals(CopyOutcome.FAILED, copyOutcome(null))
        assertNull("Android confirms a copy itself", copyMessage(CopyOutcome.COPIED))
        assertEquals("Nothing selected", copyMessage(CopyOutcome.NOTHING_SELECTED))
    }

    @Test
    fun menuShowsOnlyAfterABegin() = runTest {
        val link = ScriptedStreamLink()
        val active = MutableStateFlow<ActiveStream?>(null)
        val press = SelectionPress(SelectionDriver(link, active, MutableStateFlow(null), backgroundScope))
        // No stream: the begin is not sent, so neither the end nor the menu.
        assertFalse(press.begin(SelectionPoint(1, 1)))
        press.move(SelectionPoint(2, 2))
        assertFalse(press.end(SelectionPoint(2, 2)))
        // Nothing mapped at the begin.
        active.value = ActiveStream(1, 4, "guid-one", 1, null, 0)
        assertFalse(press.begin(null))
        assertFalse(press.end(SelectionPoint(2, 2)))
        runCurrent()
        assertTrue(link.sent.isEmpty())
        // A real one: the unmappable lift ends at the last point.
        assertTrue(press.begin(SelectionPoint(1, 1)))
        press.move(SelectionPoint(3, 3))
        press.move(null)
        assertTrue(press.end(null))
        runCurrent()
        val gestures = link.sent.map { it.message as PhoneMessage.SelectionGesture }
        assertEquals(listOf(SelectionPhase.BEGIN, SelectionPhase.MOVE, SelectionPhase.END), gestures.map { it.phase })
        assertEquals(SelectionPoint(3, 3), gestures.last().point)
    }
}
