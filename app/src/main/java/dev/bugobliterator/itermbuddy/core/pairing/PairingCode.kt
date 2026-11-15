package dev.bugobliterator.itermbuddy.core.pairing

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * A scanned direct-mode (`v=1`) iTerm2 pairing code. [relayOrigin] is already canonical
 * (see [canonicalRelayOrigin]) and is the exact string the phone signs in relay joins.
 */
data class PairingCode(
    val responderStaticPublicKey: ByteArray,
    val pairingID: String,
    val relayOrigin: String,
) {
    /** The Noise prologue, UTF-8 `"iterm2-companion/v1/pid:" + pairingID`; `v1` is a constant. */
    val prologue: ByteArray
        get() = "iterm2-companion/v1/pid:$pairingID".toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is PairingCode &&
            responderStaticPublicKey.contentEquals(other.responderStaticPublicKey) &&
            pairingID == other.pairingID &&
            relayOrigin == other.relayOrigin

    override fun hashCode(): Int =
        (responderStaticPublicKey.contentHashCode() * 31 + pairingID.hashCode()) * 31 + relayOrigin.hashCode()

    override fun toString(): String = "PairingCode(pairingID=$pairingID, relayOrigin=$relayOrigin)"
}

sealed interface PairingCodeResult {
    data class Ok(val code: PairingCode) : PairingCodeResult

    /**
     * [reason] is a stable identifier: upstream's `PairingCode.ParseError` case name, or
     * `missingRelay` / `resolverUnsupported` for codes upstream accepts but this app cannot use.
     * [message] is user-facing text.
     */
    data class Rejected(val reason: String, val message: String) : PairingCodeResult
}

/**
 * Parses a scanned QR string exactly as upstream `PairingCode.parse` (iTerm2 v3.7.7) does, then
 * applies this app's policy: only a `v=1` code that carries a `relay=` origin is accepted.
 */
fun parsePairingCode(qr: String): PairingCodeResult {
    val uri = try {
        URI(qr)
    } catch (_: URISyntaxException) {
        return rejected(Reason.MALFORMED_URL)
    }
    if (uri.scheme != "iterm2" || uri.host != "pair") return rejected(Reason.MALFORMED_URL)
    val items = queryItems(uri.rawQuery) ?: return rejected(Reason.MALFORMED_URL)
    fun value(name: String): String? = items.firstOrNull { it.first == name }?.second

    val versionString = value("v")
    val version = versionString?.takeIf { VERSION_PATTERN.matches(it) }?.toIntOrNull()
    if (version != DIRECT_VERSION && version != RESOLVED_VERSION) return rejected(Reason.UNSUPPORTED_VERSION)

    if (value("proto") != SUPPORTED_PROTOCOL) return rejected(Reason.UNSUPPORTED_PROTOCOL)

    val key = value("rs")?.let(::decodeBase64URL)
    if (key == null || key.size != KEY_SIZE) return rejected(Reason.INVALID_RESPONDER_KEY)

    val pid = value("pid")
    if (pid.isNullOrEmpty()) return rejected(Reason.MISSING_PAIRING_ID)

    if (version == RESOLVED_VERSION) {
        // Upstream accepts a well-formed resolver code; this app has no resolver client.
        val resolver = value("resolver")
        if (resolver == null || !isValidResolverURL(resolver)) return rejected(Reason.INVALID_RESOLVER)
        return rejected(Reason.RESOLVER_UNSUPPORTED)
    }
    val relay = value("relay") ?: return rejected(Reason.MISSING_RELAY)
    val origin = canonicalRelayOrigin(relay) ?: return rejected(Reason.INVALID_RELAY)
    return PairingCodeResult.Ok(PairingCode(key, pid, origin))
}

/**
 * Upstream `canonicalRelayOrigin`: an https URL with a host, no userinfo, query or fragment and
 * an empty or "/" path becomes `"https://" + host + (":" + port if one was given)`. The host keeps
 * its case and an explicit port is kept, even 443. Returns null where upstream throws.
 */
