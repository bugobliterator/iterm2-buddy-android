package dev.bugobliterator.itermbuddy.core.relay

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** What the phone presents after the relay's challenge. */
sealed interface Proof {
    /** `{}`: a room with no verifier yet. With attestation off the relay admits any proof. */
    data object Fresh : Proof

    /** `{"sig": <std base64 Ed25519>}` over the join transcript, for a room locked by a verifier. */
    data class Signed(val signer: JoinSigner) : Proof
}

sealed interface AdmissionResult {
    /** The phone holds the room's phone slot; [registrationToken] is for `/register` (5 min, single use). */
    data class Admitted(val registrationToken: String) : AdmissionResult {
        /** Redacted: the token is a bearer credential for `/register` and must not reach logs. */
        override fun toString(): String = "Admitted(registrationToken=<redacted>)"
    }

    /** The relay's `{"ok":false,"error":...}`: "mac offline", "bad signature", "signature required", ... */
    data class Rejected(val error: String) : AdmissionResult

    /**
     * The socket closed before a result: the relay's own close (for example 1008 "admission
     * timeout"), 1006 for a dropped connection or a local admission timeout, or 1000 when
     * [RelayConnection.close] ran first. 1000 "pairing cycle cap" means the room is gone; see
     * [requiresRescan].
     */
    data class Closed(val code: Int, val reason: String) : AdmissionResult {
        /** As a [RelayClose] for [nextDelayMillis] and [requiresRescan]. */
        val close: RelayClose get() = RelayClose(code, reason)
    }

    /** The WebSocket upgrade got this HTTP status instead of 101. See [isFatalHttp]. */
    data class HttpFailure(val status: Int) : AdmissionResult

    /**
     * No HTTP response: [kind] says why, [detail] is the underlying error for logs. A
     * [UnreachableKind.TLS] failure is a configuration error (wrong host or certificate) that
     * waiting will not fix; the others are what an asleep or offline Mac looks like.
     */
    data class Unreachable(val kind: UnreachableKind, val detail: String) : AdmissionResult

    /** The peer broke the admission protocol: a malformed challenge or result, or a binary frame. */
    data class ProtocolError(val detail: String) : AdmissionResult
}

/** Why a relay could not be reached before any HTTP response. */
enum class UnreachableKind {
    /** The host name did not resolve. */
    DNS,

    /** TCP connect refused or failed, or the network is unreachable. */
    CONNECT,

    /** The TLS handshake or certificate check failed: a configuration error, not a wait. */
    TLS,

    /** No connection within the connect or admission timeout. */
    TIMEOUT,

    /** Any other I/O failure before a response. */
    OTHER,
}

/** Classifies a failure before any HTTP response by walking [error]'s cause chain. */
internal fun classifyUnreachable(error: Throwable): UnreachableKind {
    var current: Throwable? = error
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is UnknownHostException -> return UnreachableKind.DNS
            is SSLException, is CertificateException -> return UnreachableKind.TLS
            is SocketTimeoutException -> return UnreachableKind.TIMEOUT
            is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                return UnreachableKind.CONNECT
        }
        current = current.cause
    }
    return when {
        error is InterruptedIOException && error.message == "timeout" -> UnreachableKind.TIMEOUT
        error is SocketException -> UnreachableKind.CONNECT
        else -> UnreachableKind.OTHER
    }
}

/**
 * The relay closed an admitted connection with [code] and [reason]; 1006 means it dropped. Pass
 * [close] to [nextDelayMillis], and check [requiresRescan] first.
 */
class RelayClosedException(val code: Int, val reason: String) : IOException("relay closed the connection: $code $reason") {
    val close: RelayClose get() = RelayClose(code, reason)
}

/** The relay broke framing after admission, such as by sending a text frame. The connection is closed. */
class RelayProtocolException(message: String) : IOException(message)

/** [RelayConnection.send] could not queue a frame; the connection is closed or closing. */
class RelaySendException(message: String) : IOException(message)

