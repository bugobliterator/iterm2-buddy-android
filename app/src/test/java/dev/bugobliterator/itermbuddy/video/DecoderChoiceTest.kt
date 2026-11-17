package dev.bugobliterator.itermbuddy.video

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderChoiceTest {
    @Test
    fun hardwareLowLatencyIsPreferred() {
        val software = decoder("c2.android.hevc.decoder", hardware = false, lowLatency = true)
        val hardwarePlain = decoder("c2.exynos.hevc.decoder", hardware = true, lowLatency = false)
        val hardwareLowLatency = decoder("c2.exynos.hevc.decoder.lowlatency", hardware = true, lowLatency = true)
        val choice = chooseDecoder(listOf(software, hardwarePlain, hardwareLowLatency), 1280, 800)
        assertEquals(DecoderChoice.Use(hardwareLowLatency), choice)
    }

    @Test
    fun hardwareIsPreferredOverLowLatencySoftware() {
        val software = decoder("sw", hardware = false, lowLatency = true)
        val hardware = decoder("hw", hardware = true, lowLatency = false)
        assertEquals(DecoderChoice.Use(hardware), chooseDecoder(listOf(software, hardware), 1280, 800))
    }

    @Test
    fun noHevcDecoderIsNone() {
        assertEquals(DecoderChoice.None, chooseDecoder(emptyList(), 1280, 800))
    }

    @Test
    fun oversizeConfigReportsTooLarge() {
        val hardware = decoder("hw", maxWidth = 4096, maxHeight = 2304)
        assertEquals(DecoderChoice.TooLarge(4096, 2304), chooseDecoder(listOf(hardware), 5120, 2880))
    }

    @Test
    fun tooLargeReportsThePreferredDecodersLimit() {
        val software = decoder("sw", hardware = false, maxWidth = 1920, maxHeight = 1088)
        val hardware = decoder("hw", hardware = true, maxWidth = 4096, maxHeight = 2304)
        assertEquals(DecoderChoice.TooLarge(4096, 2304), chooseDecoder(listOf(software, hardware), 5120, 2880))
    }

    @Test
    fun softwareUsedWhenHardwareCannotTakeSize() {
        val hardware = decoder("hw", hardware = true, maxWidth = 1920, maxHeight = 1088)
        val software = decoder("sw", hardware = false, lowLatency = false, maxWidth = 8192, maxHeight = 4320)
        assertEquals(DecoderChoice.Use(software), chooseDecoder(listOf(hardware, software), 3840, 2160))
    }

    @Test
    fun zeroSizeIsNeverSupported() {
        val hardware = decoder("hw")
        assertEquals(DecoderChoice.TooLarge(4096, 2304), chooseDecoder(listOf(hardware), 0, 0))
    }
}
