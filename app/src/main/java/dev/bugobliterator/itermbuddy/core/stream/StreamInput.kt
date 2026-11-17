package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlin.time.Duration

/** First byte of a media channel frame; a JSON envelope starts with `{` (`CompanionFrameChannel.swift`). */
const val MEDIA_CHANNEL: Byte = 0x01

/**
 * What the connection layer hands the stream controller, in wire order, through one [StreamSink].
 *
 * Every input carries the link id it arrived on. Link ids start at 1 and grow by one for each
 * connection that reaches the connected step. No [Media] or [Control] reaches the sink before its
 * link's [LinkUp] or after its [LinkDown], so a controller can scope its state by link id.
 *
 * Guarantees and their limits:
 * - [LinkUp] always precedes any [Media] or [Control] on its link. Each marker waits at most
 *   `ClientTimeouts.sinkStall` for the sink; a [LinkUp] the sink misses ends that link as lost
 *   (the client reconnects with a new id) before anything else is delivered on it.
 * - A [LinkDown] can arrive for a link the controller never saw come up (its [LinkUp] was
 *   missed or cancelled part way): ignore it. A [LinkDown] the sink misses is logged and
 *   dropped, so cancel, unpair and reconnect never hang on the sink.
 * - A newer link id supersedes everything on older ones, whatever markers were missed.
 */
sealed interface StreamInput {
    /** The link is connected; [macRevision] is the Mac's revision from its `hello` reply. */
    data class LinkUp(val linkId: Long, val macRevision: Int) : StreamInput

    /**
     * The link has ended and nothing more arrives on it. [messageTooLarge] means the Mac sent a
     * message over the Noise reassembly cap, so the client closed the link (and reconnects).
     */
    data class LinkDown(val linkId: Long, val messageTooLarge: Boolean = false) : StreamInput

    /**
     * One media frame (*notes* → Media frame). It holds the decrypted channel frame as received,
     * `0x01` marker included, so a 3 MiB keyframe is not copied just to drop one byte; [parse]
     * reads it from after the marker.
     */
    class Media(val linkId: Long, private val channelFrame: ByteArray) : StreamInput {
        init {
            require(channelFrame.isNotEmpty() && channelFrame[0] == MEDIA_CHANNEL) { "not a media channel frame" }
        }

        /** The media frame's size in bytes (header and payload, marker excluded), for byte budgets. */
        val size: Int get() = channelFrame.size - 1

        /** Parses the media frame; the result owns a copy of the payload. Throws [MediaFrameException]. */
        fun parse(): MediaFrame = parseMediaFrame(channelFrame, offset = 1)

        /** A copy of the media frame's bytes, marker excluded: for tests and inspection. */
        fun copyBytes(): ByteArray = channelFrame.copyOfRange(1, channelFrame.size)

        /** Frames are terminal content: only the size is shown. */
        override fun toString(): String = "Media(linkId=$linkId, bytes=$size)"
    }

    /**
     * A stream-scoped control message ([isStreamScoped]). [requestID] is as the Mac echoed it:
     * the start request's ID on a `streamStarted` (`MC/CompanionHostBridge.swift:1288-1289`),
     * null on a push.
     */
    data class Control(val linkId: Long, val requestID: Long?, val message: MacMessage) : StreamInput
}

/**
 * Takes [StreamInput]s in wire order. The session's receive loop awaits [accept] before it reads
 * the next frame, so a sink that suspends applies back-pressure to the relay socket; nothing is
 * dropped while the link stays up.
 *
 * Bounds: one [accept] of a [StreamInput.Media] or [StreamInput.Control] that takes longer than
 * `ClientTimeouts.sinkStall`, or that throws a `CancellationException` of its own, ends the link:
 * the frames already queued are read for replies and `unpaired`, stream inputs are dropped, a
 * request waiting for a stream-scoped reply fails rather than report what the sink never saw,
 * and the client reconnects. A missed [StreamInput.LinkUp] ends the link too; a missed
 * [StreamInput.LinkDown] is dropped. A request whose reply is queued behind frames the sink is
 * still taking gets up to one more of its own bound, never more.
 *
 * [accept] must be cancellable, and must never wait for a reply from the Mac, directly or through
 * a lock a requester holds: the receive loop that would deliver the reply is the one waiting on
 * it, so such a request just times out. Sending ([StreamLink.send]) from inside [accept] is fine.
 */
fun interface StreamSink {
    suspend fun accept(input: StreamInput)
}

/** Whether [message] belongs to a stream and so goes to the [StreamSink] rather than to `events`. */
fun isStreamScoped(message: MacMessage): Boolean = when (message) {
    is MacMessage.StreamStarted, is MacMessage.StreamConfigMessage, is MacMessage.StreamEnded -> true
    else -> false
}

/** Sending to the Mac on one link, as the stream controller uses it. */
interface StreamLink {
    /**
     * Sends [message] with no request ID, in the caller's order, and returns once its frames are
     * handed to the socket.
     *
     * @param onLink when set, the message is sent only if this is still the current link.
     * @throws dev.bugobliterator.itermbuddy.core.CompanionRequestException when not connected,
     *   when [onLink] is not the current link, or when the send fails.
     */
    suspend fun send(message: PhoneMessage, onLink: Long? = null)

    /**
     * Sends [message] as a request and returns the Mac's reply.
     *
     * @param onLink as for [send].
     * @param timeout overrides the client's rpc bound for this request.
     * @param onSent called with the assigned request ID before the envelope is written.
     * @throws dev.bugobliterator.itermbuddy.core.CompanionRequestException as for [send], on a
     *   timeout, or if the connection drops first.
     */
    suspend fun request(
        message: PhoneMessage,
        onLink: Long? = null,
        timeout: Duration? = null,
        onSent: (requestID: Long) -> Unit = {},
    ): MacMessage
}
