package dev.bugobliterator.itermbuddy.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import kotlin.random.Random

class ProtectedSpanScannerTest {
    /** Upstream's span regex as the first port compiled it: the reference the scanner must agree with. */
    private val referenceSpans = Pattern.compile(
        listOf(
            "^[ \\t]{0,3}(`{3,}|~{3,}).*?(?:^[ \\t]{0,3}\\1|\\z)",
            "(`+)(?:(?!\\n[ \\t]*\\n).)+?(?<!`)\\2(?!`)",
            "\\]\\([^)\\n]*\\)",
            "<[A-Za-z][A-Za-z0-9+.-]*:[^<>\\p{IsWhite_Space}]*>",
            "https?://[^\\p{IsWhite_Space}<>()]+",
        ).joinToString("|"),
        Pattern.MULTILINE or Pattern.DOTALL,
    )

    private val referenceIndentedLines = Pattern.compile("^(?: {4}|\\t(?!\\t?(?:[-*] |1\\. )))[^\\n]*", Pattern.MULTILINE)

    private fun reference(pattern: Pattern, text: CharSequence): List<IntRange> {
        val matcher = pattern.matcher(text)
        val spans = ArrayList<IntRange>()
        while (matcher.find()) if (matcher.end() > matcher.start()) spans += matcher.start() until matcher.end()
        return spans
    }

    /**
     * Short random texts of backticks, brackets, fences, newlines, blank lines and URL pieces: the
     * scanner finds exactly the reference regex's spans. Seeded, so a failure reproduces. Line
     * ends are the ones Java's `^` and ICU's agree on (VT and FF differ, pinned in MentionParserTest).
     */
    @Test
    fun scannerAgreesWithTheRegexOnRandomTexts() {
        val pieces = listOf(
            "`", "`", "`", "``", "```", "~~~", "~", "]", "(", ")", "](", "[", "\n", "\n\n", "\n \t\n", " ", "  ", "\t",
            "a", "x", "<", ">", "a:", "<h:", "http://", "https://", "h", "\r", "\r\n", "    ", "\u0085", " ", "- ", "1. ",
            "@ptys_7K2M9Q4R8T1VH", "ptys_3HX6W0ZD5N2BT",
        )
        val random = Random(20261118)
        repeat(60_000) { round ->
            val text = buildString { repeat(random.nextInt(0, 16)) { append(pieces[random.nextInt(pieces.size)]) } }
            val label = "round $round: " + text.map { it.code.toString(16) }
            assertEquals(label, reference(referenceSpans, text), ProtectedSpanScanner.codeAndLinkSpans(text))
            assertEquals(label, reference(referenceIndentedLines, text), ProtectedSpanScanner.indentedLineSpans(text))
        }
    }

    /** Backtick-heavy texts, where the code-span rule has the most cases. */
    @Test
    fun scannerAgreesWithTheRegexOnBacktickRuns() {
        val random = Random(7)
        repeat(60_000) { round ->
            val text = buildString {
                repeat(random.nextInt(0, 24)) {
                    when (random.nextInt(6)) {
                        0, 1, 2 -> append("`".repeat(random.nextInt(1, 5)))
                        3 -> append('a')
                        4 -> append('\n')
                        else -> append(if (random.nextBoolean()) " " else "\n \n")
                    }
                }
            }
            assertEquals("round $round: $text", reference(referenceSpans, text), ProtectedSpanScanner.codeAndLinkSpans(text))
        }
    }

    /** A text that counts its character reads and fails once they pass [budget]. */
    private class CountingText(private val text: String, private val budget: Long) : CharSequence {
        var reads = 0L
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads > budget) throw AssertionError("read more than $budget characters of a ${text.length}-character text")
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            reads += endIndex - startIndex
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String = text
    }

    private val readsPerCharacter = 32L

    private fun megabyte(unit: String): String = unit.repeat((1 shl 20) / unit.length)

    /**
     * Hostile megabyte texts read each character a bounded number of times: the backtracking
     * regex reads a 160-backtick run before 1 MB of text, or a line of `](`, billions of times.
     * Cost is counted in reads, never in time.
     */
    @Test
    fun hostileMegabyteTextsAreScannedLinearly() {
        val increasingRuns = buildString {
            var run = 1
            while (length < (1 shl 20)) {
                append("`".repeat(run++))
                append('x')
            }
        }
        val texts = mapOf(
            "long backtick run, no closing" to "x" + "`".repeat(160) + "a".repeat(1 shl 20),
            "long backtick run opening a fence" to "`".repeat(160) + "a".repeat(1 shl 20),
            "line of link openers" to megabyte("]("),
            "one link opener, no close" to "[x](" + "a".repeat(1 shl 20),
            "runs of rising length" to increasingRuns,
            "single backticks" to megabyte("`a"),
            "alternating runs" to megabyte("`` `"),
            "blank lines between backticks" to megabyte("`\n \n"),
            "unterminated fence" to "```\n" + megabyte("``\n"),
            "many fences" to megabyte("```\n"),
            "tilde fence lines" to megabyte("~~~~ \n"),
            "autolink without close" to "<a:" + "b".repeat(1 shl 20),
            "autolink scheme without colon" to "<a" + "b".repeat(1 shl 20),
            "angle brackets" to megabyte("<a"),
            "URL starts" to megabyte("http"),
            "URLs" to megabyte("http:// "),
            "indented lines" to megabyte("    a\n"),
            "tab lines" to megabyte("\t\t- \n"),
            "mentions" to megabyte("@ptys_7K2M9Q4R8T1VH "),
            "bare stable IDs" to megabyte("ptys_7K2M9Q4R8T1VH"),
            "prefixes" to megabyte("@session:"),
            "UUID starts" to megabyte("@1D2C3B4A-5968-"),
        )
        val passes = mapOf<String, (CharSequence) -> Unit>(
            "spans" to { ProtectedSpanScanner.codeAndLinkSpans(it) },
            "indented lines" to { ProtectedSpanScanner.indentedLineSpans(it) },
            "mentions" to { MentionParser.findMentions(it, atSignOptional = true) },
        )
        for ((name, text) in texts) {
            for ((pass, scan) in passes) {
                val budget = readsPerCharacter * text.length
                val counted = CountingText(text, budget)
                scan(counted)
                assertTrue("$name, $pass", counted.reads <= budget)
            }
        }
    }

    /** The read budget does catch the backtracking regex, on texts far smaller than a megabyte. */
    @Test
    fun readBudgetCatchesTheBacktrackingRegex() {
        for (text in listOf("x" + "`".repeat(160) + "a".repeat(20_000), "](".repeat(10_000))) {
            val counted = CountingText(text, readsPerCharacter * text.length)
            val failure = runCatching { reference(referenceSpans, counted) }
            assertTrue(failure.exceptionOrNull() is AssertionError)
        }
    }

    @Test
    fun codeSpanTakesTheLongestOpeningThatCloses() {
        // Three backticks open mid-line; no run of three follows, a run of two does: the span opens on two.
        val text = "x```a``b"
        assertEquals(listOf(1 until 7), ProtectedSpanScanner.codeAndLinkSpans(text))
        assertEquals(reference(referenceSpans, text), ProtectedSpanScanner.codeAndLinkSpans(text))
        // A blank line ends the search for a closing run.
        assertEquals(emptyList<IntRange>(), ProtectedSpanScanner.codeAndLinkSpans("`a\n \nb`"))
    }
}
