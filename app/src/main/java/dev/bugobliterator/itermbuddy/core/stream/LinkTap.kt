package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A link found under a tap on the terminal: [url] normalised ([linkFromSelection]), and whether
 * it names the Mac itself ([macLocal], [isMacLocalLink]), so only the Mac can open it.
 * Its string form carries the URL's length only.
 */
data class TappedLink(val url: String, val macLocal: Boolean) {
    override fun toString(): String = "TappedLink(url(len=${url.length}), macLocal=$macLocal)"
}

/** Longer than any link worth a bubble; a selection or token this long is not one. */
internal const val MAX_LINK_CHARS = 4096

/** Trailing punctuation a sentence leaves after a link (`)` and `]` only when unbalanced). */
private const val TRAILING_PUNCTUATION = ".,;:)]>'\""

/** What may open a wrap around a link: dropped from the front (`<…>`, `(…)`, quotes). */
private const val LEADING_WRAP = "<(['\""

private val SCHEMES = listOf("http://", "https://", "ftp://")

/**
 * The link in [text], the Mac's smart selection under a tap, or null when it is not one: trimmed,
 * unwrapped from `<…>` or `(…)` or quotes, trailing sentence punctuation (`.,;:)]>'"`) dropped
 * (`)` and `]` only while unbalanced, so `…/Foo_(bar)` keeps its own), then an `http`, `https` or
 * `ftp` URL with a host, or a bare `www.` host, which becomes `https://`. Rejected: whitespace,
 * control or Unicode format characters (`Cf`: bidi overrides, zero-width joiners, which would make
 * the bubble show one link and open another), a backslash anywhere (WHATWG reads it as `/` in
 * these schemes, so `http://a\@localhost` is host `a`), and any authority [linkHost] refuses
 * (user info, `%`). The scheme is lowercased; the rest is kept as the Mac gave it.
 */
fun linkFromSelection(text: String): String? {
    var s = text.trim()
    if (s.isEmpty() || s.length > MAX_LINK_CHARS) return null
    while (true) {
        val before = s
        s = s.trimStart { it in LEADING_WRAP }
        while (s.isNotEmpty() && s.last() in TRAILING_PUNCTUATION && trailingDroppable(s)) s = s.dropLast(1)
        if (s == before) break
    }
    if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it == '\\' }) return null
    val scheme = SCHEMES.firstOrNull { s.startsWith(it, ignoreCase = true) }
    val url = when {
        scheme != null -> scheme + s.substring(scheme.length)
        s.startsWith("www.", ignoreCase = true) -> "https://$s"
        else -> return null
    }
    val host = linkHost(url) ?: return null
    if (host.startsWith('.') || ".." in host) return null
    return url
}

/** Whether [s]'s last character may go: `)` and `]` only when [s] closes more than it opens. */
private fun trailingDroppable(s: String): Boolean = when (val last = s.last()) {
    ')' -> s.count { it == ')' } > s.count { it == '(' }
    ']' -> s.count { it == ']' } > s.count { it == '[' }
    else -> last in TRAILING_PUNCTUATION
}

/**
 * The host of [url] (`scheme://host[:port]/…`), lowercased, without an IPv6 literal's brackets or
 * a trailing dot; null when there is a backslash anywhere (WHATWG reads it as `/`), no `://`,
 * the host is missing, the port is not digits, or the authority (up to the first `/`, `?` or `#`)
 * has user info (`@`), a `%` escape or any character no host name or IP literal has. Refusing rather than parsing user
 * info and escapes keeps this host the one a browser would open.
 */
fun linkHost(url: String): String? {
    if ('\\' in url) return null
    val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val authority = url.substring(start, end)
    if ('@' in authority || '%' in authority) return null
    val host: String
    val port: String?
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(1, close)
        val rest = authority.substring(close + 1)
        if (rest.isNotEmpty() && !rest.startsWith(':')) return null
        port = rest.removePrefix(":").takeIf { rest.isNotEmpty() }
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return null
    } else {
        host = authority.substringBefore(':')
        port = authority.substringAfter(':', "").takeIf { ':' in authority }
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return null
    }
    if (port != null && (port.isEmpty() || port.any { !it.isDigit() })) return null
    return host.lowercase().removeSuffix(".")
}

