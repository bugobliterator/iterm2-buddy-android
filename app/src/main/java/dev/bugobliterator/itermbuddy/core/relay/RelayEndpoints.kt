package dev.bugobliterator.itermbuddy.core.relay

import dev.bugobliterator.itermbuddy.core.pairing.PLAIN_RELAY_ORIGIN
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Where to reach a relay. [origin] is the canonical relay origin from the pairing code and is
 * the string join signatures cover, byte for byte; the two URLs are only where sockets go. In
 * production both derive from [origin] via [forOrigin]; tests point them at a local server while
 * keeping a real-looking [origin].
 *
 * @property webSocketUrl the admission WebSocket, `wss://host[:port]/`.
 * @property httpBaseUrl the base for `/register`, `https://host[:port]` with no trailing slash.
 */
data class RelayEndpoints(val origin: String, val webSocketUrl: String, val httpBaseUrl: String) {
    companion object
}

/**
 * Derives the endpoints for a canonical relay [origin] (`https://host[:port]`, host a plain name
 * or bracketed IP literal), or returns null to refuse it. The origin is never rewritten: it is
 * refused unless OkHttp parses the derived URLs back to the same host (ASCII case-insensitively,
 * as DNS compares) and the same port, so a spelling OkHttp would normalise to somewhere else,
 * such as `[::0001]` for `::1`, cannot connect under one name and sign another.
 */
fun RelayEndpoints.Companion.forOrigin(origin: String): RelayEndpoints? {
    if (!PLAIN_RELAY_ORIGIN.matches(origin)) return null
    val authority = origin.removePrefix(HTTPS_PREFIX)
    val (host, port) = splitAuthority(authority) ?: return null
    val endpoints = RelayEndpoints(
        origin = origin,
        webSocketUrl = "wss://$authority/",
        httpBaseUrl = origin,
    )
    // OkHttp's Request.Builder maps wss: to https: before parsing, so check that form.
    val webSocketParsed = ("https:" + endpoints.webSocketUrl.removePrefix("wss:")).toHttpUrlOrNull()
    val httpParsed = endpoints.httpBaseUrl.toHttpUrlOrNull()
    return endpoints.takeIf { matches(webSocketParsed, host, port) && matches(httpParsed, host, port) }
}

private const val HTTPS_PREFIX = "https://"
private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65_535

/** Splits `host[:port]` or `[ip6][:port]`; the host comes back without brackets. */
private fun splitAuthority(authority: String): Pair<String, Int>? {
    val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else authority.indexOf(':')
        .let { if (it < 0) authority.length else it }
    val host = authority.substring(0, hostEnd).removePrefix("[").removeSuffix("]")
    val portText = authority.substring(hostEnd)
    val port = when {
        portText.isEmpty() -> HTTPS_DEFAULT_PORT
        else -> portText.removePrefix(":").toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
    }
    return host to port
}

private fun matches(parsed: HttpUrl?, host: String, port: Int): Boolean =
    parsed != null &&
        parsed.host == host.lowercase(Locale.ROOT) &&
        parsed.port == port &&
        parsed.encodedPath == "/"
