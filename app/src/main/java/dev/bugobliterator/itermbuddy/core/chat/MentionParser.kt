package dev.bugobliterator.itermbuddy.core.chat

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * One @-mention in a text (`MentionParser.swift:51-62`). [start] and [end] (exclusive) are UTF-16
 * offsets of the whole match, the "@" included when there is one. [prefix] is `session:`, `wg-`
 * or null for a bare session reference, lowercased; [token] is the ID without the prefix: a UUID
 * as written, or a stable ID in canonical form when it is valid, else as written.
 *
 * It holds identifiers only, which may be logged.
 */
data class Mention(val start: Int, val end: Int, val prefix: String?, val token: String) {
    /** The identifier without the "@": [prefix] plus [token]. This is what the Mac resolves. */
    val identifier: String get() = (prefix ?: "") + token
}

/**
 * Finds session and workgroup @-mentions in chat text, a port of upstream's `MentionParser`
 * (`MentionParser.swift:16-205`), which the Mac and iOS share so a mention means the same thing
 * everywhere. A mention is "@" then `<uuid>` (a legacy session GUID), `ptys_<…>` (a stable ID),
 * `session:<uuid|ptys_…>` or `wg-<uuid>`, matched case-insensitively and not followed by a
 * letter, digit or "-" (`MentionParser.swift:17-31`).
 *
 * The stable-ID part of the pattern is the strict alphabet ([StableSessionId.TOKEN_PATTERN]), so
 * an ID written with a Crockford confusable (`l` for `1`, `O` for `0`) is not a mention; only
 * [StableSessionId.canonical] folds those. Matching folds case as ICU does (Unicode, so a Kelvin
 * sign is a K), and offsets are UTF-16 as `NSString`'s.
 */
object MentionParser {
    private const val UUID_PATTERN =
        "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
    private const val ID_PATTERN = "$UUID_PATTERN|${StableSessionId.TOKEN_PATTERN}"
    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    private val regex = Pattern.compile("@(session:|wg-)?($ID_PATTERN)(?![0-9A-Za-z-])", FLAGS)

    /** The same with the "@" optional, for agent text (`MentionParser.swift:32-38`). */
    private val regexOptionalAt = Pattern.compile("@?(session:|wg-)?($ID_PATTERN)(?![0-9A-Za-z-])", FLAGS)

    /**
     * Spans whose text markdown does not parse for emphasis or does not show: fenced code blocks,
     * inline code spans, link destinations, autolinks and bare URLs (`MentionParser.swift:116-139`).
     * ICU's `\s` is Unicode White_Space, written out here since Java's is ASCII only.
     */
    private val unprotectedSpanRegex = Pattern.compile(
        listOf(
            "^[ \\t]{0,3}(`{3,}|~{3,}).*?(?:^[ \\t]{0,3}\\1|\\z)",
            "(`+)(?:(?!\\n[ \\t]*\\n).)+?(?<!`)\\2(?!`)",
            "\\]\\([^)\\n]*\\)",
            "<[A-Za-z][A-Za-z0-9+.-]*:[^<>\\p{IsWhite_Space}]*>",
            "https?://[^\\p{IsWhite_Space}<>()]+",
        ).joinToString("|"),
        Pattern.MULTILINE or Pattern.DOTALL,
    )

    /**
     * Indented code lines, as the Mac's renderer treats them: four spaces, or a tab that does not
     * start a tab-indented list item (`MentionParser.swift:141-148`).
     */
    private val indentedCodeLineRegex = Pattern.compile("^(?: {4}|\\t(?!\\t?(?:[-*] |1\\. )))[^\\n]*", Pattern.MULTILINE)

    /**
     * Every mention in [text], in order (`MentionParser.swift:64-102`). With [atSignOptional]
     * (agent-authored text, where the model sometimes drops the "@"), a match without "@" is kept
     * only when it is a valid stable ID with no prefix and no letter, digit, mark, "_" or "-"
     * right before it; a bare UUID or a bare `session:`/`wg-` form still needs the "@". An
     * "@"-signed stable ID with a bad check character is still a mention, its token as written.
     */
    fun mentions(text: String, atSignOptional: Boolean = false): List<Mention> {
        val matcher = (if (atSignOptional) regexOptionalAt else regex).matcher(text)
        val found = ArrayList<Mention>()
        while (matcher.find()) mentionAt(text, matcher)?.let(found::add)
        return found
    }

    private fun mentionAt(text: String, matcher: Matcher): Mention? {
        val hasPrefix = matcher.start(1) >= 0
        val rawToken = checkNotNull(matcher.group(2))
        val canonicalStableId = StableSessionId.canonical(rawToken)
        val atSigned = text[matcher.start()] == '@'
        if (!atSigned && (hasPrefix || canonicalStableId == null || !hasLeftBoundary(text, matcher.start()))) return null
        return Mention(
            start = matcher.start(),
            end = matcher.end(),
            prefix = matcher.group(1)?.lowercase(),
            token = canonicalStableId ?: rawToken,
        )
    }

    /**
     * Whether the UTF-16 unit before [location] leaves a boundary (`MentionParser.swift:42-49,
     * 184-190`): not a letter, mark or number (Foundation's `alphanumerics`), "_" or "-". A lone
     * surrogate half is no scalar, so it is a boundary, as upstream's `Unicode.Scalar(unichar)`.
     */
    private fun hasLeftBoundary(text: String, location: Int): Boolean {
        if (location == 0) return true
        val before = text[location - 1]
        if (before.isSurrogate()) return true
        if (before == '_' || before == '-') return false
        return when (Character.getType(before).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> false
            else -> true
        }
    }

    /**
     * The UTF-16 ranges of [markdown] where a mention must not be rewritten for display: code
     * blocks and spans, link destinations, autolinks and bare URLs, plus indented code lines when
     * [indentedCodeBlocks] (`MentionParser.swift:116-148`). A mention any span touches is left as
     * text, as upstream's `protect` leaves it (`MentionParser.swift:157-169`); every other one
     * may be linkified and is kept whole through the markdown pass, so a stable ID's "_" never
     * pairs up as emphasis. Used in place of upstream's random-nonce placeholders.
     */
    fun protectedSpans(markdown: String, indentedCodeBlocks: Boolean = false): List<IntRange> {
        val spans = ArrayList<IntRange>()
        val patterns = if (indentedCodeBlocks) listOf(unprotectedSpanRegex, indentedCodeLineRegex) else listOf(unprotectedSpanRegex)
        for (pattern in patterns) {
            val matcher = pattern.matcher(markdown)
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) spans += matcher.start() until matcher.end()
            }
        }
        return spans
    }

    /**
     * Splits a bare identifier (a mention without its "@") into prefix and canonical token, or
     * null when it is not exactly one well-formed mention identifier (`MentionParser.swift:192-204`).
     */
    fun split(identifier: String): Pair<String?, String>? {
        val full = "@$identifier"
        val mention = mentions(full).firstOrNull() ?: return null
        if (mention.start != 0 || mention.end != full.length) return null
        return mention.prefix to mention.token
    }
}