/**
 * Whether [url] names the Mac itself, so the phone cannot open it: `localhost` (and
 * `*.localhost`), an IPv4 address in `127.0.0.0/8` or `0.0.0.0` in any form a browser reads
 * ([whatwgIpv4]: `127.1`, `0x7f.0.0.1`, `2130706433`), `::1`, `::ffff:127.x.y.z`, or one of
 * [macHosts] (the Mac's own names, with or without `.local`; none are known today).
 */
fun isMacLocalLink(url: String, macHosts: Set<String> = emptySet()): Boolean {
    val host = linkHost(url) ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return true
    if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    if (isLoopbackV4(host) || (host.startsWith("::ffff:") && isLoopbackV4(host.removePrefix("::ffff:")))) return true
    val names = macHosts.map { it.lowercase().removeSuffix(".").removeSuffix(".local") }.filter { it.isNotEmpty() }
    return host.removeSuffix(".local") in names
}

/** An IPv4 host ([whatwgIpv4]) in 127.0.0.0/8, or 0.0.0.0. */
private fun isLoopbackV4(host: String): Boolean {
    val address = whatwgIpv4(host) ?: return false
    return address == 0L || address ushr 24 == 127L
}

/**
 * [host] as WHATWG's IPv4 parser reads it, or null when it is not an IPv4 address: one to four
 * dot-separated numbers (decimal, `0x` hex, or octal with a leading 0), the last filling the
 * remaining bytes, so `127.1` is 127.0.0.1 and `2130706433` is too. Null when a number is too big.
 */
internal fun whatwgIpv4(host: String): Long? {
    val parts = host.split('.')
    if (parts.isEmpty() || parts.size > 4) return null
    val numbers = parts.map { part ->
        when {
            part.isEmpty() -> return null
            part.startsWith("0x") || part.startsWith("0X") -> part.substring(2).let { if (it.isEmpty()) 0L else it.toLongOrNullBounded(16) }
            part.length > 1 && part.startsWith('0') -> part.substring(1).toLongOrNullBounded(8)
            else -> part.toLongOrNullBounded(10)
        } ?: return null
    }
    if (numbers.dropLast(1).any { it > 255 }) return null
    val lastLimit = 1L shl (8 * (5 - numbers.size))
    if (numbers.last() >= lastLimit) return null
    var address = numbers.last()
    numbers.dropLast(1).forEachIndexed { index, n -> address += n shl (8 * (3 - index)) }
    return address
}

/** [this] in [radix], or null when it is not one or longer than an IPv4 part can be. */
private fun String.toLongOrNullBounded(radix: Int): Long? = if (length > 12) null else toLongOrNull(radix)?.takeIf { it >= 0 }

/** One link in a line of text: [url] ([linkFromSelection]) found at string indices [start] until [end]. */
data class LineLink(val start: Int, val end: Int, val url: String) {
    override fun toString(): String = "LineLink(start=$start, end=$end, url(len=${url.length}))"
}

/** Ends a link token in a line: whitespace and characters no URL holds unescaped. */
private const val TOKEN_STOPS = "\"<>`{}|^"

/**
 * Every link in [line]: tokens starting at an `http://`, `https://` or `ftp://` (not inside a
 * word) or a bare `www.` (not after a host or path character), running to whitespace or one of
 * `"<>`{}|^`, each cleaned by [linkFromSelection] (trailing punctuation, unbalanced brackets) and
 * kept when it is a link. [LineLink.start] and [LineLink.end] span the link as written in [line]
 * (a bare `www.` without the `https://` it gains).
 */
