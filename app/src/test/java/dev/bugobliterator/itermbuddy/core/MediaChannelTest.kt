package dev.bugobliterator.itermbuddy.core

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The media channel: how the session routes media frames and stream-scoped control to the
 * [StreamSink], in wire order and with back-pressure, and the link lifecycle around it. Runs
 * against the in-process [FakeRelay] and [FakeMac] in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaChannelTest {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "media-channel-test-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")

    /** Records every input in order; each [accept] first suspends for [perItem] (virtual time). */
    private class RecordingSink(var perItem: Duration = Duration.ZERO) : StreamSink {
        val inputs = CopyOnWriteArrayList<StreamInput>()

        /** Runs inside [accept], before the item is recorded, for sinks that act on an input. */
        var onAccept: suspend (StreamInput) -> Unit = {}

        override suspend fun accept(input: StreamInput) {
            if (perItem > Duration.ZERO) delay(perItem)
            onAccept(input)
            inputs += input
        }

        val markers: List<StreamInput> get() = inputs.filter { it is StreamInput.LinkUp || it is StreamInput.LinkDown }

        val media: List<StreamInput.Media> get() = inputs.filterIsInstance<StreamInput.Media>()
    }

    /** Takes LinkUp, then suspends forever on everything else: a bounded inbox whose consumer died. */
    private class StuckSink : StreamSink {
        val inputs = CopyOnWriteArrayList<StreamInput>()
        val stuckOn = CopyOnWriteArrayList<StreamInput>()

        override suspend fun accept(input: StreamInput) {
            if (input is StreamInput.LinkUp) {
                inputs += input
                return
            }
            stuckOn += input
            CompletableDeferred<Unit>().await()
        }
    }

    private inner class Harness(
        test: TestScope,
        val sink: StreamSink? = RecordingSink(),
        timeouts: ClientTimeouts = ClientTimeouts(),
        val relay: FakeRelay = FakeRelay(ORIGIN),
    ) {
        val store = InMemoryPairingStore()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<MacMessage>()
        val paired = CopyOnWriteArrayList<Boolean?>()
        private val connections = MutableStateFlow(0)
        val client = CompanionClient(
            store,
            relay,
            test.backgroundScope,
            { endpoints },
            timeouts,
            Random(SEED),
            test.testScheduler.timeSource,
            EmptyCoroutineContext,
            { logs += it },
            sink,
        )
        val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)

        /** The sink as a [RecordingSink], for tests that use one. */
        val recorded: RecordingSink get() = sink as RecordingSink

        init {
            val unconfined = UnconfinedTestDispatcher(test.testScheduler)
            test.backgroundScope.launch(unconfined) {
                client.state.collect { if (it is ClientState.Connected) connections.value++ }
            }
            test.backgroundScope.launch(unconfined) { client.events.collect { events += it } }
            test.backgroundScope.launch(unconfined) { client.paired.collect { paired += it } }
        }

        suspend fun awaitConnected(times: Int = 1) {
            withTimeout(30.minutes) { connections.first { it >= times } }
        }

        /** A stored, registered pairing the Mac has pinned: [start] reconnects with no code. */
        fun storedAndPinned() {
            var record = beginFreshPairing(store, code)
            record = advancePairing(store, record, PairingStage.CONFIRMED)
            record = advancePairing(store, record, PairingStage.REGISTERED)
            relay.lock(room, JoinSigner(record.roomSecret).verifier)
            mac.roomSecret = record.roomSecret
            mac.pinnedPhoneStatic = publicKeyOf(record.phoneStaticPrivate)
        }

        suspend fun connect() {
            storedAndPinned()
            mac.start()
            client.start()
            awaitConnected()
        }
    }

    @Test
    fun mediaReachesSinkWithMarkerStripped() = runTest {
        val h = Harness(this)
        h.connect()
        val frame = Json.parseToJsonElement(Fixtures.read("media/frames.json")).jsonObject
            .getValue("valid").jsonArray.first().jsonObject
        val channel = hex(frame.getValue("channelHex").jsonPrimitive.content)
        assertEquals("the fixture carries the channel byte", 0x01.toByte(), channel[0])
        h.mac.sendRaw(channel)
        runCurrent()

        val media = h.recorded.media.single()
        assertEquals(1L, media.linkId)
        assertArrayEquals(hex(frame.getValue("encodedHex").jsonPrimitive.content), media.copyBytes())
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun mediaStaysOrderedWithConfig() = runTest {
        val sink = RecordingSink(perItem = 10.milliseconds)
        val h = Harness(this, sink)
        h.connect()
        val startID = CompletableDeferred<Long>()
        h.mac.onRequest("startSessionStream") { startID.complete(it.getValue("requestID").jsonPrimitive.long); null }
        var inputsAtReturn = -1
        val reply = async {
            h.client.request(PhoneMessage.StartSessionStream("ptys_7K2M9Q4R8T1VH")).also { inputsAtReturn = sink.inputs.size }
        }
        val id = startID.await()
        h.mac.sendSample("messages/live-session/stream-started.json", id)
        h.mac.sendSample("messages/live-session/stream-config-terminal.json", null)
        h.mac.sendMedia(byteArrayOf(7, 7, 7))
        h.mac.sendSample("messages/live-session/stream-ended-stoppedByClient.json", null)
        assertEquals(MacMessage.StreamStarted(1, StreamCodec.HEVC), reply.await())
        advanceTimeBy(1.seconds)

        val kinds = sink.inputs.drop(1).map {
            when (it) {
                is StreamInput.Control -> it.message.javaClass.simpleName
                else -> it.javaClass.simpleName
            }
        }
        assertEquals(listOf("StreamStarted", "StreamConfigMessage", "Media", "StreamEnded"), kinds)
        val started = sink.inputs[1] as StreamInput.Control
        assertEquals(StreamInput.Control(1, id, MacMessage.StreamStarted(1, StreamCodec.HEVC)), started)
        assertTrue("the reply returns after the sink took it (inputs then: $inputsAtReturn)", inputsAtReturn >= 2)
        assertTrue("stream control never reaches events: ${h.events}", h.events.isEmpty())
    }

    @Test
    fun mediaBurstIsNeverDropped() = runTest {
        val sink = RecordingSink(perItem = 1.milliseconds)
        val h = Harness(this, sink)
        h.connect()
        repeat(BURST) { h.mac.sendMedia(ByteBuffer.allocate(4).putInt(it).array()) }
        advanceTimeBy((BURST + 100).milliseconds)

        val sequence = sink.media.map { ByteBuffer.wrap(it.copyBytes()).int }
        assertEquals((0 until BURST).toList(), sequence)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun mediaNeverReachesEvents() = runTest {
        val h = Harness(this, sink = null)
        h.connect()
        h.mac.sendMedia(byteArrayOf(1, 2, 3))
        h.mac.sendSample("messages/live-session/stream-config-terminal.json", null)
        runCurrent()

        assertTrue("${h.events}", h.events.isEmpty())
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("dropped") && it.contains("Media") })
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("dropped") && it.contains("StreamConfigMessage") })
        // Still connected and answering.
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    @Test
    fun linkMarkersBracketEachConnection() = runTest {
        val h = Harness(this)
        h.mac.revision = 13
        h.connect()
        h.mac.dropSocket()
        h.awaitConnected(2)
        runCurrent()

        assertEquals(
            listOf(StreamInput.LinkUp(1, 13), StreamInput.LinkDown(1), StreamInput.LinkUp(2, 13)),
            h.recorded.markers,
        )
    }

    @Test
    fun sendOnStaleLinkThrows() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.dropSocket()
        h.awaitConnected(2)
        val before = h.mac.received.size

        val failure = runCatching { h.client.send(PhoneMessage.RequestKeyframe(1), onLink = 1) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        runCurrent()
        assertEquals(before, h.mac.received.size)
        // The current link takes it.
        h.client.send(PhoneMessage.RequestKeyframe(1), onLink = 2)
        runCurrent()
        assertEquals(listOf("requestKeyframe"), h.mac.receivedCases().drop(before))
    }

    @Test
    fun sendWhenNotConnectedThrows() = runTest {
        val h = Harness(this)
        val failure = runCatching { h.client.send(PhoneMessage.RequestKeyframe(1)) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
    }

    @Test
    fun sendPreservesOrder() = runTest {
        val h = Harness(this)
        h.connect()
        val before = h.mac.received.size
        repeat(SENDS) { h.client.send(PhoneMessage.StreamAck(1, it.toLong()), onLink = 1) }
        runCurrent()

        val acks = h.mac.receivedEnvelopes().drop(before).map {
            it.getValue("payload").jsonObject.getValue("streamAck").jsonObject.getValue("lastPTSMilliseconds").jsonPrimitive.long
        }
        assertEquals((0 until SENDS).map { it.toLong() }, acks)
    }

    @Test
    fun pairedTracksStoredStage() = runTest {
        // A stored, registered pairing: null until the store is read, then true, never false.
        val stored = Harness(this)
        assertEquals(null, stored.client.paired.value)
        stored.connect()
        assertEquals(listOf(null, true), stored.paired.toList())

        // A fresh pairing: false while the code shows, true once confirmed, false after unpair().
        val fresh = Harness(this)
        fresh.mac.start()
        fresh.client.pair(code)
        withTimeout(1.minutes) { fresh.client.state.first { it is ClientState.ShowCode } }
        assertEquals(false, fresh.client.paired.value)
        fresh.mac.awaitingCode.first { it != null }
        fresh.mac.enterCode(accept = true)
        fresh.awaitConnected()
        assertEquals(true, fresh.client.paired.value)
        fresh.client.unpair()
        assertEquals(listOf(null, false, true, false), fresh.paired.toList())
    }

    @Test
    fun linkDownSurvivesCancellation() = runTest {
        val operations: List<Pair<String, suspend (CompanionClient) -> Unit>> = listOf(
            "cancel" to { it.cancel() },
            "unpair" to { it.unpair() },
            "pair" to { it.pair(code) },
        )
        for ((name, operation) in operations) {
            val h = Harness(this, RecordingSink(perItem = 10.milliseconds))
            h.connect()
            runCurrent()
            operation(h.client)
            advanceTimeBy(1.seconds)
            assertEquals(name, StreamInput.LinkDown(1), h.recorded.markers.getOrNull(1))
            h.mac.stop()
        }
    }

    @Test
    fun linkUpSeesCurrentLink() = runTest {
        val sink = RecordingSink()
        val h = Harness(this, sink)
        val failures = CopyOnWriteArrayList<Throwable>()
        sink.onAccept = { input ->
            if (input is StreamInput.LinkUp) {
                runCatching { h.client.send(PhoneMessage.RequestKeyframe(9), onLink = input.linkId) }
                    .exceptionOrNull()?.let { failures += it }
            }
        }
        h.connect()
        runCurrent()
        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals("requestKeyframe", h.mac.receivedCases().last())
    }

    @Test
    fun requestOnStaleLinkThrows() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.dropSocket()
        h.awaitConnected(2)
        val before = h.mac.received.size

        val failure = runCatching { h.client.request(PhoneMessage.Ping, onLink = 1) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        runCurrent()
        assertEquals(before, h.mac.received.size)
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping, onLink = 2))
    }

    @Test
    fun lateStreamStartedReachesSinkWithRequestId() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.onRequest("startSessionStream") { null }
        var sentID = -1L
        val failure = runCatching {
            h.client.request(PhoneMessage.StartSessionStream("ptys_7K2M9Q4R8T1VH"), timeout = 1.seconds, onSent = { sentID = it })
        }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        assertEquals("the ID onSent saw went on the wire", sentID, h.mac.receivedEnvelopes().last().getValue("requestID").jsonPrimitive.long)

        h.mac.sendSample("messages/live-session/stream-started.json", sentID)
        runCurrent()
        assertEquals(
            listOf(StreamInput.Control(1, sentID, MacMessage.StreamStarted(1, StreamCodec.HEVC))),
            h.recorded.inputs.filterIsInstance<StreamInput.Control>(),
        )
    }

    @Test
    fun orphanHistoryTileReplyNeverReachesEvents() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.onRequest("fetchHistoryTile") { null }
        var sentID = -1L
        val failure = runCatching {
            h.client.request(PhoneMessage.FetchHistoryTile(1, 3000, 50, 1), timeout = 1.seconds, onSent = { sentID = it })
        }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)

        h.mac.sendSample("messages/history-and-selection/history-tile.json", sentID)
        h.mac.sendSample("messages/history-and-selection/selection-text.json", sentID + 100)
        runCurrent()
        assertTrue("${h.events}", h.events.isEmpty())
        assertTrue("orphans are not stream inputs", h.recorded.inputs.none { it is StreamInput.Control })
        val dropped = h.logs.filter { "orphan reply" in it }
        assertEquals(dropped.toString(), 2, dropped.size)
        assertTrue(dropped.toString(), dropped.any { "HistoryTileMessage" in it } && dropped.any { "SelectionText" in it })
        assertTrue("only the kind is logged", h.logs.none { "make test" in it })

        // An unsolicited push still reaches events, and the link is fine.
        h.mac.sendSample("messages/history-and-selection/selection-range.json", null)
        h.mac.sendSample("messages/live-session/session-tree-push.json", null)
        runCurrent()
        assertEquals(1, h.events.size)
        assertTrue("${h.events}", h.events.single() is MacMessage.SessionTreeMessage)
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    @Test
    fun perRequestDeadlineOverridesRpc() = runTest {
        val h = Harness(this, timeouts = ClientTimeouts(rpc = 15.seconds))
        h.connect()
        h.mac.onRequest("ping") { null }
        val sentID = CompletableDeferred<Long>()
        val startedAt = currentTime
        val reply = async { h.client.request(PhoneMessage.Ping, timeout = 20.seconds, onSent = { sentID.complete(it) }) }
        val id = sentID.await()
        advanceTimeBy(19.seconds)
        assertFalse(reply.isCompleted)
        h.mac.sendSample("messages/session/pong.json", id)
        assertEquals(MacMessage.Pong, reply.await())
        assertTrue(currentTime - startedAt >= 19_000)
    }

    @Test
    fun oversizedMessageEndsLinkWithFlag() = runTest {
        val h = Harness(this, timeouts = ClientTimeouts(maxMessageBytes = 1024 * 1024))
        h.connect()
        h.mac.sendMedia(ByteArray(2 * 1024 * 1024))
        h.awaitConnected(2)
        runCurrent()

        assertEquals(
            listOf(StreamInput.LinkUp(1, FakeMac.MAC_REVISION), StreamInput.LinkDown(1, messageTooLarge = true), StreamInput.LinkUp(2, FakeMac.MAC_REVISION)),
            h.recorded.markers,
        )
        assertTrue(h.recorded.media.isEmpty())
    }

    /**
     * Back-pressure versus the rpc bound: a reply queued behind media the sink is slow to take
     * is not timed out at the bound while the sink is still working through the queue ahead of
     * it, up to one more bound. A reply that never comes times out once the queue is drained.
     */
    @Test
    fun slowSinkDoesNotTimeOutQueuedReply() = runTest {
        val sink = RecordingSink(perItem = 1.seconds)
        val h = Harness(this, sink, ClientTimeouts(rpc = 15.seconds))
        h.connect()
        h.mac.onRequest("ping") { null }
        val sentID = CompletableDeferred<Long>()
        val startedAt = currentTime
        val reply = async { h.client.request(PhoneMessage.Ping, onSent = { sentID.complete(it) }) }
        val id = sentID.await()
        repeat(STALL_FRAMES) { h.mac.sendMedia(byteArrayOf(it.toByte())) }
        h.mac.sendSample("messages/session/pong.json", id)

        assertEquals(MacMessage.Pong, reply.await())
        assertTrue("the reply waited behind ${STALL_FRAMES}s of media", currentTime - startedAt >= STALL_FRAMES * 1_000L)
        assertEquals(STALL_FRAMES, sink.media.size)

        // Unanswered behind a 20 s stall: past the bound it waits in 1.5 s slices while frames
        // are queued behind the sink, then fails at the first slice with the queue drained.
        val unanswered = async { runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull() }
        runCurrent()
        val secondStart = currentTime
        repeat(STALL_FRAMES) { h.mac.sendMedia(byteArrayOf(it.toByte())) }
        val timedOut = unanswered.await()
        assertTrue("$timedOut", timedOut is CompanionRequestException)
        val waited = currentTime - secondStart
        assertTrue("waited $waited ms", waited in 19_000L..21_000L)
    }

    /** A sink slower than the arrival rate with a Mac that has stopped answering: the request fails within twice its bound. */
    @Test
    fun unansweredRequestBehindSlowSinkFailsWithinTwiceItsBound() = runTest {
        val h = Harness(this, RecordingSink(perItem = 110.milliseconds), ClientTimeouts(rpc = 15.seconds))
        h.connect()
        h.mac.onRequest("ping") { null }
        val pump = launch {
            while (true) {
                h.mac.sendMedia(byteArrayOf(1))
                delay(100)
            }
        }
        runCurrent()
        val startedAt = currentTime
        val failure = runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull()
        val waited = currentTime - startedAt
        pump.cancel()
        assertTrue("$failure", failure is CompanionRequestException)
        assertTrue("waited $waited ms", waited in 15_000L..30_000L)
    }

    /** A sink that keeps up (90 ms per 100 ms frame, nothing queued) does not stretch the bound. */
    @Test
    fun sinkThatKeepsUpDoesNotStretchTheBound() = runTest {
        val h = Harness(this, RecordingSink(perItem = 90.milliseconds), ClientTimeouts(rpc = 15.seconds))
        h.connect()
        h.mac.onRequest("ping") { null }
        val pump = launch {
            while (true) {
                h.mac.sendMedia(byteArrayOf(1))
                delay(100)
            }
        }
        runCurrent()
        val startedAt = currentTime
        val failure = runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull()
        val waited = currentTime - startedAt
        pump.cancel()
        assertTrue("$failure", failure is CompanionRequestException)
        assertTrue("waited $waited ms", waited in 15_000L..16_500L)
    }

    /** The bound firing during a long stall with frames queued behind it: ten slices, then it fails at twice the bound. */
    @Test
    fun boundFiringDuringStallTakesBoundedRounds() = runTest {
        val h = Harness(this, RecordingSink(perItem = 60.seconds), ClientTimeouts(rpc = 15.seconds, sinkStall = 2.minutes))
        h.connect()
        h.mac.onRequest("ping") { null }
        val startedAt = currentTime
        val reply = async { runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull() }
        advanceTimeBy(14.seconds + 999.milliseconds)
        h.mac.sendMedia(byteArrayOf(1))
        h.mac.sendMedia(byteArrayOf(2))
        val failure = reply.await()
        assertTrue("$failure", failure is CompanionRequestException)
        assertEquals(30_000L, currentTime - startedAt)
        val extension = h.logs.single { it.contains("extension slices") }
        assertTrue(extension, extension.contains("and 10 extension slices"))
    }

    /** A sink stuck forever: cancel, start and unpair each return within sinkStall. */
    @Test
    fun stuckSinkDoesNotWedgeCancelStartOrUnpair() = runTest {
        val sink = StuckSink()
        val stall = 30.seconds
        val h = Harness(this, sink, ClientTimeouts(sinkStall = stall))
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        runCurrent()
        assertEquals(1, sink.stuckOn.size)

        val cancelledAt = currentTime
        h.client.cancel()
        withTimeout(stall + 1.seconds) { h.client.state.first { it == ClientState.Idle } }
        assertTrue(currentTime - cancelledAt <= stall.inWholeMilliseconds)
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("missed LinkDown(linkId=1") })

        h.client.start()
        h.awaitConnected(2)
        val unpairAt = currentTime
        withTimeout(stall + 1.seconds) { h.client.unpair() }
        assertEquals(ClientState.Unpaired, h.client.state.value)
        assertTrue(currentTime - unpairAt <= stall.inWholeMilliseconds + 1_000)
    }

    /** A stuck sink ends the link after sinkStall; frames already queued are still read, so the Mac's `unpaired` is honoured. */
    @Test
    fun unpairedBehindStuckSinkIsHonouredWithinSinkStall() = runTest {
        val stall = 30.seconds
        val h = Harness(this, StuckSink(), ClientTimeouts(sinkStall = stall))
        h.connect()
        val sentAt = currentTime
        h.mac.sendMedia(byteArrayOf(1))
        h.mac.sendUnpaired()
        runCurrent()
        h.mac.dropSocket()

        val failed = withTimeout(stall + 1.seconds) { h.client.state.first { it is ClientState.Failed } }
        assertEquals(ClientState.Failed("Unpaired on the Mac", rescanNeeded = true), failed)
        assertTrue(currentTime - sentAt <= stall.inWholeMilliseconds + 1_000)
        assertEquals(null, h.store.load())
        assertEquals(false, h.client.paired.value)
    }

    /** A stuck sink with nothing behind it: the link ends after sinkStall and the client reconnects. */
    @Test
    fun stuckSinkEndsTheLinkAndReconnects() = runTest {
        val sink = StuckSink()
        val h = Harness(this, sink, ClientTimeouts(sinkStall = 30.seconds))
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        h.awaitConnected(2)
        assertEquals(
            listOf<StreamInput>(StreamInput.LinkUp(1, FakeMac.MAC_REVISION), StreamInput.LinkUp(2, FakeMac.MAC_REVISION)),
            sink.inputs.toList(),
        )
        assertTrue(h.logs.any { it.contains("did not take Media within") })
    }

    /**
     * A link cancelled while the sink is still taking its LinkUp gets a LinkDown though the sink
     * never finished the LinkUp: the controller ignores a LinkDown for a link it does not know.
     */
    @Test
    fun cancelDuringLinkUpDeliversLinkDownAlone() = runTest {
        val sink = RecordingSink()
        sink.onAccept = { if (it is StreamInput.LinkUp) delay(10.seconds) }
        val h = Harness(this, sink)
        h.storedAndPinned()
        h.mac.start()
        h.client.start()
        advanceTimeBy(2.seconds)
        h.client.cancel()
        advanceTimeBy(1.minutes)
        assertEquals(listOf<StreamInput>(StreamInput.LinkDown(1)), sink.markers)
        assertEquals(ClientState.Idle, h.client.state.value)
    }

    /**
     * A request made inside accept(LinkUp) with a stream frame ahead of its reply cannot be
     * answered (the receive loop waits for the LinkUp to finish): it fails at its own bound, and
     * the link then works, including a later `unpaired`.
     */
    @Test
    fun requestInsideLinkUpEndsAtItsBound() = runTest {
        val sink = RecordingSink()
        val h = Harness(this, sink, ClientTimeouts(rpc = 15.seconds))
        var outcome: Throwable? = null
        var waited = -1L
        sink.onAccept = { input ->
            if (input is StreamInput.LinkUp) {
                h.mac.sendMedia(byteArrayOf(1))
                val startedAt = currentTime
                outcome = runCatching { h.client.request(PhoneMessage.Ping, onLink = input.linkId) }.exceptionOrNull()
                waited = currentTime - startedAt
            }
        }
        h.connect()
        assertTrue("$outcome", outcome is CompanionRequestException)
        assertEquals(15_000L, waited)
        runCurrent()
        assertEquals(1, sink.media.size)
        h.mac.sendUnpaired()
        val failed = withTimeout(1.minutes) { h.client.state.first { it is ClientState.Failed } }
        assertEquals(ClientState.Failed("Unpaired on the Mac", rescanNeeded = true), failed)
    }

    /** The relay socket's byte budget, through FakeRelay's real FrameQueue: 1006, lost, backoff, LinkDown, LinkUp. */
    @Test
    fun transportOverBudgetEndsLinkAndReconnects() = runTest {
        val sink = RecordingSink(perItem = 10.seconds)
        val h = Harness(this, sink, relay = FakeRelay(ORIGIN, phoneQueueBytes = 1024L * 1024))
        h.connect()
        for (index in 0 until 32) {
            val frame = ByteBuffer.allocate(60 * 1024).putInt(index).array()
            if (runCatching { h.mac.sendMedia(frame) }.isFailure) break
        }
        h.awaitConnected(2)
        runCurrent()

        assertEquals(
            listOf(StreamInput.LinkUp(1, FakeMac.MAC_REVISION), StreamInput.LinkDown(1), StreamInput.LinkUp(2, FakeMac.MAC_REVISION)),
            sink.markers,
        )
        val delivered = sink.media.map { ByteBuffer.wrap(it.copyBytes()).int }
        assertTrue("$delivered", delivered.size in 10..20)
        assertEquals("every frame before the close, in order", (0 until delivered.size).toList(), delivered)
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("receive queue over budget") })
    }


    /**
     * A stream-scoped reply queued behind a stalled sink is drained, not delivered: its request
     * fails when the link ends rather than report a stream the sink never saw.
     */
    @Test
    fun streamStartedDrainedAfterStallFailsItsRequest() = runTest {
        val sink = StuckSink()
        val h = Harness(this, sink, ClientTimeouts(sinkStall = 30.seconds))
        h.connect()
        h.mac.onRequest("startSessionStream") { null }
        var sentID = -1L
        val startedAt = currentTime
        val started = async {
            runCatching {
                h.client.request(PhoneMessage.StartSessionStream("ptys_7K2M9Q4R8T1VH"), timeout = 2.minutes, onSent = { sentID = it })
            }
        }
        runCurrent()
        h.mac.sendMedia(byteArrayOf(1))
        h.mac.sendSample("messages/live-session/stream-started.json", sentID)
        val outcome = started.await()
        assertTrue("$outcome", outcome.exceptionOrNull() is CompanionRequestException)
        assertTrue(currentTime - startedAt <= 31_000)
        h.awaitConnected(2)
        assertTrue("${sink.inputs}", sink.inputs.none { it is StreamInput.Control })
        assertTrue(h.logs.any { it.contains("dropped a stream input (StreamStarted)") })
    }

    /** A relay close frame queued behind a stalled sink is still attributed: "displaced" keeps its 60 s wait. */
    @Test
    fun displacedBehindStalledSinkKeepsItsWait() = runTest {
        val sink = RecordingSink()
        sink.onAccept = { if (it is StreamInput.Media) CompletableDeferred<Unit>().await() }
        val h = Harness(this, sink, ClientTimeouts(sinkStall = 30.seconds))
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        runCurrent()
        h.relay.closePhone(room, 1000, "displaced")
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("relay closed the connection: 1000 displaced") })
        advanceTimeBy(59.seconds)
        assertEquals("waits 60 s after displaced", 1, h.relay.phoneAdmissions.size)
        advanceTimeBy(2.seconds)
        assertTrue(h.relay.phoneAdmissions.size >= 2)
        h.awaitConnected(2)
    }

    /** A LinkUp the sink misses ends the link before anything else reaches the sink on it. */
    @Test
    fun missedLinkUpEndsTheLinkBeforeAnyInput() = runTest {
        val sink = RecordingSink()
        sink.onAccept = { if (it is StreamInput.LinkUp) delay(60.seconds) }
        val h = Harness(this, sink, ClientTimeouts(sinkStall = 30.seconds))
        h.storedAndPinned()
        h.mac.start()
        h.client.start()
        advanceTimeBy(10.seconds)
        h.mac.sendMedia(byteArrayOf(1))
        advanceTimeBy(2.minutes)

        assertTrue("${sink.inputs}", sink.inputs.none { it is StreamInput.Media || it is StreamInput.LinkUp })
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("missed LinkUp(linkId=1") })
        assertTrue("never Connected on a link the sink did not see", h.client.state.value !is ClientState.Connected)
        assertTrue("a new link each time", h.relay.phoneAdmissions.size >= 2)
        assertTrue(sink.inputs.contains(StreamInput.LinkDown(1)))
    }

    /** A sink that throws a CancellationException of its own (its scope died) ends the link deliberately, with a log. */
    @Test
    fun sinkThrowingItsOwnCancellationEndsTheLink() = runTest {
        val sink = RecordingSink()
        sink.onAccept = { if (it is StreamInput.Media) throw CancellationException("controller scope is dead") }
        val h = Harness(this, sink)
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        h.awaitConnected(2)
        runCurrent()
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it == "the stream sink failed on Media: CancellationException; ending the link" })
        assertEquals(
            listOf(StreamInput.LinkUp(1, FakeMac.MAC_REVISION), StreamInput.LinkDown(1), StreamInput.LinkUp(2, FakeMac.MAC_REVISION)),
            sink.markers,
        )
    }

    /** Messages that are not stream-scoped keep their paths while a stream runs. */
    @Test
    fun otherMessagesKeepTheirPaths() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        h.mac.sendSample("messages/live-session/session-tree-push.json", null)
        h.mac.sendMedia(byteArrayOf(2))
        runCurrent()
        assertEquals(listOf("sessionTree"), h.events.map { (it as? MacMessage.SessionTreeMessage)?.let { "sessionTree" } ?: "$it" })
        assertEquals(2, h.recorded.media.size)
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261116
        const val BURST = 10_000
        const val SENDS = 500
        const val STALL_FRAMES = 20

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

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
