package dev.bugobliterator.itermbuddy.ui.conversation

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.bugobliterator.itermbuddy.core.chat.DEFUNCT_SESSION_TEXT
import dev.bugobliterator.itermbuddy.core.chat.Mention
import dev.bugobliterator.itermbuddy.core.chat.MentionDisplay
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.mentionDisplay

/** Where a run's link goes. Every [toString] shows identifiers and lengths only. */
sealed interface LinkTarget {
    /** A markdown link, an autolink or a bare URL. */
    data class Url(val url: String) : LinkTarget {
        override fun toString(): String = "Url(len=${url.length})"
    }

    /** A session mention, shown as its [name] after a terminal glyph; opens the session [guid]. */
    data class Session(val guid: String, val name: String) : LinkTarget {
        override fun toString(): String = "Session(guid=$guid, name(len=${name.length}))"
    }

    /** A workgroup mention, shown as its [name] after a terminal glyph; opens the member list. */
    data class Workgroup(val workgroupID: String, val name: String) : LinkTarget {
        override fun toString(): String = "Workgroup(workgroupID=$workgroupID, name(len=${name.length}))"
    }
}

/**
 * A stretch of message text with one style: the plain run model the renderer builds and the tests
 * check; [toAnnotatedString] is the thin Compose adapter. [toString] shows the length only.
 */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: LinkTarget? = null,
) {
    override fun toString(): String =
        "InlineRun(text(len=${text.length}), bold=$bold, italic=$italic, code=$code, strike=$strike, link=$link)"
}

/**
 * [text] as written, with each of [mentions] replaced as iOS renders it
 * (`IOS/Views/MessageBubbleView.swift:119-180`): a name linked to its session or workgroup when
 * the Mac named it, "[defunct session]" when it answered a miss, the mention as written while
 * [resolve] has no answer (null). For plain text, which iOS does not read as markdown (`:204-205`).
 */
fun plainRuns(
    text: String,
    mentions: List<Mention>,
    resolve: (String) -> MentionResolution?,
    atSignOptional: Boolean = true,
): List<InlineRun> {
    val runs = RunBuilder()
    var cursor = 0
    for (mention in mentions) {
        if (mention.start < cursor || mention.end > text.length) continue
        runs.add(text.substring(cursor, mention.start), Style.PLAIN, null)
        val (shown, target) = mentionRun(text, mention, resolve, atSignOptional)
        runs.add(shown, Style.PLAIN, target)
        cursor = mention.end
    }
    runs.add(text.substring(cursor), Style.PLAIN, null)
    return runs.build()
}

/**
 * [text] read as inline markdown, whitespace preserved (iOS's `inlineOnlyPreservingWhitespace`,
 * `IOS/Views/MessageBubbleView.swift:454-471`): `**bold**`/`__bold__`, `*italic*`/`_italic_`,
 * `` `code` ``, `~~strike~~`, `[text](url)`, `<scheme:…>` autolinks and bare `http(s)://` and
 * `www.` URLs, backslash escapes; a fenced block (three or more backticks) shows as code without
 * its fences and info string. Emphasis follows CommonMark's delimiter rules (flanking, the rule of
 * three), so an unmatched delimiter, a partial streaming chunk for one, shows as written.
 *
 * [mentions] are the protected ones (`MentionParser.protectedMentions`, with the "@" optional
 * for the agent's text): each is kept whole through the markdown pass, so a stable ID's "_" never
 * pairs up as emphasis (`MentionParser.swift:98-118`), takes the emphasis around it, and renders
 * as in [plainRuns]. With [atSignOptional] false (the owner's text) a bare stable ID is still kept
 * whole but shown as written, as iOS links only "@"-signed mentions (`MessageBubbleView.swift:119-121`).
 *
 * Linear in [text]'s length: every forward scan is bounded (a link destination stops at whitespace
 * or after [MAX_LINK_DESTINATION] characters) or remembered for the openers after it, so a hostile
 * or partial text costs a bounded number of steps per character ([inlineMarkdownSteps]). Anything
 * unexpected falls back to [plainRuns].
 */
