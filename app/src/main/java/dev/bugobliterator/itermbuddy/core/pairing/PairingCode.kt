package dev.bugobliterator.itermbuddy.core.pairing

import java.util.Base64

/**
 * A scanned direct-mode (`v=1`) iTerm2 pairing code. [relayOrigin] is already canonical
 * (see [canonicalRelayOrigin]) and is the exact string the phone signs in relay joins.
 * The key is copied in and out, so no caller can change it.
 */
class PairingCode(
    responderStaticPublicKey: ByteArray,
    val pairingID: String,
    val relayOrigin: String,
) {
    private val key: ByteArray = responderStaticPublicKey.copyOf()

    init {
        require(key.size == KEY_SIZE) { "responder static public key must be $KEY_SIZE bytes, got ${key.size}" }
    }

    /** The Mac's X25519 static public key (32 bytes); a fresh copy on every read. */
    val responderStaticPublicKey: ByteArray
        get() = key.copyOf()

    /** The Noise prologue, UTF-8 `"iterm2-companion/v1/pid:" + pairingID`; `v1` is a constant. */
    val prologue: ByteArray
        get() = "iterm2-companion/v1/pid:$pairingID".toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is PairingCode &&
            key.contentEquals(other.key) &&
            pairingID == other.pairingID &&
            relayOrigin == other.relayOrigin

    override fun hashCode(): Int =
        (key.contentHashCode() * 31 + pairingID.hashCode()) * 31 + relayOrigin.hashCode()

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
 * applies this app's policy: only a `v=1` code that carries a `relay=` origin is accepted, and
 * only when that origin's host is a plain hostname or a bracketed IP literal.
 */
fun parsePairingCode(qr: String): PairingCodeResult {
    val url = UrlComponents.parse(qr) ?: return rejected(Reason.MALFORMED_URL)
    if (url.scheme != "iterm2" || url.host != "pair") return rejected(Reason.MALFORMED_URL)
    val items = url.queryItems
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
    // App policy: upstream percent-decodes the host, so it can hold '@', '/', '#', control or
    // non-ASCII characters. Only a plain hostname or bracketed IP literal is used.
    if (!isAcceptedRelayOrigin(origin)) return rejected(Reason.INVALID_RELAY)
    return PairingCodeResult.Ok(PairingCode(key, pid, origin))
}

/**
 * Upstream `canonicalRelayOrigin`: an https URL with a host, no userinfo, query or fragment and
 * an empty or "/" path becomes `"https://" + host + (":" + port if one was given)`. The host keeps
 * its case and is percent-decoded, and an explicit port is kept, even 443. Returns null where
 * upstream throws.
 */
internal fun canonicalRelayOrigin(raw: String): String? {
    val url = parseHttpsURL(raw) ?: return null
    if (url.path.isNotEmpty() && url.path != "/") return null
    return "https://" + url.host + (url.port?.let { ":$it" } ?: "")
}

/**
 * True when [origin] is exactly what [parsePairingCode] would store: already canonical (upstream
 * `canonicalRelayOrigin` maps it to itself) and a plain hostname or bracketed IP literal.
 * The pairing store re-checks a loaded origin with this.
 */
internal fun isAcceptedRelayOrigin(origin: String): Boolean =
    PLAIN_RELAY_ORIGIN.matches(origin) && canonicalRelayOrigin(origin) == origin

/** Upstream `canonicalResolverURL`'s validity rule: like the relay origin, but any path is allowed. */
private fun isValidResolverURL(raw: String): Boolean = parseHttpsURL(raw) != null

private fun parseHttpsURL(raw: String): UrlComponents? {
    val url = UrlComponents.parse(raw) ?: return null
    val valid = url.scheme?.lowercase() == "https" &&
        !url.host.isNullOrEmpty() &&
        url.user == null && url.password == null &&
        url.rawQuery == null && url.rawFragment == null
    return url.takeIf { valid }
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
internal const val KEY_SIZE = 32

/** Swift's `Int(String)` takes only ASCII digits with an optional sign. */
private val VERSION_PATTERN = Regex("[+-]?[0-9]+")

/** A canonical origin whose host is `[A-Za-z0-9._-]+` or `[` IP literal `]`, with an optional port. */
internal val PLAIN_RELAY_ORIGIN = Regex("https://([A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+\\])(:[0-9]+)?")
