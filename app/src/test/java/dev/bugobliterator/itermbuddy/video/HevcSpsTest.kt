package dev.bugobliterator.itermbuddy.video

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [spsMaxNumReorderPics] on x265 parameter sets and hand-built SPSs (values checked with ffmpeg's `trace_headers`). */
class HevcSpsTest {
    @Test
    fun x265WithoutBFramesDoesNotReorder() {
        assertEquals(0, spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), hex(BF0_SPS), hex(PPS))))
    }

    @Test
    fun x265WithBFramesReorders() {
        // ffmpeg trace_headers: sps_max_num_reorder_pics[0] = 2.
        assertEquals(2, spsMaxNumReorderPics(extradataOf(hex(BF3_VPS), hex(BF3_SPS), hex(PPS))))
    }

    @Test
    fun theCommittedTerminalClipDoesNotReorder() {
        // fixtures/media/hevc/terminal-clip.json's extradata, as the Mac sends it.
        assertEquals(0, spsMaxNumReorderPics(Base64.getDecoder().decode(CLIP_EXTRADATA)))
    }

    @Test
    fun theLargestOverEverySubLayer() {
        assertEquals(3, spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), sps(orderingForEach = true, reorders = listOf(0, 1, 3)), hex(PPS))))
        assertEquals(1, spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), sps(orderingForEach = true, reorders = listOf(0, 1, 0)), hex(PPS))))
        // Without ordering info for each sub-layer, only the highest's is signalled.
        assertEquals(2, spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), sps(orderingForEach = false, reorders = listOf(2)), hex(PPS))))
    }

    @Test
    fun unknownWhenThereIsNoSpsOrItDoesNotParse() {
        assertNull(spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), hex(PPS))))
        assertNull(spsMaxNumReorderPics(extradataOf(hex(BF0_VPS), hex(BF0_SPS).copyOf(12), hex(PPS))))
        assertNull(spsMaxNumReorderPics(byteArrayOf(0, 3, 0)))
        assertNull(spsMaxNumReorderPics(completeExtradata()))
    }

    /** An SPS with three sub-layers (or [reorders].size when not [orderingForEach]); PTL all zeros, so it needs emulation prevention. */
    private fun sps(orderingForEach: Boolean, reorders: List<Int>): ByteArray {
        val w = BitWriter()
        val maxSubLayersMinus1 = 2
        w.bits(0, 4) // vps id
        w.bits(maxSubLayersMinus1, 3)
        w.bits(1, 1) // temporal id nesting
        w.bits(0, 88) // general profile
        w.bits(0, 8) // general level
        w.bits(1, 1); w.bits(1, 1) // sub-layer 0: profile and level present
        w.bits(0, 1); w.bits(1, 1) // sub-layer 1: level only
        w.bits(0, 2 * (8 - maxSubLayersMinus1))
        w.bits(0, 88); w.bits(0, 8) // sub-layer 0
        w.bits(0, 8) // sub-layer 1
        w.ue(0) // sps id
        w.ue(1) // chroma 4:2:0
        w.ue(640); w.ue(400)
        w.bits(1, 1); repeat(4) { w.ue(it) } // conformance window
        w.ue(0); w.ue(0) // bit depths
        w.ue(4) // log2 max poc lsb - 4
        w.bits(if (orderingForEach) 1 else 0, 1)
        for (reorder in reorders) {
            w.ue(reorder + 1)
            w.ue(reorder)
            w.ue(0)
        }
        w.bits(1, 1) // the rest is not read
        return byteArrayOf(0x42, 0x01) + w.escaped()
    }

    private class BitWriter {
        private val bits = ArrayList<Int>()

        fun bits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += if (i >= 31) 0 else (value shr i) and 1
        }

        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            bits(0, length - 1)
            bits(code, length)
        }

        /** The bytes, padded with zero bits, with `03` inserted after every `00 00` before a byte up to 3. */
        fun escaped(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            val raw = bits.chunked(8).map { byte -> byte.fold(0) { acc, bit -> (acc shl 1) or bit } }
            val out = ArrayList<Byte>()
            var zeros = 0
            for (byte in raw) {
                if (zeros >= 2 && byte <= 3) {
                    out += 3
                    zeros = 0
                }
                out += byte.toByte()
                zeros = if (byte == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }

    companion object {
        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        // x265 (ffmpeg 6.1.1), 1280x800: bframes=0 and bframes=3.
        const val BF0_VPS = "40010c01ffff0408000003009e08000003000078928090"
        const val BF0_SPS = "4201010408000003009e08000003000078900050100642cb2549265780b7020200040000030004000003007820"
        const val BF3_VPS = "40010c01ffff0408000003009e08000003000078959409"
        const val BF3_SPS = "4201010408000003009e08000003000078900050100642cb2b2c9265780b7020200040000003004000000782"
        const val PPS = "4401c172860c4624"
        const val CLIP_EXTRADATA =
            "AAMAAAAYQAEMAf//AWAAAAMAkAAAAwAAAwB4koCQAAAAKkIBAQFgAAADAJAAAAMAAAMAeKACgIAyFlkqSTK8BaAgAAADACAAAAMDwQAAAAdEAcFytGJA"
    }
}