fun inlineMarkdown(
    text: String,
    mentions: List<Mention>,
    resolve: (String) -> MentionResolution?,
    atSignOptional: Boolean = true,
): List<InlineRun> =
    try {
        InlineMarkdownParser(text, mentions, resolve, atSignOptional).parse()
    } catch (_: RuntimeException) {
        plainRuns(text, mentions, resolve, atSignOptional)
    }

/** How many scanning steps [inlineMarkdown] takes on [text]: the tests bound it by the length. */
internal fun inlineMarkdownSteps(text: String, mentions: List<Mention>): Long =
    InlineMarkdownParser(text, mentions, { null }, atSignOptional = true).also { it.parse() }.steps

/** The longest link destination read, as cmark bounds it: a longer one is text. */
const val MAX_LINK_DESTINATION = 2048

private fun mentionRun(
    text: String,
    mention: Mention,
    resolve: (String) -> MentionResolution?,
    atSignOptional: Boolean,
): Pair<String, LinkTarget?> {
    val raw = text.substring(mention.start, mention.end)
    if (!atSignOptional && !raw.startsWith('@')) return raw to null
    return when (val display = mentionDisplay(resolve(mention.identifier))) {
        is MentionDisplay.Named -> {
            val resolution = display.resolution
            val workgroup = resolution.workgroupID
            val target = if (workgroup != null) {
                LinkTarget.Workgroup(workgroup, display.displayName)
            } else {
                LinkTarget.Session(checkNotNull(resolution.sessionGuid), display.displayName)
            }
            display.displayName to target
        }
        MentionDisplay.Defunct -> DEFUNCT_SESSION_TEXT to null
        MentionDisplay.Raw -> raw to null
    }
}

private data class Style(val bold: Boolean, val italic: Boolean, val code: Boolean, val strike: Boolean) {
    companion object {
        val PLAIN = Style(bold = false, italic = false, code = false, strike = false)
    }
}

/** Joins adjacent stretches with the same style and link into one run; drops empty ones. */
private class RunBuilder {
    private val runs = ArrayList<InlineRun>()
    private val pending = StringBuilder()
    private var style = Style.PLAIN
    private var link: LinkTarget? = null

    fun add(text: String, style: Style, link: LinkTarget?) {
        if (text.isEmpty()) return
        if (pending.isNotEmpty() && (style != this.style || link != this.link)) flush()
        this.style = style
        this.link = link
        pending.append(text)
    }

    private fun flush() {
        if (pending.isEmpty()) return
        runs += InlineRun(pending.toString(), style.bold, style.italic, style.code, style.strike, link)
        pending.setLength(0)
    }

    fun build(): List<InlineRun> {
        flush()
        return runs
    }
}

/**
 * One pass over the text builds tokens (text, code, links, mention atoms and delimiter runs);
 * CommonMark's "process emphasis" then pairs the delimiter runs, each pair recorded as a token
 * range, and a prefix sum over the ranges gives each token its style. Single use.
 */
