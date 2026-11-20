package dev.bugobliterator.itermbuddy.core.stream

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

/**
 * Below this release speed (dp/s) nothing coasts: Android's `ViewConfiguration` minimum fling
 * velocity (`config_viewMinFlingVelocity`, 50 dp/s).
 */
const val MIN_FLING_DP_PER_SEC = 50f

/**
 * The one fling decay of the app's scrolling: Android's spline fling, the curve `OverScroller`,
 * `RecyclerView` and Compose's `splineBasedDecay` (the default for `LazyColumn` and
 * `Modifier.scrollable`) coast with, so a flick feels like any Android list. A pure port of
 * Compose's `FlingCalculator` and `AndroidFlingSpline` (Android's scroll friction 0.015, the
 * 0.84 physical coefficient, gravity at the screen's density), in px of the screen it was
 * measured on, so it is tested on the JVM and shared by the scrollback coast, the mouse-wheel
 * coast and the browser page's fling.
 *
 * Built from a release velocity [velocityPxPerSec] (signed; screen px/s) on a screen of
 * [densityPxPerDp] px per dp, it coasts [distancePx] (signed as the velocity) over
 * [durationMillis], fast at first and slowing smoothly to a stop: [distanceAt] is the travel
 * after a time, [velocityAt] the speed then. Non-finite or zero input coasts nowhere.
 * Holds no state beyond the curve's two numbers; thread-safe.
 */
class FlingCurve(velocityPxPerSec: Float, densityPxPerDp: Float) {
    private val direction = if (velocityPxPerSec.isFinite()) sign(velocityPxPerSec) else 0f

    /** The whole coast, signed as the velocity; 0 when it does not move. */
    val distancePx: Float

    /** How long the coast lasts; 0 when it does not move. */
    val durationMillis: Long

    init {
        val speed = if (velocityPxPerSec.isFinite()) abs(velocityPxPerSec) else 0f
        val density = if (densityPxPerDp.isFinite() && densityPxPerDp > 0f) densityPxPerDp else 1f
        val physical = GRAVITY_EARTH * INCHES_PER_METER * density * 160f * PHYSICAL_TUNING
        if (speed <= 0f) {
            distancePx = 0f
            durationMillis = 0L
        } else {
            val l = ln(INFLECTION * speed / (SCROLL_FRICTION * physical).toDouble())
            val decelMinusOne = DECELERATION_RATE - 1.0
            durationMillis = (1000.0 * exp(l / decelMinusOne)).toLong()
            distancePx = direction * (SCROLL_FRICTION * physical * exp(DECELERATION_RATE / decelMinusOne * l)).toFloat()
        }
    }

    /** Whether the coast is over at [millis] after the release. */
    fun isFinished(millis: Long): Boolean = millis >= durationMillis

    /** The travel [millis] after the release, signed; [distancePx] once it is over. */
    fun distanceAt(millis: Long): Float {
        if (durationMillis <= 0L) return 0f
        if (millis <= 0L) return 0f
        if (millis >= durationMillis) return distancePx
        return distancePx * splineAt(millis.toFloat() / durationMillis).first
    }

    /** The speed [millis] after the release, px/s signed as the velocity; 0 once it is over. */
    fun velocityAt(millis: Long): Float {
        if (durationMillis <= 0L || millis >= durationMillis) return 0f
        val t = maxOf(0L, millis).toFloat() / durationMillis
        return splineAt(t).second * distancePx / durationMillis * 1000f
    }

    companion object {
        /** A curve for [velocityPxPerSec], or null when it is below [MIN_FLING_DP_PER_SEC] and nothing should coast. */
        fun orNull(velocityPxPerSec: Float, densityPxPerDp: Float): FlingCurve? {
            if (!velocityPxPerSec.isFinite()) return null
            val density = if (densityPxPerDp.isFinite() && densityPxPerDp > 0f) densityPxPerDp else 1f
            if (abs(velocityPxPerSec) < MIN_FLING_DP_PER_SEC * density) return null
            return FlingCurve(velocityPxPerSec, density).takeIf { it.durationMillis > 0L && it.distancePx != 0f }
        }

        private const val SCROLL_FRICTION = 0.015f
        private const val PHYSICAL_TUNING = 0.84f
        private const val GRAVITY_EARTH = 9.80665f
        private const val INCHES_PER_METER = 39.37f
        private const val INFLECTION = 0.35f
        private val DECELERATION_RATE = ln(0.78) / ln(0.9)

        private const val SAMPLES = 100
        private const val START_TENSION = 0.5f
        private const val END_TENSION = 1.0f
        private const val P1 = START_TENSION * INFLECTION
        private const val P2 = 1.0f - END_TENSION * (1.0f - INFLECTION)

        /** The spline's distance at each of [SAMPLES] even steps of time (and 1 at the end), as `OverScroller` builds it. */
        private val POSITIONS: FloatArray = FloatArray(SAMPLES + 1).also { positions ->
            var xMin = 0f
            for (i in 0 until SAMPLES) {
                val alpha = i.toFloat() / SAMPLES
                var xMax = 1f
                var x: Float
                var coef: Float
                while (true) {
                    x = xMin + (xMax - xMin) / 2f
                    coef = 3f * x * (1f - x)
                    val tx = coef * ((1f - x) * P1 + x * P2) + x * x * x
                    if (abs(tx - alpha) < 1e-5f) break
                    if (tx > alpha) xMax = x else xMin = x
                }
                positions[i] = coef * ((1f - x) * START_TENSION + x) + x * x * x
            }
            positions[SAMPLES] = 1f
        }

        /** (distance fraction, velocity coefficient) at time fraction [t] (0…1). */
        private fun splineAt(t: Float): Pair<Float, Float> {
            val index = (SAMPLES * t).toInt()
            if (index >= SAMPLES) return 1f to 0f
            val tInf = index.toFloat() / SAMPLES
            val tSup = (index + 1).toFloat() / SAMPLES
            val dInf = POSITIONS[index]
            val dSup = POSITIONS[index + 1]
            val velocity = (dSup - dInf) / (tSup - tInf)
            return (dInf + (t - tInf) * velocity) to velocity
        }
    }
}

/**
 * A fling over a [FlingCurve] stepped frame by frame (or tick by tick): each [advance] hands
 * over the travel since the last one, so the steps always add up to the curve's distance. Each
 * step counts at most [maxStepMillis] of time, so a stalled frame or tick resumes the coast
 * where it was instead of jumping. Not thread-safe.
 */
class FlingStepper(val curve: FlingCurve, private val maxStepMillis: Long = 100L) {
    private var elapsed = 0L
    private var travelled = 0f
    private var lastMillis: Long? = null

    /** Whether the whole curve has been handed over. */
    val isFinished: Boolean get() = curve.isFinished(elapsed)

    /** The speed now, px/s signed as the curve. */
    val velocity: Float get() = curve.velocityAt(elapsed)

    /** Starts the clock at [nowMillis] (the release); the first [advance] counts from there. */
    fun start(nowMillis: Long) {
        lastMillis = nowMillis
    }

    /** The travel from the last step to [nowMillis] (from [start], or 0 the first time without one), signed. */
    fun advance(nowMillis: Long): Float {
        val last = lastMillis ?: nowMillis
        lastMillis = nowMillis
        elapsed += (nowMillis - last).coerceIn(0L, maxStepMillis)
        val at = curve.distanceAt(elapsed)
        val step = at - travelled
        travelled = at
        return step
    }
}