/**
 * One WebSocket to a relay room for the phone role (relay room.js at b8aa2f9).
 *
 * [admit] connects with only `x-relay-room` and `User-Agent` (never `Origin`, which the relay
 * refuses with 403) and runs the text-frame admission: Hello `{"v":1,"role":"phone"}`, Challenge
 * `{"nonce": <std base64, 32 bytes>}`, Proof, then `{"ok":true,"registrationToken":...}` or
 * `{"ok":false,"error":...}`. After admission every relay message is one binary frame carrying one
 * Noise transport message. A text frame then is fatal: the socket closes with 1002 and [incoming]
 * fails with [RelayProtocolException].
 *
 * One connection is admitted once; reconnecting makes a new RelayConnection.
 *
 * @param endpoints where sockets go; join signatures always cover [RelayEndpoints.origin].
 * @param roomName 64 lowercase hex characters, as `roomName(code)` gives.
 * @param admissionTimeoutMillis bound on [admit] from connect to result (upstream: 30 s).
 * @param pingIntervalMillis WebSocket ping cadence; OkHttp fails the socket on a missed pong.
 * @param client the caller's client. An `Origin` header any of its application interceptors add
 *   is stripped. WebSocket calls run no network interceptors, but the production client must not
 *   add `Origin` anywhere regardless.
 */