private class InlineMarkdownParser(
    private val text: String,
    private val mentions: List<Mention>,
    private val resolve: (String) -> MentionResolution?,
    private val atSignOptional: Boolean,
) {
    /** Scanning steps taken: every loop over the text counts one per character or delimiter it visits. */
    var steps = 0L
        private set

    private class Token(var text: String, val code: Boolean = false, var link: LinkTarget? = null)

    private class Delimiter(val token: Int, val char: Char, val originalCount: Int, val canOpen: Boolean, val canClose: Boolean) {
        var count = originalCount
        var previous: Delimiter? = null
        var next: Delimiter? = null
    }

    private class Bracket(val token: Int, val delimiterBelow: Delimiter?)

    private val tokens = ArrayList<Token>()
    private val delimiterTokens = HashMap<Int, Delimiter>()
    private var topDelimiter: Delimiter? = null
    private val brackets = ArrayList<Bracket>()

    /** The brackets below this stack depth are inactive: a link formed after them (no links in links). */
    private var activeFrom = 0

    /** The last [nextBreak] answer: from [breakFrom], the next whitespace or "<" is at [breakAt]. */
    private var breakFrom = -1
    private var breakAt = -1
    private val pending = StringBuilder()

    /** Emphasis pairs as token ranges (exclusive at both ends), by style. */
    private val boldRanges = ArrayList<IntArray>()
    private val italicRanges = ArrayList<IntArray>()
    private val strikeRanges = ArrayList<IntArray>()

    /** By length, the start of every backtick run, ascending; built on the first code span. */
    private var backtickRuns: Map<Int, IntArray>? = null

    /** The unescaped parentheses, built on the first link destination. */
    private var parens: ParenIndex? = null

    /** The last [nextDestinationBreak] answer, as for [nextBreak]. */
    private var destinationBreakFrom = -1
    private var destinationBreakAt = -1

    /** Per title closer, the last [nextTitleClose] answer: from `[0]`, the next one is at `[1]`. */
    private val titleCloses = HashMap<Char, IntArray>()

    fun parse(): List<InlineRun> {
        var index = 0
        var mention = 0
        while (index < text.length) {
            steps++
            while (mention < mentions.size && mentions[mention].start < index) mention++
            if (mention < mentions.size && mentions[mention].start == index && mentions[mention].end <= text.length) {
                val found = mentions[mention]
                val (shown, target) = mentionRun(text, found, resolve, atSignOptional)
                addToken(Token(shown, link = target))
                index = found.end
                continue
            }
            val nextMention = if (mention < mentions.size) mentions[mention].start else text.length
            index = when (text[index]) {
                '\\' -> escape(index)
                '`' -> codeSpan(index)
                '*', '_' -> delimiterRun(index, text[index])
                '~' -> tildes(index)
                '[' -> openBracket(index)
                ']' -> closeBracket(index)
                '<' -> autolink(index)
                'h', 'H', 'w', 'W' -> bareUrl(index, nextMention)
                else -> literal(index)
            }
        }
        processEmphasis(null)
        return runs()
    }

    private fun literal(index: Int): Int {
        pending.append(text[index])
        return index + 1
    }

    private fun flushText() {
        if (pending.isEmpty()) return
        tokens += Token(pending.toString())
        pending.setLength(0)
    }

    private fun addToken(token: Token): Int {
        flushText()
        tokens += token
        return tokens.size - 1
    }

    private fun escape(index: Int): Int {
        val next = text.getOrNull(index + 1)
        if (next != null && next.code < 128 && isAsciiPunctuation(next)) {
            pending.append(next)
            return index + 2
        }
        return literal(index)
    }

    private fun runLength(index: Int, char: Char): Int {
        var end = index
        while (end < text.length && text[end] == char) end++
        steps += end - index
        return end - index
    }

    private fun codeSpan(index: Int): Int {
        val length = runLength(index, '`')
        val contentStart = index + length
        val closer = findCodeCloser(contentStart, length)
        if (closer < 0) {
            pending.append(text, index, contentStart)
            return contentStart
        }
        addToken(Token(codeContent(text.substring(contentStart, closer), length), code = true))
        return closer + length
    }

    /**
     * The start of the next backtick run of exactly [length] from [from], or -1: a binary search
     * in the runs of that length, all found in one pass on the first code span.
     */
    private fun findCodeCloser(from: Int, length: Int): Int {
        val runs = backtickRuns ?: backtickRuns(text).also {
            backtickRuns = it
            steps += text.length
        }
        val starts = runs[length] ?: return -1
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            steps++
            val middle = (low + high) ushr 1
            if (starts[middle] >= from) {
                found = starts[middle]
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return found
    }

    /** A fence drops its info line and closing newline; a span drops one space from each end. */
    private fun codeContent(raw: String, fence: Int): String {
        var content = raw
        if (fence >= 3 && '\n' in content) {
            val firstLine = content.substringBefore('\n')
            if (firstLine.none(Char::isWhitespace) || firstLine.isBlank()) content = content.substringAfter('\n')
            if (content.endsWith('\n')) content = content.dropLast(1)
            return content
        }
        if (content.length >= 2 && content.first() == ' ' && content.last() == ' ' && content.isNotBlank()) {
            content = content.substring(1, content.length - 1)
        }
        return content
    }

    private fun tildes(index: Int): Int {
        val length = runLength(index, '~')
        // Only a pair of tildes is strikethrough; any other run is text.
        if (length != 2) {
            pending.append(text, index, index + length)
            return index + length
        }
        return delimiterRun(index, '~')
    }

    private fun delimiterRun(index: Int, char: Char): Int {
        val length = runLength(index, char)
        val before = if (index == 0) '\n' else text[index - 1]
        val after = if (index + length >= text.length) '\n' else text[index + length]
        val leftFlanking = !isSpace(after) && (!isPunctuation(after) || isSpace(before) || isPunctuation(before))
        val rightFlanking = !isSpace(before) && (!isPunctuation(before) || isSpace(after) || isPunctuation(after))
        val canOpen: Boolean
        val canClose: Boolean
        if (char == '_') {
            canOpen = leftFlanking && (!rightFlanking || isPunctuation(before))
            canClose = rightFlanking && (!leftFlanking || isPunctuation(after))
        } else {
            canOpen = leftFlanking
            canClose = rightFlanking
        }
        val token = addToken(Token(text.substring(index, index + length)))
        if (canOpen || canClose) {
            val delimiter = Delimiter(token, char, length, canOpen, canClose)
            delimiter.previous = topDelimiter
            topDelimiter?.next = delimiter
            topDelimiter = delimiter
            delimiterTokens[token] = delimiter
        }
        return index + length
    }

    private fun openBracket(index: Int): Int {
        val token = addToken(Token("["))
        brackets += Bracket(token, topDelimiter)
        return index + 1
    }

    private fun closeBracket(index: Int): Int {
        val opener = brackets.removeLastOrNull() ?: return literal(index)
        val active = brackets.size >= activeFrom
        activeFrom = minOf(activeFrom, brackets.size)
        if (!active) return literal(index)
        val destination = linkDestination(index + 1) ?: return literal(index)
        flushText()
        val end = tokens.size
        processEmphasis(opener.delimiterBelow)
        tokens[opener.token].text = ""
        val target = LinkTarget.Url(destination.first)
        // The link's target covers its text, a bare URL in it included (iOS); a mention keeps its own.
        for (inside in opener.token + 1 until end) {
            steps++
            val link = tokens[inside].link
            if (link == null || link is LinkTarget.Url) tokens[inside].link = target
        }
        // No link inside a link: every earlier opener becomes text.
        activeFrom = brackets.size
        return destination.second
    }

    /**
     * `(destination "title")` at [start]: the destination and the index after ")", or null. A
     * bare destination ends at its ")" at depth zero (found through [ParenIndex], not by
     * scanning) or at whitespace, within [MAX_LINK_DESTINATION]; a `<…>` one ends at ">" within
     * the same bound, never past a newline or "<"; a title's closer is found by [nextTitleClose].
     */
    private fun linkDestination(start: Int): Pair<String, Int>? {
        if (text.getOrNull(start) != '(') return null
        var at = skipSpaces(start + 1)
        val destination: String
        if (text.getOrNull(at) == '<') {
            val limit = minOf(text.length, at + 1 + MAX_LINK_DESTINATION)
            var close = at + 1
            while (close < limit && text[close] != '>' && text[close] != '<' && text[close] != '\n') {
                steps++
                close++
            }
            if (close >= limit || text[close] != '>') return null
            destination = text.substring(at + 1, close)
            at = close + 1
        } else {
            val begin = at
            val stop = nextDestinationBreak(begin)
            val index = parens ?: ParenIndex(text).also {
                parens = it
                steps += text.length
            }
            val level = index.levelBefore(begin)
            val close = index.firstCloseAt(level, begin)
            val end = if (close in begin until stop) {
                close
            } else if (index.levelBefore(stop) == level) {
                stop
            } else {
                return null
            }
            steps += 2
            if (end - begin > MAX_LINK_DESTINATION) return null
            destination = text.substring(begin, end)
            at = end
        }
        if (destination.isEmpty()) return null
        at = skipSpaces(at)
        val quote = text.getOrNull(at)
        if (quote == '"' || quote == '\'' || quote == '(') {
            val close = nextTitleClose(if (quote == '(') ')' else quote, at + 1)
            if (close < 0) return null
            at = skipSpaces(close + 1)
        }
        if (text.getOrNull(at) != ')') return null
        return destination to at + 1
    }

    /** The next whitespace or control character at or after [from], remembered for the run it ends. */
    private fun nextDestinationBreak(from: Int): Int {
        if (from >= destinationBreakFrom && from <= destinationBreakAt) return destinationBreakAt
        var at = from
        while (at < text.length && !text[at].isWhitespace() && text[at].code >= 0x20) {
            steps++
            at++
        }
        destinationBreakFrom = from
        destinationBreakAt = at
        return at
    }

    /** The next [closer] at or after [from], or -1; remembered, so the openers before it share one scan. */
    private fun nextTitleClose(closer: Char, from: Int): Int {
        val known = titleCloses[closer]
        if (known != null && from >= known[0] && from <= known[1]) return if (known[1] < text.length) known[1] else -1
        var at = from
        while (at < text.length && text[at] != closer) {
            steps++
            at++
        }
        titleCloses[closer] = intArrayOf(from, at)
        return if (at < text.length) at else -1
    }

    private fun skipSpaces(from: Int): Int {
        var at = from
        var newlines = 0
        while (at < text.length && (text[at] == ' ' || text[at] == '\t' || text[at] == '\n')) {
            steps++
            if (text[at] == '\n' && ++newlines > 1) break
            at++
        }
        return at
    }

    private fun autolink(index: Int): Int {
        var at = index + 1
        val schemeStart = at
        while (at < text.length && (text[at].isAsciiLetterOrDigit() || text[at] in "+.-")) {
            steps++
            at++
        }
        val schemeLength = at - schemeStart
        if (schemeLength !in 2..32 || !text[schemeStart].isAsciiLetter() || text.getOrNull(at) != ':') return literal(index)
        while (at < text.length && text[at] != '>' && text[at] != '<' && text[at] > ' ') {
            steps++
            at++
        }
        if (text.getOrNull(at) != '>') return literal(index)
        val url = text.substring(index + 1, at)
        addToken(Token(url, link = LinkTarget.Url(url)))
        return at + 1
    }

    private fun bareUrl(index: Int, limit: Int): Int {
        val before = if (index == 0) ' ' else text[index - 1]
        if (!(isSpace(before) || before in "*_~(")) return literal(index)
        val www = text.regionMatches(index, "www.", 0, 4, ignoreCase = true)
        val hostStart = when {
            text.regionMatches(index, "https://", 0, 8, ignoreCase = true) -> index + 8
            text.regionMatches(index, "http://", 0, 7, ignoreCase = true) -> index + 7
            www -> index
            else -> return literal(index)
        }
        var end = minOf(nextBreak(hostStart), limit)
        var hostEnd = hostStart
        while (hostEnd < end && (text[hostEnd].isAsciiLetterOrDigit() || text[hostEnd] in "._-:")) {
            steps++
            hostEnd++
        }
        // After a valid host the URL runs to whitespace or "<" (GFM), then sheds trailing punctuation.
        if (!validHost(hostStart, hostEnd, www)) return literal(index)
        // Trailing punctuation, and a ")" without its "(", belong to the sentence.
        var opens = 0
        var closes = 0
        for (at in index until end) {
            steps++
            if (text[at] == '(') opens++ else if (text[at] == ')') closes++
        }
        while (end > hostStart) {
            steps++
            val last = text[end - 1]
            if (last in "?!.,:*_~'\"") {
                end--
            } else if (last == ')' && closes > opens) {
                end--
                closes--
            } else {
                break
            }
        }
        if (!validHost(hostStart, minOf(hostEnd, end), www)) return literal(index)
        val shown = text.substring(index, end)
        addToken(Token(shown, link = LinkTarget.Url(if (www) "http://$shown" else shown)))
        return end
    }

    /** A host is not empty; a `www.` one has something after "www." (GFM links `www.foo`). */
    private fun validHost(start: Int, end: Int, www: Boolean): Boolean = end > start && (!www || end - start > 4)

    /** The next whitespace or "<" at or after [from] (or the end), remembered for the run it ends. */
    private fun nextBreak(from: Int): Int {
        if (from >= breakFrom && from <= breakAt) return breakAt
        var at = from
        while (at < text.length && !isSpace(text[at]) && text[at] != '<') {
            steps++
            at++
        }
        breakFrom = from
        breakAt = at
        return at
    }

    /** CommonMark's "process emphasis" over the delimiters above [bottom], which are then removed. */
    private fun processEmphasis(bottom: Delimiter?) {
        var closer = topDelimiter
        if (closer == null || closer === bottom) return
        while (closer!!.previous != null && closer.previous !== bottom) {
            steps++
            closer = closer.previous
        }
        val openersBottom = HashMap<Int, Delimiter?>()
        var current: Delimiter? = closer
        while (current != null) {
            steps++
            if (!current.canClose) {
                current = current.next
                continue
            }
            val key = current.char.code * 8 + (if (current.canOpen) 4 else 0) + current.originalCount % 3
            val stop = if (openersBottom.containsKey(key)) openersBottom[key] else bottom
            var opener = current.previous
            while (opener != null && opener !== bottom && opener !== stop) {
                steps++
                if (opener.char == current.char && opener.canOpen && opener.count > 0 && pairs(opener, current)) break
                opener = opener.previous
            }
            if (opener == null || opener === bottom || opener === stop) {
                openersBottom[key] = current.previous
                val next = current.next
                if (!current.canOpen) unlink(current)
                current = next
                continue
            }
            val use = if (current.char == '~' || (opener.count >= 2 && current.count >= 2)) 2 else 1
            val range = intArrayOf(opener.token, current.token)
            when {
                current.char == '~' -> strikeRanges += range
                use == 2 -> boldRanges += range
                else -> italicRanges += range
            }
            opener.count -= use
            current.count -= use
            // The delimiters between the pair stay text.
            opener.next = current
            current.previous = opener
            if (opener.count == 0) unlink(opener)
            if (current.count == 0) {
                val next = current.next
                unlink(current)
                current = next
            }
        }
        // Whatever is left above the bottom is text.
        if (bottom == null) {
            topDelimiter = null
        } else {
            bottom.next = null
            topDelimiter = bottom
        }
    }

    /** The rule of three, and a strikethrough pairs only with a run of its own length. */
    private fun pairs(opener: Delimiter, closer: Delimiter): Boolean {
        if (closer.char == '~') return opener.originalCount == closer.originalCount
        val sum = opener.originalCount + closer.originalCount
        return !((opener.canClose || closer.canOpen) && sum % 3 == 0 && !(opener.originalCount % 3 == 0 && closer.originalCount % 3 == 0))
    }

    private fun unlink(delimiter: Delimiter) {
        delimiter.previous?.next = delimiter.next
        delimiter.next?.previous = delimiter.previous
        if (topDelimiter === delimiter) topDelimiter = delimiter.previous
    }

    private fun runs(): List<InlineRun> {
        flushText()
        val bold = depths(boldRanges)
        val italic = depths(italicRanges)
        val strike = depths(strikeRanges)
        val builder = RunBuilder()
        steps += 2L * tokens.size
        for ((index, token) in tokens.withIndex()) {
            // A delimiter run shows the characters no pair used.
            val shown = delimiterTokens[index]?.let { it.char.toString().repeat(it.count) } ?: token.text
            builder.add(shown, Style(bold[index] > 0, italic[index] > 0, token.code, strike[index] > 0), token.link)
        }
        return builder.build()
    }

    private fun depths(ranges: List<IntArray>): IntArray {
        val depth = IntArray(tokens.size + 1)
        for (range in ranges) {
            depth[range[0] + 1]++
            depth[range[1]]--
        }
        for (index in 1 until depth.size) depth[index] += depth[index - 1]
        return depth
    }
}

/** By length, the start of every run of backticks in [text], ascending. */
private fun backtickRuns(text: String): Map<Int, IntArray> {
    val runs = HashMap<Int, ArrayList<Int>>()
    var at = 0
    while (at < text.length) {
        if (text[at] != '`') {
            at++
            continue
        }
        val start = at
        while (at < text.length && text[at] == '`') at++
        runs.getOrPut(at - start) { ArrayList() } += start
    }
    return runs.mapValues { it.value.toIntArray() }
}

/**
 * The text's unescaped parentheses in one pass, so a link destination's closing ")" (the first ")"
 * at the destination's own depth) is a binary search, never a scan per opener.
 */
private class ParenIndex(text: String) {
    private val positions: IntArray
    private val levels: IntArray
    private val isOpen: BooleanArray

    /** By depth before it: the positions of the ")"s at that depth, ascending. */
    private val closesByLevel = HashMap<Int, IntArray>()

    init {
        val found = ArrayList<Int>()
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '\\' && at + 1 < text.length && isAsciiPunctuation(text[at + 1])) {
                at += 2
                continue
            }
            if (char == '(' || char == ')') found += at
            at++
        }
        positions = found.toIntArray()
        levels = IntArray(positions.size)
        val closes = HashMap<Int, ArrayList<Int>>()
        var level = 0
        for ((index, position) in positions.withIndex()) {
            levels[index] = level
            if (text[position] == '(') {
                level++
            } else {
                closes.getOrPut(level) { ArrayList() } += position
                level--
            }
        }
        for ((depth, list) in closes) closesByLevel[depth] = list.toIntArray()
        isOpen = BooleanArray(positions.size) { text[positions[it]] == '(' }
    }

    /** The depth just before [position]: opens minus closes before it. */
    fun levelBefore(position: Int): Int {
        val last = lastBefore(positions, position)
        if (last < 0) return 0
        return levels[last] + if (isOpen[last]) 1 else -1
    }

    /** The first ")" at or after [from] whose depth before it is [level], or -1. */
    fun firstCloseAt(level: Int, from: Int): Int {
        val closes = closesByLevel[level] ?: return -1
        val index = lastBefore(closes, from) + 1
        return if (index < closes.size) closes[index] else -1
    }

    /** The index of the last element of [sorted] below [value], or -1. */
    private fun lastBefore(sorted: IntArray, value: Int): Int {
        var low = 0
        var high = sorted.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (sorted[middle] < value) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

private fun isAsciiPunctuation(char: Char): Boolean = char in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

/** Unicode whitespace as CommonMark reads it (the text's ends count as whitespace too). */
private fun isSpace(char: Char): Boolean = char.isWhitespace() || Character.getType(char) == Character.SPACE_SEPARATOR.toInt()

/** Unicode punctuation or symbol (CommonMark 0.31). */
private fun isPunctuation(char: Char): Boolean {
    if (char.code < 128) return isAsciiPunctuation(char)
    return when (Character.getType(char).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }
}

/** The inline-content id the adapter puts before a mention's name: a small terminal glyph. */
const val MENTION_GLYPH_ID = "mention-terminal-glyph"

/** URL schemes a tap opens; any other link is styled but not opened. */
private val OPENABLE_SCHEMES = listOf("http://", "https://", "mailto:")

/** Whether a tap on a link to [url] opens it: http, https and mailto only (never `javascript:`, `intent:`, `file:`). */
fun isOpenableUrl(url: String): Boolean = OPENABLE_SCHEMES.any { url.startsWith(it, ignoreCase = true) }

/**
 * The runs as Compose text: bold, italic, monospace code on [codeBackground], strikethrough;
 * links underlined in [linkColor]. A mention gets the terminal glyph ([MENTION_GLYPH_ID] inline
 * content) and a thin space before its name, as iOS and the Mac show it, and calls [onMention]
 * when tapped; a web link opens in the browser. Not unit-tested: a thin adapter over [InlineRun].
 */
fun List<InlineRun>.toAnnotatedString(
    linkColor: Color,
    codeBackground: Color,
    onMention: (LinkTarget) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (run in this@toAnnotatedString) {
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.SemiBold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBackground else Color.Unspecified,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
        )
        val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        when (val link = run.link) {
            null -> withStyle(style) { append(run.text) }
            is LinkTarget.Url -> if (isOpenableUrl(link.url)) {
                withLink(LinkAnnotation.Url(link.url, linkStyles)) { withStyle(style) { append(run.text) } }
            } else {
                withStyle(style.merge(linkStyles.style ?: SpanStyle())) { append(run.text) }
            }
            is LinkTarget.Session, is LinkTarget.Workgroup -> {
                appendInlineContent(MENTION_GLYPH_ID, "terminal")
                append(' ')
                withLink(LinkAnnotation.Clickable("mention", linkStyles) { onMention(link) }) {
                    withStyle(style) { append(run.text) }
                }
            }
        }
    }
}
