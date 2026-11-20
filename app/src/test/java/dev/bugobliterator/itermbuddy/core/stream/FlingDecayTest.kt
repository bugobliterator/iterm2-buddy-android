package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The app's one fling decay: Android's spline fling, stepped in virtual time. */
class FlingDecayTest {
    @Test
    fun matchesAndroidsSplineFling() {
        // Compose's FlingCalculator at density 1: 3000 px/s coasts 1309 px over 1246 ms.
        val curve = FlingCurve(3000f, 1f)
        assertEquals(1246L, curve.durationMillis)
        assertEquals(1308.9f, curve.distancePx, 0.5f)
        // At density 2.75 (a 440 dpi phone) 5000 px/s coasts about 1509 px in 862 ms.
        val phone = FlingCurve(5000f, 2.75f)
        assertEquals(862L, phone.durationMillis)
        assertEquals(1508.8f, phone.distancePx, 0.5f)
        // Signed as the velocity.
        assertEquals(-1308.9f, FlingCurve(-3000f, 1f).distancePx, 0.5f)
    }

    @Test
    fun travelRisesSmoothlyAndSpeedFallsToZero() {
        val curve = FlingCurve(4000f, 2f)
        var last = 0f
        var lastSpeed = Float.MAX_VALUE
        for (t in 0..curve.durationMillis step 8) {
            val at = curve.distanceAt(t)
            val speed = curve.velocityAt(t)
            assertTrue("monotonic at $t", at >= last)
            assertTrue("decelerating at $t: $speed after $lastSpeed", speed <= lastSpeed + 1e-3f)
            last = at
            lastSpeed = speed
        }
        // It starts near the release velocity and ends at rest, exactly at its distance.
        assertEquals(4000f, curve.velocityAt(0), 4000f * 0.15f)
        assertEquals(0f, curve.velocityAt(curve.durationMillis))
        assertEquals(curve.distancePx, curve.distanceAt(curve.durationMillis))
        assertEquals(curve.distancePx, curve.distanceAt(curve.durationMillis + 5_000))
        assertTrue(curve.isFinished(curve.durationMillis))
        assertFalse(curve.isFinished(curve.durationMillis - 1))
        // Most of the travel happens early: half the distance in well under half the time.
        assertTrue(curve.distanceAt(curve.durationMillis / 4) > curve.distancePx / 2)
    }

    @Test
    fun aFasterReleaseGoesFurtherAndLonger() {
        val slow = FlingCurve(1000f, 2f)
        val fast = FlingCurve(8000f, 2f)
        assertTrue(fast.distancePx > 4 * slow.distancePx)
        assertTrue(fast.durationMillis > slow.durationMillis)
        // A denser screen (more px per dp) coasts fewer px for the same px/s: the same physical feel.
        assertTrue(FlingCurve(3000f, 3f).distancePx < FlingCurve(3000f, 1f).distancePx)
    }

    @Test
    fun nothingCoastsFromNothingOrBelowTheMinimum() {
        assertEquals(0f, FlingCurve(0f, 2f).distancePx)
        assertEquals(0L, FlingCurve(Float.NaN, 2f).durationMillis)
        assertEquals(0f, FlingCurve(Float.POSITIVE_INFINITY, 2f).distanceAt(100))
        assertNull(FlingCurve.orNull(MIN_FLING_DP_PER_SEC * 2f - 1f, 2f))
        assertNotNull(FlingCurve.orNull(MIN_FLING_DP_PER_SEC * 2f + 50f, 2f))
        assertNull(FlingCurve.orNull(Float.NaN, 2f))
        // A broken density falls back to 1 rather than dividing by it.
        assertEquals(FlingCurve(3000f, 1f).distancePx, FlingCurve(3000f, Float.NaN).distancePx)
    }

    @Test
    fun stepsAddUpToTheCurveWhateverTheFrameRate() {
        for (frame in listOf(7L, 16L, 40L)) {
            val stepper = FlingStepper(FlingCurve(-6000f, 2.5f))
            stepper.start(1_000)
            var t = 1_000L
            var sum = 0f
            var lastStep = Float.MAX_VALUE
            while (!stepper.isFinished) {
                t += frame
                val step = abs(stepper.advance(t))
                // Each frame moves no more than the one before: the tail thins out smoothly.
                assertTrue("frame $frame at $t: $step after $lastStep", step <= lastStep + 0.01f)
                lastStep = step
                sum -= step
            }
            assertEquals("frame $frame", stepper.curve.distancePx, sum, 0.5f)
            assertEquals(0f, stepper.advance(t + 1_000), 0f)
        }
    }

    @Test
    fun aStalledFrameResumesTheCoastRatherThanJumping() {
        val curve = FlingCurve(3000f, 1f)
        val stepper = FlingStepper(curve, maxStepMillis = 100)
        stepper.start(0)
        val step = stepper.advance(5_000)
        assertEquals(curve.distanceAt(100), step, 0.01f)
        assertFalse(stepper.isFinished)
        assertTrue(stepper.velocity > 0f)
    }
}
