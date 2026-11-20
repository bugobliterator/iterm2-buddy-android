package dev.bugobliterator.itermbuddy.video

import android.media.MediaFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * How hard a decoder is asked for low latency.
 *
 * - [REQUESTED]: `KEY_LOW_LATENCY = 1` on API 30+ whether or not the decoder advertises
 *   `FEATURE_LowLatency`, plus the vendor keys of its family ([vendorLowLatencyKeys]). The default.
 * - [MINIMAL]: the earlier format: `KEY_LOW_LATENCY = 1` only when the decoder advertises
 *   `FEATURE_LowLatency`, no vendor keys. Used when a decoder rejected [REQUESTED] and when the
 *   debug switch "Decoder low-latency keys" is off.
 *
 * Both always carry `KEY_PRIORITY = 0` (real time) and `KEY_MAX_INPUT_SIZE`. `KEY_OPERATING_RATE`
 * is never set: with `KEY_PRIORITY = 0` an operating rate the decoder cannot meet makes some
 * decoders fail (Moonlight sets one or the other, never both, and the high rate only on Qualcomm).
 */
enum class LowLatencyRequest { REQUESTED, MINIMAL }

/** What the running decoder was configured with, for the stats overlay. */
enum class LowLatencyState {
    /** [LowLatencyRequest.REQUESTED] was accepted. */
    ON,

    /** The decoder rejected [LowLatencyRequest.REQUESTED] (now or earlier in the process) and runs [LowLatencyRequest.MINIMAL]. */
    FALLBACK,

    /** The debug switch is off: [LowLatencyRequest.MINIMAL] by choice. */
    OFF,
}

/** `KEY_LOW_LATENCY` exists from API 30. */
private const val LOW_LATENCY_MIN_SDK = 30

/** `vendor.*` MediaFormat keys reach the codec from API 26 (Android 8.0). */
private const val VENDOR_KEYS_MIN_SDK = 26

/**
 * The vendor low-latency keys for [decoderName]'s family, each set to 1; empty for any other.
 * Names and values as Moonlight's `MediaCodecHelper.setDecoderLowLatencyOptions`
 * (moonlight-stream/moonlight-android), which ships them for low-latency game streaming:
 *
 * - Exynos (`c2.exynos`, `omx.exynos`): `vendor.rtc-ext-dec-low-latency.enable`.
 * - Qualcomm (`c2.qti`, `omx.qcom`): `vendor.qti-ext-dec-picture-order.enable` and
 *   `vendor.qti-ext-dec-low-latency.enable` (Qualcomm's `vidc_vendor_extensions.h`).
 * - MediaTek (`c2.mtk`, `omx.mtk`): `vdec-lowlatency`, which MediaTek's ACodec passes down as
 *   `OMX.MTK.index.param.video.LowLatencyDecode`. It is not a `vendor.` key; a Codec2 decoder
 *   that does not know it ignores it.
 */
fun vendorLowLatencyKeys(decoderName: String): Map<String, Int> {
    val name = decoderName.lowercase()
    fun family(vararg prefixes: String) = prefixes.any { name.startsWith(it) }
    return when {
        family("c2.exynos", "omx.exynos") -> mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1)
        family("c2.qti", "omx.qcom") -> mapOf(
            "vendor.qti-ext-dec-picture-order.enable" to 1,
            "vendor.qti-ext-dec-low-latency.enable" to 1,
        )
        family("c2.mtk", "omx.mtk") -> mapOf("vdec-lowlatency" to 1)
        else -> emptyMap()
    }
}

/**
 * The integer keys a decoder named [decoderName] is configured with, in the order they are set;
 * see [LowLatencyRequest]. [advertisesLowLatency] is its `FEATURE_LowLatency`; [sdkInt] the
 * device's API level.
 */
fun decoderIntegerKeys(
    decoderName: String,
    advertisesLowLatency: Boolean,
    request: LowLatencyRequest,
    sdkInt: Int,
    maxInputSize: Int,
): Map<String, Int> = buildMap {
    val lowLatency = when (request) {
        LowLatencyRequest.REQUESTED -> true
        LowLatencyRequest.MINIMAL -> advertisesLowLatency
    }
    if (lowLatency && sdkInt >= LOW_LATENCY_MIN_SDK) put(MediaFormat.KEY_LOW_LATENCY, 1)
    if (request == LowLatencyRequest.REQUESTED && sdkInt >= VENDOR_KEYS_MIN_SDK) putAll(vendorLowLatencyKeys(decoderName))
    put(MediaFormat.KEY_PRIORITY, 0)
    put(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
}

/**
 * The decoders that rejected [LowLatencyRequest.REQUESTED] (configure or start threw, and the
 * minimal format then worked), by name: they get [LowLatencyRequest.MINIMAL] straight away for the
 * rest of the process. Thread-safe.
 */
class LowLatencyFallbacks {
    private val names = ConcurrentHashMap.newKeySet<String>()

    fun add(decoderName: String) {
        names += decoderName
    }

    operator fun contains(decoderName: String): Boolean = decoderName in names

    companion object {
        /** The process's memory, shared by every [HevcVideoOutput] that is not given its own. */
        val process = LowLatencyFallbacks()
    }
}

/** The overlay's and the log's word for [state]: "on", "fallback" or "off". */
fun lowLatencyLabel(state: LowLatencyState): String = when (state) {
    LowLatencyState.ON -> "on"
    LowLatencyState.FALLBACK -> "fallback"
    LowLatencyState.OFF -> "off"
}
