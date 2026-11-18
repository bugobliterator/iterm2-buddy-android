package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.ScrollWheelAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
