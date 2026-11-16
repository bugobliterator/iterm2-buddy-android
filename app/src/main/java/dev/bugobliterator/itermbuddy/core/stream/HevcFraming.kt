package dev.bugobliterator.itermbuddy.core.stream

/**
 * A parameter-set container or access unit that ends mid-record. The message is upstream's
 * `FramingError` as `String(describing:)` writes it: always `truncated`
 * (`sources/Companion/Shared/CompanionHEVCFraming.swift:29-32`; `tooManyParameterSets` is only
 * thrown by the encoder side).
 */
class HevcFramingException(message: String) : Exception(message)

private const val NAL_LENGTH_SIZE = 4
private val START_CODE = byteArrayOf(0, 0, 0, 1)

private fun readU16(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

/** Reads a big-endian u32 as a non-negative Long, so lengths at or above 2^31 stay huge, never negative. */
private fun readU32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

/**
 * Splits `StreamConfig.codecExtradata` into its parameter sets (VPS, SPS, PPS in practice), as
 * upstream `CompanionHEVCFraming.decodeParameterSets` (`CompanionHEVCFraming.swift:50-66`).
 * The container is `u16 count`, then per set `u32 length || bytes`, big-endian; not hvcC.
 *
 * Matches upstream on every edge:
 * - fewer than 2 bytes, a length field cut short, or a length running past the end throws
 *   `truncated` (the `need` checks, lines 52-55, 60, 62); the u32 length is unsigned, so a length
 *   at or above 2^31 is a huge length that overruns, never a negative one;
 * - a count of 0 gives an empty list; a 0-length set gives an empty array in its place;
 * - bytes after the last of `count` sets are **ignored**, not rejected (upstream stops after
 *   `count` records and never checks the remainder).
 *
 * Each returned array is a fresh copy; [extradata] is not retained.
 *
 * @throws HevcFramingException as above.
 */
fun parseParameterSets(extradata: ByteArray): List<ByteArray> {
    var cursor = 0
    fun need(count: Long) {
        if (extradata.size - cursor < count) throw HevcFramingException("truncated")
    }
    need(2)
    val count = readU16(extradata, cursor)
    cursor += 2
    val sets = ArrayList<ByteArray>(minOf(count, extradata.size / NAL_LENGTH_SIZE))
    repeat(count) {
        need(NAL_LENGTH_SIZE.toLong())
        val length = readU32(extradata, cursor)
        cursor += NAL_LENGTH_SIZE
        need(length)
        val end = cursor + length.toInt()
        sets += extradata.copyOfRange(cursor, end)
        cursor = end
    }
    return sets
}

/**
 * MediaCodec `csd-0` for `video/hevc`: every parameter set of [extradata] prefixed with the
 * Annex-B start code `00 00 00 01`, in container order, concatenated. A 0-length set becomes a
 * bare start code, as the set list holds it; an empty set list gives an empty array.
 *
 * @throws HevcFramingException when [parseParameterSets] does.
 */
fun parameterSetsToCsd(extradata: ByteArray): ByteArray {
    val sets = parseParameterSets(extradata)
    val csd = ByteArray(sets.sumOf { START_CODE.size + it.size })
    var cursor = 0
    for (set in sets) {
        START_CODE.copyInto(csd, cursor)
        set.copyInto(csd, cursor + START_CODE.size)
        cursor += START_CODE.size + set.size
    }
    return csd
}

/**
 * Converts one AVCC access unit (a media frame payload) to Annex-B for MediaCodec input: each
 * 4-byte big-endian NAL length is replaced by `00 00 00 01`. The output is the same size as
 * [payload], which is never modified.
 *
 * Accepts and rejects exactly what upstream `CompanionHEVCFraming.decodeAccessUnit` does
 * (`CompanionHEVCFraming.swift:83-93`):
 * - an empty payload is valid and gives an empty array (the loop at line 86 never runs);
 * - a 0-length NAL is valid and becomes a bare start code;
 * - fewer than 4 bytes left for a length (line 87), or a length running past the end (line 89),
 *   throws `truncated`; the length is unsigned, so one at or above 2^31 overruns, never goes
 *   negative.
 * The whole payload is validated before anything is written, so a bad payload throws without
 * producing partial output.
 *
 * @throws HevcFramingException as above.
 */
fun accessUnitToAnnexB(payload: ByteArray): ByteArray {
    var cursor = 0
    while (cursor < payload.size) {
        if (payload.size - cursor < NAL_LENGTH_SIZE) throw HevcFramingException("truncated")
        val length = readU32(payload, cursor)
        cursor += NAL_LENGTH_SIZE
        if (payload.size - cursor < length) throw HevcFramingException("truncated")
        cursor += length.toInt()
    }
    val annexB = payload.copyOf()
    cursor = 0
    while (cursor < annexB.size) {
        val length = readU32(payload, cursor).toInt()
        START_CODE.copyInto(annexB, cursor)
        cursor += NAL_LENGTH_SIZE + length
    }
    return annexB
}
