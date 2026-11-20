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
 * - Qualcomm (`c2.qti`, `omx.qcom`): `vendor.qti-ext-dec-low-latency.enable` (Qualcomm's
 *   `vidc_vendor_extensions.h`), and `vendor.qti-ext-dec-picture-order.enable` unless
 *   [reorders]. Picture order outputs each picture in decode order, as soon as it is decoded:
 *   right only for a stream that never reorders. The Mac's encoder never does (it turns off
 *   `kVTCompressionPropertyKey_AllowFrameReordering`, `MC/CompanionVideoEncoder.swift:69-82`),
 *   but the phone checks the SPS rather than rely on it ([spsMaxNumReorderPics]).
 * - MediaTek (`c2.mtk`, `omx.mtk`): `vdec-lowlatency`, which MediaTek's ACodec passes down as
 *   `OMX.MTK.index.param.video.LowLatencyDecode`. It is not a `vendor.` key; a Codec2 decoder
 *   that does not know it ignores it.
 */
fun vendorLowLatencyKeys(decoderName: String, reorders: Boolean): Map<String, Int> {
    val name = decoderName.lowercase()
    fun family(vararg prefixes: String) = prefixes.any { name.startsWith(it) }
    return when {
        family("c2.exynos", "omx.exynos") -> mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1)
        family("c2.qti", "omx.qcom") -> buildMap {
            // Decode-order output: only for a stream with no reordering (see above).
            if (!reorders) put("vendor.qti-ext-dec-picture-order.enable", 1)
            put("vendor.qti-ext-dec-low-latency.enable", 1)
        }
        family("c2.mtk", "omx.mtk") -> mapOf("vdec-lowlatency" to 1)
        else -> emptyMap()
    }
}

/**
 * The integer keys a decoder named [decoderName] is configured with, in the order they are set;
 * see [LowLatencyRequest]. [advertisesLowLatency] is its `FEATURE_LowLatency`; [sdkInt] the
 * device's API level; [reorders] whether the stream may output pictures out of decode order
 * (`sps_max_num_reorder_pics > 0`, or unknown).
 */
fun decoderIntegerKeys(
    decoderName: String,
    advertisesLowLatency: Boolean,
    request: LowLatencyRequest,
    sdkInt: Int,
    maxInputSize: Int,
    reorders: Boolean = true,
): Map<String, Int> = buildMap {
    val lowLatency = when (request) {
        LowLatencyRequest.REQUESTED -> true
        LowLatencyRequest.MINIMAL -> advertisesLowLatency
    }
    if (lowLatency && sdkInt >= LOW_LATENCY_MIN_SDK) put(MediaFormat.KEY_LOW_LATENCY, 1)
    if (request == LowLatencyRequest.REQUESTED && sdkInt >= VENDOR_KEYS_MIN_SDK) putAll(vendorLowLatencyKeys(decoderName, reorders))
    put(MediaFormat.KEY_PRIORITY, 0)
    put(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
}

/**
 * The decoders that rejected [LowLatencyRequest.REQUESTED], by name: they get
 * [LowLatencyRequest.MINIMAL] straight away for the rest of the process. [add] remembers one at
 * once (a configure-time rejection, or an error before its first output); [reject] counts a
 * one-off rejection (a start failure) and remembers the decoder at the [REJECTIONS_TO_REMEMBER]th.
 * Thread-safe.
 */
class LowLatencyFallbacks {
    private val names = ConcurrentHashMap.newKeySet<String>()
    private val rejections = ConcurrentHashMap<String, Int>()

    fun add(decoderName: String) {
        names += decoderName
    }

    /** Counts a rejection; true when [decoderName] is now remembered. */
    fun reject(decoderName: String): Boolean {
        if (rejections.merge(decoderName, 1, Int::plus)!! >= REJECTIONS_TO_REMEMBER) add(decoderName)
        return decoderName in names
    }

    operator fun contains(decoderName: String): Boolean = decoderName in names

    companion object {
        /** One-off rejections of a decoder before it is remembered. */
        const val REJECTIONS_TO_REMEMBER = 2

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
