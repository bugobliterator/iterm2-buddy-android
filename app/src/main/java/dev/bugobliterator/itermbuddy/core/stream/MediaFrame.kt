package dev.bugobliterator.itermbuddy.core.stream

import java.nio.ByteBuffer

/**
 * A media frame [parseMediaFrame] rejects. The message is upstream's `DecodeError` as
 * `String(describing:)` writes it (`truncated`, `unsupportedVersion(3)`,
 * `payloadLengthMismatch(declared: 5, actual: 4)`), plus `ptsOutOfRange` for the one Android-only
 * rejection.
 */
class MediaFrameException(message: String) : Exception(message)

/**
 * Upstream `CompanionMediaFrame` (`sources/Companion/Shared/CompanionMediaFrame.swift`): one
 * frame on the media channel, header fields plus an HEVC access unit in AVCC framing.
 *
 * [streamID], [sequence] and [generationId] are upstream `UInt32`, held as non-negative [Long]
 * (0 to 2^32 − 1). [ptsMilliseconds] is upstream `UInt64`; [parseMediaFrame] only produces values
 * in 0 to 2^63 − 1. [liveTop] is upstream `Int64`, signed. [generationId] and [liveTop] are null
 * for a version 1 frame (upstream stores 0 there, `CompanionMediaFrame.swift:72-76`).
 *
 * **Payload ownership.** The frame owns a private payload array that nobody else can reach, so a
 * frame queued in the inbox can never be changed under the decoder:
 * - the public constructor copies [payload] (it is used by tests and the fake Mac, not per frame);
 * - [parseMediaFrame] slices the payload out of its input once and hands that new array to the
 *   frame without a second copy, so the input buffer may be reused or changed afterwards;
 * - [payload] returns a fresh copy on every read; the hot path does not read it: [payloadSize]
 *   gives the length for byte budgets and [annexBAccessUnit] converts straight from the private
 *   array. At 30 fps this is one copy out of the decrypted frame and one Annex-B output per frame.
 *
 * Equality compares the payload by content. [toString] prints the payload length only, never its
 * bytes (they are terminal content).
 */
class MediaFrame private constructor(
    val version: Int,
    val flags: Int,
    val streamID: Long,
    val sequence: Long,
    val ptsMilliseconds: Long,
    val generationId: Long?,
    val liveTop: Long?,
    private val bytes: ByteArray,
    @Suppress("UNUSED_PARAMETER") adopt: Unit,
) {
    constructor(
        version: Int,
        flags: Int,
        streamID: Long,
        sequence: Long,
        ptsMilliseconds: Long,
        generationId: Long?,
        liveTop: Long?,
        payload: ByteArray,
    ) : this(version, flags, streamID, sequence, ptsMilliseconds, generationId, liveTop, payload.copyOf(), Unit)

    /** A copy of the access unit (AVCC framing: each NAL prefixed by its 4-byte big-endian length). */
    val payload: ByteArray get() = bytes.copyOf()

    /** The payload length in bytes, without copying it. */
    val payloadSize: Int get() = bytes.size

    /** Flag bit 0: an IDR the decoder can start from (`CompanionMediaFrame.swift:51-53`). */
    val isKeyframe: Boolean get() = flags and FLAG_KEYFRAME != 0

    /** Flag bit 1: a new stream config was sent just before this frame (`CompanionMediaFrame.swift:54-57`). */
    val configChanged: Boolean get() = flags and FLAG_CONFIG_CHANGED != 0

    /** The payload as Annex-B for MediaCodec, as [accessUnitToAnnexB], without copying the payload first. */
    fun annexBAccessUnit(): ByteArray = accessUnitToAnnexB(bytes)

    private fun fields(): List<Any?> =
        listOf(version, flags, streamID, sequence, ptsMilliseconds, generationId, liveTop)

    override fun equals(other: Any?): Boolean =
        other is MediaFrame && other.fields() == fields() && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * fields().hashCode() + bytes.contentHashCode()

    override fun toString(): String =
        "MediaFrame(version=$version, flags=$flags, streamID=$streamID, sequence=$sequence, " +
            "ptsMilliseconds=$ptsMilliseconds, generationId=$generationId, liveTop=$liveTop, " +
            "payload=${bytes.size} bytes)"

    internal companion object {
        const val FLAG_KEYFRAME = 1
        const val FLAG_CONFIG_CHANGED = 2
        const val VERSION_LEGACY = 1
        const val VERSION_CURRENT = 2
        const val HEADER_SIZE_V1 = 22
        const val HEADER_SIZE_V2 = 34

        /** Builds a frame that takes ownership of [payload]; only for an array nobody else holds. */
        fun adopting(
            version: Int,
            flags: Int,
            streamID: Long,
            sequence: Long,
            ptsMilliseconds: Long,
            generationId: Long?,
            liveTop: Long?,
            payload: ByteArray,
        ): MediaFrame = MediaFrame(version, flags, streamID, sequence, ptsMilliseconds, generationId, liveTop, payload, Unit)
    }
}