fun linksInLine(line: String): List<LineLink> {
    val found = ArrayList<LineLink>()
    var i = 0
    while (i < line.length) {
        val scheme = SCHEMES.firstOrNull { line.startsWith(it, i, ignoreCase = true) }
        val previous = if (i > 0) line[i - 1] else ' '
        val starts = when {
            scheme != null -> !previous.isLetterOrDigit()
            line.startsWith("www.", i, ignoreCase = true) -> !(previous.isLetterOrDigit() || previous in ".-/:@_")
            else -> false
        }
        if (!starts) {
            i++
            continue
        }
        var end = i
        while (end < line.length && !line[end].isWhitespace() && line[end] !in TOKEN_STOPS) end++
        val url = linkFromSelection(line.substring(i, end))
        if (url != null) {
            val written = if (scheme == null) url.length - "https://".length else url.length
            found += LineLink(i, i + written, url)
        }
        i = maxOf(end, i + 1)
    }
    return found
}

/**
 * Cells a code point takes on a terminal grid, as best as can be told without the font: 0 for
 * combining and format characters, 2 for East Asian wide (W and F) ranges and the emoji with
 * default emoji presentation (East Asian Width W), else 1. A tab is placed by the walkers
 * ([indexAtCell], [cellsOf]), not here.
 */
internal fun cellWidth(codePoint: Int): Int {
    val type = Character.getType(codePoint)
    if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.FORMAT.toInt()) return 0
    return if (WIDE.any { codePoint in it }) 2 else 1
}

/** East Asian Width W and F, and the emoji-presentation symbols that are W. */
private val WIDE: List<IntRange> = listOf(
    0x1100..0x115F, 0x231A..0x231B, 0x2329..0x232A, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3,
    0x25FD..0x25FE, 0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
    0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4, 0x26EA..0x26EA,
    0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD, 0x2705..0x2705, 0x270A..0x270B,
    0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E, 0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797,
    0x27B0..0x27B0, 0x27BF..0x27BF, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
    0x2E80..0x303E, 0x3041..0xA4CF, 0xAC00..0xD7A3, 0xF900..0xFAFF, 0xFE10..0xFE19, 0xFE30..0xFE6F,
    0xFF00..0xFF60, 0xFFE0..0xFFE6,
    0x1F004..0x1F004, 0x1F0CF..0x1F0CF, 0x1F18E..0x1F18E, 0x1F191..0x1F19A, 0x1F1E6..0x1F1FF,
    0x1F200..0x1F202, 0x1F210..0x1F23B, 0x1F240..0x1F248, 0x1F250..0x1F251, 0x1F300..0x1F64F,
    0x1F680..0x1F6FF, 0x1F7E0..0x1F7EB, 0x1F90C..0x1F9FF, 0x1FA70..0x1FAFF, 0x20000..0x3FFFD,
)

/**
 * Whether [codePoint]'s width is not certain: anything not one cell ([cellWidth]), any other
 * symbol (`So`: a font or a variation selector can make it two), and anything above the BMP.
 */
internal fun uncertainWidth(codePoint: Int): Boolean =
    cellWidth(codePoint) != 1 || Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt() || codePoint > 0xFFFF

/** Tab stops every this many cells, iTerm2's default. */
internal const val TAB_STOP = 8

/** The cell after one at [cells] advanced by [codePoint]: a tab to the next tab stop, else its [cellWidth]. */
private fun advance(cells: Int, codePoint: Int): Int =
    if (codePoint == '\t'.code) (cells / TAB_STOP + 1) * TAB_STOP else cells + cellWidth(codePoint)

/**
 * Where cell [cell] of [line] falls: its string index (past the end, the length plus the blank
 * cells beyond), and how many code points before it have an [uncertainWidth] (each may put the
 * index a cell off). A tab covers the cells up to the next tab stop.
 */
internal fun indexAtCell(line: String, cell: Int): Pair<Int, Int> {
    var cells = 0
    var i = 0
    var uncertain = 0
    while (i < line.length) {
        val codePoint = line.codePointAt(i)
        val next = advance(cells, codePoint)
        if (cell < next && next > cells) return i to uncertain
        if (uncertainWidth(codePoint)) uncertain++
        cells = next
        i += Character.charCount(codePoint)
    }
    // Past the end: one index per blank cell beyond it, so a tap far right of a link is far from it.
    return line.length + (cell - cells) to uncertain
}

/** [line]'s width in cells (tabs to their stops). */
internal fun cellsOf(line: String): Int {
    var cells = 0
    var i = 0
    while (i < line.length) {
        val codePoint = line.codePointAt(i)
        cells = advance(cells, codePoint)
        i += Character.charCount(codePoint)
    }
    return cells
}

