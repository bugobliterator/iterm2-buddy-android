package dev.bugobliterator.itermbuddy.fakemac

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.noise.NoiseChannel
import dev.bugobliterator.itermbuddy.core.noise.NoiseException
import dev.bugobliterator.itermbuddy.core.noise.NoiseResponder
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.pairing.sasCode
import dev.bugobliterator.itermbuddy.core.relay.JoinRole
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayClosedException
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.RelaySendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One parked Mac socket: binary frames to and from the phone after admission. */
interface MacSocket {
    val incoming: ReceiveChannel<ByteArray>

    /** Completes when the socket closes, from either side, even while frames wait unread. */
    val closed: Deferred<Unit>

    suspend fun send(frame: ByteArray)

    fun close()
}

sealed interface MacPark {
    class Parked(val socket: MacSocket) : MacPark

    data class Refused(val reason: String) : MacPark
}

/** Parks the Mac in a relay room; [sign] signs a challenge nonce, null for proof `{}`. */
interface MacRelay {
    suspend fun park(roomName: String, sign: ((ByteArray) -> ByteArray)?): MacPark
}

/**
 * iTerm2's side of pairing, for tests, built from the real Kotlin core: the real Noise responder,
 * the real [JoinSigner] with [JoinRole.MAC], and replies taken from the v3.7.7 golden samples.
 *
 * It follows upstream v3.7.7:
 * - Before it has a room secret it parks with proof `{}`; after `relayRoomSecret` it keeps the
 *   secret and signs its parks (`CompanionLink.swift:429-453`).
 * - With no pinned phone it is in SAS mode: after the handshake it stays silent until
 *   [enterCode], then sends the bare verdict (`CompanionPairingController.swift:1470-1490`). On
 *   acceptance it pins the phone's static key; on rejection it closes, mints a new pid and parks
 *   under it, as iTerm2 regenerates the code (`:1478-1488`), so the old code is dead. If the
 *   socket closes while it waits for the code, it dismisses the entry and parks again under the
 *   same pid. Frames the phone sends before the verdict wait in the socket's queue and are
 *   handled once the link starts, like the plugin's queue (`iTermCompanionPlugin.js:52-69`).
 * - With a pinned phone (a reconnect) it sends nothing first and rejects any other static key
 *   (`CompanionParkSupervisor.swift:425-437`).
 * - Its link answers `hello`, `relayRoomSecret` and `ping`, records `unpairing`, and can send
 *   `unpaired`. When its socket closes it parks again.
 * - Tests drive streams with [sendMedia], [sendMessage] and [sendSample], answer requests with
 *   [onRequest], and read what the phone sent in [received].
 */
