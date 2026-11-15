package dev.bugobliterator.itermbuddy.core.pairing

import java.io.ByteArrayOutputStream

/**
 * Domain-separated encoding for hashed or signed tuples, byte-identical to upstream
 * `CanonicalEncoding.encode`: `u32be(len(domain)) || domain || for each field: u32be(len(f)) || f`.
 */
fun canonical(domain: String, fields: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    out.writeLengthPrefixed(domain.toByteArray(Charsets.UTF_8))
    for (field in fields) {
        out.writeLengthPrefixed(field)
    }
    return out.toByteArray()
}

private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
    val length = bytes.size
    write(length ushr 24)
    write(length ushr 16)
    write(length ushr 8)
    write(length)
    write(bytes)
}