internal fun canonicalRelayOrigin(raw: String): String? {
    val uri = parseHttpsURI(raw) ?: return null
    val path = uri.rawPath.orEmpty()
    if (path.isNotEmpty() && path != "/") return null
    return origin(uri)
}

/** Upstream `canonicalResolverURL`'s validity rule: like the relay origin, but any path is allowed. */
private fun isValidResolverURL(raw: String): Boolean = parseHttpsURI(raw) != null

private fun parseHttpsURI(raw: String): URI? {
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        return null
    }
    val valid = uri.scheme?.lowercase() == "https" &&
        !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
    return uri.takeIf { valid }
}

private fun origin(uri: URI): String = "https://" + uri.host + (if (uri.port >= 0) ":${uri.port}" else "")

/**
 * Splits a raw query like Foundation's `URLComponents.queryItems`: on '&', then on the first '=',
 * percent-decoding each part as UTF-8 with '+' kept literal. A part with no '=' has a null value.
 * Returns null if any part is not valid percent-encoded UTF-8.
 */
private fun queryItems(rawQuery: String?): List<Pair<String, String?>>? {
    if (rawQuery == null) return emptyList()
    return rawQuery.split('&').map { item ->
        val equals = item.indexOf('=')
        val rawName = if (equals < 0) item else item.substring(0, equals)
        val name = percentDecode(rawName) ?: return null
        val value = if (equals < 0) null else percentDecode(item.substring(equals + 1)) ?: return null
        name to value
    }
}

private fun percentDecode(text: String): String? {
    val bytes = ByteArrayOutputStream(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '%') {
            if (index + 2 >= text.length) return null
            val high = HEX_VALUES.indexOf(text[index + 1].lowercaseChar())
            val low = HEX_VALUES.indexOf(text[index + 2].lowercaseChar())
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            index += 3
        } else {
            val end = if (Character.isHighSurrogate(char) && index + 1 < text.length) index + 2 else index + 1
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

/**
 * Upstream `Data(base64URLEncoded:)`: map base64url to the standard alphabet, pad to a multiple
 * of four, then decode strictly. Accepts both alphabets, padded or not.
 */
private fun decodeBase64URL(text: String): ByteArray? {
    var base64 = text.replace('-', '+').replace('_', '/')
    val remainder = base64.length % 4
    if (remainder > 0) base64 += "=".repeat(4 - remainder)
    return try {
        Base64.getDecoder().decode(base64)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private enum class Reason(val id: String, val message: String) {
    MALFORMED_URL("malformedURL", "This QR code is not an iTerm2 pairing code."),
    UNSUPPORTED_VERSION("unsupportedVersion", "This QR code needs a newer version of iTerm2 Buddy."),
    UNSUPPORTED_PROTOCOL("unsupportedProtocol", "This QR code needs a newer version of iTerm2 Buddy."),
    INVALID_RESPONDER_KEY("invalidResponderKey", "This QR code is invalid."),
    MISSING_PAIRING_ID("missingPairingID", "This QR code is invalid."),
    INVALID_RELAY("invalidRelay", "This QR code is invalid."),
    INVALID_RESOLVER("invalidResolver", "This QR code is invalid."),
    MISSING_RELAY(
        "missingRelay",
        "This QR has no relay origin. Set companionRelayOrigin in iTerm2's Advanced settings.",
    ),
    RESOLVER_UNSUPPORTED(
        "resolverUnsupported",
        "This QR uses iTerm2's resolver mode. Set companionResolverURL empty in iTerm2's Advanced settings.",
    ),
}

private fun rejected(reason: Reason) = PairingCodeResult.Rejected(reason.id, reason.message)

private const val SUPPORTED_PROTOCOL = "Noise_XK_25519_ChaChaPoly_BLAKE2s"
private const val DIRECT_VERSION = 1
private const val RESOLVED_VERSION = 2
private const val KEY_SIZE = 32
private const val HEX_VALUES = "0123456789abcdef"

/** Swift's `Int(String)` takes only ASCII digits with an optional sign. */
private val VERSION_PATTERN = Regex("[+-]?[0-9]+")
