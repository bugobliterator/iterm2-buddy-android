package dev.bugobliterator.itermbuddy.core.pairing

import java.security.MessageDigest

/** `hexLower(SHA256(canonical("iterm2-room", [rs, utf8(pid)])))`, as upstream `RelayRoom.name`: 64 lowercase hex. */
fun roomName(code: PairingCode): String {
    val preimage = canonical(
        "iterm2-room",
        listOf(code.responderStaticPublicKey, code.pairingID.toByteArray(Charsets.UTF_8)),
    )
    val digest = MessageDigest.getInstance("SHA-256").digest(preimage)
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xff
        hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return hex.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
