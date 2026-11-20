package dev.bugobliterator.itermbuddy.core

import dev.bugobliterator.itermbuddy.applog.logSummary
import dev.bugobliterator.itermbuddy.core.chat.ChatInboxStoppedException
import dev.bugobliterator.itermbuddy.core.chat.ChatInput
import dev.bugobliterator.itermbuddy.core.chat.ChatSink
import dev.bugobliterator.itermbuddy.core.chat.isChatScoped
import dev.bugobliterator.itermbuddy.core.input.InputTrace
import dev.bugobliterator.itermbuddy.core.input.isTracedInput
import dev.bugobliterator.itermbuddy.core.input.traceEnvelope
import dev.bugobliterator.itermbuddy.core.noise.NoiseChannel
import dev.bugobliterator.itermbuddy.core.noise.NoiseException
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.DEFAULT_MAX_QUEUED_BYTES
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayClose
import dev.bugobliterator.itermbuddy.core.relay.RelayClosedException
import dev.bugobliterator.itermbuddy.core.relay.RelayConnection
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.RelayRegistrar
import dev.bugobliterator.itermbuddy.core.stream.MEDIA_CHANNEL
import dev.bugobliterator.itermbuddy.core.stream.NO_RECEIVE_TIME
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.isStreamScoped
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.RAW_CHAT_CASES
import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.decodePairingConfirmation
import dev.bugobliterator.itermbuddy.core.wire.encodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.peekChatID
import dev.bugobliterator.itermbuddy.core.wire.peekEnvelope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * One relay WebSocket as the client uses it: the surface [RelayConnection] exposes. A seam so
 * the client tests can run against an in-process fake relay in virtual time.
 */
internal interface RelaySocket {
    suspend fun admit(proof: Proof): AdmissionResult

    val incoming: ReceiveChannel<ByteArray>

    /**
     * When the frame read last from [incoming] arrived, in [System.nanoTime]; [NO_RECEIVE_TIME]
     * when unknown (the default: a socket that does not stamp its frames).
     */
    val lastReceivedAtNanos: Long get() = NO_RECEIVE_TIME

    /**
     * Queues [frame] for the Mac. Must never suspend once it has started handing the frame over
     * (production queues on OkHttp and returns): [CompanionSession.sendEnvelope] relies on it, so
     * that a caller cancelled mid-send (the input sender's 2 s `withTimeout`) can only be cancelled
     * while waiting for the send lock, before any frame of its envelope was sent.
     */
    suspend fun send(frame: ByteArray)

    fun close()
}

/** Opens relay sockets and registers verifiers; production is [OkHttpRelayTransport]. */
internal interface RelayTransport {
    fun open(endpoints: RelayEndpoints, roomName: String): RelaySocket

    suspend fun register(
        endpoints: RelayEndpoints,
        roomName: String,
        registrationToken: String,
        verifier: ByteArray,
    ): RegisterResult
}

/** [RelayTransport] over OkHttp: a [RelayConnection] per socket and a [RelayRegistrar] per call. */
internal class OkHttpRelayTransport(
    private val http: OkHttpClient,
    private val admissionTimeoutMillis: Long,
    private val registerTimeoutMillis: Long,
    private val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
) : RelayTransport {
    override fun open(endpoints: RelayEndpoints, roomName: String): RelaySocket {
        val connection = RelayConnection(http, endpoints, roomName, admissionTimeoutMillis, maxQueuedBytes = maxQueuedBytes)
        return object : RelaySocket {
            override suspend fun admit(proof: Proof): AdmissionResult = connection.admit(proof)

            override val incoming: ReceiveChannel<ByteArray> get() = connection.incoming

            override val lastReceivedAtNanos: Long get() = connection.lastReceivedAtNanos

            override suspend fun send(frame: ByteArray) = connection.send(frame)

            override fun close() = connection.close()
        }
    }

    override suspend fun register(
        endpoints: RelayEndpoints,
        roomName: String,
        registrationToken: String,
        verifier: ByteArray,
    ): RegisterResult = RelayRegistrar(http, endpoints, registerTimeoutMillis).register(roomName, registrationToken, verifier)
}

