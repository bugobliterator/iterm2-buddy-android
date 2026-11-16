package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlin.time.Duration

/**
 * What the connection layer hands the stream controller, in wire order, through one [StreamSink].
 *
 * Every input carries the link id it arrived on. Link ids start at 1 and grow by one for each
 * connection that reaches the connected step. [LinkUp] comes before anything else on its link and
 * [LinkDown] after everything else on it, so a controller can scope its state by link id.
 */
sealed interface StreamInput {
    /** The link is connected; [macRevision] is the Mac's revision from its `hello` reply. */
    data class LinkUp(val linkId: Long, val macRevision: Int) : StreamInput

    /**
     * The link has ended and nothing more arrives on it. [messageTooLarge] means the Mac sent a
     * message over the Noise reassembly cap, so the client closed the link (and reconnects).
     */
    data class LinkDown(val linkId: Long, val messageTooLarge: Boolean = false) : StreamInput

    /** One media frame with its `0x01` channel byte stripped (*notes* → Media frame). */
    class Media(val linkId: Long, val bytes: ByteArray) : StreamInput {
        /** Frames are terminal content: only the size is shown. */
        override fun toString(): String = "Media(linkId=$linkId, bytes=${bytes.size})"
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
 * the next frame, so a sink that suspends applies back-pressure all the way to the relay socket
 * (whose byte budget then closes and reconnects a link that stalls too long); nothing is dropped.
 * Time spent in [accept] does not count against request timeouts.
 *
 * [accept] must never wait for a reply from the Mac (directly or through a lock a requester
 * holds): the receive loop that would deliver the reply is the one waiting on it. Sending
 * ([StreamLink.send]) from inside [accept] is fine.
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
