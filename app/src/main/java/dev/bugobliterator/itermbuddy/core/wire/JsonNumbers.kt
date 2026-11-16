package dev.bugobliterator.itermbuddy.core.wire

import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

private const val TWO_TO_53 = 9007199254740992.0

/**
 * A Swift `Double` as upstream's `JSONEncoder` writes it, for the values the phone sends.
 *
 * Global Constraint: an integral value with |x| < 2^53 is an integer literal (`30`, not
 * Kotlin's `30.0`), so a parity test can compare JSON trees exactly. `-0.0` is `-0`,
 * which is what Swift 6.2's `JSONEncoder` writes (probed in `swift:6.2-noble`; it writes
 * `0.0` as `0`). Any other value keeps Kotlin's literal: the same number, though not
 * always Swift's text (Swift writes 2^53 as `9007199254740992`, Kotlin as
 * `9.007199254740992E15`). Swift's encoder refuses NaN and infinities, so this does too.
 */
@OptIn(ExperimentalSerializationApi::class)
fun swiftDouble(value: Double): JsonPrimitive {
    require(value.isFinite()) { "JSON has no literal for $value" }
    return when {
        value == 0.0 && 1.0 / value < 0 -> JsonUnquotedLiteral("-0")
        value % 1.0 == 0.0 && abs(value) < TWO_TO_53 -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }
}