/** A request could not complete: no live connection, the connection closed, or no reply in time. */
open class CompanionRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A [CompanionRequestException] for a message that never reached the Mac: not connected, the
 * named link had ended, or the envelope could not be written whole (the channel then closes, so
 * the Mac cannot read any part of it). A request failing this way was never seen by the Mac; any
 * other failure may come after the Mac received it.
 */
class RequestNotSentException(message: String, cause: Throwable? = null) : CompanionRequestException(message, cause)

/** How a session treats the Mac's first decrypted frame. */
internal enum class ConfirmationMode {
    /** Reconnect: every frame is an envelope. */
    NONE,

    /** Fresh pairing: the first frame is the bare verdict; anything but "accepted" is a rejection. */
    REQUIRED,

    /**
     * Pending recovery: the first frame is either a bare verdict (the Mac is still in SAS mode)
     * or an envelope (the Mac had pinned this phone and its link is answering).
     */
    POSSIBLE,
}

/**
 * The encrypted session over one admitted relay socket, after the Noise handshake.
 *
 * Sends are serialised: each message's frames all reach the socket, in order, before the next
 * message is encrypted. If any frame cannot be sent (the socket refuses it, or the send is
 * cancelled part way), the Noise channel and the socket are closed, because the send nonce has
 * already advanced and the Mac could never decrypt anything after it.
 *
 * A failed send closes the channel and socket but leaves ending the session to the receive loop,
 * so a close frame the relay sent just before (say "displaced") is still reported in [ended].
 *
 * The receive loop decrypts frames in arrival order. A frame that does not decode
 * ([WireDecodeException]) or is empty is dropped and logged and the session stays up, as
 * upstream `CompanionSession.swift:120-127` does. A Noise failure or the socket closing ends the
 * session; [ended] then completes with the relay's close frame, if the relay sent one.
 *
 * Routing, after the first-frame confirmation check: a frame whose first byte is `0x01` is media
 * and goes to [sink] as [StreamInput.Media] (the frame as decrypted; the marker is skipped by
 * offset, never copied off). Every other frame is first peeked ([peekEnvelope]: its request ID
 * and payload case, by one allocation-free scan; a frame the peek rejects is dropped as one that
 * does not decode). Then:
 * - A chat case whose body can be big ([RAW_CHAT_CASES]: `history`, `chatsAndSessions`,
 *   `chatListChanged`), and a `delivery` longer than [rawDeliveryBytes] (an `appendAttachment`
 *   with inline bytes can reach the Noise cap), is never parsed here: it goes to [chatSink] as
 *   [ChatInput.Payload], the frame's own bytes, so a live stream's frames never wait behind its
 *   decode. One longer than [maxRawChatBytes] goes as [ChatInput.PayloadTooLarge] instead, judged
 *   by its length after the linear, allocation-free peek and never decoded, its bytes dropped.
 *   Its waiting request, if any, then completes with [MacMessage.ChatPayloadTaken]. The peek can
 *   accept a frame [decodeEnvelope] would reject; the consumer finds that out when it decodes
 *   ([ChatInput.Payload.decode]) and reports it as the request's outcome.
 * - Any other frame is decoded ([decode]). A stream-scoped message ([isStreamScoped]) goes to
 *   [sink] as [StreamInput.Control]; a chat-scoped one ([isChatScoped]) goes to [chatSink] as
 *   [ChatInput.Message]; either way whether or not a request waits for it, and a waiting request
 *   is completed only after the sink took it. Neither ever reaches [onEvent]. An unsolicited
 *   `error` goes to [chatSink] and then to [onEvent] (a refused `deleteChat` comes back so).
 *   Everything else goes to its waiting request or to [onEvent], as before.
 *
 * The loop awaits each accept before it reads the next frame: back-pressure, never a drop. Both
 * sinks are fed under one lock, the same one [linkUp] holds while it announces the link, so stream
 * and chat inputs keep one wire order between them and each sink sees its LinkUp first. Inputs are
 * dropped (logged by kind or case only) when their sink is null or before [linkUp]. A chat reply
 * the chat sink did not take is never handed to its waiter: a request never reports a reply the
 * chat feature did not see. When the session goes on (no chat sink, the link not up, the sink
 * threw) the waiter fails at once with [CompanionRequestException]; when the sink stalled or
 * failed the link, it fails as the session ends.
 *
 * Sink bounds (the same for either sink, which share them):
 * - One accept longer than [sinkStall], or one that throws a cancellation of its own while the
 *   loop is active, ends the session: the frames already queued are read without waiting
 *   (replies and `unpaired` are honoured; stream inputs are dropped and their waiters left to
 *   fail), then the session ends as a lost link and the client reconnects. A relay close frame
 *   queued behind is still reported in [ended].
 * - A missed LinkUp ([linkUp]) ends the session the same way, before any input reaches the sink.
 * - A request with a bound that passes while frames are queued behind an accept the sink has not
 *   finished (the reply may be among them) keeps waiting, in slices of a tenth of its bound, for
 *   at most one more bound: never more than twice its bound in all. Waiting for the stream lock
 *   (an [linkUp] still announcing) is not a sink wait.
 *
 * Session trees: each [MacMessage.SessionTreeMessage] is stamped here, before it goes to its
 * waiter or to [onEvent], with the next value of [arrivals], so a tree pushed through the events
 * path and a tree returned to a request can be put back in wire order by whoever applies them.
 *
 * @param sink where stream inputs go once the link is up; null drops them.
 * @param sinkStall the longest one accept may take before the session ends.
 * @param arrivals the session-tree arrival counter; the client shares one across all its links.
 * @param chatSink where chat inputs go once the link is up; null drops them.
 * @param maxRawChatBytes the raw chat cap: a longer raw chat frame is never parsed.
 * @param rawDeliveryBytes a `delivery` frame longer than this crosses raw, decoded by the consumer.
 * @param decode the envelope decoder for every frame that is not raw chat; tests count its calls.
 */
