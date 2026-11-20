package dev.bugobliterator.itermbuddy.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [decoderIntegerKeys] and [vendorLowLatencyKeys]: which keys a decoder is configured with. */
class DecoderFormatTest {
    private val maxInput = 4 shl 20

    private fun keys(name: String, advertises: Boolean, request: LowLatencyRequest, sdk: Int = 34) =
        decoderIntegerKeys(name, advertises, request, sdk, maxInput)

    @Test
    fun requestedAsksForLowLatencyWhetherOrNotAdvertised() {
        for (advertises in listOf(true, false)) {
            assertEquals(
                linkedMapOf("low-latency" to 1, "priority" to 0, "max-input-size" to maxInput),
                keys("c2.android.hevc.decoder", advertises, LowLatencyRequest.REQUESTED),
            )
        }
    }

    @Test
    fun minimalIsTheEarlierFormat() {
        assertEquals(
            mapOf("low-latency" to 1, "priority" to 0, "max-input-size" to maxInput),
            keys("c2.exynos.hevc.decoder", advertises = true, request = LowLatencyRequest.MINIMAL),
        )
        assertEquals(
            mapOf("priority" to 0, "max-input-size" to maxInput),
            keys("c2.exynos.hevc.decoder", advertises = false, request = LowLatencyRequest.MINIMAL),
        )
    }

    @Test
    fun requestedAddsTheVendorKeysOfTheDecodersFamily() {
        assertEquals(
            listOf("low-latency", "vendor.rtc-ext-dec-low-latency.enable", "priority", "max-input-size"),
            keys("c2.exynos.hevc.decoder", advertises = false, request = LowLatencyRequest.REQUESTED).keys.toList(),
        )
        assertEquals(
            mapOf(
                "low-latency" to 1,
                "vendor.qti-ext-dec-picture-order.enable" to 1,
                "vendor.qti-ext-dec-low-latency.enable" to 1,
                "priority" to 0,
                "max-input-size" to maxInput,
            ),
            keys("c2.qti.hevc.decoder", advertises = false, request = LowLatencyRequest.REQUESTED),
        )
        assertEquals(1, keys("c2.mtk.hevc.decoder", false, LowLatencyRequest.REQUESTED)["vdec-lowlatency"])
        assertEquals(1, keys("OMX.Exynos.HEVC.Decoder", false, LowLatencyRequest.REQUESTED)["vendor.rtc-ext-dec-low-latency.enable"])
        assertEquals(1, keys("OMX.qcom.video.decoder.hevc", false, LowLatencyRequest.REQUESTED)["vendor.qti-ext-dec-low-latency.enable"])
        assertEquals(1, keys("OMX.MTK.VIDEO.DECODER.HEVC", false, LowLatencyRequest.REQUESTED)["vdec-lowlatency"])
    }

    @Test
    fun noVendorKeysForOtherDecodersOrTheMinimalFormat() {
        assertTrue(vendorLowLatencyKeys("c2.android.hevc.decoder").isEmpty())
        assertTrue(vendorLowLatencyKeys("c2.goldfish.hevc.decoder").isEmpty())
        assertTrue(vendorLowLatencyKeys("exynos.c2.hevc").isEmpty())
        assertEquals(
            listOf("priority", "max-input-size"),
            keys("c2.qti.hevc.decoder", advertises = false, request = LowLatencyRequest.MINIMAL).keys.toList(),
        )
    }

    @Test
    fun keysFollowTheApiLevel() {
        // KEY_LOW_LATENCY from API 30; vendor keys from API 26.
        assertEquals(
            listOf("vendor.rtc-ext-dec-low-latency.enable", "priority", "max-input-size"),
            keys("c2.exynos.hevc.decoder", false, LowLatencyRequest.REQUESTED, sdk = 29).keys.toList(),
        )
        assertEquals(
            listOf("priority", "max-input-size"),
            keys("c2.exynos.hevc.decoder", true, LowLatencyRequest.REQUESTED, sdk = 25).keys.toList(),
        )
        assertEquals(
            listOf("priority", "max-input-size"),
            keys("c2.exynos.hevc.decoder", true, LowLatencyRequest.MINIMAL, sdk = 29).keys.toList(),
        )
        assertEquals(
            listOf("low-latency", "vendor.rtc-ext-dec-low-latency.enable", "priority", "max-input-size"),
            keys("c2.exynos.hevc.decoder", false, LowLatencyRequest.REQUESTED, sdk = 30).keys.toList(),
        )
    }

    @Test
    fun neverAnOperatingRate() {
        for (request in LowLatencyRequest.entries) {
            assertTrue("operating-rate" !in keys("c2.qti.hevc.decoder", true, request))
        }
    }

    @Test
    fun fallbacksRememberByName() {
        val fallbacks = LowLatencyFallbacks()
        assertTrue("c2.exynos.hevc.decoder" !in fallbacks)
        fallbacks.add("c2.exynos.hevc.decoder")
        assertTrue("c2.exynos.hevc.decoder" in fallbacks)
        assertTrue("c2.android.hevc.decoder" !in fallbacks)
    }
}
