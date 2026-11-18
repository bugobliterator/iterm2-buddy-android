package dev.bugobliterator.itermbuddy.core.wire

import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonObject

private val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * A fresh message `uniqueID`: a random UUID in the canonical form Swift's `UUID.uuidString`
 * writes, uppercase (`Message.swift:509`; Swift encodes a `UUID` as that string).
 */
fun newMessageId(): String = UUID.randomUUID().toString().uppercase(Locale.ROOT)

/** True when [text] is a canonical uppercase UUID, the form the phone holds and sends. */
fun isCanonicalUppercaseUuid(text: String): Boolean =
    CANONICAL_UUID.matches(text) && text == text.uppercase(Locale.ROOT)

/**
 * A Swift `UUID` field: a string in the canonical 8-4-4-4-12 hex form, read case-insensitively
 * as Foundation's `UUID(uuidString:)` does, and returned uppercase. Anything else throws a
 * [WireDecodeException] naming [case] and [key] only.
 */
fun JsonObject.uuid(case: String, key: String): String {
    val text = string(case, key)
    if (!CANONICAL_UUID.matches(text)) throw WireDecodeException("$case.$key is not a UUID")
    return text.uppercase(Locale.ROOT)
}

/**
 * A Swift `Date` field as the companion wire writes it: milliseconds since 1970 as a JSON
 * number (`WireCoding.swift:15-25`). Any number literal is accepted, fractions included.
 */
fun JsonObject.millis(case: String, key: String): Double = double(case, key)