class FakeMac(
    private val relay: MacRelay,
    code: PairingCode,
    private val macStaticPrivate: ByteArray,
    private val scope: CoroutineScope,
    private val reparkDelayMillis: Long = DEFAULT_REPARK_DELAY_MILLIS,
) {
    /** A Mac on a real relay, reached over OkHttp. */
    constructor(
        endpoints: RelayEndpoints,
        code: PairingCode,
        macStaticPrivate: ByteArray,
        http: OkHttpClient,
        scope: CoroutineScope,
    ) : this(OkHttpMacRelay(http, endpoints), code, macStaticPrivate, scope)

    /** The pairing code the Mac currently advertises; a rejected code is replaced. */
    @Volatile var code: PairingCode = code
        private set

    private val room: String get() = roomName(code)

    private var regenerations = 0

    /** The hello reply's revision and minimum peer; v3.7.7 sends 15 and 11. */
    @Volatile var revision = MAC_REVISION

    @Volatile var minimumPeer = MAC_MINIMUM_PEER

    /** When false, requests are recorded but never answered (a stalled Mac whose relay link is fine). */
    @Volatile var answering = true

    /** How many of the next `relayRoomSecret` requests get an `error` reply (a failed Keychain write). */
    val roomSecretFailures = AtomicInteger(0)

    /**
     * When set, the Mac is unpairing as the next `relayRoomSecret` arrives: it answers with an
     * `error` (storage stopped) and then sends `unpaired` (`CompanionLink.swift:442-453`).
     */
    @Volatile var unpairOnRoomSecret = false

    /** The room secret from the phone's `relayRoomSecret`; preset it for a Mac that already has one. */
    @Volatile var roomSecret: ByteArray? = null

    @Volatile var pinnedPhoneStatic: ByteArray? = null

    private val awaiting = MutableStateFlow<String?>(null)

    /** The code the Mac expects while it is in SAS mode with a phone handshaken, else null. */
    val awaitingCode: StateFlow<String?> = awaiting.asStateFlow()

    /** Every decrypted envelope from the phone, decoded, in the order received. */
    val received: List<JsonObject> get() = receivedList

    private val receivedList = CopyOnWriteArrayList<JsonObject>()

    /** Handlers that answer a request case instead of the built-in replies; see [onRequest]. */
    private val requestHandlers = ConcurrentHashMap<String, (JsonObject) -> JsonObject?>()

    val parks = AtomicInteger()
    val handshakes = AtomicInteger()
    val confirmationsSent = AtomicInteger()
    val unpairings = AtomicInteger()
    val staticMismatches = AtomicInteger()

    @Volatile private var job: Job? = null

    @Volatile private var verdict: CompletableDeferred<Boolean>? = null

    @Volatile private var link: Link? = null

    private class Link(val socket: MacSocket, val channel: NoiseChannel) {
        val sendLock = Mutex()
    }

    /** Received messages decoded as envelopes, in order: a snapshot of [received]. */
    fun receivedEnvelopes(): List<JsonObject> = receivedList.toList()

    /** The payload cases of [receivedEnvelopes], in order. */
    fun receivedCases(): List<String> = receivedEnvelopes().map { it.getValue("payload").jsonObject.keys.single() }

    /** Starts parking and serving; the first park runs before this returns when the relay answers at once. */
    fun start() {
        check(job == null) { "already started" }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { runLoop() }
    }

    /** Goes offline: iTerm2 quit. */
    fun stop() {
        job?.cancel()
        job = null
        link?.socket?.close()
    }

    /** The user types the code on the Mac ([accept]) or declines it. */
    fun enterCode(accept: Boolean) {
        val pending = checkNotNull(verdict) { "the Mac is not waiting for a code" }
        pending.complete(accept)
    }

    /** Closes the current socket; the relay then closes the phone's with 1001 "peer gone". */
    fun dropSocket() {
        link?.socket?.close()
    }

    /** Sends an unsolicited `unpaired`, as the Mac does when the user removes the phone. */
    suspend fun sendUnpaired() {
        val current = checkNotNull(link) { "no link" }
        send(current, fixtureEnvelope("unpaired.json", null))
    }

    /**
     * Fixture paths (relative to `fixtures/`, such as `messages/live-session/session-tree-push.json`)
     * whose payloads are sent, with no request ID, just before each hello reply: a Mac that
     * persisted the phone's revision can push before it answers the hello.
     */
    val beforeHelloReply = CopyOnWriteArrayList<String>()

    /** Sends the payload of the fixture at [path] (relative to `fixtures/`) with [requestID]. */
    suspend fun sendSample(path: String, requestID: Long?) {
        val current = checkNotNull(link) { "no link" }
        send(current, sampleEnvelope(path, requestID))
    }

    /** Sends one media frame: [encodedFrame] (header and payload) behind the `0x01` channel byte. */
    suspend fun sendMedia(encodedFrame: ByteArray) {
        sendRaw(byteArrayOf(MEDIA_CHANNEL) + encodedFrame)
    }

    /** Sends the payload [message] (`{"<case>": {...}}`) in an envelope with [requestID]. */
    suspend fun sendMessage(message: JsonObject, requestID: Long?) {
        val current = checkNotNull(link) { "no link" }
        send(current, envelope(message, requestID))
    }

    /**
     * Answers requests whose payload case is [case] with [answer] instead of the built-in reply:
     * it gets the phone's envelope and returns the reply payload (`{"<case>": {...}}`), sent with
     * the same request ID, or null to send nothing.
     */
    fun onRequest(case: String, answer: (JsonObject) -> JsonObject?) {
        requestHandlers[case] = answer
    }

    /** Sends raw [plaintext] on the link, such as a frame the phone cannot decode. */
    suspend fun sendRaw(plaintext: ByteArray) {
        val current = checkNotNull(link) { "no link" }
        send(current, plaintext)
    }

    private suspend fun runLoop() {
        while (true) {
            val secret = roomSecret
            val sign: ((ByteArray) -> ByteArray)? = secret?.let { s ->
                { nonce -> JoinSigner(s, JoinRole.MAC).sign(nonce, room, code.relayOrigin) }
            }
            val park = relay.park(room, sign)
            parks.incrementAndGet()
            if (park is MacPark.Parked) {
                val keepParking = try {
                    serve(park.socket)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    true
                } catch (_: ClosedReceiveChannelException) {
                    true
                } catch (_: NoiseException) {
                    true
                } finally {
                    link = null
                    park.socket.close()
                }
                if (!keepParking) return
            }
            delay(reparkDelayMillis)
        }
    }

    /** Serves one parked socket; returns false when the Mac should go away (a rejected code). */
    private suspend fun serve(socket: MacSocket): Boolean {
        val responder = NoiseResponder(macStaticPrivate, code.prologue)
        val channel = try {
            responder.readMessage1(socket.incoming.receive())
            socket.send(responder.writeMessage2())
            responder.readMessage3(socket.incoming.receive())
            handshakes.incrementAndGet()
            responder.split()
        } finally {
            responder.close()
        }
        val phoneStatic = responder.remoteStaticPublicKey
        val current = Link(socket, channel)
        val pinned = pinnedPhoneStatic
        if (pinned == null) {
            val pending = CompletableDeferred<Boolean>()
            verdict = pending
            awaiting.value = sasCode(responder.handshakeHash)
            val accepted: Boolean? = try {
                select {
                    pending.onAwait { it }
                    socket.closed.onAwait { null }
                }
            } finally {
                verdict = null
                awaiting.value = null
            }
            // The phone went away mid-entry: dismiss the entry and park again.
            if (accepted == null) return true
            val bytes = Json.parseToJsonElement(
                Fixtures.read("messages/confirmation/${if (accepted) "accepted" else "rejected"}.json"),
            ).jsonObject.getValue("bytes").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            send(current, bytes)
            confirmationsSent.incrementAndGet()
            if (!accepted) {
                // Void this pid and advertise a new one: the rejected code can never pair.
                code = PairingCode(code.responderStaticPublicKey, "${code.pairingID}-regenerated-${++regenerations}", code.relayOrigin)
                return true
            }
            pinnedPhoneStatic = phoneStatic
        } else if (!pinned.contentEquals(phoneStatic)) {
            staticMismatches.incrementAndGet()
            return true
        }
        link = current
        for (frame in socket.incoming) {
            val plaintext = channel.decryptFrame(frame) ?: continue
            val envelope = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
            receivedList += envelope
            answer(current, envelope)
        }
        return true
    }

    private suspend fun answer(current: Link, envelope: JsonObject) {
        val requestID = (envelope["requestID"] as? JsonPrimitive)?.long
        val payload = envelope.getValue("payload").jsonObject
        val case = payload.keys.single()
        val handler = requestHandlers[case]
        if (handler != null) {
            handler(envelope)?.let { send(current, envelope(it, requestID)) }
            return
        }
        when (case) {
            "hello" -> if (answering) {
                for (path in beforeHelloReply) send(current, sampleEnvelope(path, null))
                send(current, helloReply(requestID))
            }
            "relayRoomSecret" -> if (answering) {
                if (unpairOnRoomSecret) {
                    unpairOnRoomSecret = false
                    send(current, fixtureEnvelope("error-reply.json", requestID))
                    send(current, fixtureEnvelope("unpaired.json", null))
                } else if (roomSecretFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    send(current, fixtureEnvelope("error-reply.json", requestID))
                } else {
                    val encoded = payload.getValue("relayRoomSecret").jsonObject.getValue("_0").jsonPrimitive.content
                    roomSecret = Base64.getDecoder().decode(encoded)
                    send(current, fixtureEnvelope("relay-room-secret-stored.json", requestID))
                }
            }
            "ping" -> if (answering) send(current, fixtureEnvelope("pong.json", requestID))
            "unpairing" -> unpairings.incrementAndGet()
        }
    }

    private fun helloReply(requestID: Long?): ByteArray {
        val sample = fixtureJson("hello-mac.json")
        val hello = sample.getValue("payload").jsonObject.getValue("hello").jsonObject
        val body = JsonObject(hello + mapOf("revision" to JsonPrimitive(revision), "minimumPeer" to JsonPrimitive(minimumPeer)))
        return envelope(JsonObject(mapOf("hello" to body)), requestID)
    }

    private fun fixtureEnvelope(name: String, requestID: Long?): ByteArray = sampleEnvelope("messages/session/$name", requestID)

    private fun sampleEnvelope(path: String, requestID: Long?): ByteArray {
        val json = Json.parseToJsonElement(Fixtures.read(path)).jsonObject.getValue("json").jsonObject
        return envelope(json.getValue("payload").jsonObject, requestID)
    }

    private fun envelope(payload: JsonObject, requestID: Long?): ByteArray {
        val map = LinkedHashMap<String, JsonElement>()
        map["payload"] = payload
        if (requestID != null) map["requestID"] = JsonPrimitive(requestID)
        return JsonObject(map).toString().toByteArray(Charsets.UTF_8)
    }

    private suspend fun send(current: Link, plaintext: ByteArray) {
        current.sendLock.withLock {
            for (frame in current.channel.encryptMessage(plaintext)) current.socket.send(frame)
        }
    }

    companion object {
        const val MAC_REVISION = 15
        const val MAC_MINIMUM_PEER = 11
        const val DEFAULT_REPARK_DELAY_MILLIS = 50L
        private const val MEDIA_CHANNEL: Byte = 0x01

        /** A session sample's `json` tree. */
        fun fixtureJson(name: String): JsonObject =
            Json.parseToJsonElement(Fixtures.read("messages/session/$name")).jsonObject.getValue("json").jsonObject
    }
}

