package dev.bugobliterator.itermbuddy.core.wire

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

private const val TWO_TO_53 = 9007199254740992.0
private const val MAX_SIGNIFICANT_DIGITS = 17
private const val LOWEST_DECIMAL_EXPONENT = -4

/**
 * A Swift `Double` exactly as upstream's `JSONEncoder` writes it (Swift 6.2, swift-foundation),
 * so parity tests can compare JSON trees, whose number literals compare as text.
 *
 * The encoder writes `Double.description` with a trailing `.0` removed. That is the shortest
 * digit string that reads back as the same double, laid out as:
 * - decimal when 1e-4 <= |x| <= 2^53 (`0.0001`, `15.75`, `30`, `9007199254740992`),
 *   integral values as integers;
 * - otherwise `d.ddde±XX`, with at least two exponent digits (`1e-05`, `9.007199254740994e+15`,
 *   `1e+16`, `1e+300`);
 * - `-0.0` as `-0`, `0.0` as `0`.
 * Probed in `swift:6.2-noble`; JsonNumbersTest pins the probe's output. Swift's encoder refuses
 * NaN and infinities, so this does too.
 *
 * The digits are the correctly rounded shortest decimal that round-trips. Swift picks the
 * closest shortest decimal; the two differ only if a shorter decimal round-trips while the
 * correctly rounded one of that length does not, which needs an asymmetric rounding interval
 * at a power of two and is not reached by any value the phone sends.
 */
@OptIn(ExperimentalSerializationApi::class)
fun swiftDouble(value: Double): JsonPrimitive = JsonUnquotedLiteral(swiftDoubleText(value))

internal fun swiftDoubleText(value: Double): String {
    require(value.isFinite()) { "JSON has no literal for $value" }
    if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
    val sign = if (value < 0) "-" else ""
    val magnitude = abs(value)
    val (digits, exponent) = shortestDigits(magnitude)
    val body = if (exponent < LOWEST_DECIMAL_EXPONENT || magnitude > TWO_TO_53) {
        val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
        val exponentSign = if (exponent < 0) "-" else "+"
        "${mantissa}e$exponentSign${abs(exponent).toString().padStart(2, '0')}"
    } else {
        when {
            exponent >= digits.length - 1 -> digits + "0".repeat(exponent - (digits.length - 1))
            exponent >= 0 -> "${digits.substring(0, exponent + 1)}.${digits.substring(exponent + 1)}"
            else -> "0." + "0".repeat(-exponent - 1) + digits
        }
    }
    return sign + body
}

/**
 * The shortest significant digits (no trailing zeros) that read back as [magnitude], and the
 * decimal exponent `e` with `magnitude ≈ d.ddd × 10^e`.
 */
private fun shortestDigits(magnitude: Double): Pair<String, Int> {
    val exact = BigDecimal(magnitude)
    for (precision in 1..MAX_SIGNIFICANT_DIGITS) {
        val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        if (rounded.toDouble() == magnitude) {
            val stripped = rounded.stripTrailingZeros()
            val digits = stripped.unscaledValue().toString()
            return digits to digits.length - 1 - stripped.scale()
        }
    }
    error("17 significant digits always round-trip a double")
}
