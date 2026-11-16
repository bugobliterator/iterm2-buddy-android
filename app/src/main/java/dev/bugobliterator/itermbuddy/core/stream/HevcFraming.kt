package dev.bugobliterator.itermbuddy.core.stream

/**
 * A parameter-set container or access unit that ends mid-record. The message is upstream's
 * `FramingError` as `String(describing:)` writes it: always `truncated`
 * (`sources/Companion/Shared/CompanionHEVCFraming.swift:29-32`). Upstream also declares
 * `tooManyParameterSets`, but nothing throws it, so it never reaches the phone.
 */
class HevcFramingException(message: String) : Exception(message)

private const val NAL_LENGTH_SIZE = 4
internal val START_CODE = byteArrayOf(0, 0, 0, 1)

private const val NAL_TYPE_VPS = 32
private const val NAL_TYPE_SPS = 33
private const val NAL_TYPE_PPS = 34

private fun readU16(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

/** Reads a big-endian u32 as a non-negative Long, so lengths at or above 2^31 stay huge, never negative. */
internal fun readU32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

/** Reads a big-endian 64-bit value; the caller decides whether it is signed. */
internal fun readU64(bytes: ByteArray, at: Int): Long = (readU32(bytes, at) shl 32) or readU32(bytes, at + 4)

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
 * True when [extradata] is a valid container holding at least one each of VPS (NAL type 32),
 * SPS (33) and PPS (34), the sets an HEVC decoder needs. Other set types are ignored. False for
 * a container that does not parse, an empty set list (`00 00`), or a 0-length set where one of
 * the three would be. The decoder must not configure MediaCodec from a config that fails this:
 * it keeps waiting for a config that passes.
 */
fun hasCompleteHevcParameterSets(extradata: ByteArray): Boolean {
    val sets = try {
        parseParameterSets(extradata)
    } catch (_: HevcFramingException) {
        return false
    }
    val types = sets.filter { it.isNotEmpty() }.map { (it[0].toInt() shr 1) and 0x3F }.toSet()
    return NAL_TYPE_VPS in types && NAL_TYPE_SPS in types && NAL_TYPE_PPS in types
}

/**
 * Checks that [payload] is a whole AVCC access unit, with upstream's rules (see
 * [accessUnitToAnnexB]).
 *
 * @throws HevcFramingException `truncated` when it is not.
 */
internal fun validateAccessUnit(payload: ByteArray) {
    var cursor = 0
    while (cursor < payload.size) {
        if (payload.size - cursor < NAL_LENGTH_SIZE) throw HevcFramingException("truncated")
        val length = readU32(payload, cursor)
        cursor += NAL_LENGTH_SIZE
        if (payload.size - cursor < length) throw HevcFramingException("truncated")
        cursor += length.toInt()
    }
}

/** Calls [action] with the offset of each 4-byte NAL length in [payload], already validated. */
internal inline fun forEachNalLengthOffset(payload: ByteArray, action: (Int) -> Unit) {
    var cursor = 0
    while (cursor < payload.size) {
        action(cursor)
        cursor += NAL_LENGTH_SIZE + readU32(payload, cursor).toInt()
    }
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
    validateAccessUnit(payload)
    val annexB = payload.copyOf()
    forEachNalLengthOffset(payload) { at -> START_CODE.copyInto(annexB, at) }
    return annexB
}
