package dev.bugobliterator.itermbuddy.video

import dev.bugobliterator.itermbuddy.core.stream.HevcFramingException
import dev.bugobliterator.itermbuddy.core.stream.parseParameterSets

private const val NAL_TYPE_SPS = 33

/** `profile_tier_level`'s general (and each sub-layer's) profile part, in bits (H.265 7.3.3). */
private const val PROFILE_BITS = 88
private const val LEVEL_BITS = 8
private const val MAX_SUB_LAYERS = 8

/**
 * The largest `sps_max_num_reorder_pics` of the SPSs in [extradata] (the `StreamConfig`
 * parameter-set container), over every sub-layer signalled: 0 when no picture is ever output
 * out of decode order. Null when there is no SPS or one does not parse, so the caller cannot
 * assume the stream does not reorder. Reads H.265 7.3.2.2 up to `sps_max_latency_increase_plus1`.
 */
fun spsMaxNumReorderPics(extradata: ByteArray): Int? {
    val sets = try {
        parseParameterSets(extradata)
    } catch (_: HevcFramingException) {
        return null
    }
    val sps = sets.filter { it.size > 2 && (it[0].toInt() shr 1) and 0x3F == NAL_TYPE_SPS }
    if (sps.isEmpty()) return null
    var max = 0
    for (set in sps) {
        val reorder = try {
            maxNumReorderPics(set)
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        max = maxOf(max, reorder)
    }
    return max
}

private fun maxNumReorderPics(nal: ByteArray): Int {
    val bits = RbspReader(unescape(nal, from = 2))
    bits.skip(4) // sps_video_parameter_set_id
    val maxSubLayersMinus1 = bits.read(3)
    bits.skip(1) // sps_temporal_id_nesting_flag
    // profile_tier_level(1, sps_max_sub_layers_minus1)
    bits.skip(PROFILE_BITS + LEVEL_BITS)
    val profilePresent = BooleanArray(maxSubLayersMinus1)
    val levelPresent = BooleanArray(maxSubLayersMinus1)
    for (i in 0 until maxSubLayersMinus1) {
        profilePresent[i] = bits.flag()
        levelPresent[i] = bits.flag()
    }
    if (maxSubLayersMinus1 > 0) bits.skip(2 * (MAX_SUB_LAYERS - maxSubLayersMinus1))
    for (i in 0 until maxSubLayersMinus1) {
        if (profilePresent[i]) bits.skip(PROFILE_BITS)
        if (levelPresent[i]) bits.skip(LEVEL_BITS)
    }
    bits.ue() // sps_seq_parameter_set_id
    if (bits.ue() == 3) bits.skip(1) // chroma_format_idc, separate_colour_plane_flag
    bits.ue() // pic_width_in_luma_samples
    bits.ue() // pic_height_in_luma_samples
    if (bits.flag()) repeat(4) { bits.ue() } // conformance window offsets
    bits.ue() // bit_depth_luma_minus8
    bits.ue() // bit_depth_chroma_minus8
    bits.ue() // log2_max_pic_order_cnt_lsb_minus4
    val orderingInfoForEach = bits.flag()
    var max = 0
    for (i in (if (orderingInfoForEach) 0 else maxSubLayersMinus1)..maxSubLayersMinus1) {
        bits.ue() // sps_max_dec_pic_buffering_minus1
        max = maxOf(max, bits.ue())
        bits.ue() // sps_max_latency_increase_plus1
    }
    return max
}

/** The RBSP of [nal] after its [from]-byte header: emulation-prevention bytes (`00 00 03`) removed. */
private fun unescape(nal: ByteArray, from: Int): ByteArray {
    val out = ByteArray(nal.size - from)
    var size = 0
    var zeros = 0
    for (i in from until nal.size) {
        val byte = nal[i].toInt() and 0xFF
        if (zeros >= 2 && byte == 3) {
            zeros = 0
            continue
        }
        zeros = if (byte == 0) zeros + 1 else 0
        out[size++] = byte.toByte()
    }
    return out.copyOf(size)
}

private class RbspReader(private val bytes: ByteArray) {
    private var position = 0

    fun read(count: Int): Int {
        var value = 0
        repeat(count) {
            val byte = bytes[position shr 3].toInt()
            value = (value shl 1) or ((byte shr (7 - (position and 7))) and 1)
            position++
        }
        return value
    }

    fun skip(count: Int) {
        check(position + count <= bytes.size * 8) { "past the end" }
        position += count
    }

    fun flag(): Boolean = read(1) == 1

    /** Exp-Golomb `ue(v)`; at most 31 leading zeros. */
    fun ue(): Int {
        var zeros = 0
        while (read(1) == 0) {
            zeros++
            check(zeros <= 31) { "ue(v) too long" }
        }
        return ((1L shl zeros) - 1 + read(zeros)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