private const val U32_MASK = 0xFFFFFFFFL

/**
 * Parses one media frame, [bytes] being the frame **after** the `0x01` channel marker. Mirrors
 * upstream `CompanionMediaFrame.init(decoding:)` (`CompanionMediaFrame.swift:130-171`), checks
 * in the same order:
 * 1. fewer than 2 bytes: `truncated` (line 133), whatever the version byte;
 * 2. a version other than 1 or 2: `unsupportedVersion(v)` (lines 136-140);
 * 3. fewer bytes than that version's header (22 or 34): `truncated` (lines 141-143);
 * 4. `payloadLength` (u32) not equal to the bytes after the header, too many or too few:
 *    `payloadLengthMismatch(declared:actual:)` (lines 156-163). An empty payload is valid.
 *
 * `streamID`, `sequence`, `generationId` and `payloadLength` are read unsigned (values at or
 * above 2^31 stay positive, lines 145-156); `liveTop` is signed (`Int64(bitPattern:)`, line 153).
 * Flags are kept raw, unknown bits included, as upstream's `OptionSet` does (line 144).
 *
 * One deviation: upstream accepts any `UInt64` pts (line 147); Android rejects a pts with the top
 * bit set (`ptsOutOfRange`), because a [Long] cannot hold it and no Mac monotonic clock in
 * milliseconds reaches 2^63.
 *
 * The returned frame owns a fresh copy of the payload; [bytes] is not retained.
 *
 * @throws MediaFrameException on any of the above.
 */
fun parseMediaFrame(bytes: ByteArray): MediaFrame {
    if (bytes.size < 2) throw MediaFrameException("truncated")
    val version = bytes[0].toInt() and 0xFF
    val headerSize = when (version) {
        MediaFrame.VERSION_LEGACY -> MediaFrame.HEADER_SIZE_V1
        MediaFrame.VERSION_CURRENT -> MediaFrame.HEADER_SIZE_V2
        else -> throw MediaFrameException("unsupportedVersion($version)")
    }
    if (bytes.size < headerSize) throw MediaFrameException("truncated")

    val buffer = ByteBuffer.wrap(bytes) // big-endian by default
    val flags = bytes[1].toInt() and 0xFF
    val streamID = buffer.getInt(2).toLong() and U32_MASK
    val sequence = buffer.getInt(6).toLong() and U32_MASK
    val pts = buffer.getLong(10)
    var generationId: Long? = null
    var liveTop: Long? = null
    var cursor = 18
    if (version == MediaFrame.VERSION_CURRENT) {
        generationId = buffer.getInt(cursor).toLong() and U32_MASK
        liveTop = buffer.getLong(cursor + 4)
        cursor += 12
    }
    val declaredLength = buffer.getInt(cursor).toLong() and U32_MASK
    val actualLength = bytes.size - headerSize
    if (declaredLength != actualLength.toLong()) {
        throw MediaFrameException("payloadLengthMismatch(declared: $declaredLength, actual: $actualLength)")
    }
    if (pts < 0) throw MediaFrameException("ptsOutOfRange")
    return MediaFrame.adopting(
        version, flags, streamID, sequence, pts, generationId, liveTop,
        bytes.copyOfRange(headerSize, bytes.size),
    )
}
