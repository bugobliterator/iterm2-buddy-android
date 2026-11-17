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

    /** No decoder takes the size; [maxWidth] x [maxHeight] is the most preferred decoder's limit. */
    data class TooLarge(val maxWidth: Int, val maxHeight: Int) : DecoderChoice
}

/**
 * Picks the decoder for a [width] x [height] stream: hardware before software, low-latency before
 * not (a stable order otherwise), and the first in that order that supports the size. When none
 * does, [DecoderChoice.TooLarge] carries the limit of the first in that order, the decoder a
 * smaller (fitted) window would use.
 */
fun chooseDecoder(decoders: List<DecoderInfo>, width: Int, height: Int): DecoderChoice {
    if (decoders.isEmpty()) return DecoderChoice.None
    val ordered = decoders.sortedWith(compareBy({ !it.isHardware }, { !it.supportsLowLatency }))
    ordered.firstOrNull { width > 0 && height > 0 && it.isSizeSupported(width, height) }
        ?.let { return DecoderChoice.Use(it) }
    val preferred = ordered.first()
    return DecoderChoice.TooLarge(preferred.maxWidth, preferred.maxHeight)
}