/**
 * Parks the Mac on a real relay over OkHttp: Hello `{"v":1,"role":"mac"}`, the challenge, then
 * `{}` or `{"sig":...}`, and `{"ok":true}` (a Mac gets no registration token).
 */
class OkHttpMacRelay(private val http: OkHttpClient, private val endpoints: RelayEndpoints) : MacRelay {
    override suspend fun park(roomName: String, sign: ((ByteArray) -> ByteArray)?): MacPark {
        val result = CompletableDeferred<MacPark>()
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(endpoints.webSocketUrl)
            .header("x-relay-room", roomName)
            .header("User-Agent", "FakeMac")
            .build()
        val listener = object : WebSocketListener() {
            private var challenged = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"v":1,"role":"mac"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val reply = Json.parseToJsonElement(text).jsonObject
                if (!challenged) {
                    challenged = true
                    val nonce = Base64.getDecoder().decode(reply.getValue("nonce").jsonPrimitive.content)
                    val proof = sign?.let { buildJsonObject { put("sig", Base64.getEncoder().encodeToString(it(nonce))) } }
                    webSocket.send(proof?.toString() ?: "{}")
                    return
                }
                if (reply["ok"]?.jsonPrimitive?.content == "true") {
                    result.complete(MacPark.Parked(Socket(webSocket, frames, closed)))
                } else {
                    result.complete(MacPark.Refused(reply["error"]?.jsonPrimitive?.content ?: text))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.trySend(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ended(code, reason)
                webSocket.close(CLOSE_NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                ended(CLOSE_ABNORMAL, t.toString())

            private fun ended(code: Int, reason: String) {
                result.complete(MacPark.Refused("closed $code $reason"))
                frames.close(RelayClosedException(code, reason))
                closed.complete(Unit)
            }
        }
        val webSocket = http.newWebSocket(request, listener)
        return withTimeoutOrNull(PARK_TIMEOUT_MILLIS) { result.await() } ?: run {
            webSocket.cancel()
            MacPark.Refused("park timed out")
        }
    }

    private class Socket(
        private val webSocket: WebSocket,
        private val frames: Channel<ByteArray>,
        override val closed: CompletableDeferred<Unit>,
    ) : MacSocket {
        override val incoming: ReceiveChannel<ByteArray> get() = frames

        override suspend fun send(frame: ByteArray) {
            if (!webSocket.send(frame.toByteString())) throw RelaySendException("mac socket refused the frame")
        }

        override fun close() {
            webSocket.close(CLOSE_NORMAL, null)
            frames.close()
            closed.complete(Unit)
        }
    }

    private companion object {
        const val PARK_TIMEOUT_MILLIS = 10_000L
        const val CLOSE_NORMAL = 1000
        const val CLOSE_ABNORMAL = 1006
    }
}
