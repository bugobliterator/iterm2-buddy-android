package dev.bugobliterator.itermbuddy.core.chat

/**
 * Swift `String.truncatedWithTrailingEllipsis(to:)` (iOS `MacShims.swift:22-27`): [this] when it
 * has at most [maxLength] `Character`s, else its first `maxLength - 1` followed by "…".
 *
 * A Swift `Character` is an extended grapheme cluster, so the count is of clusters
 * ([graphemeBoundaries]), never of UTF-16 units or code points: a ZWJ emoji family is one.
 */
fun String.truncatedWithTrailingEllipsis(maxLength: Int): String {
    val boundaries = graphemeBoundaries(this, limit = maxLength + 1)
    // boundaries[i] is the end of cluster i + 1; more than maxLength ends means it is too long.
    if (boundaries.size <= maxLength) return this
    val keep = maxLength - 1
    val end = if (keep <= 0) 0 else boundaries[keep - 1]
    return substring(0, end) + "…"
}

/** The number of extended grapheme clusters (Swift `Character`s) in [text]. */
fun graphemeCount(text: String): Int = graphemeBoundaries(text, limit = Int.MAX_VALUE).size

/**
 * The UTF-16 end offsets of the first [limit] extended grapheme clusters of [text], per the
 * Unicode default rules (UAX #29: GB3-GB13, GB999), so a cut agrees with Swift's `Character`
 * on the phone and on the JVM alike. `java.text.BreakIterator` is not used: before JDK 20 it
 * does not keep emoji ZWJ sequences together, so JVM tests would not see what the device does.
 *
 * Property tables are derived from [Character.getType] plus the fixed ranges below. Known gaps,
 * each moving a cut by at most one cluster in rare text:
 * - GB9c (Indic conjunct breaks, Unicode 15.1) is not implemented, so a few Indic conjuncts that
 *   Swift keeps whole are split.
 * - Every `Mc` is SpacingMark; the handful of Myanmar (and similar) `Mc` that Unicode lists as
 *   Extend or excludes from SpacingMark are not special-cased.
 * - GB11 is tracked loosely: `ExtPict ZWJ ZWJ ExtPict` stays one cluster here, where Unicode
 *   breaks before the second pictograph.
 */
internal fun graphemeBoundaries(text: String, limit: Int): List<Int> {
    val ends = ArrayList<Int>()
    if (text.isEmpty() || limit <= 0) return ends
    var index = 0
    var previous = breakProperty(text.codePointAt(0))
    index += Character.charCount(text.codePointAt(0))
    // GB11 state: the run so far is ExtPict Extend* (ZWJ pending is checked from previous).
    var pictographicRun = previous == BreakProperty.EXTENDED_PICTOGRAPHIC
    // GB12/13: regional indicators seen in the current run, to pair them.
    var regionalCount = if (previous == BreakProperty.REGIONAL_INDICATOR) 1 else 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val current = breakProperty(codePoint)
        if (breaksBetween(previous, current, pictographicRun, regionalCount)) {
            ends.add(index)
            if (ends.size == limit) return ends
            regionalCount = 0
            pictographicRun = false
        }
        pictographicRun = when (current) {
            BreakProperty.EXTENDED_PICTOGRAPHIC -> true
            BreakProperty.EXTEND, BreakProperty.ZWJ -> pictographicRun
            else -> false
        }
        regionalCount = if (current == BreakProperty.REGIONAL_INDICATOR) regionalCount + 1 else 0
        previous = current
        index += Character.charCount(codePoint)
    }
    ends.add(text.length)
    return ends
}

private enum class BreakProperty {
    CR, LF, CONTROL, EXTEND, ZWJ, REGIONAL_INDICATOR, PREPEND, SPACING_MARK,
    HANGUL_L, HANGUL_V, HANGUL_T, HANGUL_LV, HANGUL_LVT, EXTENDED_PICTOGRAPHIC, OTHER,
}

private fun breaksBetween(
    previous: BreakProperty,
    current: BreakProperty,
    pictographicRun: Boolean,
    regionalCount: Int,
): Boolean = when {
    previous == BreakProperty.CR && current == BreakProperty.LF -> false // GB3
    previous.isControl() || current.isControl() -> true // GB4, GB5
    previous == BreakProperty.HANGUL_L && current in HANGUL_AFTER_L -> false // GB6
    previous in HANGUL_LV_OR_V && current in HANGUL_V_OR_T -> false // GB7
    previous in HANGUL_LVT_OR_T && current == BreakProperty.HANGUL_T -> false // GB8
    current == BreakProperty.EXTEND || current == BreakProperty.ZWJ -> false // GB9
    current == BreakProperty.SPACING_MARK -> false // GB9a
    previous == BreakProperty.PREPEND -> false // GB9b
    // GB11: ExtPict Extend* ZWJ × ExtPict. pictographicRun covers the ExtPict Extend* ZWJ prefix.
    previous == BreakProperty.ZWJ && current == BreakProperty.EXTENDED_PICTOGRAPHIC && pictographicRun -> false
    // GB12, GB13: pairs of regional indicators.
    previous == BreakProperty.REGIONAL_INDICATOR && current == BreakProperty.REGIONAL_INDICATOR -> regionalCount % 2 == 0
    else -> true // GB999
}

private fun BreakProperty.isControl(): Boolean =
    this == BreakProperty.CR || this == BreakProperty.LF || this == BreakProperty.CONTROL

private val HANGUL_AFTER_L = setOf(
    BreakProperty.HANGUL_L, BreakProperty.HANGUL_V, BreakProperty.HANGUL_LV, BreakProperty.HANGUL_LVT,
)
private val HANGUL_LV_OR_V = setOf(BreakProperty.HANGUL_LV, BreakProperty.HANGUL_V)
private val HANGUL_V_OR_T = setOf(BreakProperty.HANGUL_V, BreakProperty.HANGUL_T)
private val HANGUL_LVT_OR_T = setOf(BreakProperty.HANGUL_LVT, BreakProperty.HANGUL_T)