class RelayConnection(
    client: OkHttpClient,
    private val endpoints: RelayEndpoints,
    private val roomName: String,
    private val admissionTimeoutMillis: Long = DEFAULT_ADMISSION_TIMEOUT_MILLIS,
    pingIntervalMillis: Long = DEFAULT_PING_INTERVAL_MILLIS,
) {
    init {
        require(ROOM_NAME_PATTERN.matches(roomName)) { "room name must be 64 lowercase hex characters" }
    }

    private val client: OkHttpClient = client.newBuilder()
        .pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS)
        .addInterceptor(StripOriginHeader)
        .build()

    // Unbounded on purpose: OkHttp's single reader thread must never block, or pings and the
    // close handshake stall behind a slow consumer. The relay bounds what can pile up here: it
    // splices at most 500 frames/s per room, each at most 256 KiB, and 512 MiB per room per day
    // (room.js:51-61). A consumer that stops reading for long should close the connection.
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Binary frames from the Mac, in order, after admission. Closes normally after [close], or
     * with [RelayClosedException] or [RelayProtocolException] when the relay side ends it.
     */
    val incoming: ReceiveChannel<ByteArray> get() = frames

    private val admission = CompletableDeferred<AdmissionResult>()
    private val lock = Any()
    private var phase = Phase.NEW
    private var admitCalled = false
    private var closeCalled = false
    private var proof: Proof? = null
    private var webSocket: WebSocket? = null

    private enum class Phase { NEW, CONNECTING, HELLO_SENT, PROOF_SENT, ADMITTED, CLOSED }

    /**
     * Test hook: runs after the admission timeout fires and before the timeout takes the lock,
     * which is the window where a late relay result can still win.
     */
    @VisibleForTesting
    internal var beforeTimeoutSettles: (() -> Unit)? = null

    /** Whether admission has a result yet; for tests that drive the timeout race. */
    @VisibleForTesting
    internal val admissionSettled: Boolean get() = admission.isCompleted

    /**
     * Connects and runs admission. Any result other than [AdmissionResult.Admitted] leaves the
     * connection closed. Cancelling the caller closes it too.
     *
     * @throws IllegalStateException on a second call.
     */
    suspend fun admit(proof: Proof): AdmissionResult {
        synchronized(lock) {
            check(!admitCalled) { "admit() runs once per connection" }
            admitCalled = true
            if (phase == Phase.NEW) {
                this.proof = proof
                phase = Phase.CONNECTING
                val request = Request.Builder()
                    .url(endpoints.webSocketUrl)
                    .header(ROOM_HEADER, roomName)
                    .header("User-Agent", USER_AGENT)
                    .build()
                webSocket = client.newWebSocket(request, Listener())
            }
        }
        return try {
            withTimeoutOrNull(admissionTimeoutMillis) { admission.await() } ?: admissionTimedOut()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    /**
     * Queues one binary frame for the Mac.
     *
     * Throws [RelaySendException] when the frame cannot be queued: before admission, after the
     * connection closed, or when OkHttp refuses it (`WebSocket.send` returned false because the
     * socket is closing or its 16 MiB outgoing buffer is full). The frame is then lost and the
     * connection is closed. The caller has already advanced its Noise send nonce for this frame,
     * so it must close its Noise channel and reconnect rather than send again.
     *
     * @throws IllegalArgumentException for a frame over the relay's 256 KiB limit (room.js:51),
     *   which would make the relay close the room with 1009.
     */
    suspend fun send(frame: ByteArray) {
        require(frame.size <= MAX_FRAME_BYTES) { "frame of ${frame.size} bytes exceeds the relay's $MAX_FRAME_BYTES" }
        val socket = synchronized(lock) {
            if (phase != Phase.ADMITTED) throw RelaySendException("cannot send: connection is ${phase.name.lowercase()}")
            webSocket ?: throw RelaySendException("cannot send: no socket")
        }
        if (!socket.send(frame.toByteString())) {
            close()
            throw RelaySendException("cannot send: OkHttp refused the frame")
        }
    }

    /**
     * Closes the connection with 1000 (or cancels a connect still in flight), resolves a pending
     * [admit] with [AdmissionResult.Closed] and closes [incoming]. Safe to call any number of times.
     */
    fun close() {
        val toClose: WebSocket?
        val wasConnecting: Boolean
        synchronized(lock) {
            if (closeCalled) return
            closeCalled = true
            wasConnecting = phase == Phase.CONNECTING
            val wasOpen = phase != Phase.CLOSED
            phase = Phase.CLOSED
            admission.complete(AdmissionResult.Closed(CLOSE_NORMAL, "closed by client"))
            frames.close()
            toClose = webSocket.takeIf { wasOpen }
        }
        when {
            toClose == null -> Unit
            wasConnecting -> toClose.cancel()
            else -> toClose.close(CLOSE_NORMAL, null)
        }
    }

    private suspend fun admissionTimedOut(): AdmissionResult {
        beforeTimeoutSettles?.invoke()
        val socket = synchronized(lock) {
            // A result that landed after the timeout fired wins; an admitted socket stays open.
            if (admission.isCompleted) return@synchronized null
            val result = if (phase == Phase.CONNECTING) {
                AdmissionResult.Unreachable(UnreachableKind.TIMEOUT, "no connection within $admissionTimeoutMillis ms")
            } else {
                AdmissionResult.Closed(CLOSE_ABNORMAL, ADMISSION_TIMEOUT)
            }
            phase = Phase.CLOSED
            admission.complete(result)
            frames.close(RelayClosedException(CLOSE_ABNORMAL, ADMISSION_TIMEOUT))
            webSocket
        }
        socket?.cancel()
        return admission.await()
    }

    /** Ends admission with [result] and closes the socket with [code]. Caller holds [lock]. */
    private fun failAdmission(socket: WebSocket, result: AdmissionResult, code: Int, reason: String?) {
        phase = Phase.CLOSED
        admission.complete(result)
        frames.close()
        socket.close(code, reason)
    }

    private fun onChallenge(socket: WebSocket, text: String) {
        val nonce = parseNonce(text)
        if (nonce == null) {
            failAdmission(socket, AdmissionResult.ProtocolError("bad challenge"), CLOSE_PROTOCOL_ERROR, "bad challenge")
            return
        }
        val proofJson = when (val chosen = proof) {
            is Proof.Signed -> buildJsonObject {
                put("sig", Base64.getEncoder().encodeToString(chosen.signer.sign(nonce, roomName, endpoints.origin)))
            }.toString()
            Proof.Fresh, null -> "{}"
        }
        phase = Phase.PROOF_SENT
        socket.send(proofJson)
    }

    private fun onResult(socket: WebSocket, text: String) {
        val reply = parseObject(text)
        val ok = (reply?.get("ok") as? JsonPrimitive)?.booleanOrNull
        val token = reply?.string("registrationToken")
        val error = reply?.string("error")
        when {
            ok == true && !token.isNullOrEmpty() -> {
                phase = Phase.ADMITTED
                admission.complete(AdmissionResult.Admitted(token))
            }
            ok == false && error != null ->
                failAdmission(socket, AdmissionResult.Rejected(error), CLOSE_NORMAL, null)
            else ->
                failAdmission(socket, AdmissionResult.ProtocolError("bad result"), CLOSE_PROTOCOL_ERROR, "bad result")
        }
    }

    /** The socket ended (close frame or failure) with [admittedError] for an admitted caller. */
    private fun onEnded(beforeAdmission: AdmissionResult, admittedError: IOException) {
        when (phase) {
            Phase.CONNECTING, Phase.HELLO_SENT, Phase.PROOF_SENT -> {
                admission.complete(beforeAdmission)
                frames.close()
            }
            Phase.ADMITTED -> frames.close(admittedError)
            Phase.NEW, Phase.CLOSED -> Unit
        }
        phase = Phase.CLOSED
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (phase != Phase.CONNECTING) return
                phase = Phase.HELLO_SENT
                webSocket.send(HELLO)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                when (phase) {
                    Phase.HELLO_SENT -> onChallenge(webSocket, text)
                    Phase.PROOF_SENT -> onResult(webSocket, text)
                    Phase.ADMITTED -> {
                        phase = Phase.CLOSED
                        frames.close(RelayProtocolException("relay sent a text frame after admission"))
                        webSocket.close(CLOSE_PROTOCOL_ERROR, "text frame after admission")
                    }
                    Phase.NEW, Phase.CONNECTING, Phase.CLOSED -> Unit
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(lock) {
                when (phase) {
                    Phase.ADMITTED -> frames.trySend(bytes.toByteArray())
                    Phase.HELLO_SENT, Phase.PROOF_SENT -> failAdmission(
                        webSocket,
                        AdmissionResult.ProtocolError("binary frame before admission"),
                        CLOSE_PROTOCOL_ERROR,
                        "binary frame before admission",
                    )
                    Phase.NEW, Phase.CONNECTING, Phase.CLOSED -> Unit
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                onEnded(AdmissionResult.Closed(code, reason), RelayClosedException(code, reason))
            }
            // Answer the relay's close frame; a no-op if this side already sent one.
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                onEnded(AdmissionResult.Closed(code, reason), RelayClosedException(code, reason))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                val cause = t.toString()
                val beforeAdmission = when {
                    phase != Phase.CONNECTING -> AdmissionResult.Closed(CLOSE_ABNORMAL, cause)
                    response == null -> AdmissionResult.Unreachable(classifyUnreachable(t), cause)
                    response.code == HTTP_SWITCHING_PROTOCOLS -> AdmissionResult.ProtocolError("bad upgrade: $cause")
                    else -> AdmissionResult.HttpFailure(response.code)
                }
                onEnded(beforeAdmission, RelayClosedException(CLOSE_ABNORMAL, cause))
            }
        }
    }

    private companion object {
        const val DEFAULT_ADMISSION_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_PING_INTERVAL_MILLIS = 15_000L

        /** room.js:15-16: admission protocol version and challenge nonce length. */
        const val PROTOCOL_VERSION = 1
        const val NONCE_BYTES = 32

        /** room.js:51: largest frame the relay splices after admission. */
        const val MAX_FRAME_BYTES = 256 * 1024

        const val CLOSE_NORMAL = 1000
        const val CLOSE_PROTOCOL_ERROR = 1002
        const val CLOSE_ABNORMAL = 1006
        const val HTTP_SWITCHING_PROTOCOLS = 101
        const val ADMISSION_TIMEOUT = "admission timeout"

        val HELLO: String = buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("role", "phone")
        }.toString()

        fun parseObject(text: String): JsonObject? = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

        fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The relay's nonce is standard base64 of exactly 32 random bytes (room.js:376, 394). */
        fun parseNonce(text: String): ByteArray? {
            val encoded = parseObject(text)?.string("nonce") ?: return null
            val nonce = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return nonce.takeIf { it.size == NONCE_BYTES }
        }
    }
}
