package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollWheelAccumulatorTest {
    @Test
    fun fingerDownIsUp() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 24f)
        assertEquals("finger moving down reveals older content", listOf(true), wheel.drag(30f))
        wheel.reset()
        assertEquals("finger moving up reveals newer content", listOf(false), wheel.drag(-30f))
    }

    @Test
    fun partialNotchesAccumulate() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 24f)
        assertEquals(emptyList<Boolean>(), wheel.drag(10f))
        assertEquals(emptyList<Boolean>(), wheel.drag(10f))
        assertEquals(listOf(true), wheel.drag(10f))
    }

    @Test
    fun directionChangeResetsRemainder() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 24f)
        assertEquals(emptyList<Boolean>(), wheel.drag(20f))
        // The 20 px down are dropped, not netted against: -10 then -14 is one notch down.
        assertEquals(emptyList<Boolean>(), wheel.drag(-10f))
        assertEquals(listOf(false), wheel.drag(-14f))
    }

    @Test
    fun longDragGivesOneEntryPerNotchAndKeepsTheRest() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 24f)
        assertEquals(listOf(true, true, true), wheel.drag(75f))
        // 3 px carried: 21 more make the fourth notch.
        assertEquals(listOf(true), wheel.drag(21f))
    }

    @Test
    fun resetDropsTheRemainder() {
        val wheel = ScrollWheelAccumulator(pointsPerLine = 24f)
        wheel.drag(20f)
        wheel.reset()
        assertEquals(emptyList<Boolean>(), wheel.drag(20f))
    }

    @Test
    fun unusableLineHeightFallsBack() {
        assertEquals(24f, wheelStepPx(lineHeightPx = 0f, fallbackPx = 24f))
        assertEquals(24f, wheelStepPx(lineHeightPx = Float.NaN, fallbackPx = 24f))
        assertEquals(16.875f, wheelStepPx(lineHeightPx = 16.875f, fallbackPx = 24f))
        // Nonsense never divides by zero or loops: no notches.
        assertEquals(emptyList<Boolean>(), ScrollWheelAccumulator(0f).drag(100f))
        assertEquals(emptyList<Boolean>(), ScrollWheelAccumulator(24f).drag(Float.NaN))
    }
}
