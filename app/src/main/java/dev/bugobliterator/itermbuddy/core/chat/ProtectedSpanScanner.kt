package dev.bugobliterator.itermbuddy.core.chat

import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Finds the spans of upstream's `unprotectedSpanRegex` (`MentionParser.swift:125-139`) and
 * `indentedCodeLineRegex` (`MentionParser.swift:146-148`) in time linear in the text (up to a log
 * factor for code spans), where the regexes themselves backtrack: the inline-code alternative is
 * cubic in a long backtick run with no closing run, and the link-destination alternative is
 * quadratic on a line of `](`. Both would hang the app on hostile chat text.
 *
 * The scan keeps the regex's leftmost-first semantics: from each position it tries the
 * alternatives in upstream's order (fenced block, inline code, link destination, autolink, bare
 * URL), takes the first that matches, and continues after it. Each alternative can only start
 * on one kind of character, so the others are skipped without work. Fenced blocks, autolinks,
 * bare URLs and indented lines stay regexes, all linear: none of them backtracks over more than
 * the characters a match or a failed attempt from a single start position reads, and those
 * ranges never overlap. The two backtracking alternatives are written by hand.
 *
 * Line starts are ICU's (`\n`, VT, FF, `\r` not before `\n`, NEL, LS, PS), written out since
 * Java's `^` leaves out VT and FF, so the JVM and the device agree with upstream.
 */
internal object ProtectedSpanScanner {
    /** Upstream's `^` with `.anchorsMatchLines`, as ICU defines a line start. */
    private const val LINE_START = "(?:(?<![\\s\\S])|(?<=[\\n\\u000B\\u000C\\u0085\\u2028\\u2029])|(?<=\\r)(?!\\n))"

    /** ICU's `\s`: Unicode White_Space, spelt out so Java and Android's ICU agree. */
    private const val WHITE_SPACE = "\\t\\n\\u000B\\u000C\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    /** Fenced block: through the matching closing fence, or to the end. */
    private val fence = Pattern.compile("$LINE_START[ \\t]{0,3}(`{3,}|~{3,}).*?(?:$LINE_START[ \\t]{0,3}\\1|\\z)", Pattern.DOTALL)

    /** Autolink: `<scheme:...>`. */
    private val autolink = Pattern.compile("<[A-Za-z][A-Za-z0-9+.-]*:[^<>$WHITE_SPACE]*>")

    /** Bare URL. */
    private val bareUrl = Pattern.compile("https?://[^$WHITE_SPACE<>()]+")

    /** Indented code line: four spaces, or a tab not starting a tab-indented list item. */
    private val indentedCodeLine = Pattern.compile("$LINE_START(?: {4}|\\t(?!\\t?(?:[-*] |1\\. )))[^\\n]*")

    /** The spans of upstream's `unprotectedSpanRegex`, ascending and non-overlapping. */
    fun codeAndLinkSpans(text: CharSequence): List<IntRange> {
        val spans = ArrayList<IntRange>()
        scanUnprotected(text, spans)
        return spans
    }