private const val HANGUL_SYLLABLE_FIRST = 0xAC00
private const val HANGUL_SYLLABLE_LAST = 0xD7A3
private const val HANGUL_T_COUNT = 28

private fun breakProperty(codePoint: Int): BreakProperty = when {
    codePoint == 0x0D -> BreakProperty.CR
    codePoint == 0x0A -> BreakProperty.LF
    codePoint == 0x200D -> BreakProperty.ZWJ
    codePoint == 0x200C -> BreakProperty.EXTEND
    codePoint in 0x1F1E6..0x1F1FF -> BreakProperty.REGIONAL_INDICATOR
    codePoint in 0x1F3FB..0x1F3FF -> BreakProperty.EXTEND // emoji modifiers
    codePoint in 0xE0020..0xE007F -> BreakProperty.EXTEND // tags
    codePoint == 0xFF9E || codePoint == 0xFF9F -> BreakProperty.EXTEND // halfwidth sound marks
    codePoint == 0x0E33 || codePoint == 0x0EB3 -> BreakProperty.SPACING_MARK // Thai and Lao SARA AM (Lo)
    PREPEND.any { codePoint in it } -> BreakProperty.PREPEND
    codePoint in 0x1100..0x115F || codePoint in 0xA960..0xA97C -> BreakProperty.HANGUL_L
    codePoint in 0x1160..0x11A7 || codePoint in 0xD7B0..0xD7C6 -> BreakProperty.HANGUL_V
    codePoint in 0x11A8..0x11FF || codePoint in 0xD7CB..0xD7FB -> BreakProperty.HANGUL_T
    codePoint in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST ->
        if ((codePoint - HANGUL_SYLLABLE_FIRST) % HANGUL_T_COUNT == 0) BreakProperty.HANGUL_LV else BreakProperty.HANGUL_LVT
    EXTENDED_PICTOGRAPHIC.any { codePoint in it } -> BreakProperty.EXTENDED_PICTOGRAPHIC
    else -> when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> BreakProperty.EXTEND
        Character.COMBINING_SPACING_MARK -> BreakProperty.SPACING_MARK
        Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT,
        Character.SURROGATE,
        -> BreakProperty.CONTROL
        else -> BreakProperty.OTHER
    }
}

/** Grapheme_Cluster_Break=Prepend (Unicode 15 `GraphemeBreakProperty.txt`), the format characters among them. */
private val PREPEND = listOf(
    0x0600..0x0605, 0x06DD..0x06DD, 0x070F..0x070F, 0x0890..0x0891, 0x08E2..0x08E2,
    0x0D4E..0x0D4E, 0x110BD..0x110BD, 0x110CD..0x110CD, 0x111C2..0x111C3, 0x1193F..0x1193F,
    0x11941..0x11941, 0x11A3A..0x11A3A, 0x11A84..0x11A89, 0x11D46..0x11D46,
)

/** Extended_Pictographic (Unicode 15 `emoji-data.txt`), merged into ranges. */
private val EXTENDED_PICTOGRAPHIC = listOf(
    0x00A9..0x00A9, 0x00AE..0x00AE, 0x203C..0x203C, 0x2049..0x2049, 0x2122..0x2122, 0x2139..0x2139,
    0x2194..0x2199, 0x21A9..0x21AA, 0x231A..0x231B, 0x2328..0x2328, 0x2388..0x2388, 0x23CF..0x23CF,
    0x23E9..0x23F3, 0x23F8..0x23FA, 0x24C2..0x24C2, 0x25AA..0x25AB, 0x25B6..0x25B6, 0x25C0..0x25C0,
    0x25FB..0x25FE, 0x2600..0x2605, 0x2607..0x2612, 0x2614..0x2685, 0x2690..0x2705, 0x2708..0x2712,
    0x2714..0x2714, 0x2716..0x2716, 0x271D..0x271D, 0x2721..0x2721, 0x2728..0x2728, 0x2733..0x2734,
    0x2744..0x2744, 0x2747..0x2747, 0x274C..0x274C, 0x274E..0x274E, 0x2753..0x2755, 0x2757..0x2757,
    0x2763..0x2767, 0x2795..0x2797, 0x27A1..0x27A1, 0x27B0..0x27B0, 0x27BF..0x27BF, 0x2934..0x2935,
    0x2B05..0x2B07, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55, 0x3030..0x3030, 0x303D..0x303D,
    0x3297..0x3297, 0x3299..0x3299, 0x1F000..0x1F0FF, 0x1F10D..0x1F10F, 0x1F12F..0x1F12F,
    0x1F16C..0x1F171, 0x1F17E..0x1F17F, 0x1F18E..0x1F18E, 0x1F191..0x1F19A, 0x1F1AD..0x1F1E5,
    0x1F201..0x1F20F, 0x1F21A..0x1F21A, 0x1F22F..0x1F22F, 0x1F232..0x1F23A, 0x1F23C..0x1F23F,
    0x1F249..0x1F3FA, 0x1F400..0x1F53D, 0x1F546..0x1F64F, 0x1F680..0x1F6FF, 0x1F774..0x1F77F,
    0x1F7D5..0x1F7FF, 0x1F80C..0x1F80F, 0x1F848..0x1F84F, 0x1F85A..0x1F85F, 0x1F888..0x1F88F,
    0x1F8AE..0x1F8FF, 0x1F90C..0x1F93A, 0x1F93C..0x1F945, 0x1F947..0x1FAFF, 0x1FC00..0x1FFFD,
)
