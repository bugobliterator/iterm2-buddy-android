package dev.bugobliterator.itermbuddy.core.pairing

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

private val SAS_INFO = "iterm2-sas-v1".toByteArray(Charsets.UTF_8)
private const val SAS_DIGITS = 6
private const val SAS_MODULUS = 1_000_000uL

/**
 * The 6-digit Short Authentication String, as upstream `PairingSAS.code`:
 * HKDF-SHA256(ikm = handshakeHash, salt = empty, info = "iterm2-sas-v1") to 8 bytes,
 * read big-endian as an unsigned 64-bit value, mod 10^6, zero-padded.
 */
fun sasCode(handshakeHash: ByteArray): String {
    val derived = ByteArray(Long.SIZE_BYTES)
    HKDFBytesGenerator(SHA256Digest()).run {
        init(HKDFParameters(handshakeHash, null, SAS_INFO))
        generateBytes(derived, 0, derived.size)
    }
    val value = derived.fold(0uL) { acc, byte -> (acc shl 8) or byte.toUByte().toULong() }
    return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
}
