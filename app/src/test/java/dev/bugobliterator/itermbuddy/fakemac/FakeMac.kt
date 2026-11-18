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
import dev.bugobliterator.itermbuddy.core.stream.peekMediaFrameHeader
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

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
 * - Live streams, as `MC/CompanionHostBridge.swift` and `CompanionSessionStreamer.swift` serve
 *   them: `startSessionStream` is answered with `streamStarted` (stream IDs from 1 on each link,
 *   assigned when the request arrives) and then a `streamConfig` ([startCodec], [startError],
 *   [configOnStart]); `stopSessionStream` ends the stream with `streamEnded(stoppedByClient)`;
 *   `streamAck` feeds that stream's own in-flight limiter (`CompanionInFlightLimiter.swift`);
 *   `requestKeyframe` is answered at once with the stream's config and a keyframe, which bypasses
 *   the limiter, as a requested keyframe does on the Mac; [delayReply] holds back the next reply
 *   to a case.
 * - Chat: [onRequestScripted] answers a request with a scripted run of messages (a `history`
 *   reply, then the subscribe snapshot), and [helloAiAvailable] sets the hello's `aiAvailable`;
 *   [FakeChatMac] builds the chat side of the Mac on these.
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

    /**
     * The `macStatus` the hello reply carries, as a v3.7.7 Mac sends it to a phone at revision 14
     * or later (`CompanionLink.swift:518-536`); null omits it, as `hello-mac.json` does.
     */
    @Volatile var helloMacStatus: JsonObject? = null

    /**
     * The `aiAvailable` the hello reply carries (revision 13, `MSG:986-991`); `hello-mac.json` has
     * true. Null omits the key, as a Mac that predates it does.
     */
    @Volatile var helloAiAvailable: Boolean? = true

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

    /** Scripts that answer a request case with any number of messages; see [onRequestScripted]. */
    private val scriptedHandlers = ConcurrentHashMap<String, suspend (JsonObject) -> Unit>()

    val parks = AtomicInteger()
    val handshakes = AtomicInteger()
    val confirmationsSent = AtomicInteger()
    val unpairings = AtomicInteger()
    val staticMismatches = AtomicInteger()

    @Volatile private var job: Job? = null

    /** The codec string the next `streamStarted` replies carry; v3.7.7 always sends `hevc`. */
    @Volatile var startCodec = "hevc"

    /** When set, `startSessionStream` is answered with this error fixture (path under `fixtures/`). */
    @Volatile var startError: String? = null

    /** Whether a `streamConfig` follows each `streamStarted` at once, as the Mac sends it. */
    @Volatile var configOnStart = true

    /** Stream IDs handed out on the current link: the next one is this plus one. */
    private val streamCounter = AtomicLong()

    /** One-shot reply delays by request case; see [delayReply]. */
    private val replyDelays = ConcurrentHashMap<String, Duration>()

    /**
     * One stream's state on the Mac: its in-flight limiter (`CompanionInFlightLimiter.swift`:
     * before any ack everything may go; after it, nothing but a requested keyframe while
     * `lastSentPts − lastAckedPts > 500` or `queueDepth > 4`), its newest config, and when the
     * last frame went and whether it was a keyframe (for the insurance keyframe).
     */
    private class MacStream(val streamID: Long) {
        var haveAck = false
        var lastSentPts = 0L
        var lastAckedPts = 0L
        var queueDepth = 0
        var stopped = false
        var config: JsonObject = streamConfigPayload(streamID, emptyMap())
        var insurance: Job? = null

        fun mayEmit(): Boolean {
            if (!haveAck) return true
            if (queueDepth > LIMITER_MAX_QUEUE_DEPTH) return false
            return maxOf(0, lastSentPts - lastAckedPts) <= LIMITER_MAX_IN_FLIGHT_MILLIS
        }

        fun noteSent(pts: Long) {
            lastSentPts = maxOf(lastSentPts, pts)
        }

        fun noteAck(pts: Long, depth: Int) {
            haveAck = true
            lastAckedPts = maxOf(lastAckedPts, pts)
            queueDepth = depth
        }
    }

    /** Streams on the current link by stream ID. */
    private val streams = ConcurrentHashMap<Long, MacStream>()

    private val sentFrames = AtomicInteger()
    private val heldFrames = AtomicInteger()
    private val insuranceKeyframes = AtomicInteger()
    private val keyframeAnswers = AtomicInteger()

    /** `requestKeyframe`s answered with config and a keyframe. */
    val keyframeAnswerCount: Int get() = keyframeAnswers.get()

    /** Media frames from [startStreaming] sent so far (insurance keyframes not included). */
    val sentFrameCount: Int get() = sentFrames.get()

    /** Non-keyframes [startStreaming] held back (not sent) because of the in-flight limiter. */
    val heldFrameCount: Int get() = heldFrames.get()

    /** Insurance keyframes sent 0.3 s after the last P-frame. */
    val insuranceKeyframeCount: Int get() = insuranceKeyframes.get()

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

    /**
     * Fixture paths whose payloads are sent, with no request ID, just after each hello reply: a
     * status change the Mac pushes before the phone's next request is answered.
     */
    val afterHelloReply = CopyOnWriteArrayList<String>()

    /** Sends the payload of the fixture at [path] (relative to `fixtures/`) with [requestID]. */
    suspend fun sendSample(path: String, requestID: Long?) {
        val current = checkNotNull(link) { "no link" }
        send(current, sampleEnvelope(path, requestID))
    }

    /**
     * Sends one media frame: [encodedFrame] (header and payload) behind the `0x01` channel byte,
     * whatever the limiter says (a test forcing a frame). A frame whose header names a known stream
     * counts as sent by that stream's limiter.
     */
    suspend fun sendMedia(encodedFrame: ByteArray) {
        sendRaw(byteArrayOf(MEDIA_CHANNEL) + encodedFrame)
        noteSentBy(encodedFrame)
    }

    /**
     * A frame the Mac sent ([encodedFrame], counted as sent by its stream's limiter) that reaches
     * the phone corrupted: its version byte reads 3, so the phone cannot parse even its header.
     */
    suspend fun sendCorruptedMedia(encodedFrame: ByteArray) {
        val corrupted = encodedFrame.copyOf().also { it[0] = 3 }
        sendRaw(byteArrayOf(MEDIA_CHANNEL) + corrupted)
        noteSentBy(encodedFrame)
    }

    /** A stream the Mac is serving with no phone request behind it (one the phone never adopted). */
    fun beginStream(): Long {
        val streamID = streamCounter.incrementAndGet()
        streams[streamID] = MacStream(streamID)
        return streamID
    }

    private fun noteSentBy(encodedFrame: ByteArray) {
        val peek = peekMediaFrameHeader(encodedFrame)
        val stream = peek.streamID?.let { streams[it] } ?: return
        peek.ptsMilliseconds?.let { stream.noteSent(it) }
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

    /**
     * Answers requests whose payload case is [case] by running [script] with the phone's envelope,
     * inside the serve loop, before the next frame from the phone is read: it sends whatever it
     * likes with [sendMessage], [sendSample] or [sendRaw], in order (a `history` reply and then the
     * subscribe snapshot, say). Takes precedence over [onRequest] and the built-in replies.
     */
    fun onRequestScripted(case: String, script: suspend (JsonObject) -> Unit) {
        scriptedHandlers[case] = script
    }

    /** The next reply to a request whose case is [case] is sent [by] later; the serve loop is not held up. */
    fun delayReply(case: String, by: Duration) {
        replyDelays[case] = by
    }

    /** The bodies (`{"<case>": body}`) of the received messages whose case is [case], in order. */
    fun receivedBodies(case: String): List<JsonObject> =
        receivedEnvelopes().mapNotNull { (it.getValue("payload").jsonObject[case] as? JsonObject) }

    /** Sends the fixture `streamConfig` for [streamID], with [patch] applied to its fields. */
    suspend fun sendStreamConfig(streamID: Long, patch: Map<String, JsonElement> = emptyMap()) {
        val payload = streamConfigPayload(streamID, patch)
        streams[streamID]?.config = payload
        sendMessage(payload, null)
    }

    /** Sends `streamEnded(streamID:reason:)`. */
    suspend fun sendStreamEnded(streamID: Long, reason: String) {
        sendMessage(streamEnded(streamID, reason), null)
    }

    /**
     * Sends [frames] (encoded media frames, header and payload, all for one stream) one every
     * [frameIntervalMillis], as the Mac's streamer tick does (`CompanionSessionStreamer.swift`
     * `tick`): while that stream's limiter blocks, a frame is held (not sent, counted in
     * [heldFrameCount]), keyframe flag or not, since only a requested keyframe bypasses it (and
     * those are answered in [answerKeyframeRequest]). 0.3 s after a P-frame, with nothing sent
     * since, the Mac sends an insurance keyframe, but only once the limiter lets it emit
     * (`!blocked`). Stops when the stream is stopped or the link goes away.
     */
    fun startStreaming(frames: List<ByteArray>, frameIntervalMillis: Long): Job = scope.launch {
        for ((index, frame) in frames.withIndex()) {
            if (index > 0) delay(frameIntervalMillis)
            val peek = peekMediaFrameHeader(frame)
            val streamID = checkNotNull(peek.streamID) { "not a media frame" }
            val pts = checkNotNull(peek.ptsMilliseconds)
            val stream = streams.getOrPut(streamID) { MacStream(streamID) }
            if (stream.stopped) return@launch
            if (!stream.mayEmit()) {
                heldFrames.incrementAndGet()
                continue
            }
            if (!sendMediaIfLinked(frame)) return@launch
            sentFrames.incrementAndGet()
            stream.noteSent(pts)
            stream.insurance?.cancel()
            val keyframe = frame[1].toInt() and 1 != 0
            if (!keyframe) stream.insurance = scope.launch { sendInsurance(stream, pts) }
        }
    }

    private suspend fun sendInsurance(stream: MacStream, lastPts: Long) {
        delay(INSURANCE_DELAY_MILLIS)
        // The Mac re-evaluates each tick: no insurance while blocked; it goes once unblocked.
        while (!stream.mayEmit()) {
            if (stream.stopped || link == null) return
            delay(TICK_MILLIS)
        }
        if (stream.stopped) return
        val pts = maxOf(lastPts + INSURANCE_DELAY_MILLIS, stream.lastSentPts + 1)
        if (sendMediaIfLinked(keyframeFor(stream, pts))) {
            insuranceKeyframes.incrementAndGet()
            stream.noteSent(pts)
        }
    }

    /** `requestKeyframe`: the config again (same generation), then a keyframe that bypasses the limiter. */
    private suspend fun answerKeyframeRequest(current: Link, streamID: Long) {
        val stream = streams[streamID] ?: return
        if (stream.stopped) return
        stream.insurance?.cancel()
        send(current, envelope(stream.config, null))
        val pts = stream.lastSentPts + 1
        send(current, byteArrayOf(MEDIA_CHANNEL) + keyframeFor(stream, pts))
        stream.noteSent(pts)
        keyframeAnswers.incrementAndGet()
    }

    private suspend fun sendMediaIfLinked(frame: ByteArray): Boolean {
        val current = link ?: return false
        return try {
            send(current, byteArrayOf(MEDIA_CHANNEL) + frame)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
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
        streamCounter.set(0)
        streams.values.forEach { it.insurance?.cancel() }
        streams.clear()
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
        val script = scriptedHandlers[case]
        if (script != null) {
            script(envelope)
            return
        }
        val handler = requestHandlers[case]
        if (handler != null) {
            handler(envelope)?.let { send(current, envelope(it, requestID)) }
            return
        }
        when (case) {
            "startSessionStream" -> if (answering) answerStart(current, requestID)
            "stopSessionStream" -> {
                val streamID = payload.getValue(case).jsonObject.getValue("streamID").jsonPrimitive.long
                streams[streamID]?.let {
                    it.stopped = true
                    it.insurance?.cancel()
                }
                send(current, envelope(streamEnded(streamID, "stoppedByClient"), null))
            }
            "streamAck" -> {
                // Routed by stream ID to that stream's limiter (CompanionHostBridge.swift).
                val ack = payload.getValue(case).jsonObject
                val streamID = ack.getValue("streamID").jsonPrimitive.long
                streams[streamID]?.noteAck(
                    ack.getValue("lastPTSMilliseconds").jsonPrimitive.long,
                    ack.getValue("queueDepth").jsonPrimitive.long.toInt(),
                )
            }
            "requestKeyframe" -> {
                val streamID = payload.getValue(case).jsonObject.getValue("streamID").jsonPrimitive.long
                answerKeyframeRequest(current, streamID)
            }
            "hello" -> if (answering) {
                for (path in beforeHelloReply) send(current, sampleEnvelope(path, null))
                send(current, helloReply(requestID))
                for (path in afterHelloReply) send(current, sampleEnvelope(path, null))
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

    /** Starts a stream at once (its ID is taken now) and replies, after any [delayReply]. */
    private suspend fun answerStart(current: Link, requestID: Long?) {
        val error = startError
        val streamID = if (error == null) streamCounter.incrementAndGet() else 0L
        if (error == null) streams[streamID] = MacStream(streamID)
        val codec = startCodec
        val withConfig = configOnStart
        val reply: suspend () -> Unit = {
            if (error != null) {
                send(current, sampleEnvelope(error, requestID))
            } else {
                val started = JsonObject(mapOf("codec" to JsonPrimitive(codec), "streamID" to JsonPrimitive(streamID)))
                send(current, envelope(JsonObject(mapOf("streamStarted" to JsonObject(mapOf("_0" to started)))), requestID))
                if (withConfig) send(current, envelope(streamConfigPayload(streamID, emptyMap()), null))
            }
        }
        val wait = replyDelays.remove("startSessionStream")
        if (wait == null) {
            reply()
        } else {
            scope.launch {
                delay(wait)
                try {
                    reply()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The link went away while the reply was held back.
                }
            }
        }
    }

    private fun helloReply(requestID: Long?): ByteArray {
        val sample = fixtureJson("hello-mac.json")
        val hello = sample.getValue("payload").jsonObject.getValue("hello").jsonObject
        val ai = helloAiAvailable
        val withAi = if (ai == null) hello - "aiAvailable" else hello + ("aiAvailable" to JsonPrimitive(ai))
        val fields = withAi + mapOf("revision" to JsonPrimitive(revision), "minimumPeer" to JsonPrimitive(minimumPeer))
        val body = JsonObject(helloMacStatus?.let { fields + ("macStatus" to it) } ?: fields)
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
        const val LIMITER_MAX_IN_FLIGHT_MILLIS = 500L
        const val LIMITER_MAX_QUEUE_DEPTH = 4
        const val INSURANCE_DELAY_MILLIS = 300L
        const val TICK_MILLIS = 33L
        const val MAC_REVISION = 15
        const val MAC_MINIMUM_PEER = 11
        const val DEFAULT_REPARK_DELAY_MILLIS = 50L
        private const val MEDIA_CHANNEL: Byte = 0x01

        /** `{"streamConfig": {"_0": ...}}` from the terminal fixture, for [streamID], with [patch] applied. */
        fun streamConfigPayload(streamID: Long, patch: Map<String, JsonElement>): JsonObject {
            val sample = Json.parseToJsonElement(Fixtures.read("messages/live-session/stream-config-terminal.json"))
                .jsonObject.getValue("json").jsonObject.getValue("payload").jsonObject
            val body = sample.getValue("streamConfig").jsonObject.getValue("_0").jsonObject
            val fields = LinkedHashMap(body)
            fields["streamID"] = JsonPrimitive(streamID)
            fields.putAll(patch)
            return JsonObject(mapOf("streamConfig" to JsonObject(mapOf("_0" to JsonObject(fields)))))
        }

        fun streamEnded(streamID: Long, reason: String): JsonObject = JsonObject(
            mapOf("streamEnded" to JsonObject(mapOf("streamID" to JsonPrimitive(streamID), "reason" to JsonPrimitive(reason)))),
        )

        /** A version 2 keyframe for [stream] at [pts], in its config's generation, with a 16-byte payload. */
        private fun keyframeFor(stream: MacStream, pts: Long): ByteArray {
            val config = stream.config.getValue("streamConfig").jsonObject.getValue("_0").jsonObject
            val generation = config.getValue("generationId").jsonPrimitive.long
            val payload = 16
            return ByteBuffer.allocate(KEYFRAME_HEADER + payload)
                .put(2).put(1).putInt(stream.streamID.toInt()).putInt(0).putLong(pts)
                .putInt(generation.toInt()).putLong(0).putInt(payload)
                .array()
        }

        private const val KEYFRAME_HEADER = 34

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
