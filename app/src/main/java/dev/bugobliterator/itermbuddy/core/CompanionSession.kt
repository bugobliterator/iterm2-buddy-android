package dev.bugobliterator.itermbuddy.core

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
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.isStreamScoped
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.decodePairingConfirmation
import dev.bugobliterator.itermbuddy.core.wire.encodeEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One relay WebSocket as the client uses it: the surface [RelayConnection] exposes. A seam so
 * the client tests can run against an in-process fake relay in virtual time.
 */
internal interface RelaySocket {
    suspend fun admit(proof: Proof): AdmissionResult

    val incoming: ReceiveChannel<ByteArray>

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
class CompanionRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

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
 * and goes to [sink] as [StreamInput.Media] with that byte stripped. A stream-scoped message
 * ([isStreamScoped]) goes to [sink] as [StreamInput.Control], whether or not a request waits for
 * it; a waiting request is completed only after [StreamSink.accept] returns. Neither ever reaches
 * [onEvent]. Everything else goes to its waiting request or to [onEvent], as before.
 *
 * The loop awaits [StreamSink.accept] before it reads the next frame: back-pressure, never a drop.
 * Stream inputs are dropped (logged by kind only) when there is no [sink] or before [linkUp].
 * Time the loop spends waiting on the sink does not count against a request's timeout, so a
 * reply queued behind media a slow sink has not taken yet is not timed out.
 *
 * @param sink where stream inputs go once the link is up; null drops them.
 * @param timeSource measures request timeouts; tests pass the virtual clock.
 */
internal class CompanionSession(
    private val socket: RelaySocket,
    private val channel: NoiseChannel,
    private val confirmationMode: ConfirmationMode,
    private val onEvent: (MacMessage) -> Unit,
    private val log: (String) -> Unit,
    private val sink: StreamSink? = null,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
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

    /** Held while the link is announced and while each stream input is accepted, so [StreamInput.LinkUp] comes first. */
    private val streamLock = Mutex()

    /** The link id once [linkUp] has run; stream inputs before it are dropped. */
    @Volatile
    private var linkId: Long? = null

    private val stallLock = Any()
    private var stalledTotal = Duration.ZERO
    private var stalledSince: ComparableTimeMark? = null

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
     * [timeout] (null: no bound). The bound covers the send too. Time the receive loop spends
     * waiting on the [StreamSink] is not counted: the reply may be queued behind media.
     * [onSent] gets the request ID before the envelope is written.
     *
     * @throws CompanionRequestException if the session ends first or the send fails.
     */
    suspend fun request(message: PhoneMessage, timeout: Duration?, onSent: (Long) -> Unit): MacMessage? {
        val id = nextRequestID.getAndIncrement()
        val reply = CompletableDeferred<MacMessage>()
        pending[id] = reply
        try {
            onSent(id)
            if (timeout == null) {
                sendEnvelope(message, id)
                return awaitReply(reply)
            }
            val start = timeSource.markNow()
            val stalledAtStart = stalled()
            var sent = false
            while (true) {
                val remaining = timeout - (start.elapsedNow() - (stalled() - stalledAtStart))
                if (remaining <= Duration.ZERO) return null
                val answer = withTimeoutOrNull(remaining) {
                    if (!sent) {
                        sendEnvelope(message, id)
                        sent = true
                    }
                    awaitReply(reply)
                }
                // A send cut off by the bound has closed the channel: no reply can come.
                if (answer != null || !sent) return answer
            }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun awaitReply(reply: CompletableDeferred<MacMessage>): MacMessage = select {
        reply.onAwait { it }
        ended.onAwait { throw CompanionRequestException("the connection closed before the reply") }
        unpaired.onAwait { throw CompanionRequestException("the Mac unpaired this phone") }
    }

    /**
     * Makes this session link [id]: stream inputs from here on carry it. [announce] runs first,
     * before any stream input on the link reaches the sink, so it can deliver [StreamInput.LinkUp].
     */
    suspend fun linkUp(id: Long, announce: suspend () -> Unit) {
        streamLock.withLock {
            linkId = id
            announce()
        }
    }

    /** Waits for the receive loop to stop after [close], so nothing more reaches the sink. */
    suspend fun awaitStopped() {
        receiveJob?.join()
    }

    /** Total time the receive loop has spent waiting on the sink, including a wait in progress. */
    private fun stalled(): Duration = synchronized(stallLock) {
        stalledTotal + (stalledSince?.elapsedNow() ?: Duration.ZERO)
    }

    /** Closes the Noise channel and the socket; the receive loop then ends. Idempotent. */
    fun close() {
        closing = true
        channel.close()
        socket.close()
        receiveJob?.cancel()
        finish(null)
    }

    private suspend fun sendEnvelope(message: PhoneMessage, requestID: Long?) {
        sendLock.withLock {
            val frames = try {
                channel.encryptMessage(encodeEnvelope(message, requestID))
            } catch (e: NoiseException) {
                abort()
                throw CompanionRequestException("the connection is closed", e)
            }
            var sent = false
            try {
                for (frame in frames) socket.send(frame)
                sent = true
            } catch (e: IOException) {
                throw CompanionRequestException("the frame could not be sent", e)
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
        try {
            for (frame in socket.incoming) {
                val plaintext = channel.decryptFrame(frame) ?: continue
                heardFromMac = true
                handle(plaintext)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayClosedException) {
            // 1006 is a drop, never a close frame the relay sent.
            relayClose = e.close.takeIf { it.code != CLOSE_ABNORMAL }
            log("relay closed the connection: ${e.code} ${e.reason}")
        } catch (e: IOException) {
            log("connection failed: $e")
        } catch (e: NoiseException) {
            log("Noise channel failed: ${e.message}")
        } finally {
            channel.close()
            socket.close()
            finish(relayClose)
        }
    }

    private suspend fun handle(plaintext: ByteArray) {
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
            deliver("Media") { StreamInput.Media(it, plaintext.copyOfRange(1, plaintext.size)) }
            return
        }
        val (requestID, message) = try {
            decodeEnvelope(plaintext)
        } catch (e: WireDecodeException) {
            log("dropped a frame that does not decode: ${e.message}")
            return
        }
        if (isStreamScoped(message)) {
            // The sink first, so the controller has the stream before the request returns.
            deliver(message.javaClass.simpleName) { StreamInput.Control(it, requestID, message) }
            requestID?.let { pending.remove(it) }?.complete(message)
            return
        }
        val waiter = requestID?.let { pending.remove(it) }
        if (waiter != null) {
            waiter.complete(message)
            return
        }
        if (message == MacMessage.Unpaired) unpaired.complete(Unit)
        onEvent(message)
    }

    /**
     * Hands one stream input to the sink and waits for it to be taken, counting the wait as
     * stalled time. [kind] is all that is logged: inputs carry terminal content.
     */
    private suspend fun deliver(kind: String, input: (linkId: Long) -> StreamInput) {
        val target = sink
        if (target == null) {
            log("dropped a stream input ($kind): no stream sink")
            return
        }
        synchronized(stallLock) { stalledSince = timeSource.markNow() }
        try {
            streamLock.withLock {
                val id = linkId
                if (id == null) {
                    log("dropped a stream input ($kind): the link is not up yet")
                    return
                }
                try {
                    target.accept(input(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("the stream sink failed on $kind: ${e.javaClass.simpleName}")
                }
            }
        } finally {
            synchronized(stallLock) {
                stalledSince?.let { stalledTotal += it.elapsedNow() }
                stalledSince = null
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

        /** First byte of a media frame; a JSON envelope starts with `{` (`CompanionFrameChannel.swift`). */
        const val MEDIA_CHANNEL: Byte = 0x01
    }
}

/** How a session ended: [relayClose] is the relay's close frame, null for anything else. */
internal data class SessionEnd(val relayClose: RelayClose?)
