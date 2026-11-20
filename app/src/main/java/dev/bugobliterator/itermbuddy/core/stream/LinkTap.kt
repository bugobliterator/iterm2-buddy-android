package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A link found under a tap on the terminal: [url] normalised ([linkFromSelection]), and whether
 * it names the Mac itself ([macLocal], [isMacLocalLink]), so only the Mac can open it.
 * Its string form carries the URL's length only.
 */
data class TappedLink(val url: String, val macLocal: Boolean) {
    override fun toString(): String = "TappedLink(url(len=${url.length}), macLocal=$macLocal)"
}

/** Longer than any link worth a bubble; a smart selection this long is not one. */
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

/**
 * Finds the link under a tap on a terminal stream, with the Mac's own smart selection (the phone
 * has video, no text): `selectionGesture` begin and end in [SelectionMode.SMART] at the tapped
 * cell, `copySelection`, then `clearSelection`, all through the app's [selection] driver (one
 * ordered queue with the long-press selection). The Mac's selection is replaced and then cleared.
 *
 * - **Bounded.** The copy is bounded by [timeout] (its request too, so a late reply holds the
 *   queue no longer); late, it finds nothing.
 * - **Only when free.** Nothing is sent while the Mac reports a selection ([SelectionDriver.range])
 *   or when the active stream is not [sessionGuid]'s terminal stream.
 * - **Clear.** Always queued after the copy (also on a timeout, a failure or a cancel), unless
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
) {
    /** The link under terminal [point] of [sessionGuid]'s stream, or null (see the class comment). */
    suspend fun lookup(sessionGuid: String, point: SelectionPoint): TappedLink? {
        val stream = active.value?.takeIf { it.sessionGuid == sessionGuid && it.config?.isBrowser != true } ?: return null
        if (selection.range.value != null) return null
        // Begin and end with no suspension between them: nothing can slip into the gesture.
        if (!selection.begin(point, SelectionMode.SMART)) return null
        val begun = selection.gesturesBegun()
        selection.end(point)
        var text: String? = null
        try {
            text = withTimeoutOrNull(timeout) { selection.copy(sessionGuid, timeout) }
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
        val now = active.value
        if (now == null || now.linkId != stream.linkId || now.streamID != stream.streamID) return null
        if (text == null) {
            log("link lookup: no answer")
            return null
        }
        val url = linkFromSelection(text)
        val link = url?.let { TappedLink(it, isMacLocalLink(it, macHosts())) }
        log("link lookup: ${text.length} chars, ${if (link == null) "no link" else "a link of ${link.url.length} chars${if (link.macLocal) ", Mac-local" else ""}"}")
        return link
    }
}

/** How long a tap waits for the Mac's smart selection before showing nothing. */
val DEFAULT_LINK_LOOKUP_TIMEOUT: Duration = 1.seconds
