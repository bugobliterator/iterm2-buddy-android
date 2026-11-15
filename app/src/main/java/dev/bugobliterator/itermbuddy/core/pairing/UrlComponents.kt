package dev.bugobliterator.itermbuddy.core.pairing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The parts of `URLComponents(string:)` that upstream's pairing-code parse reads, modelled on
 * swift-corelibs-foundation (Swift 6.2, Linux), which produced the fixtures. Splits per RFC 3986
 * Appendix B and then follows Foundation's leniency:
 * - an invalid scheme, host or port makes the whole string unparseable ([parse] returns null);
 * - a host or userinfo is percent-decoded, and is null when that does not give valid UTF-8;
 * - a query or path holding characters RFC 3986 does not allow there (or a malformed `%`
 *   escape) is re-encoded by Foundation as a whole, so its decoded parts come back as raw text.
 */
internal class UrlComponents private constructor(
    val scheme: String?,
    val user: String?,
    val password: String?,
    val host: String?,
    val port: Long?,
    /** The decoded path; empty when it does not decode to valid UTF-8, as Foundation's `path`. */
    val path: String,
    val rawQuery: String?,
    val rawFragment: String?,
) {
    /** Foundation's `queryItems`: split on '&', then the first '='; a part with no '=' has a null value. */
    val queryItems: List<Pair<String?, String?>>
        get() {
            val query = rawQuery ?: return emptyList()
            val reencoded = !isStrictlyValid(query, QUERY_EXTRA)
            fun decode(part: String): String? = if (reencoded) part else percentDecode(part)
            return query.split('&').map { item ->
                val equals = item.indexOf('=')
                if (equals < 0) {
                    decode(item) to null
                } else {
                    decode(item.substring(0, equals)) to decode(item.substring(equals + 1))
                }
            }
        }

    companion object {
        private val REFERENCE = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$", RegexOption.DOT_MATCHES_ALL)
        private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

        fun parse(text: String): UrlComponents? {
            if (hasUnpairedSurrogate(text)) return null
            val match = REFERENCE.matchEntire(text) ?: return null
            fun group(index: Int): String? = match.groups[index]?.value
            val scheme = group(2)
            if (scheme != null && !SCHEME.matches(scheme)) return null

            var user: String? = null
            var password: String? = null
            var host: String? = null
            var port: Long? = null
            val authority = group(4)
            if (authority != null) {
                val at = authority.lastIndexOf('@')
                if (at >= 0) {
                    val userinfo = authority.substring(0, at)
                    val colon = userinfo.indexOf(':')
                    user = decodeComponent(if (colon < 0) userinfo else userinfo.substring(0, colon))
                    if (colon >= 0) password = decodeComponent(userinfo.substring(colon + 1))
                }
                val hostPort = authority.substring(at + 1)
                val hostEnd = if (hostPort.startsWith('[')) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    if (!isValidHost(hostPort.substring(1, close))) return null
                    close + 1
                } else {
                    val colon = hostPort.indexOf(':')
                    val end = if (colon < 0) hostPort.length else colon
                    if (!isValidHost(hostPort.substring(0, end))) return null
                    end
                }
                val portText = hostPort.substring(hostEnd)
                if (portText.isNotEmpty()) {
                    if (portText[0] != ':') return null
                    val digits = portText.substring(1)
                    if (!digits.all { it in '0'..'9' }) return null
                    // Foundation keeps a port that overflows Int as no port at all.
                    port = digits.toLongOrNull()
                }
                host = percentDecode(hostPort.substring(0, hostEnd))
            }
            val path = decodeComponent(group(5).orEmpty()).orEmpty()
            return UrlComponents(scheme, user, password, host, port, path, group(7), group(9))
        }

        /** A raw component's decoded value: raw text when Foundation re-encodes it, null for bad UTF-8. */
        private fun decodeComponent(raw: String): String? =
            if (isStrictlyValid(raw, PATH_EXTRA)) percentDecode(raw) else raw

        private fun isValidHost(raw: String): Boolean = raw.indices.all { index ->
            val char = raw[index]
            when {
                char == '%' -> isEscapeAt(raw, index)
                char.code <= 0x20 || char.code == 0x7f -> false
                else -> char !in HOST_FORBIDDEN
            }
        }

        /** True when every character is RFC 3986 unreserved, a sub-delim, ':' '@', [extra], or a valid escape. */
        private fun isStrictlyValid(raw: String, extra: String): Boolean = raw.indices.all { index ->
            val char = raw[index]
            when {
                char == '%' -> isEscapeAt(raw, index)
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' -> true
                else -> char in UNRESERVED_MARKS || char in SUB_DELIMS || char == ':' || char == '@' || char in extra
            }
        }

        private fun isEscapeAt(text: String, index: Int): Boolean =
            index + 2 < text.length && hexValue(text[index + 1]) >= 0 && hexValue(text[index + 2]) >= 0
    }
}

/**
 * Percent-decodes [text] as UTF-8, '+' kept literal. Null when an escape is malformed, the bytes
 * are not valid UTF-8, or [text] holds an unpaired surrogate.
 */
internal fun percentDecode(text: String): String? {
    if (hasUnpairedSurrogate(text)) return null
    val bytes = ByteArrayOutputStream(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '%') {
            if (index + 2 >= text.length) return null
            val high = hexValue(text[index + 1])
            val low = hexValue(text[index + 2])
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            index += 3
        } else {
            val end = if (Character.isHighSurrogate(char)) index + 2 else index + 1
            bytes.write(text.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun hasUnpairedSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (Character.isHighSurrogate(char)) {
            if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
            index += 2
        } else {
            if (Character.isLowSurrogate(char)) return true
            index += 1
        }
    }
    return false
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

private const val UNRESERVED_MARKS = "-._~"
private const val SUB_DELIMS = "!$&'()*+,;="
private const val PATH_EXTRA = "/"
private const val QUERY_EXTRA = "/?"

/** Characters that make Foundation refuse a host outright (with controls, space and a bad '%'). */
private const val HOST_FORBIDDEN = "\"<>[\\]^`{|}"