/**
 * [text] as a grid line: each NUL (a cell never written) becomes a space, so cells keep their
 * places, and trailing whitespace (spaces, `\r`, `\n`) is dropped.
 */
internal fun gridLine(text: String): String = text.replace('\u0000', ' ').trimEnd()

/**
 * The link under the tap at [point] in [text], the Mac's whole (soft-wrapped, logical) line there
 * ([gridLine] first), or null. The tapped cell's offset into the line is its rows below the line's
 * first row (from [span], the Mac's report of that selection, when it covers [point]) times
 * [columns], plus its column (a whole line starts at column 0, whatever column the report gives).
 * With no usable span, a line longer than a row could start on any of its rows above the tap, so
 * each row's offset (`k × columns + column`) is tried. Each offset becomes a string index through
 * [indexAtCell]; a link counts only when it is within [SURE_SLACK_CELLS] of it, plus one for each
 * code point of uncertain width before it. The nearest link that counts is taken. A tap never
 * takes a link far from it, however unsure the mapping.
 */
fun linkAtTap(text: String, point: SelectionPoint, span: SelectionSpan?, columns: Int): String? {
    val line = gridLine(text)
    val links = linksInLine(line)
    if (links.isEmpty()) return null
    val usableSpan = span?.takeIf { point.absLine in it.start.absLine..it.end.absLine && columns > 0 }
    val lineCells = cellsOf(line)
    val cells: List<Long> = when {
        usableSpan != null -> listOf((point.absLine - usableSpan.start.absLine) * columns + point.column)
        columns > 0 && lineCells > columns -> (0..(lineCells - 1) / columns).map { k -> k.toLong() * columns + point.column }
        else -> listOf(point.column.toLong())
    }
    var best: Pair<Int, String>? = null
    for (cell in cells) {
        if (cell < 0 || cell > Int.MAX_VALUE) continue
        val (index, uncertain) = indexAtCell(line, cell.toInt())
        for (link in links) {
            val distance = when {
                index < link.start -> link.start - index
                index >= link.end -> index - link.end + 1
                else -> 0
            }
            if (distance <= SURE_SLACK_CELLS + uncertain && (best == null || distance < best.first)) best = distance to link.url
        }
    }
    return best?.second
}

/** How far off a sure tap may land and still take a link: rounding, a cell's edge, a wrap's seam. */
const val SURE_SLACK_CELLS = 3

/**
 * Finds the link under a tap on a terminal stream with the Mac's help (the phone has video, no
 * text), through the app's [selection] driver (one ordered queue with the long-press selection):
 * `selectionGesture` begin and end in [SelectionMode.LINE] at the tapped cell (iTerm2's whole
 * line: the soft-wrapped logical line, `kiTermSelectionModeWholeLine`), `copySelection`, and the
 * link covering the tapped cell in that text ([linkAtTap], placed with the Mac's selection report,
 * [SelectionDriver.range]). Failing that, a [SelectionMode.SMART] selection at the cell, taken
 * only when it is a whole link (iTerm2's smart selection stops at the scheme's `:`, so it often
 * gives `example.com/path`, which is not). Then `clearSelection`. The Mac's selection is replaced
 * and then cleared.
 *
 * - **Bounded.** Everything is bounded by [timeout] together: the line copy by 60 % of it (its
 *   request too, so a late reply holds the queue no longer; past it, or with a line longer than
 *   64 K characters, the smart fallback runs), the smart copy by what is left; late, it finds
 *   nothing. A gesture the owner begins meanwhile ends the lookup with nothing.
 * - **Only when free.** Nothing is sent while the Mac reports a selection ([SelectionDriver.range])
 *   or when the active stream is not [sessionGuid]'s terminal stream.
 * - **Clear.** Always queued at the end (also on a timeout, a failure or a cancel), unless
 *   another gesture began meanwhile (a long press: its selection is the owner's). The selection
 *   is the session's, so when the stream changed it still goes: through the active stream when
 *   that is still [sessionGuid]'s (a restart or reconnect), else for the old stream on its own
 *   link ([SelectionDriver.clearStream], best effort: a stream the Mac already stopped, as after
 *   Back, drops it, and a link that ended fails it, logged).
 * - **Stale.** A stream change during the lookup finds nothing.
 *
 * Logs lengths and yes/no only, never the selected text or the link.
 */
