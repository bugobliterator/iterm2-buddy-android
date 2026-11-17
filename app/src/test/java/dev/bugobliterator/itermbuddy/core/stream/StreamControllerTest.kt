package dev.bugobliterator.itermbuddy.core.stream

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import dev.bugobliterator.itermbuddy.core.wire.decodeStreamConfig
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The stream controller over a real [CompanionClient], the in-process [FakeRelay] and [FakeMac],
 * with a [FakeVideoOutput], in virtual time (`nowMillis` is the test scheduler's clock). The inbox
 * tests drive the controller's sink directly through a [ScriptedStreamLink].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamControllerTest {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "stream-controller-test-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")

    /** Passes every input to [target] and records when each accept returned. */
    private class CountingSink(private val now: () -> Long) : StreamSink {
        lateinit var target: StreamSink
        val accepted = CopyOnWriteArrayList<Pair<StreamInput, Long>>()

        override suspend fun accept(input: StreamInput) {
            target.accept(input)
            accepted += input to now()
        }
    }

    private inner class Harness(
        val test: TestScope,
        timeouts: StreamTimeouts = StreamTimeouts(),
        clientTimeouts: ClientTimeouts = ClientTimeouts(),
    ) {
        val store = InMemoryPairingStore()
        val relay = FakeRelay(ORIGIN)
        val logs = CopyOnWriteArrayList<String>()
        val video = FakeVideoOutput { test.currentTime }
        val sink = CountingSink { test.currentTime }
        private val connections = MutableStateFlow(0)
        val client = CompanionClient(
            store,
            relay,
            test.backgroundScope,
            { endpoints },
            clientTimeouts,
            Random(SEED),
            test.testScheduler.timeSource,
            EmptyCoroutineContext,
            { logs += it },
            sink,
        )
        val controller = StreamController(
            client,
            video,
            test.backgroundScope,
            timeouts,
            nowMillis = { test.currentTime },
            log = { logs += it },
        )
        val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)

        init {
            sink.target = controller
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                client.state.collect { if (it is ClientState.Connected) connections.value++ }
            }
        }

        suspend fun awaitConnected(times: Int = 1) {
            withTimeout(30.minutes) { connections.first { it >= times } }
            test.runCurrent()
        }

        suspend fun connect() {
            var record = beginFreshPairing(store, code)
            record = advancePairing(store, record, PairingStage.CONFIRMED)
            record = advancePairing(store, record, PairingStage.REGISTERED)
            relay.lock(room, JoinSigner(record.roomSecret).verifier)
            mac.roomSecret = record.roomSecret
            mac.pinnedPhoneStatic = publicKeyOf(record.phoneStaticPrivate)
            mac.start()
            client.start()
            awaitConnected()
        }

        /** Connects, opens [session], and checks it went Live on stream 1 with one keyframe in. */
        suspend fun openLive(session: SessionSummary = A) {
            connect()
            controller.open(session)
            test.runCurrent()
            mac.sendMedia(frame(1, 0, keyframe = true))
            test.runCurrent()
            check(controller.state.value is LiveViewState.Live) { "not live: ${controller.state.value}" }
        }

        val state: LiveViewState get() = controller.state.value
        val active: ActiveStream? get() = controller.activeStream.value

        fun starts(): List<JsonObject> = mac.receivedBodies("startSessionStream")
        fun startGuids(): List<String> = starts().map { it.getValue("sessionGuid").jsonPrimitive.content }
        fun stops(): List<Long> = mac.receivedBodies("stopSessionStream").map { it.getValue("streamID").jsonPrimitive.long }
        fun ackPts(): List<Long> = mac.receivedBodies("streamAck").map { it.getValue("lastPTSMilliseconds").jsonPrimitive.long }
        fun keyframeRequests(): Int = mac.receivedBodies("requestKeyframe").size

        /** Payload cases sent about streams, in order. */
        fun streamCases(): List<String> =
            mac.receivedCases().filter { it == "startSessionStream" || it == "stopSessionStream" }
    }

    @Test
    fun openStartsWithIosParamsAndGoesLive() = runTest {
        val h = Harness(this)
        h.connect()
        h.controller.open(A)
        runCurrent()
        val start = h.starts().single()
        assertEquals(
            Json.parseToJsonElement("""{"sessionGuid":"guid-alpha","params":{"supportedCodecs":["hevc"],"maxFrameRate":30,"maxMediaFrameVersion":2}}"""),
            start,
        )
        h.mac.sendMedia(frame(1, 0, keyframe = true))
        runCurrent()
        val live = h.state as LiveViewState.Live
        assertEquals(A, live.session)
        assertFalse(live.stalled)
        assertEquals(1, h.video.configures.size)
        assertEquals(1, h.video.submits.size)
        assertEquals(ActiveStream(1, 1, A.guid, h.active!!.watchEpoch, live.config, 0), h.active)
    }

    @Test
    fun configAfterStartIsNotDropped() = runTest {
        val h = Harness(this)
        h.connect()
        // FakeMac sends streamConfig straight after the streamStarted reply, in the same tick.
        h.controller.open(A)
        runCurrent()
        val expected = fixtureConfig()
        assertEquals(expected, h.active?.config)
        assertEquals(listOf(expected), h.video.configures)
        assertEquals(LiveViewState.Live(A, expected), h.state)
    }

    @Test
    fun configSetsLiveTopFromExtent() = runTest {
        val h = Harness(this)
        h.mac.configOnStart = false
        h.connect()
        h.controller.open(A)
        runCurrent()
        assertNull(h.active?.liveTop)
        h.mac.sendStreamConfig(1, mapOf("firstAbsLine" to JsonPrimitive(1000), "totalLines" to JsonPrimitive(1200), "rows" to JsonPrimitive(40)))
        runCurrent()
        assertEquals(2160L, h.active?.liveTop)
        // A later frame's liveTop replaces it.
        h.mac.sendMedia(frame(1, 0, keyframe = true, liveTop = 2170))
        runCurrent()
        assertEquals(2170L, h.active?.liveTop)
    }

    @Test
    fun everyFrameIsAckedWithItsPts() = runTest {
        val h = Harness(this)
        h.openLive()
        val frames = (1..60).map { frame(1, it * 33L, keyframe = it % 30 == 0) }
        h.mac.startStreaming(frames, 33)
        advanceTimeBy(60 * 33 + 1000L)
        assertEquals(listOf(0L) + (1..60).map { it * 33L }, h.ackPts())
    }

    @Test
    fun ackQueueDepthIsAlwaysZero() = runTest {
        val h = Harness(this)
        h.openLive()
        repeat(50) { h.mac.sendMedia(frame(1, 10L + it)) }
        h.video.detachSurface()
        repeat(50) { h.mac.sendMedia(frame(1, 100L + it)) }
        h.mac.sendStreamConfig(1, mapOf("generationId" to JsonPrimitive(2)))
        repeat(20) { h.mac.sendMedia(frame(1, 200L + it, generation = 1)) }
        advanceTimeBy(1000)
        val acks = h.mac.receivedBodies("streamAck")
        assertTrue("acks: ${acks.size}", acks.size > 1)
        assertEquals(setOf(0L), acks.map { it.getValue("queueDepth").jsonPrimitive.long }.toSet())
        assertEquals("the newest frame is acked last", 219L, acks.last().getValue("lastPTSMilliseconds").jsonPrimitive.long)
    }

    @Test
    fun framesWithoutSurfaceAreStillAcked() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.startStreaming((1..5).map { frame(1, it * 33L) }, 33)
        advanceTimeBy(200)
        h.video.detachSurface()
        assertEquals(VideoReadiness.WaitingForSurface, h.video.readiness())
        val submitsBefore = h.video.submits.size
        h.mac.startStreaming((6..15).map { frame(1, it * 33L) }, 33)
        advanceTimeBy(500)
        assertEquals("every frame was offered to the decoder", submitsBefore + 10, h.video.submits.size)
        assertEquals(listOf(0L) + (1..15).map { it * 33L }, h.ackPts())
        assertEquals("no keyframe request while there is no surface", 0, h.keyframeRequests())
        h.video.attachSurface()
        runCurrent()
        assertEquals(1, h.keyframeRequests())
    }

    /** Ack first: a frame the output throws on is still acked, and recovery asks for a keyframe. */
    @Test
    fun framesWhoseSubmitThrowsAreStillAcked() = runTest {
        val h = Harness(this)
        h.openLive()
        h.video.submitResult = { throw IllegalStateException("codec released") }
        h.mac.startStreaming((1..5).map { frame(1, it * 33L) }, 33)
        advanceTimeBy(500)
        assertEquals(listOf(0L) + (1..5).map { it * 33L }, h.ackPts())
        assertEquals(1, h.keyframeRequests())
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun keyframeRequestsAreRateLimited() = runTest {
        val h = Harness(this)
        h.openLive()
        h.video.submitResult = { false }
        h.video.requestOnSubmit = true
        h.mac.startStreaming((1..30).map { frame(1, it * 33L) }, 33)
        advanceTimeBy(1000)
        assertEquals(30, h.video.submits.size - 1)
        assertEquals("one per 500 ms over a second of failures", 2, h.keyframeRequests())
    }

    @Test
    fun streamKeepsFlowingPastFiveHundredMillis() = runTest {
        val h = Harness(this)
        h.openLive()
        // A decoder slower than the stream: each frame is output 50 ms after the one before.
        h.video.decodeMillisPerFrame = 50
        val frames = (1..300).map { frame(1, it * 33L, keyframe = it % 150 == 0) }
        h.mac.startStreaming(frames, 33)
        advanceTimeBy(300 * 33 + 2000L)
        assertEquals(0, h.mac.heldFrameCount)
        assertEquals(300, h.mac.sentFrameCount)
        assertEquals(301, h.video.submits.size)
        assertTrue("the limiter was engaged by acks", h.ackPts().size > 1)
    }

    /** The FakeMac limiter is real: with acks stuck at pts 0, P-frames past 500 ms are held and keyframes still go. */
    @Test
    fun fakeMacLimiterHoldsFramesWhenAcksStop() = runTest {
        val h = Harness(this)
        h.connect()
        val started = h.client.request(PhoneMessage.StartSessionStream(A.guid)) as MacMessage.StreamStarted
        h.client.send(PhoneMessage.StreamAck(started.streamID, 0, 0))
        runCurrent()
        val frames = (1..60).map { frame(started.streamID, it * 33L, keyframe = it == 45) }
        h.mac.startStreaming(frames, 33)
        advanceTimeBy(60 * 33 + 1000L)
        // pts 33..528 go (each sent while the lag before it is ≤ 500); then P-frames are held, the
        // keyframe at 45 still goes, and the P-frames after it are held too.
        assertEquals(16 + 1, h.mac.sentFrameCount)
        assertEquals(60 - 17, h.mac.heldFrameCount)
    }

    @Test
    fun olderGenerationFramesAreDroppedButAcked() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.sendStreamConfig(1, mapOf("generationId" to JsonPrimitive(2)))
        // One frame per tick, so the AckSender has no newer ack to fold each one into.
        h.mac.sendMedia(frame(1, 100, generation = 1))
        runCurrent()
        h.mac.sendMedia(frame(1, 133, keyframe = true, generation = 2))
        runCurrent()
        h.mac.sendMedia(frame(1, 166, generation = 3))
        runCurrent()
        assertEquals(listOf(0L, 133L, 166L), h.video.submits.map { it.ptsMilliseconds })
        assertEquals(listOf(0L, 100L, 133L, 166L), h.ackPts())
    }

    @Test
    fun versionOneFramesAreNotDroppedByGeneration() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.sendStreamConfig(1, mapOf("generationId" to JsonPrimitive(5)))
        h.mac.sendMedia(frame(1, 100, generation = null))
        runCurrent()
        assertEquals(listOf(0L, 100L), h.video.submits.map { it.ptsMilliseconds })
        assertEquals(1, h.video.submits.last().version)
        assertEquals(listOf(0L, 100L), h.ackPts())
    }

    @Test
    fun identicalConfigKeepsDecoder() = runTest {
        val h = Harness(this)
        h.openLive()
        val resets = h.video.resets
        h.mac.sendStreamConfig(1)
        runCurrent()
        assertEquals(2, h.video.configures.size)
        assertEquals(1, h.video.builds)
        assertEquals("a config never resets the output", resets, h.video.resets)
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun newSizeRebuildsDecoder() = runTest {
        val h = Harness(this)
        h.openLive()
        val resets = h.video.resets
        h.mac.sendStreamConfig(1, mapOf("pixelWidth" to JsonPrimitive(1920), "pixelHeight" to JsonPrimitive(1200)))
        runCurrent()
        assertEquals(2, h.video.builds)
        assertEquals(resets, h.video.resets)
        assertEquals(1920, h.active?.config?.pixelWidth)
        assertEquals(1920, (h.state as LiveViewState.Live).config.pixelWidth)
    }

    @Test
    fun framesFromAnEndedLinkAreIgnored() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals(listOf(A.guid, A.guid), h.startGuids())
        assertEquals(2L, h.active?.linkId)
        assertEquals(1L, h.active?.streamID)
        val submits = h.video.submits.size
        val acks = h.ackPts().size
        // A late frame from link 1, stream 1 (the same stream ID as link 2's stream).
        h.controller.accept(StreamInput.Media(1, byteArrayOf(0x01) + frame(1, 9999)))
        runCurrent()
        assertEquals(submits, h.video.submits.size)
        assertEquals(acks, h.ackPts().size)
        assertTrue(9999L !in h.ackPts())
    }

    @Test
    fun backgroundStopsAndForegroundRestarts() = runTest {
        val h = Harness(this)
        h.openLive()
        h.controller.pause()
        runCurrent()
        assertEquals(listOf(1L), h.stops())
        assertEquals(LiveViewState.Paused(A), h.state)
        assertNull(h.active)
        val submits = h.video.submits.size
        h.mac.sendMedia(frame(1, 500))
        runCurrent()
        assertEquals(submits, h.video.submits.size)
        h.controller.resume()
        runCurrent()
        assertEquals(2, h.starts().size)
        assertEquals(2L, h.active?.streamID)
        h.mac.sendMedia(frame(1, 600))
        h.mac.sendMedia(frame(2, 0, keyframe = true))
        runCurrent()
        assertEquals(listOf(2L), h.video.submits.drop(submits).map { it.streamID })
        assertTrue(600L !in h.ackPts())
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun reconnectWhileBackgroundedDoesNotStart() = runTest {
        val h = Harness(this)
        h.openLive()
        h.controller.pause()
        runCurrent()
        h.mac.dropSocket()
        h.awaitConnected(2)
        advanceTimeBy(5000)
        assertEquals(1, h.starts().size)
        assertEquals(LiveViewState.Paused(A), h.state)
        h.controller.resume()
        runCurrent()
        assertEquals(2, h.starts().size)
        assertEquals(2L, h.active?.linkId)
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun startRacingCloseIsStoppedAfterReply() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 1.seconds)
        h.controller.open(A)
        runCurrent()
        h.controller.close()
        runCurrent()
        assertEquals(LiveViewState.Closed, h.state)
        advanceTimeBy(1500)
        assertEquals(listOf(1L), h.stops())
        assertEquals(LiveViewState.Closed, h.state)
        assertNull(h.active)
        assertEquals(1, h.starts().size)
    }

    @Test
    fun reopeningSameSessionAdoptsInFlightStart() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 1.seconds)
        h.controller.open(A)
        runCurrent()
        h.controller.close()
        runCurrent()
        h.controller.open(A)
        runCurrent()
        advanceTimeBy(1500)
        assertEquals(1, h.starts().size)
        assertEquals(emptyList<Long>(), h.stops())
        assertTrue("${h.state}", h.state is LiveViewState.Live)
        assertEquals(A.guid, h.active?.sessionGuid)
    }

    @Test
    fun switchingSessionsSerializesStarts() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 1.seconds)
        h.controller.open(A)
        runCurrent()
        h.controller.open(B)
        runCurrent()
        assertEquals("one start outstanding", listOf(A.guid), h.startGuids())
        assertEquals(LiveViewState.Starting(B), h.state)
        advanceTimeBy(1500)
        assertEquals(listOf("startSessionStream", "stopSessionStream", "startSessionStream"), h.streamCases())
        assertEquals(listOf(1L), h.stops())
        assertEquals(listOf(A.guid, B.guid), h.startGuids())
        assertEquals(B.guid, h.active?.sessionGuid)
        assertEquals(2L, h.active?.streamID)
        assertEquals(B, (h.state as LiveViewState.Live).session)
    }

    @Test
    fun lateStartReplyForOtherSessionIsStopped() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 20.seconds)
        h.controller.open(A)
        runCurrent()
        advanceTimeBy(15.seconds + 10.milliseconds)
        assertEquals(LiveViewState.Failed(A, LiveProblem.StartFailed(retryable = true)), h.state)
        h.mac.delayReply("startSessionStream", 10.seconds)
        h.controller.open(B)
        runCurrent()
        assertEquals(listOf(A.guid, B.guid), h.startGuids())
        advanceTimeBy(5.seconds) // t = 20 s: A's late reply
        assertEquals(listOf(1L), h.stops())
        assertEquals(LiveViewState.Starting(B), h.state)
        assertNull(h.active)
        advanceTimeBy(6.seconds) // t = 26 s: B's reply
        assertEquals(B.guid, h.active?.sessionGuid)
        assertEquals(2L, h.active?.streamID)
        assertEquals(listOf(1L), h.stops())
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun startAfterTimeoutIsStopped() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 20.seconds)
        h.controller.open(A)
        runCurrent()
        advanceTimeBy(15.seconds + 10.milliseconds)
        val failed = LiveViewState.Failed(A, LiveProblem.StartFailed(retryable = true))
        assertEquals(failed, h.state)
        advanceTimeBy(6.seconds)
        assertEquals(listOf(1L), h.stops())
        assertEquals(failed, h.state)
        assertNull(h.active)
        assertEquals("a failed start is not retried in a loop", 1, h.starts().size)
    }

    @Test
    fun openSameSessionIsNoOp() = runTest {
        val h = Harness(this)
        h.openLive()
        val resets = h.video.resets
        h.controller.open(A)
        runCurrent()
        assertEquals(1, h.starts().size)
        assertEquals(emptyList<Long>(), h.stops())
        assertEquals(resets, h.video.resets)
        assertTrue(h.state is LiveViewState.Live)
    }

    @Test
    fun nonHevcStartIsStopped() = runTest {
        val h = Harness(this)
        h.mac.startCodec = "h264"
        h.connect()
        h.controller.open(A)
        runCurrent()
        assertEquals(listOf(1L), h.stops())
        assertNull(h.active)
        assertEquals(LiveViewState.Failed(A, LiveProblem.StartFailed(retryable = false)), h.state)
        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals(1, h.starts().size)
    }

    @Test
    fun unknownSessionEndsAsSessionClosed() = runTest {
        val h = Harness(this)
        h.mac.startError = "messages/live-session/error-unknown-session.json"
        h.connect()
        h.controller.open(A)
        runCurrent()
        assertEquals(LiveViewState.Ended(A, StreamEndReason.SESSION_CLOSED), h.state)
        assertNull(h.active)
        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals("the intent was dropped", 1, h.starts().size)
    }

    @Test
    fun startTimeoutIsRetryable() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.delayReply("startSessionStream", 20.seconds)
        h.controller.open(A)
        runCurrent()
        advanceTimeBy(15.seconds + 10.milliseconds)
        assertEquals(LiveViewState.Failed(A, LiveProblem.StartFailed(retryable = true)), h.state)
        advanceTimeBy(30.seconds)
        assertEquals(1, h.starts().size)
        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals(2, h.starts().size)
        assertTrue("${h.state}", h.state is LiveViewState.Live)
    }

    @Test
    fun streamEndedDropsIntent() = runTest {
        val h = Harness(this)
        h.openLive()
        val resets = h.video.resets
        h.mac.sendStreamEnded(1, "sessionClosed")
        runCurrent()
        assertEquals(LiveViewState.Ended(A, StreamEndReason.SESSION_CLOSED), h.state)
        assertNull(h.active)
        assertTrue(h.video.resets > resets)
        h.mac.dropSocket()
        h.awaitConnected(2)
        advanceTimeBy(5000)
        assertEquals(1, h.starts().size)
        assertEquals(LiveViewState.Ended(A, StreamEndReason.SESSION_CLOSED), h.state)
    }

    @Test
    fun dataLimitReachedShowsEnded() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.sendStreamEnded(1, "dataLimitReached")
        runCurrent()
        assertEquals(LiveViewState.Ended(A, StreamEndReason.DATA_LIMIT_REACHED), h.state)
        assertEquals(emptyList<Long>(), h.stops())
    }

    @Test
    fun noDecoderFailsButAcksContinue() = runTest {
        val h = Harness(this)
        h.video.configureResult = { VideoReadiness.NoDecoder }
        h.connect()
        h.controller.open(A)
        runCurrent()
        assertEquals(LiveViewState.Failed(A, LiveProblem.NoDecoder), h.state)
        repeat(5) {
            h.mac.sendMedia(frame(1, it * 33L, keyframe = it == 0))
            runCurrent()
        }
        assertEquals((0 until 5).map { it * 33L }, h.ackPts())
        assertEquals(0, h.keyframeRequests())
    }

    @Test
    fun tooLargeFailsWithLimitsAndKeepsConfig() = runTest {
        val h = Harness(this)
        h.video.configureResult = { VideoReadiness.TooLarge(it.pixelWidth, it.pixelHeight, 1024, 768) }
        h.connect()
        h.controller.open(A)
        runCurrent()
        assertEquals(LiveViewState.Failed(A, LiveProblem.TooLarge(1280, 800, 1024, 768)), h.state)
        assertEquals(fixtureConfig(), h.active?.config)
        h.mac.sendMedia(frame(1, 0, keyframe = true))
        runCurrent()
        assertEquals(listOf(0L), h.ackPts())
        assertEquals(0, h.keyframeRequests())
    }

    @Test
    fun overdueOutputIsLabelledStalled() = runTest {
        val h = Harness(this)
        h.openLive()
        h.video.autoOutput = false
        h.mac.sendMedia(frame(1, 33))
        runCurrent()
        advanceTimeBy(100)
        h.mac.sendMedia(frame(1, 66))
        runCurrent()
        advanceTimeBy(899)
        runCurrent()
        assertEquals(false, (h.state as LiveViewState.Live).stalled)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(true, (h.state as LiveViewState.Live).stalled)
        // One output; the frame left pending is older than 1 s by the next check.
        advanceTimeBy(300)
        h.video.outputOldest(1)
        advanceTimeBy(1000)
        assertEquals(true, (h.state as LiveViewState.Live).stalled)
        h.video.outputAll()
        advanceTimeBy(1000)
        assertEquals(false, (h.state as LiveViewState.Live).stalled)
        assertEquals(0, h.keyframeRequests())
    }

    @Test
    fun idleTerminalStaysLive() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.startStreaming((1..10).map { frame(1, it * 33L) }, 33)
        advanceTimeBy(60.seconds)
        assertEquals(LiveViewState.Live(A, h.active!!.config!!, stalled = false), h.state)
        assertEquals(0, h.keyframeRequests())
    }

    @Test
    fun retryRestartsAnActiveStream() = runTest {
        val h = Harness(this)
        h.openLive()
        h.video.autoOutput = false
        h.mac.sendMedia(frame(1, 33))
        advanceTimeBy(1100)
        assertEquals(true, (h.state as LiveViewState.Live).stalled)
        val resets = h.video.resets
        h.video.autoOutput = true
        h.controller.retry()
        runCurrent()
        assertEquals(listOf(1L), h.stops())
        assertTrue(h.video.resets > resets)
        assertEquals(2, h.starts().size)
        assertEquals(2L, h.active?.streamID)
        h.mac.sendMedia(frame(2, 0, keyframe = true))
        advanceTimeBy(2000)
        assertEquals(false, (h.state as LiveViewState.Live).stalled)
    }

    @Test
    fun liveViewStateToStringHidesSessionName() {
        val session = SessionSummary("guid-sentinel", SENTINEL, SENTINEL, isBrowser = false)
        val config = fixtureConfig()
        val states = listOf(
            LiveViewState.Closed,
            LiveViewState.Starting(session),
            LiveViewState.Live(session, config),
            LiveViewState.Live(session, config, stalled = true),
            LiveViewState.Reconnecting(session),
            LiveViewState.Paused(session),
            LiveViewState.Ended(session, StreamEndReason.SESSION_CLOSED),
            LiveViewState.Failed(session, LiveProblem.NoDecoder),
            LiveViewState.Failed(session, LiveProblem.TooLarge(1, 2, 3, 4)),
            LiveViewState.Failed(session, LiveProblem.StartFailed(retryable = true)),
        )
        for (state in states) assertFalse("$state", state.toString().contains(SENTINEL))
        val active = ActiveStream(1, 1, session.guid, 1, config, 10)
        assertFalse("$active", active.toString().contains(SENTINEL))
    }

    @Test
    fun inboxSuspendsAtByteBudget() = runTest {
        // Real threads, so a decoder can block the consumer in submit while acks still go out on the other.
        val executor = Executors.newFixedThreadPool(2)
        val controllerScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val link = ScriptedStreamLink()
            val video = FakeVideoOutput { 0L }
            val controller = StreamController(
                link,
                video,
                controllerScope,
                budgets = StreamBudgets(inboxMediaBytes = MIB),
                nowMillis = { 0L },
                log = {},
            )
            realTime { makeLive(controller, link) }
            val latch = CountDownLatch(1)
            video.submitLatch = latch
            val frames = (0 until 8).map { StreamInput.Media(1, byteArrayOf(0x01) + frame(1, 100L + it, payload = 300 * KIB)) }
            controller.accept(frames[0])
            controller.accept(frames[1])
            controller.accept(frames[2])
            var taken = 3
            val rest = launch(start = CoroutineStart.UNDISPATCHED) {
                for (f in frames.drop(3)) {
                    controller.accept(f)
                    taken++
                }
            }
            // Ack first: frame 0 is acked although its submit is still blocked.
            realTime { withTimeout(10.seconds) { while (link.sent.none { it.message == PhoneMessage.StreamAck(1, 100, 0) }) delay(5) } }
            realTime { delay(300) }
            runCurrent()
            assertTrue("the fourth accept waits", rest.isActive)
            assertEquals(3, taken)
            latch.countDown()
            realTime { withTimeout(10.seconds) { rest.join() } }
            realTime { withTimeout(10.seconds) { while (video.submits.size < 8) delay(5) } }
            assertEquals((0 until 8).map { 100L + it }, video.submits.map { it.ptsMilliseconds })
            realTime { withTimeout(10.seconds) { while (link.sent.lastOrNull()?.message != PhoneMessage.StreamAck(1, 107, 0)) delay(5) } }
        } finally {
            controllerScope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun oversizedFrameIsAdmittedWhenInboxEmpty() = runTest {
        val link = ScriptedStreamLink()
        val video = FakeVideoOutput { currentTime }
        val controller = StreamController(
            link,
            video,
            backgroundScope,
            budgets = StreamBudgets(inboxMediaBytes = MIB),
            nowMillis = { currentTime },
            log = {},
        )
        makeLive(controller, link)
        controller.accept(StreamInput.Media(1, byteArrayOf(0x01) + frame(1, 10, payload = 100 * KIB)))
        val big = StreamInput.Media(1, byteArrayOf(0x01) + frame(1, 20, payload = 3 * MIB.toInt()))
        var bigTaken = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.accept(big)
            bigTaken = true
            controller.accept(StreamInput.Control(1, null, MacMessage.StreamEnded(1, StreamEndReason.DATA_LIMIT_REACHED)))
        }
        assertFalse("the big frame waits while the inbox holds a frame", bigTaken)
        runCurrent()
        assertTrue(bigTaken)
        assertFalse(job.isActive)
        assertEquals(listOf(10L, 20L), video.submits.map { it.ptsMilliseconds })
        assertEquals(LiveViewState.Ended(A, StreamEndReason.DATA_LIMIT_REACHED), controller.state.value)
    }

    @Test
    fun tooLargeMessageEndsStreamWithError() = runTest {
        val h = Harness(this, clientTimeouts = ClientTimeouts(maxMessageBytes = MIB.toInt()))
        h.openLive()
        h.mac.sendMedia(frame(1, 33, payload = 2 * MIB.toInt()))
        h.awaitConnected(2)
        advanceTimeBy(5000)
        assertEquals(LiveViewState.Ended(A, StreamEndReason.ERROR), h.state)
        assertNull(h.active)
        assertEquals(1, h.starts().size)
    }

    @Test
    fun consumerNeverAwaitsReplies() = runTest {
        val h = Harness(this)
        h.openLive()
        h.mac.delayReply("startSessionStream", 10.seconds)
        h.controller.retry()
        runCurrent()
        assertEquals(2, h.starts().size)
        val submits = h.video.submits.size
        val acks = h.ackPts().size
        val from = currentTime
        val before = h.sink.accepted.size
        repeat(500) { h.mac.sendMedia(frame(1, 1000L + it)) }
        advanceTimeBy(1000)
        val media = h.sink.accepted.drop(before).filter { it.first is StreamInput.Media }
        assertEquals(500, media.size)
        assertTrue("all taken within 1 s", media.all { it.second - from <= 1000 })
        assertEquals(submits, h.video.submits.size)
        assertEquals(acks, h.ackPts().size)
        advanceTimeBy(10.seconds)
        assertEquals(2L, h.active?.streamID)
    }

    /** Brings [controller] to Live on link 1, stream 1, over [link]: the reply goes through the sink first, as the session delivers it. */
    private suspend fun makeLive(controller: StreamController, link: ScriptedStreamLink) {
        controller.accept(StreamInput.LinkUp(1, FakeMac.MAC_REVISION))
        controller.open(A)
        val pending = link.requests.receive()
        val started = MacMessage.StreamStarted(1, StreamCodec.HEVC)
        controller.accept(StreamInput.Control(1, pending.requestID, started))
        pending.reply.complete(started)
        controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(fixtureConfig())))
        controller.state.first { it is LiveViewState.Live }
    }

    /** Runs [block] on a real-time dispatcher, for waits on the controller's own thread. */
    private suspend fun <T> realTime(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Default, block)

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261117
        const val SENTINEL = "SENTINEL-session-name-7Q"
        const val KIB = 1024
        const val MIB = 1024L * 1024

        val A = SessionSummary("guid-alpha", "$SENTINEL alpha", "subtitle alpha", isBrowser = false)
        val B = SessionSummary("guid-bravo", "$SENTINEL bravo", "subtitle bravo", isBrowser = false)

        /**
         * An encoded media frame (header and payload): version 2 with [generation], or version 1
         * when it is null. The payload is zeros; the fake decoder never reads it.
         */
        fun frame(
            streamID: Long,
            pts: Long,
            keyframe: Boolean = false,
            generation: Long? = 1,
            liveTop: Long = 0,
            payload: Int = 16,
        ): ByteArray {
            val v2 = generation != null
            val buffer = ByteBuffer.allocate((if (v2) 34 else 22) + payload)
            buffer.put((if (v2) 2 else 1).toByte())
            buffer.put((if (keyframe) 1 else 0).toByte())
            buffer.putInt(streamID.toInt())
            buffer.putInt(pts.toInt())
            buffer.putLong(pts)
            if (v2) {
                buffer.putInt(generation.toInt())
                buffer.putLong(liveTop)
            }
            buffer.putInt(payload)
            return buffer.array()
        }

        /** The terminal fixture's config for stream 1, as the phone decodes it. */
        fun fixtureConfig(): StreamConfig {
            val payload = FakeMac.streamConfigPayload(1, emptyMap())
            return (decodeStreamConfig(payload.getValue("streamConfig")) as MacMessage.StreamConfigMessage).config
        }

        fun publicKeyOf(privateKey: ByteArray): ByteArray {
            val dh = Noise.createDH("25519")
            try {
                dh.setPrivateKey(privateKey, 0)
                return ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
            } finally {
                dh.destroy()
            }
        }
    }
}
