package dev.bugobliterator.itermbuddy.core.wire

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonNumbersTest {
    private fun literal(value: Double): String = swiftDouble(value).toString()

    private fun double(bits: String): Double = java.lang.Double.longBitsToDouble(bits.toULong(16).toLong())

    /**
     * IEEE bit pattern to the text Swift 6.2's `JSONEncoder` wrote for it, from a probe in
     * `swift:6.2-noble` (`JSONEncoder().encode(S(v: Double(bitPattern:)))`): boundaries of the
     * decimal range (1e-4, 2^53, 1e16), integral, fractional, negative, extreme and random values.
     */
    private val swiftEncoderOutput = listOf(
        "3f1a36e2eb1c432d" to "0.0001",
        "3ee4f8b588e368f1" to "1e-05",
        "423cbe991a144000" to "123456789012.25",
        "4341c37937e08000" to "1e+16",
        "4340000000000000" to "9007199254740992",
        "3e8421f5f40d8376" to "1.5e-07",
        "7e37e43c8800759c" to "1e+300",
        "8000000000000000" to "-0",
        "0000000000000000" to "0",
        "403e000000000000" to "30",
        "402f800000000000" to "15.75",
        "3f1a36e2eb1c432c" to "9.999999999999999e-05",
        "430c6bf526340000" to "1000000000000000",
        "42dc12218377de66" to "123456789012345.6",
        "43118b54f22aeb03" to "1234567890123456.8",
        "3fb999999999999a" to "0.1",
        "3fd5555555555555" to "0.3333333333333333",
        "3fe5555555555555" to "0.6666666666666666",
        "01a56e1fc2f8f359" to "1e-300",
        "0000000000000001" to "5e-324",
        "7fefffffffffffff" to "1.7976931348623157e+308",
        "c341c37937e08000" to "-1e+16",
        "bf1a36e2eb1c432d" to "-0.0001",
        "4480f0cf064dd592" to "1e+22",
        "44b52d02c7e14af6" to "1e+23",
        "4350000000000000" to "1.8014398509481984e+16",
        "43e0000000000000" to "9.223372036854776e+18",
        "43f0000000000000" to "1.8446744073709552e+19",
        "3fd3333333333334" to "0.30000000000000004",
        "4059000000000000" to "100",
        "3eff75104d551d6a" to "3.0000000000000004e-05",
        "4011666666666666" to "4.35",
        "c121ad81e1179d19" to "-579264.939633283",
        "c12175439c99c5ed" to "-572065.805860696",
        "c1074f05bf6bb268" to "-190944.71846713428",
        "412a455adf98bb4e" to "860845.4367121251",
        "40c08fe44e567d40" to "8479.783640681417",
        "c115c380c86f1924" to "-356576.195736306",
        "c12db6f26965fb66" to "-973689.2058561861",
        "c122fdfb26e5863c" to "-622333.5759698818",
        "614b558901adfa28" to "4.8036663143242085e+160",
        "404057c6857a05c4" to "32.685745892125425",
        "4985f56ea0ce37d9" to "1.5670265728960347e+46",
        "2e9a1da3fdd0b802" to "3.360812496882499e-84",
        "783122e38095682e" to "9.053005073270356e+270",
        "482011ec3eb65079" to "2.7341707003378996e+39",
        "41bf0dc82d1eb986" to "520996909.12001836",
        "3fd6d6132476195e" to "0.3568160873477576",
        "42d2ed39a4c5f74a" to "83240334596061.16",
        "41b5d3bce6311ec1" to "366197990.1918755",
        "4341c37937e07fff" to "9.999999999999998e+15",
        "4340000000000001" to "9.007199254740994e+15",
        "3f23a92a30553262" to "0.00015000000000000001",
        "41678c29c0000000" to "12345678",
        "3f50624dd2f1a9fc" to "0.001",
        "3ff0000000000000" to "1",
        "3ddb7cdfd9d7bdbb" to "1e-10",
        "441aabdf2145b430" to "1.23e+20",
    )

    @Test
    fun everyProbedValueMatchesSwiftJsonEncoder() {
        for ((bits, swift) in swiftEncoderOutput) {
            val value = double(bits)
            assertEquals("$bits ($value)", swift, literal(value))
            assertEquals("$bits parses back", value, Json.parseToJsonElement(literal(value)).toString().toDouble(), 0.0)
        }
    }

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
        assertEquals("0.0001", literal(0.0001))
        assertEquals("123456789012.25", literal(123456789012.25))
    }

    @Test
    fun negativeIntegralValuesAreIntegerLiterals() {
        assertEquals("-30", literal(-30.0))
        assertEquals("-1", literal(-1.0))
    }

    @Test
    fun decimalRangeEndsAtTwoToThe53() {
        val limit = 9007199254740992.0
        assertEquals("9007199254740991", literal(limit - 1))
        assertEquals("9007199254740992", literal(limit))
        assertEquals("-9007199254740992", literal(-limit))
        assertEquals("9.007199254740994e+15", literal(limit + 2))
        assertEquals("1e+16", literal(1e16))
    }

    @Test
    fun smallValuesUseTwoDigitExponents() {
        assertEquals("1e-05", literal(0.00001))
        assertEquals("1.5e-07", literal(1.5e-7))
        assertEquals("1e+300", literal(1e300))
        assertEquals("5e-324", literal(Double.MIN_VALUE))
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
