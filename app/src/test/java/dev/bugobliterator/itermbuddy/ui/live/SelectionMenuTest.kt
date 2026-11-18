package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
