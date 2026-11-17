package dev.bugobliterator.itermbuddy.ui.settings

import dev.bugobliterator.itermbuddy.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderRowTest {
    @Test
    fun decoderRowShowsTheDecoderInUseElseTheLastOne() {
        assertEquals("Not used yet", decoderRowText(VideoStats(null, 0, 0, null)))
        assertEquals("c2.vendor.hevc.decoder", decoderRowText(VideoStats("c2.vendor.hevc.decoder", 1, 1, 5, 1, "c2.vendor.hevc.decoder")))
        // Settings is reached only with the stream closed, so the decoder is released by then.
        assertEquals("c2.vendor.hevc.decoder", decoderRowText(VideoStats(null, 9, 9, 5, 0, "c2.vendor.hevc.decoder")))
    }
}