    /** The spans of upstream's `indentedCodeLineRegex`, ascending and non-overlapping. */
    fun indentedLineSpans(text: CharSequence): List<IntRange> {
        val spans = ArrayList<IntRange>()
        val matcher = indentedCodeLine.matcher(text)
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) spans += matcher.start() until matcher.end()
        }
        return spans
    }

    private fun scanUnprotected(text: CharSequence, spans: MutableList<IntRange>) {
        val length = text.length
        val fenceMatcher = anchoredMatcher(fence, text)
        val autolinkMatcher = anchoredMatcher(autolink, text)
        val urlMatcher = anchoredMatcher(bareUrl, text)
        var codeSpans: CodeSpans? = null
        // A `](` that found no `)` before its line's end: every later `](` on that line fails too.
        var linkDeadBefore = -1
        var index = 0
        while (index < length) {
            val char = text[index]
            var end = -1
            if ((char == ' ' || char == '\t' || char == '`' || char == '~') && mayStartLine(text, index)) {
                end = matchAt(fenceMatcher, index, length)
            }
            if (end < 0 && char == '`') {
                end = (codeSpans ?: CodeSpans(text).also { codeSpans = it }).matchAt(index)
            }
            if (end < 0 && char == ']' && index >= linkDeadBefore && index + 1 < length && text[index + 1] == '(') {
                end = linkDestinationEnd(text, index)
                if (end < 0) linkDeadBefore = -end - 1
            }
            if (end < 0 && char == '<') end = matchAt(autolinkMatcher, index, length)
            if (end < 0 && char == 'h') end = matchAt(urlMatcher, index, length)
            if (end > index) {
                spans += index until end
                index = end
            } else {
                index++
            }
        }
    }

    private fun anchoredMatcher(pattern: Pattern, text: CharSequence): Matcher =
        pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false)

    /** The end of [matcher]'s pattern matched at [start] in a text of [length], or -1. */
    private fun matchAt(matcher: Matcher, start: Int, length: Int): Int {
        matcher.region(start, length)
        return if (matcher.lookingAt()) matcher.end() else -1
    }

    /** A cheap filter for [LINE_START]: the regex decides exactly. */
    private fun mayStartLine(text: CharSequence, index: Int): Boolean {
        if (index == 0) return true
        return when (text[index - 1]) {
            '\n', '\u000B', '\u000C', '\r', '\u0085', '\u2028', '\u2029' -> true
            else -> false
        }
    }

    /**
     * `\]\([^)\n]*\)` at [start] (where `](` is): the end of the match, or, when there is none,
     * `-(k + 1)` for the newline or end of text `k` that stopped it.
     */
    private fun linkDestinationEnd(text: CharSequence, start: Int): Int {
        var index = start + 2
        while (index < text.length) {
            when (text[index]) {
                ')' -> return index + 1
                '\n' -> return -(index + 1)
            }
            index++
        }
        return -(text.length + 1)
    }

    /**
     * Upstream's inline-code alternative, ``(`+)(?:(?!\n[ \t]*\n).)+?(?<!`)\2(?!`)``, without
     * backtracking. From a start inside a backtick run with `r` backticks left, the regex tries
     * group lengths `g = r, r-1, …, 1` and, for each, the nearest closing: a maximal run of exactly
     * `g` backticks after the opening run with no blank-line start (`\n[ \t]*\n`) between them. So
     * the match is the longest `g ≤ r` that has such a run, closed at its first one.
     *
     * The text is indexed once: every maximal run, and the blank-line segment it lies in. Queries
     * come in increasing position, as the scan moves forward, so each segment's runs are loaded
     * once into a map from run length to the runs of that length still ahead.
     */
    private class CodeSpans(text: CharSequence) {
        private var runStarts = IntArray(16)
        private var runLengths = IntArray(16)
        private var runSegments = IntArray(16)
        private var runCount = 0

        /** The run of the last query, and the last run dropped from [ahead] (passed by the scan). */
        private var cursor = 0
        private var droppedThrough = -1
        private var loadedSegment = -1
        private var segmentEnd = 0
        private val ahead = TreeMap<Int, ArrayDeque<Int>>()

        init {
            val length = text.length
            var segment = 0
            var index = 0
            while (index < length) {
                when (text[index]) {
                    '`' -> {
                        val start = index
                        while (index < length && text[index] == '`') index++
                        addRun(start, index - start, segment)
                        continue
                    }
                    '\n' -> {
                        var next = index + 1
                        while (next < length && (text[next] == ' ' || text[next] == '\t')) next++
                        if (next < length && text[next] == '\n') segment++
                        // The spaces are read again by the outer loop: at most twice in all.
                    }
                }
                index++
            }
        }

        private fun addRun(start: Int, length: Int, segment: Int) {
            if (runCount == runStarts.size) {
                runStarts = runStarts.copyOf(runCount * 2)
                runLengths = runLengths.copyOf(runCount * 2)
                runSegments = runSegments.copyOf(runCount * 2)
            }
            runStarts[runCount] = start
            runLengths[runCount] = length
            runSegments[runCount] = segment
            runCount++
        }

        /** The end of the code span starting at [position] (a backtick), or -1. */
        fun matchAt(position: Int): Int {
            while (cursor < runCount && runStarts[cursor] + runLengths[cursor] <= position) cursor++
            if (cursor == runCount || runStarts[cursor] > position) return -1
            val run = cursor
            val left = runStarts[run] + runLengths[run] - position
            if (runSegments[run] != loadedSegment) load(run)
            while (droppedThrough < run) {
                droppedThrough++
                if (droppedThrough >= segmentEnd) continue
                val length = runLengths[droppedThrough]
                val queue = ahead[length] ?: continue
                queue.removeFirst()
                if (queue.isEmpty()) ahead.remove(length)
            }
            val length = ahead.floorKey(left) ?: return -1
            val closing = ahead.getValue(length).first()
            return runStarts[closing] + length
        }

        /**
         * Loads the runs of [run]'s segment from [run] on; the scan is past the ones before it.
         * [run] itself is dropped by the caller next, as the scan reaches it.
         */
        private fun load(run: Int) {
            ahead.clear()
            loadedSegment = runSegments[run]
            var end = run
            while (end < runCount && runSegments[end] == loadedSegment) {
                ahead.getOrPut(runLengths[end]) { ArrayDeque() }.addLast(end)
                end++
            }
            segmentEnd = end
            droppedThrough = run - 1
        }
    }
}
