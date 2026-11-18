package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope

/**
 * What the connection layer hands the chat feature, in wire order, through one [ChatSink]: the
 * chat-scoped messages ([isChatScoped]) and unsolicited errors, bracketed per link, as
 * [dev.bugobliterator.itermbuddy.core.stream.StreamInput] is for streams. None of them ever
 * reaches `CompanionClient.events`, whose buffer drops when full (an unsolicited `error` goes to
 * both).
 *
 * The same guarantees as stream inputs:
 * - Link ids are the client's; [LinkUp] precedes every other input on its link, and nothing from
 *   a link reaches the sink after its [LinkDown]. The session's one ordering lock covers stream
 *   and chat inputs, so the two sinks see one wire order between them.
 * - A [LinkUp] the sink misses (`ClientTimeouts.sinkStall`) ends that link before anything else
 *   is delivered on it; a [LinkDown] can arrive for a link the sink never saw come up; a missed
 *   [LinkDown] is logged and dropped; a newer link id supersedes everything older.
 *
 * The Mac sends a subscription's `history` before any live delivery on it, and its control lane
 * keeps order (`HB:1411-1443`, `CompanionPriorityOutbox.swift:5-21`): this order is the wire's.
 */
sealed interface ChatInput {
    /** The link is connected; [macRevision] and [aiAvailable] are from the Mac's `hello` (absent `aiAvailable` is true, `MSG:986-991`). */
    data class LinkUp(val linkId: Long, val macRevision: Int, val aiAvailable: Boolean) : ChatInput

    /** The link has ended. [messageTooLarge]: the Mac sent a message over the Noise reassembly cap. */
    data class LinkDown(val linkId: Long, val messageTooLarge: Boolean = false) : ChatInput

    /**
     * A chat-scoped message decoded on the receive loop, or an unsolicited `error`. [requestID] is
     * as the Mac echoed it: a reply's request, null on a push. A reply reaches the sink whether or
     * not its request still waits (a late `chatCreated` is still news). [frameBytes] is the size of
     * the frame it was decoded from, which [ChatInbox] charges to its byte bound.
     */
    data class Message(val linkId: Long, val requestID: Long?, val message: MacMessage, val frameBytes: Int) : ChatInput {
        /** [MacMessage]s that carry chat content show counts and IDs only; this adds nothing more. */
        override fun toString(): String =
            "Message(linkId=$linkId, requestID=$requestID, message=$message, frameBytes=$frameBytes)"
    }

    /**
     * A big-bodied chat frame as decrypted, never parsed on the receive loop: a
     * [dev.bugobliterator.itermbuddy.core.wire.RAW_CHAT_CASES] case (`history`, `chatsAndSessions`,
     * `chatListChanged`), or a `delivery` over the session's raw delivery threshold (an
     * `appendAttachment` with inline bytes, say). [case] is its payload's one key, read by
     * `peekEnvelope`; [bytes] is the whole envelope, owned by this input (not copied). The consumer
     * decodes it with [decode], off the loop.
     */
    class Payload(val linkId: Long, val requestID: Long?, val case: String, val bytes: ByteArray) : ChatInput {
        /** The envelope's size, for the inbox's byte bound. */
        val size: Int get() = bytes.size

        /**
         * Decodes the envelope as the receive loop would have: bytes, a string, the kotlinx tree,
         * then the chat codecs, with [attachmentBudgets]. Never throws: a frame `peekEnvelope`
         * accepted but the decoder rejects (a bad `\u` escape, invalid UTF-8, nesting deeper than
         * kotlinx allows, a nested trailing comma) is [PayloadDecoding.Undecodable], which names
         * this input's request so the consumer can report it as that request's outcome.
         */
        fun decode(attachmentBudgets: AttachmentBudgets = AttachmentBudgets()): PayloadDecoding = try {
            PayloadDecoding.Decoded(requestID, decodeEnvelope(bytes, attachmentBudgets).second)
        } catch (e: WireDecodeException) {
            // Its message names keys and cases only, never values (Envelope.kt).
            PayloadDecoding.Undecodable(requestID, case, e.message ?: "does not decode")
        } catch (e: RuntimeException) {
            // Never the exception's message: a parser's message can quote the input.
            PayloadDecoding.Undecodable(requestID, case, e.javaClass.simpleName)
        } catch (e: StackOverflowError) {
            PayloadDecoding.Undecodable(requestID, case, e.javaClass.simpleName)
        }

        /** The bytes are chat content: the case and size only. */
        override fun toString(): String = "Payload(linkId=$linkId, requestID=$requestID, case=$case, bytes=$size)"
    }

    /**
     * A frame that would have crossed as a [Payload] but is over the raw chat cap
     * (`maxRawChatBytes`, 8 MiB by default), judged by its length after the linear, allocation-free
     * peek and before any decoding: it was never parsed and its bytes are gone. [case] is its
     * payload case; [chatID] is the body's top-level `chatID` when a second such scan finds one (a
     * `history` or `delivery`; null for the list cases or when it cannot be read). A `history` too
     * large still means its subscription is installed on the Mac (`HB:1411-1427`).
     */
    data class PayloadTooLarge(
        val linkId: Long,
        val case: String,
        val chatID: String?,
        val requestID: Long?,
        val sizeBytes: Int,
    ) : ChatInput
}

/** What [ChatInput.Payload.decode] made of a raw chat frame. */
sealed interface PayloadDecoding {
    val requestID: Long?

    /** The message the frame holds; its `toString` shows counts and IDs only. */
    data class Decoded(override val requestID: Long?, val message: MacMessage) : PayloadDecoding

    /**
     * The frame did not decode and is dropped (upstream drops such a frame too,
     * `CompanionSession.swift:120-127`). [reason] names keys, cases or an exception class, never a
     * value. A non-null [requestID] means that request's reply will never come: report this as
     * its outcome, so nothing waits on it.
     */
    data class Undecodable(override val requestID: Long?, val case: String, val reason: String) : PayloadDecoding
}

/**
 * Takes [ChatInput]s in wire order, with the same contract as
 * [dev.bugobliterator.itermbuddy.core.stream.StreamSink]: the receive loop awaits [accept] before
 * it reads the next frame (back-pressure, never a drop); one accept longer than
 * `ClientTimeouts.sinkStall`, or one that throws a `CancellationException` of its own, ends the
 * link and a reply queued behind it fails; a missed [ChatInput.LinkUp] ends the link too.
 *
 * [accept] must be cancellable and must never wait for a reply from the Mac, directly or through a
 * lock a requester holds: it runs while the receive loop holds the session's ordering lock, so
 * the reply could never be read. Sending from inside [accept] is fine. [ChatInbox] is the sink to
 * use: its accept only enqueues.
 */
fun interface ChatSink {
    suspend fun accept(input: ChatInput)
}

/** The payload cases that go to the [ChatSink] rather than to `events` or a plain waiter. */
private val CHAT_SCOPED_CASES: Set<String> = setOf(
    "history",
    "delivery",
    "typingStatus",
    "turnLifecycle",
    "messagesRemoved",
    "chatListChanged",
    "chatsAndSessions",
    "chatCreated",
    "aiAvailabilityChanged",
)

/**
 * Whether a payload [case] belongs to the chat feature and so goes to the [ChatSink], in wire order
 * with its subscription's other messages, whether or not a request waits for it. Not
 * `mentionsResolved`, `workgroupInfo`, `sessionScreenInfo` or `error`: plain replies, which go to
 * their waiter (an unsolicited `error` goes to the sink as well as to `events`).
 */
fun isChatScoped(case: String): Boolean = case in CHAT_SCOPED_CASES
