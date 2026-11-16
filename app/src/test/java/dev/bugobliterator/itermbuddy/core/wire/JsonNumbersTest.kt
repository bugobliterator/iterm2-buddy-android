package dev.bugobliterator.itermbuddy.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonNumbersTest {
    private fun literal(value: Double): String = swiftDouble(value).toString()

    @Test
    fun integralValuesAreIntegerLiterals() {
        assertEquals("30", literal(30.0))
        assertEquals("1", literal(1.0))
        assertEquals("4096", literal(4096.0))
        assertEquals(Json.parseToJsonElement("30"), swiftDouble(30.0))
    }

    @Test
    fun fractionalValuesKeepTheirFraction() {
        assertEquals("15.75", literal(15.75))
        assertEquals("0.5", literal(0.5))
        assertEquals("-2.25", literal(-2.25))
    }

    @Test
    fun negativeIntegralValuesAreIntegerLiterals() {
        assertEquals("-30", literal(-30.0))
        assertEquals("-1", literal(-1.0))
    }

    @Test
    fun integerLiteralsStopBelowTwoToThe53() {
        val limit = 9007199254740992.0
        assertEquals("9007199254740991", literal(limit - 1))
        assertEquals("-9007199254740991", literal(-(limit - 1)))
        // At 2^53 and beyond, Kotlin's own literal: the same value, not integer text.
        assertEquals(JsonPrimitive(limit), swiftDouble(limit))
        assertEquals(JsonPrimitive(-limit), swiftDouble(-limit))
    }

    @Test
    fun zeroesMatchSwiftJsonEncoder() {
        // Swift 6.2's JSONEncoder writes [-0.0, 0.0] as [-0,0].
        assertEquals("0", literal(0.0))
        assertEquals("-0", literal(-0.0))
        assertEquals(Json.parseToJsonElement("-0"), swiftDouble(-0.0))
    }

    @Test
    fun nonFiniteValuesAreRefused() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows("$value", IllegalArgumentException::class.java) { swiftDouble(value) }
        }
    }
}
