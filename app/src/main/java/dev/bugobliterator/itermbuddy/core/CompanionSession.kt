package dev.bugobliterator.itermbuddy.core

import dev.bugobliterator.itermbuddy.core.noise.NoiseChannel
import dev.bugobliterator.itermbuddy.core.noise.NoiseException
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayClose
import dev.bugobliterator.itermbuddy.core.relay.RelayClosedException
import dev.bugobliterator.itermbuddy.core.relay.RelayConnection
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.RelayRegistrar
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
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
) : RelayTransport {
    override fun open(endpoints: RelayEndpoints, roomName: String): RelaySocket {
        val connection = RelayConnection(http, endpoints, roomName, admissionTimeoutMillis)
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
 * The receive loop decrypts frames in arrival order. A frame that does not decode
 * ([WireDecodeException]) or is empty is dropped and logged and the session stays up, as
 * upstream `CompanionSession.swift:120-127` does. A Noise failure or the socket closing ends the
 * session; [ended] then completes with the relay's close frame, if the relay sent one.
 */
internal class CompanionSession(
    private val socket: RelaySocket,
    private val channel: NoiseChannel,
    private val confirmationMode: ConfirmationMode,
    private val onEvent: (MacMessage) -> Unit,
    private val log: (String) -> Unit,
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

    /** Completes when the Mac sends an unsolicited `unpaired`. */
    val unpaired = CompletableDeferred<Unit>()

    private var receiveJob: Job? = null

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
    suspend fun request(message: PhoneMessage): MacMessage {
        val id = nextRequestID.getAndIncrement()
        val reply = CompletableDeferred<MacMessage>()
        pending[id] = reply
        try {
            sendEnvelope(message, id)
            return select {
                reply.onAwait { it }
                ended.onAwait { throw CompanionRequestException("the connection closed before the reply") }
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Closes the Noise channel and the socket; the receive loop then ends. Idempotent. */
    fun close() {
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
                close()
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
                if (!sent) close()
            }
        }
    }

    private suspend fun receiveLoop() {
        var relayClose: RelayClose? = null
        try {
            for (frame in socket.incoming) {
                val plaintext = channel.decryptFrame(frame) ?: continue
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

    private fun handle(plaintext: ByteArray) {
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
        val (requestID, message) = try {
            decodeEnvelope(plaintext)
        } catch (e: WireDecodeException) {
            log("dropped a frame that does not decode: ${e.message}")
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
    }
}

/** How a session ended: [relayClose] is the relay's close frame, null for anything else. */
internal data class SessionEnd(val relayClose: RelayClose?)
