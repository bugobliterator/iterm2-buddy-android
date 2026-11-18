package dev.bugobliterator.itermbuddy.core.chat

import java.text.Normalizer

/**
 * A session's stable ID, `ptys_` plus 12 Crockford base32 characters plus a check character
 * (`StableSessionID.swift:8-21`). It survives a shell reload and state restoration, unlike the
 * session GUID, and is how chats and agents name sessions in mentions.
 */
object StableSessionId {
    /** The leading tag (`StableSessionID.swift:27`). */
    const val PREFIX = "ptys_"

    /** Crockford base32, canonical uppercase: digits and A-Z without I, L, O and U (`StableSessionID.swift:29-30`). */
    internal const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val BODY_LENGTH = 12

    /**
     * The strict token pattern, prefix plus 13 alphabet characters, for embedding in a larger
     * regex (`StableSessionID.swift:44, 61`). It does not match a confusable (`I`, `L`, `O`)
     * even when the larger regex is case-insensitive: detection is strict, and only [canonical]
     * folds.
     */
    internal const val TOKEN_PATTERN = "$PREFIX[$ALPHABET]{${BODY_LENGTH + 1}}"

    /**
     * The canonical form of [candidate] (lowercase prefix, uppercase strict-alphabet body), or
     * null when it is not a valid stable ID: wrong prefix, wrong length, a character outside the
     * alphabet after folding, or a bad check character (`StableSessionID.swift:85-109`). Case is
     * ignored, and the Crockford confusables `I`/`i`/`L`/`l` fold to `1` and `O`/`o` to `0`
     * (`StableSessionID.swift:124-133`). This is the only place they fold.
     *
     * As in Swift, lengths count `Character`s (extended grapheme clusters) and characters compare
     * by canonical equivalence, so a Kelvin sign (U+212A) is the letter K.
     */
    fun canonical(candidate: String): String? {
        // Five clusters of prefix and thirteen of body, plus one more to see a longer input.
        val ends = graphemeBoundaries(candidate, limit = PREFIX.length + BODY_LENGTH + 2)
        if (ends.size != PREFIX.length + BODY_LENGTH + 1) return null
        val prefixEnd = ends[PREFIX.length - 1]
        if (nfc(candidate.substring(0, prefixEnd).lowercase()) != PREFIX) return null
        val indices = IntArray(BODY_LENGTH + 1)
        var start = prefixEnd
        for (position in 0..BODY_LENGTH) {
            val end = ends[PREFIX.length + position]
            indices[position] = alphabetIndex(normalize(candidate.substring(start, end))) ?: return null
            start = end
        }
        if (indices[BODY_LENGTH] != checksum(indices)) return null
        return buildString(PREFIX.length + BODY_LENGTH + 1) {
            append(PREFIX)
            for (index in indices) append(ALPHABET[index])
        }
    }

    /** Position-weighted sum of the body's indices, mod 32 (`StableSessionID.swift:115-121`). */
    private fun checksum(indices: IntArray): Int {
        var sum = 0
        for (position in 0 until BODY_LENGTH) sum += (position + 1) * indices[position]
        return sum % ALPHABET.length
    }

    /** Folds the confusables and uppercases one `Character` (`StableSessionID.swift:124-133`). */
    private fun normalize(character: String): String = when (character) {
        "i", "I", "l", "L" -> "1"
        "o", "O" -> "0"
        else -> character.uppercase().takeIf { graphemeCount(it) == 1 } ?: character
    }

    private fun alphabetIndex(character: String): Int? {
        val composed = nfc(character)
        if (composed.length != 1) return null
        return ALPHABET.indexOf(composed[0]).takeIf { it >= 0 }
    }

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
}