class LinkLookup(
    private val selection: SelectionDriver,
    private val active: StateFlow<ActiveStream?>,
    private val timeout: Duration = DEFAULT_LINK_LOOKUP_TIMEOUT,
    private val macHosts: () -> Set<String> = { emptySet() },
    private val log: (String) -> Unit = {},
    /** The clock the bound is measured on; tests pass the scheduler's. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The link under terminal [point] of [sessionGuid]'s stream, or null (see the class comment). */
    suspend fun lookup(sessionGuid: String, point: SelectionPoint): TappedLink? {
        val stream = active.value?.takeIf { it.sessionGuid == sessionGuid && it.config?.isBrowser != true } ?: return null
        if (selection.range.value != null) return null
        val columns = stream.config?.columns ?: 0
        fun sameStream(): Boolean = active.value.let { it != null && it.linkId == stream.linkId && it.streamID == stream.streamID }
        // Begin and end with no suspension between them: nothing can slip into the gesture.
        if (!selection.begin(point, SelectionMode.LINE)) return null
        var begun = selection.gesturesBegun()
        selection.end(point)
        var lineChars = -1
        var smartChars = -1
        var url: String? = null
        val started = timeSource.markNow()
        try {
            url = withTimeoutOrNull(timeout) {
                // The line gets its share of the bound, so a slow (long) line leaves time for smart.
                val lineBound = timeout * LINE_SHARE
                val line = withTimeoutOrNull(lineBound) { selection.copy(sessionGuid, lineBound) }
                // A gesture the owner began meanwhile owns the selection (and its report): stop.
                if (selection.gesturesBegun() != begun) return@withTimeoutOrNull null
                if (line != null) {
                    lineChars = line.length
                    if (line.length <= MAX_LINE_CHARS) linkAtTap(line, point, selection.range.value, columns)?.let { return@withTimeoutOrNull it }
                }
                if (!sameStream()) return@withTimeoutOrNull null
                val left = timeout - started.elapsedNow()
                if (!left.isPositive()) return@withTimeoutOrNull null
                if (!selection.begin(point, SelectionMode.SMART)) return@withTimeoutOrNull null
                begun = selection.gesturesBegun()
                selection.end(point)
                val smart = selection.copy(sessionGuid, left) ?: return@withTimeoutOrNull null
                smartChars = smart.length
                linkFromSelection(smart)
            }
        } finally {
            val now = active.value
            when {
                selection.gesturesBegun() != begun -> log("link lookup: a selection began meanwhile; no clear")
                now != null && now.sessionGuid == sessionGuid -> selection.clear()
                else -> {
                    log("link lookup: the stream changed; clearing on the old stream ${stream.streamID}")
                    selection.clearStream(stream.linkId, stream.streamID)
                }
            }
        }
        if (!sameStream()) return null
        val link = url?.let { TappedLink(it, isMacLocalLink(it, macHosts())) }
        val answered = if (lineChars < 0) "no answer" else "line $lineChars chars" + (if (smartChars >= 0) ", smart $smartChars chars" else "")
        log("link lookup: $answered, ${if (link == null) "no link" else "a link of ${link.url.length} chars${if (link.macLocal) ", Mac-local" else ""}"}")
        return link
    }

    private companion object {
        /**
         * A line longer than this is not searched (a minified file): only the smart selection is
         * tried. The protocol cannot bound what `copySelection` sends, so such a line still
         * crosses the link; [LINE_SHARE] keeps a slow one from using the whole bound.
         */
        const val MAX_LINE_CHARS = 65_536

        /** The line copy's share of the bound; the smart fallback gets what is left. */
        const val LINE_SHARE = 0.6
    }
}

/** How long a tap waits for the Mac's answer before showing nothing. */
val DEFAULT_LINK_LOOKUP_TIMEOUT: Duration = 1.seconds