internal class CompanionSession(
    private val socket: RelaySocket,
    private val channel: NoiseChannel,
    private val confirmationMode: ConfirmationMode,
    private val onEvent: (MacMessage) -> Unit,
    private val log: (String) -> Unit,
    private val sink: StreamSink? = null,
    private val sinkStall: Duration = DEFAULT_SINK_STALL,
    private val arrivals: AtomicLong = AtomicLong(),
    private val chatSink: ChatSink? = null,
    private val maxRawChatBytes: Int = DEFAULT_MAX_RAW_CHAT_BYTES,
    private val rawDeliveryBytes: Int = DEFAULT_RAW_DELIVERY_BYTES,
    private val decode: (ByteArray) -> Pair<Long?, MacMessage> = { decodeEnvelope(it) },
) {
    private val sendLock = Mutex()
    private val nextRequestID = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<MacMessage>>()

    @Volatile
    private var awaitingFirstFrame = confirmationMode != ConfirmationMode.NONE

    /** The Mac's bare verdict: true for "accepted", false for anything else in [ConfirmationMode.REQUIRED]. */
    val confirmation = CompletableDeferred<Boolean>()

    /** Completes when the session ends, with the relay's close frame or null (drop, local close, Noise failure). */
    val ended = CompletableDeferred<SessionEnd>()

    /** How the session ended, once it has; the same value [ended] completes with. */
    @Volatile
    var endedWith: SessionEnd? = null
        private set

    /** Whether any frame from the Mac has decrypted: proof the Mac is there. */
    @Volatile
    var heardFromMac = false
        private set

    /** Whether the session has been closed or aborted locally; [ended] follows promptly. */
    @Volatile
    var closing = false
        private set

    /** Completes when the Mac sends an unsolicited `unpaired`. */
    val unpaired = CompletableDeferred<Unit>()

    private var receiveJob: Job? = null

    /**
     * Held while the link is announced and while each stream or chat input is accepted, so each
     * sink's LinkUp comes first and the two sinks share one wire order.
     */
    private val streamLock = Mutex()

    /** The link id once [linkUp] has run; stream and chat inputs before it are dropped. */
    @Volatile
    private var linkId: Long? = null

    /** Whether the receive loop is inside an accept (the stream lock held). */
    @Volatile
    private var accepting = false

    /** Accepts begun so far, so a request can tell the loop worked through a slice. */
    private val acceptsBegun = AtomicLong()

    /**
     * Set when the sink failed the link: an accept outlasted [sinkStall], the sink threw its own
     * cancellation, or it missed the LinkUp. The loop drains what is queued and the session ends.
     */
    @Volatile
    private var endingForSink = false

    /** Whether a [ChatInboxStoppedException] has been logged on this session: it is logged once, not per frame. */
    private var chatInboxStoppedLogged = false

    /** Whether the session ended because the Mac sent a message over the Noise reassembly cap. */
    val messageTooLarge: Boolean get() = channel.messageTooLarge

    /** Starts the receive loop in [scope]; the session ends when [scope] is cancelled. */
    fun start(scope: CoroutineScope) {
        receiveJob = scope.launch { receiveLoop() }
    }

    /** Sends [message] with no request ID (fire-and-forget). */
    suspend fun send(message: PhoneMessage) = sendEnvelope(message, null)

    /**
     * Sends [message] as a request and waits for the reply with the same request ID. Bound it
     * with a timeout; this waits as long as the session lives.
     *
     * @throws CompanionRequestException if the session ends first or the send fails.
     */
    suspend fun request(message: PhoneMessage): MacMessage =
        request(message, timeout = null, onSent = {}) ?: throw CompanionRequestException("no reply")

    /**
     * Sends [message] as a request and returns the reply, or null when none came within
     * [timeout] (null: no bound). [onSent] gets the request ID before the envelope is written.
     *
     * The bound runs from when the envelope has been handed to the socket: the send only waits
     * for [sendLock] behind other sends, and socket sends do not suspend. When it passes while
     * the receive loop is working through frames the sink is slow to take, with more queued
     * behind (the reply may be one), the request waits on in slices of a tenth of [timeout] while
     * that lasts, at most [EXTENSION_SLICES] of them: never more than twice [timeout] in all.
     *
     * @throws CompanionRequestException if the session ends first or the send fails.
     */
    suspend fun request(message: PhoneMessage, timeout: Duration?, onSent: (Long) -> Unit): MacMessage? {
        val id = nextRequestID.getAndIncrement()
        val reply = CompletableDeferred<MacMessage>()
        pending[id] = reply
        try {
            onSent(id)
            sendEnvelope(message, id)
            if (timeout == null) return awaitReply(reply)
            var acceptsSeen = acceptsBegun.get()
            withTimeoutOrNull(timeout) { awaitReply(reply) }?.let { return it }
            val slice = timeout / EXTENSION_SLICES
            var slices = 0
            while (slices < EXTENSION_SLICES && sinkBusy(acceptsSeen)) {
                acceptsSeen = acceptsBegun.get()
                slices++
                withTimeoutOrNull(slice) { awaitReply(reply) }?.let { return it }
            }
            if (slices > 0) log("no reply to request $id within $timeout and $slices extension slices behind a busy sink")
            return null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Whether a reply could be stuck behind the sink: frames are queued and the loop is in an
     * accept now or began one since [acceptsSeen]. A loop waiting for the stream lock is not.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sinkBusy(acceptsSeen: Long): Boolean =
        !socket.incoming.isEmpty && (accepting || acceptsBegun.get() != acceptsSeen)

    private suspend fun awaitReply(reply: CompletableDeferred<MacMessage>): MacMessage = select {
        reply.onAwait { it }
        ended.onAwait { throw CompanionRequestException("the connection closed before the reply") }
        unpaired.onAwait { throw CompanionRequestException("the Mac unpaired this phone") }
    }

    /**
     * Makes this session link [id]: stream and chat inputs from here on carry it. [announce] runs
     * first, before any input on the link reaches either sink, so it can deliver
     * [StreamInput.LinkUp] and [ChatInput.LinkUp]. When [announce] returns false (a sink missed its
     * LinkUp) the session is closed before the lock is released, so nothing reaches a sink on a
     * link it never saw come up; this then returns false.
     */
    suspend fun linkUp(id: Long, announce: suspend () -> Boolean): Boolean = streamLock.withLock {
        linkId = id
        announce().also { taken ->
            if (!taken) {
                endingForSink = true
                close()
            }
        }
    }

    /** Waits for the receive loop to stop after [close], so nothing more reaches the sink. */
    suspend fun awaitStopped() {
        receiveJob?.join()
    }

    /** Closes the Noise channel and the socket; the receive loop then ends. Idempotent. */
    fun close() {
        closing = true
        channel.close()
        socket.close()
        receiveJob?.cancel()
        finish(null)
    }

    /**
     * Encrypts and sends one envelope under [sendLock]. The only suspension point is acquiring the
     * lock: once held, nothing here suspends ([RelaySocket.send] must not), so cancellation (such
     * as a caller's `withTimeout`) either prevents the send entirely or does not interrupt it, and
     * never leaves part of an envelope sent with the Noise nonce advanced. Keep it that way.
     */
    private suspend fun sendEnvelope(message: PhoneMessage, requestID: Long?) {
        sendLock.withLock {
            val frames = try {
                val envelope = encodeEnvelope(message, requestID)
                if (isTracedInput(message)) {
                    InputTrace.current.log { "send envelope requestID=$requestID ${traceEnvelope(envelope)}" }
                }
                channel.encryptMessage(envelope)
            } catch (e: NoiseException) {
                abort()
                throw RequestNotSentException("the connection is closed", e)
            }
            var sent = false
            try {
                for (frame in frames) socket.send(frame)
                sent = true
            } catch (e: IOException) {
                throw RequestNotSentException("the frame could not be sent", e)
            } finally {
                // The nonce advanced for every frame: one not sent desynchronises the channel.
                if (!sent) abort()
            }
        }
    }

    /** Stops the channel and the socket; the receive loop then ends the session with what it saw. */
    private fun abort() {
        closing = true
        channel.close()
        socket.close()
        if (receiveJob == null) finish(null)
    }

    private suspend fun receiveLoop() {
        var relayClose: RelayClose? = null
        // When the first frame of the message being reassembled arrived (a message can span frames).
        var messageReceivedAt = NO_RECEIVE_TIME
        var midMessage = false
        try {
            while (true) {
                val frame = if (endingForSink) {
                    // Draining after a sink failure: only what is already queued. A close the
                    // relay sent behind it (say "displaced") is still attributed below.
                    val queued = socket.incoming.tryReceive()
                    queued.exceptionOrNull()?.let { throw it }
                    queued.getOrNull() ?: break
                } else {
                    val next = socket.incoming.receiveCatching()
                    next.exceptionOrNull()?.let { throw it }
                    next.getOrNull() ?: break
                }
                if (!midMessage) messageReceivedAt = socket.lastReceivedAtNanos
                midMessage = true
                val plaintext = channel.decryptFrame(frame) ?: continue
                midMessage = false
                heardFromMac = true
                handle(plaintext, messageReceivedAt)
            }
            if (endingForSink) log("ending the link: the stream or chat sink failed or stalled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayClosedException) {
            // 1006 is a drop, never a close frame the relay sent.
            relayClose = e.close.takeIf { it.code != CLOSE_ABNORMAL }
            log("relay closed the connection: ${e.code} ${e.reason}")
        } catch (e: IOException) {
            log("connection failed: ${logSummary(e)}")
        } catch (e: NoiseException) {
            log("Noise channel failed: ${e.message}")
        } finally {
            channel.close()
            socket.close()
            finish(relayClose)
        }
    }

    /** One decrypted message; [receivedAtNanos] is when its first frame arrived ([RelaySocket.lastReceivedAtNanos]). */
    private suspend fun handle(plaintext: ByteArray, receivedAtNanos: Long) {
        if (awaitingFirstFrame) {
            awaitingFirstFrame = false
            val verdict = decodePairingConfirmation(plaintext)
            when {
                verdict != null -> {
                    confirmation.complete(verdict)
                    return
                }
                confirmationMode == ConfirmationMode.REQUIRED -> {
                    // Upstream accepts only an exact "accepted"; anything else is a decline.
                    log("first frame is not a pairing confirmation; treating it as declined")
                    confirmation.complete(false)
                    return
                }
                // POSSIBLE: the Mac's link is answering, so this is an envelope.
            }
        }
        if (plaintext.isEmpty()) {
            log("dropped an empty frame")
            return
        }
        if (plaintext[0] == MEDIA_CHANNEL) {
            deliver("Media") { StreamInput.Media(it, plaintext, receivedAtNanos) }
            return
        }
        val peek = try {
            peekEnvelope(plaintext)
        } catch (e: WireDecodeException) {
            // The peek's messages name keys and positions only, as the decoder's do.
            log("dropped a frame that does not decode: ${e.message}")
            return
        }
        if (peek.case in RAW_CHAT_CASES || (peek.case == DELIVERY_CASE && plaintext.size > rawDeliveryBytes)) {
            deliverRawChat(peek.case, peek.requestID, plaintext)
            return
        }
        val (requestID, decoded) = try {
            decode(plaintext)
        } catch (e: WireDecodeException) {
            log("dropped a frame that does not decode: ${e.message}")
            return
        }
        // The receive loop is the one place frames are in wire order: number trees here.
        val message = if (decoded is MacMessage.SessionTreeMessage) decoded.copy(arrival = arrivals.incrementAndGet()) else decoded
        if (isStreamScoped(message)) {
            // The sink first, so the controller has the stream before the request returns. If the
            // sink has failed or stalled it never saw this, so the waiter is left to fail when
            // the session ends: a request never reports a stream the sink does not know.
            deliver(message.javaClass.simpleName) { StreamInput.Control(it, requestID, message) }
            if (!endingForSink) requestID?.let { pending.remove(it) }?.complete(message)
            return
        }
        if (isChatScoped(peek.case)) {
            // As for stream messages: the sink first, then the waiter, and a reply nobody waits for
            // any more still reaches the sink (it is news: a late chatCreated, say).
            val taken = deliverChat(message.javaClass.simpleName) { ChatInput.Message(it, requestID, message, plaintext.size) }
            settleChatReply(requestID, taken, message)
            return
        }
        if (requestID == null && message is MacMessage.Error && chatSink != null) {
            // An unsolicited error is chat news too (a refused deleteChat, HB:458-470) as well as an event.
            deliverChat("Error") { ChatInput.Message(it, null, message, plaintext.size) }
        }
        if (requestID != null) {
            val waiter = pending.remove(requestID)
            if (waiter != null) {
                waiter.complete(message)
            } else {
                // An orphan reply: its request timed out or was cancelled. It is never an event
                // (a reply can carry terminal content), so only its kind is logged.
                log("dropped an orphan reply (${message.javaClass.simpleName}) to request $requestID")
            }
            return
        }
        if (message == MacMessage.Unpaired) unpaired.complete(Unit)
        onEvent(message)
    }

    /**
     * A raw chat frame: to the chat sink unparsed ([ChatInput.Payload]), or, over
     * [maxRawChatBytes], as [ChatInput.PayloadTooLarge] with its bytes dropped. Its waiter completes
     * with [MacMessage.ChatPayloadTaken] only once the sink took it. [case] and sizes are all that
     * is logged.
     */
    private suspend fun deliverRawChat(case: String, requestID: Long?, plaintext: ByteArray) {
        val size = plaintext.size
        val tooLarge = size > maxRawChatBytes
        // A second scan only for a frame too large to decode, before the lock: it names the chat.
        // A raw delivery names its chat too, so one that does not decode still marks that chat's
        // transcript as having lost an append.
        val chatID = if (tooLarge || case == DELIVERY_CASE) peekChatID(plaintext) else null
        if (tooLarge) log("a $case of $size bytes is over the raw chat cap of $maxRawChatBytes; not parsed")
        val taken = deliverChat(case) { id ->
            if (tooLarge) ChatInput.PayloadTooLarge(id, case, chatID, requestID, size) else ChatInput.Payload(id, requestID, case, plaintext, chatID)
        }
        settleChatReply(requestID, taken, MacMessage.ChatPayloadTaken(case, size))
    }

    /**
     * Completes the waiter for [requestID], if any, with [reply] when the chat sink [taken] it. When
     * it did not and the session goes on, the waiter fails at once rather than wait out its bound;
     * when the sink failed the link, it is left to fail as the session ends.
     */
    private fun settleChatReply(requestID: Long?, taken: Boolean, reply: MacMessage) {
        if (requestID == null || endingForSink) return
        val waiter = pending.remove(requestID) ?: return
        if (taken) {
            waiter.complete(reply)
        } else {
            waiter.completeExceptionally(CompanionRequestException("the chat sink did not take the reply"))
        }
    }

    /** Hands one stream input to [sink]; see [deliverTo]. */
    private suspend fun deliver(kind: String, input: (linkId: Long) -> StreamInput) {
        deliverTo("stream", sink?.let { target -> { linkId: Long -> target.accept(input(linkId)) } }, kind)
    }

    /** Hands one chat input to [chatSink]; see [deliverTo]. Returns whether the sink took it. */
    private suspend fun deliverChat(kind: String, input: (linkId: Long) -> ChatInput): Boolean =
        deliverTo("chat", chatSink?.let { target -> { linkId: Long -> target.accept(input(linkId)) } }, kind)

    /**
     * Runs [accept] for one input of the [name]d sink under the ordering lock with the link id, and
     * waits, at most [sinkStall], for it to return. Returns whether it did (false when dropped, the
     * sink stalled or threw). [kind] is all that is logged: inputs carry terminal and chat content.
     */
    private suspend fun deliverTo(name: String, accept: (suspend (linkId: Long) -> Unit)?, kind: String): Boolean {
        if (accept == null || endingForSink) {
            log("dropped a $name input ($kind): ${if (accept == null) "no $name sink" else "the $name sink failed or stalled"}")
            return false
        }
        streamLock.withLock {
            val id = linkId
            if (id == null) {
                log("dropped a $name input ($kind): the link is not up yet")
                return false
            }
            acceptsBegun.incrementAndGet()
            accepting = true
            try {
                val taken = withTimeoutOrNull(sinkStall) { accept(id) }
                if (taken == null) {
                    log("the $name sink did not take $kind within $sinkStall; ending the link")
                    endingForSink = true
                    return false
                }
                return true
            } catch (e: CancellationException) {
                // The receive loop itself is being cancelled: pass it on. Otherwise the sink threw
                // a cancellation of its own (its scope died): a sink failure that ends the link.
                if (!currentCoroutineContext().isActive) throw e
                log("the $name sink failed on $kind: ${e.javaClass.simpleName}; ending the link")
                endingForSink = true
                return false
            } catch (e: ChatInboxStoppedException) {
                // A dead chat consumer refuses every chat frame at once: say so once per session.
                if (!chatInboxStoppedLogged) {
                    chatInboxStoppedLogged = true
                    log("the $name sink failed on $kind: ${e.javaClass.simpleName}; chat inputs are refused from here on")
                }
                return false
            } catch (e: Exception) {
                log("the $name sink failed on $kind: ${e.javaClass.simpleName}")
                return false
            } finally {
                accepting = false
            }
        }
    }

    private fun finish(relayClose: RelayClose?) {
        val end = SessionEnd(relayClose)
        synchronized(this) {
            if (endedWith != null) return
            endedWith = end
        }
        ended.complete(end)
        pending.values.forEach { it.completeExceptionally(CompanionRequestException("the connection closed")) }
    }

    private companion object {
        const val CLOSE_ABNORMAL = 1006

        /** Slices of a request's bound it may wait on behind a busy sink: one more bound in all. */
        const val EXTENSION_SLICES = 10

        const val DELIVERY_CASE = "delivery"
    }
}

/** Default for [ClientTimeouts.sinkStall]. */
internal val DEFAULT_SINK_STALL: Duration = 30.seconds

/**
 * The raw chat cap (the client's `maxRawChatBytes`): a raw chat frame (a `history`, the chat list,
 * a big `delivery`) longer than this, 8 MiB, is never parsed and reaches the chat sink as
 * [ChatInput.PayloadTooLarge]. Well under the 32 MiB Noise cap; the tree decode's peak at this size
 * fits the Pixel's heap (`HistoryMemoryTest`).
 */
const val DEFAULT_MAX_RAW_CHAT_BYTES: Int = 8 shl 20

/**
 * The client's `rawDeliveryBytes`: a `delivery` frame longer than this, 256 KiB, crosses to the
 * chat sink raw and is decoded by its consumer, not on the receive loop; smaller ones (text deltas,
 * typing) decode on the loop.
 */
const val DEFAULT_RAW_DELIVERY_BYTES: Int = 256 shl 10

/** How a session ended: [relayClose] is the relay's close frame, null for anything else. */
internal data class SessionEnd(val relayClose: RelayClose?)
