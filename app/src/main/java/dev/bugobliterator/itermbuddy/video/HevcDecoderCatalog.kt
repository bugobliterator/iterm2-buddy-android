package dev.bugobliterator.itermbuddy.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * One HEVC decoder on this device, as [MediaCodecCatalog] reads it from `MediaCodecList`.
 * [maxWidth] and [maxHeight] are the upper bounds of the supported widths and heights; not every
 * pair under both is supported, so [isSizeSupported] is the real test.
 */
data class DecoderInfo(
    val name: String,
    val isHardware: Boolean,
    val supportsLowLatency: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val isSizeSupported: (Int, Int) -> Boolean,
)

/** The HEVC decoders on this device. */
interface HevcDecoderCatalog {
    fun decoders(): List<DecoderInfo>
}

/**
 * The device's `video/hevc` decoders from `MediaCodecList(REGULAR_CODECS)`: decoders only,
 * aliases skipped (they name a decoder already listed).
 */
class MediaCodecCatalog : HevcDecoderCatalog {
    override fun decoders(): List<DecoderInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                !info.isEncoder && !info.isAlias &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
            .mapNotNull { info ->
                val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val video = capabilities.videoCapabilities ?: return@mapNotNull null
                DecoderInfo(
                    name = info.name,
                    isHardware = info.isHardwareAccelerated,
                    supportsLowLatency = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    isSizeSupported = { width, height -> video.isSizeSupported(width, height) },
                )
            }
}

/** What [chooseDecoder] picked for a stream size. */
sealed interface DecoderChoice {
    /** Decode with [info]. */
    data class Use(val info: DecoderInfo) : DecoderChoice

    /** The device has no HEVC decoder. */
    data object None : DecoderChoice

    /** The size is over the most preferred decoder's limit, [maxWidth] x [maxHeight], and no decoder takes it. */
    data class TooLarge(val maxWidth: Int, val maxHeight: Int) : DecoderChoice

    /**
     * The size is within the most preferred decoder's limits but no decoder supports it (its
     * shape or alignment, or a zero side): a smaller window would not help, so it is not
     * [TooLarge].
     */
    data object UnsupportedSize : DecoderChoice
}

/** HEVC decoders in preference order: hardware before software, low-latency before not, otherwise as listed. */
fun preferenceOrder(decoders: List<DecoderInfo>): List<DecoderInfo> =
    decoders.sortedWith(compareBy({ !it.isHardware }, { !it.supportsLowLatency }))

/**
 * Every decoder that supports a [width] x [height] stream, in [preferenceOrder]: the first is
 * [chooseDecoder]'s pick, the rest are the fallbacks when it keeps failing.
 */
fun decoderCandidates(decoders: List<DecoderInfo>, width: Int, height: Int): List<DecoderInfo> =
    if (width <= 0 || height <= 0) emptyList() else preferenceOrder(decoders).filter { it.isSizeSupported(width, height) }

/**
 * Picks the decoder for a [width] x [height] stream: the first of [decoderCandidates]. When none
 * supports the size: [DecoderChoice.TooLarge] with the most preferred decoder's limit (the decoder
 * a smaller, fitted window would use) when the size is over that limit, else
 * [DecoderChoice.UnsupportedSize].
 */
fun chooseDecoder(decoders: List<DecoderInfo>, width: Int, height: Int): DecoderChoice {
    if (decoders.isEmpty()) return DecoderChoice.None
    decoderCandidates(decoders, width, height).firstOrNull()?.let { return DecoderChoice.Use(it) }
    val preferred = preferenceOrder(decoders).first()
    return if (width > preferred.maxWidth || height > preferred.maxHeight) {
        DecoderChoice.TooLarge(preferred.maxWidth, preferred.maxHeight)
    } else {
        DecoderChoice.UnsupportedSize
    }
}
