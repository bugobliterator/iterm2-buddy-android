package dev.bugobliterator.itermbuddy.core

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.core.chat.ChatInbox
import dev.bugobliterator.itermbuddy.core.chat.ChatInput
import dev.bugobliterator.itermbuddy.core.chat.ChatSink
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.PayloadDecoding
import dev.bugobliterator.itermbuddy.core.noise.DEFAULT_MAX_MESSAGE_BYTES
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.wire.HistoryMemoryTest
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.peekEnvelope
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat channel: how the session routes chat-scoped messages to the [ChatSink], in wire order,
 * bracketed per link, with big bodies as raw bytes, and keeps them off `events`. Runs against the
 * in-process [FakeRelay], [FakeMac] and [FakeChatMac] in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatChannelTest {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "chat-channel-test-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")

    /** What both sinks took, in the one order the session delivered it. */
    private class Arrivals {
        val all = CopyOnWriteArrayList<Any>()
    }

    /** Records every chat input in order; each [accept] first suspends for [perItem] (virtual time). */
    private class RecordingChatSink(private val arrivals: Arrivals, var perItem: Duration = Duration.ZERO) : ChatSink {
        val inputs = CopyOnWriteArrayList<ChatInput>()

        var onAccept: suspend (ChatInput) -> Unit = {}

        override suspend fun accept(input: ChatInput) {
            if (perItem > Duration.ZERO) delay(perItem)
            onAccept(input)
            inputs += input
            arrivals.all += input
        }

        val markers: List<ChatInput> get() = inputs.filter { it is ChatInput.LinkUp || it is ChatInput.LinkDown }

        val messages: List<ChatInput.Message> get() = inputs.filterIsInstance<ChatInput.Message>()

        val payloads: List<ChatInput.Payload> get() = inputs.filterIsInstance<ChatInput.Payload>()
    }

    private class RecordingStreamSink(private val arrivals: Arrivals) : StreamSink {
        val inputs = CopyOnWriteArrayList<StreamInput>()

        override suspend fun accept(input: StreamInput) {
            inputs += input
            arrivals.all += input
        }

        val markers: List<StreamInput> get() = inputs.filter { it is StreamInput.LinkUp || it is StreamInput.LinkDown }
    }

    private inner class Harness(
        test: TestScope,
        timeouts: ClientTimeouts = ClientTimeouts(),
        maxRawChatBytes: Int = DEFAULT_MAX_RAW_CHAT_BYTES,
        rawDeliveryBytes: Int = DEFAULT_RAW_DELIVERY_BYTES,
        chatSink: ChatSink? = null,
        chatPerItem: Duration = Duration.ZERO,
        val relay: FakeRelay = FakeRelay(ORIGIN),
    ) {
        val arrivals = Arrivals()
        val chat = RecordingChatSink(arrivals, chatPerItem)
        private val sinkInUse: ChatSink = chatSink ?: chat
        val stream = RecordingStreamSink(arrivals)
        val store = InMemoryPairingStore()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<MacMessage>()

        /** Every message the receive loop decoded with decodeEnvelope, by its class. */
        val decoded = CopyOnWriteArrayList<String>()
        val decodes = AtomicInteger()
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
            stream,
            sinkInUse,
            maxRawChatBytes,
            envelopeDecoder = { bytes ->
                decodes.incrementAndGet()
                decodeEnvelope(bytes).also { decoded += it.second.javaClass.simpleName }
            },
            rawDeliveryBytes = rawDeliveryBytes,
        )
        val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)
        val chatMac = FakeChatMac(mac, test.backgroundScope)

        init {
            val unconfined = UnconfinedTestDispatcher(test.testScheduler)
            test.backgroundScope.launch(unconfined) {
                client.state.collect { if (it is ClientState.Connected) connections.value++ }
            }
            test.backgroundScope.launch(unconfined) { client.events.collect { events += it } }
        }

        suspend fun awaitConnected(times: Int = 1) {
            withTimeout(30.minutes) { connections.first { it >= times } }
        }

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
    fun historyReplyPrecedesLiveDeliveriesInSink() = runTest {
        val h = Harness(this, chatPerItem = 5.milliseconds)
        h.chatMac.afterSubscribe = { chatID, _ -> repeat(APPENDS) { h.mac.sendMessage(h.chatMac.append(chatID, it), null) } }
        h.connect()
        var takenAtReturn = -1
        var sentID = -1L
        val reply = h.client.request(PhoneMessage.Subscribe(FakeChatMac.SAMPLE_CHAT), onSent = { sentID = it })
            .also { takenAtReturn = h.chat.inputs.size }
        val historySize = h.chat.payloads.single().size

        assertEquals(MacMessage.ChatPayloadTaken("history", historySize), reply)
        assertTrue("the sink took the history before the request returned", takenAtReturn >= 2)
        advanceTimeBy(10.seconds)
        val afterLinkUp = h.chat.inputs.drop(1)
        val history = afterLinkUp.first() as ChatInput.Payload
        assertEquals("history", history.case)
        assertEquals(sentID, history.requestID)
        val appendIDs = afterLinkUp.drop(1).map { ((it as ChatInput.Message).message as MacMessage.Delivery).message.uniqueID }
        assertEquals((0 until APPENDS).map { FakeChatMac.appendID(it) }, appendIDs)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun bigChatBodiesCrossAsRawBytes() = runTest {
        val h = Harness(this)
        h.connect()
        val frames = listOf(
            envelope(h.chatMac.history(), 12),
            envelope(FakeChatMac.samplePayload("chats-and-sessions.json"), 9),
            envelope(FakeChatMac.samplePayload("chat-list-changed.json"), null),
        )
        val decodesBefore = h.decodes.get()
        for (frame in frames) h.mac.sendRaw(frame)
        runCurrent()

        assertEquals(listOf("history", "chatsAndSessions", "chatListChanged"), h.chat.payloads.map { it.case })
        assertEquals(listOf(12L, 9L, null), h.chat.payloads.map { it.requestID })
        for ((index, payload) in h.chat.payloads.withIndex()) assertArrayEquals(frames[index], payload.bytes)
        assertEquals("the receive loop decoded none of them", decodesBefore, h.decodes.get())
        assertTrue(h.decoded.none { it == "History" || it == "ChatsAndSessions" || it == "ChatListChanged" })
        // Orphans to no request: raw chat replies still reach the sink and never events.
        assertTrue(h.events.isEmpty())
        // The consumer's decode gives the messages back.
        val history = h.chat.payloads[0].decode() as PayloadDecoding.Decoded
        assertTrue(history.message is MacMessage.History)
        assertEquals(12L, history.requestID)
    }

    @Test
    fun historyOverTheChatCapIsNeverParsed() = runTest {
        val h = Harness(this)
        h.connect()
        val frame = HistoryMemoryTest.historyFrame(DEFAULT_MAX_RAW_CHAT_BYTES + 1, messages = 1_000)
        assertEquals(DEFAULT_MAX_RAW_CHAT_BYTES + 1, frame.size)
        val decodesBefore = h.decodes.get()
        h.mac.sendRaw(frame)
        runCurrent()

        assertEquals(
            listOf(ChatInput.PayloadTooLarge(1, "history", HISTORY_CHAT, 12, DEFAULT_MAX_RAW_CHAT_BYTES + 1)),
            h.chat.inputs.drop(1),
        )
        assertEquals(decodesBefore, h.decodes.get())
        // The link stays up.
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
        assertEquals(listOf<ChatInput>(ChatInput.LinkUp(1, FakeMac.MAC_REVISION, true)), h.chat.markers)
        // The peek that judged it allocates next to nothing, whatever the frame's size.
        assertTrue("peek allocated ${peekAllocation(frame)} bytes", peekAllocation(frame) < 64 * 1024)
    }

    @Test
    fun historyAtTheCapStillCrosses() = runTest {
        val h = Harness(this, maxRawChatBytes = 64 * 1024)
        h.connect()
        h.mac.sendRaw(HistoryMemoryTest.historyFrame(64 * 1024, messages = 10))
        h.mac.sendRaw(HistoryMemoryTest.historyFrame(64 * 1024 + 1, messages = 10))
        runCurrent()
        val inputs = h.chat.inputs.drop(1)
        assertEquals(64 * 1024, (inputs[0] as ChatInput.Payload).size)
        assertEquals(ChatInput.PayloadTooLarge(1, "history", HISTORY_CHAT, 12, 64 * 1024 + 1), inputs[1])
    }

    @Test
    fun tooLargeHistoryCompletesItsRequest() = runTest {
        val h = Harness(this, maxRawChatBytes = 1024)
        h.connect()
        val reply = h.client.request(PhoneMessage.Subscribe(FakeChatMac.SAMPLE_CHAT))
        val tooLarge = h.chat.inputs.filterIsInstance<ChatInput.PayloadTooLarge>().single()
        assertEquals(MacMessage.ChatPayloadTaken("history", tooLarge.sizeBytes), reply)
        assertTrue(tooLarge.sizeBytes > 1024)
    }

    @Test
    fun chatEventsNeverReachEvents() = runTest {
        val h = Harness(this, chatPerItem = 5.milliseconds)
        h.connect()
        repeat(DELIVERIES) { h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, it), null) }
        h.chatMac.streamReply(appends = 0)
        h.chatMac.flipAi(available = false)
        h.mac.sendMessage(FakeChatMac.samplePayload("messages-removed.json"), null)
        h.mac.sendMessage(FakeChatMac.samplePayload("chat-created.json"), 77)
        advanceTimeBy((DELIVERIES * 5 + 1000).milliseconds)

        val deliveries = h.chat.messages.map { it.message }.filterIsInstance<MacMessage.Delivery>()
        assertEquals((0 until DELIVERIES).map { FakeChatMac.appendID(it) }, deliveries.take(DELIVERIES).map { it.message.uniqueID })
        val kinds = h.chat.messages.drop(DELIVERIES).map { it.message.javaClass.simpleName }
        assertEquals(
            listOf("TypingStatus", "TurnLifecycle", "Delivery", "TypingStatus", "TurnLifecycle", "AiAvailabilityChanged", "MessagesRemoved", "ChatCreated"),
            kinds,
        )
        assertEquals("a late reply still reaches the sink", 77L, h.chat.messages.last().requestID)
        assertTrue("${h.events}", h.events.isEmpty())
        assertTrue(h.logs.none { "orphan" in it })
    }

    @Test
    fun unsolicitedErrorReachesChatSinkAndEvents() = runTest {
        val h = Harness(this)
        h.connect()
        h.chatMac.refuseDelete()
        runCurrent()
        val error = h.chat.messages.single()
        assertEquals(null, error.requestID)
        assertEquals("internalError", (error.message as MacMessage.Error).code)
        assertEquals(listOf<MacMessage>(error.message), h.events.toList())
        // An error that answers a request goes to its waiter only.
        h.mac.onRequest("ping") { FakeChatMac.samplePayload("error-delete-refused.json") }
        assertTrue(h.client.request(PhoneMessage.Ping) is MacMessage.Error)
        assertEquals(1, h.chat.messages.size)
        assertEquals(1, h.events.size)
    }

    @Test
    fun plainChatRepliesGoToTheirWaiterOnly() = runTest {
        val h = Harness(this)
        h.connect()
        val resolved = h.client.request(PhoneMessage.ResolveMentions(listOf("ptys_8M3B5X1QJ7TW1")))
        assertTrue("$resolved", resolved is MacMessage.MentionsResolved)
        val workgroup = h.client.request(PhoneMessage.FetchWorkgroupInfo("wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"))
        assertTrue("$workgroup", workgroup is MacMessage.WorkgroupInfoMessage)
        val created = h.client.request(PhoneMessage.CreateChat("Orchestrator", NewChatMode.Orchestrator))
        assertTrue("$created", created is MacMessage.ChatCreated)
        val listed = h.client.request(PhoneMessage.ListChatsAndSessions)
        assertEquals("chatsAndSessions", (listed as MacMessage.ChatPayloadTaken).case)
        assertEquals(
            listOf("ChatCreated", "Payload(chatsAndSessions)"),
            h.chat.inputs.drop(1).map { if (it is ChatInput.Payload) "Payload(${it.case})" else (it as ChatInput.Message).message.javaClass.simpleName },
        )
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun chatLinkMarkersBracketEachConnection() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.dropSocket()
        h.awaitConnected(2)
        runCurrent()
        assertEquals(
            listOf(ChatInput.LinkUp(1, 15, true), ChatInput.LinkDown(1), ChatInput.LinkUp(2, 15, true)),
            h.chat.markers,
        )
        // Each link's stream LinkUp comes first, then the chat one.
        val firstTwo = h.arrivals.all.take(2)
        assertEquals(listOf<Any>(StreamInput.LinkUp(1, 15), ChatInput.LinkUp(1, 15, true)), firstTwo)

        h.mac.helloAiAvailable = false
        h.mac.dropSocket()
        h.awaitConnected(3)
        h.mac.helloAiAvailable = null
        h.mac.dropSocket()
        h.awaitConnected(4)
        runCurrent()
        val ups = h.chat.markers.filterIsInstance<ChatInput.LinkUp>()
        assertEquals(listOf(true, true, false, true), ups.map { it.aiAvailable })
    }

    @Test
    fun chatInputsFromAnEndedLinkAreDropped() = runTest {
        val h = Harness(this, chatPerItem = 1.seconds)
        h.connect()
        repeat(20) { h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, it), null) }
        advanceTimeBy(3.seconds)
        h.mac.dropSocket()
        h.awaitConnected(2)
        advanceTimeBy(1.minutes)

        val linkDown = h.chat.inputs.indexOf(ChatInput.LinkDown(1))
        assertTrue("${h.chat.markers}", linkDown > 0)
        val afterDown = h.chat.inputs.drop(linkDown + 1)
        assertTrue("$afterDown", afterDown.none { it is ChatInput.Message && it.linkId == 1L })
        assertTrue(afterDown.first() == ChatInput.LinkUp(2, 15, true))
    }

    @Test
    fun streamAndChatInputsKeepWireOrder() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.sendMedia(byteArrayOf(1))
        h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 0), null)
        h.mac.sendSample("messages/live-session/stream-config-terminal.json", null)
        h.mac.sendRaw(envelope(FakeChatMac.samplePayload("chat-list-changed.json"), null))
        h.mac.sendMedia(byteArrayOf(2))
        h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 1), null)
        runCurrent()
        h.mac.dropSocket()
        h.awaitConnected(2)
        h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 2), null)
        h.mac.sendMedia(byteArrayOf(3))
        runCurrent()

        val order = h.arrivals.all.map { label(it) }
        assertEquals(
            listOf(
                "stream LinkUp 1", "chat LinkUp 1",
                "media 1", "chat Delivery 1", "stream StreamConfigMessage 1", "chat Payload 1", "media 1", "chat Delivery 1",
                "stream LinkDown 1", "chat LinkDown 1",
                "stream LinkUp 2", "chat LinkUp 2",
                "chat Delivery 2", "media 2",
            ),
            order,
        )
    }

    @Test
    fun missedChatLinkUpEndsTheLink() = runTest {
        val h = Harness(this, ClientTimeouts(sinkStall = 30.seconds))
        h.chat.onAccept = { if (it is ChatInput.LinkUp) delay(60.seconds) }
        h.storedAndPinned()
        h.mac.start()
        h.client.start()
        advanceTimeBy(10.seconds)
        h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 0), null)
        h.mac.sendMedia(byteArrayOf(1))
        advanceTimeBy(2.minutes)

        assertTrue("${h.chat.inputs}", h.chat.inputs.none { it is ChatInput.Message || it is ChatInput.LinkUp })
        assertTrue("${h.stream.inputs}", h.stream.inputs.none { it is StreamInput.Media })
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.contains("the chat sink missed LinkUp(linkId=1") })
        assertTrue("never Connected on a link the chat sink did not see", h.client.state.value !is ClientState.Connected)
        assertTrue("a new link each time", h.relay.phoneAdmissions.size >= 2)
        assertTrue(h.chat.inputs.contains(ChatInput.LinkDown(1)))
        assertTrue(h.stream.inputs.contains(StreamInput.LinkDown(1)))
    }

    @Test
    fun chatLinkDownSurvivesCancellation() = runTest {
        val operations: List<Pair<String, suspend (CompanionClient) -> Unit>> = listOf(
            "cancel" to { it.cancel() },
            "unpair" to { it.unpair() },
            "pair" to { it.pair(code) },
        )
        for ((name, operation) in operations) {
            val h = Harness(this, chatPerItem = 10.milliseconds)
            h.connect()
            runCurrent()
            operation(h.client)
            advanceTimeBy(1.seconds)
            assertEquals(name, ChatInput.LinkDown(1), h.chat.markers.getOrNull(1))
            assertEquals(name, StreamInput.LinkDown(1), h.stream.markers.getOrNull(1))
            h.mac.stop()
        }
    }

    @Test
    fun oversizedHistoryEndsLinkWithFlag() = runTest {
        // The Noise cap scaled down (1 MiB for 32 MiB) so the test stays light: a history over it ends the link.
        val h = Harness(this, ClientTimeouts(maxMessageBytes = 1024 * 1024), maxRawChatBytes = 256 * 1024)
        h.connect()
        h.mac.sendRaw(HistoryMemoryTest.historyFrame(2 * 1024 * 1024 + 1024, messages = 100))
        h.awaitConnected(2)
        runCurrent()

        assertEquals(
            listOf(
                ChatInput.LinkUp(1, FakeMac.MAC_REVISION, true),
                ChatInput.LinkDown(1, messageTooLarge = true),
                ChatInput.LinkUp(2, FakeMac.MAC_REVISION, true),
            ),
            h.chat.inputs.toList(),
        )
    }

    @Test
    fun sessionTreesStillUseEvents() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.sendSample("messages/live-session/session-tree-push.json", null)
        runCurrent()
        val tree = h.events.single() as MacMessage.SessionTreeMessage
        assertTrue("stamped with its arrival", tree.arrival > 0)
        assertTrue(h.chat.inputs.drop(1).isEmpty())
    }

    /**
     * A raw chat reply the peek accepts but the decoder rejects (nesting deeper than kotlinx
     * allows) still completes its request, and the consumer's decode names that request without
     * any content, so the request's Loading can end.
     */
    @Test
    fun undecodableRawReplyNamesItsRequest() = runTest {
        val h = Harness(this)
        h.connect()
        h.mac.onRequestScripted("listChatsAndSessions") {}
        var sentID = -1L
        val reply = async { h.client.request(PhoneMessage.ListChatsAndSessions, onSent = { sentID = it }) }
        runCurrent()
        val nested = "[".repeat(NESTING) + "\"SENTINEL-chat-text\"" + "]".repeat(NESTING)
        val frame = """{"payload":{"chatsAndSessions":{"chats":$nested,"sessions":[]}},"requestID":$sentID}""".toByteArray()
        peekEnvelope(frame)
        h.mac.sendRaw(frame)
        assertEquals(MacMessage.ChatPayloadTaken("chatsAndSessions", frame.size), reply.await())

        val outcome = h.chat.payloads.single().decode()
        assertTrue("$outcome", outcome is PayloadDecoding.Undecodable)
        assertEquals(sentID, outcome.requestID)
        assertFalse(outcome.toString(), outcome.toString().contains("SENTINEL"))
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun chatMessagesWithoutAChatSinkAreDroppedByKind() = runTest {
        val relay = FakeRelay(ORIGIN)
        val store = InMemoryPairingStore()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<MacMessage>()
        val client = CompanionClient(
            store, relay, backgroundScope, { endpoints }, ClientTimeouts(), Random(SEED),
            testScheduler.timeSource, EmptyCoroutineContext, { logs += it },
        )
        val mac = FakeMac(relay, code, macKeys.first, backgroundScope)
        val chatMac = FakeChatMac(mac, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.collect { events += it } }
        var record = beginFreshPairing(store, code)
        record = advancePairing(store, record, PairingStage.CONFIRMED)
        record = advancePairing(store, record, PairingStage.REGISTERED)
        relay.lock(room, JoinSigner(record.roomSecret).verifier)
        mac.roomSecret = record.roomSecret
        mac.pinnedPhoneStatic = publicKeyOf(record.phoneStaticPrivate)
        mac.start()
        client.start()
        withTimeout(1.minutes) { client.state.first { it is ClientState.Connected } }

        mac.sendMessage(chatMac.append(FakeChatMac.SAMPLE_CHAT, 0), null)
        mac.sendRaw(envelope(FakeChatMac.samplePayload("chat-list-changed.json"), null))
        runCurrent()
        assertTrue("${events}", events.isEmpty())
        assertTrue(logs.joinToString("\n"), logs.any { it == "dropped a chat input (Delivery): no chat sink" })
        assertTrue(logs.joinToString("\n"), logs.any { it == "dropped a chat input (chatListChanged): no chat sink" })
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
    }

    /**
     * A `delivery` over the raw delivery threshold (an attachment with inline bytes) crosses raw
     * and is decoded by the consumer, so a video frame behind it reaches the stream sink before
     * the consumer has even started on it; a small delivery still decodes on the loop.
     */
    @Test
    fun bigDeliveryCrossesRawAndIsDecodedByTheConsumer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val consumed = CopyOnWriteArrayList<ChatInput>()
        val inbox = ChatInbox(backgroundScope) { input ->
            if (input !is ChatInput.LinkUp) gate.await()
            consumed += input
        }
        val h = Harness(this, rawDeliveryBytes = 4096, chatSink = inbox)
        h.connect()
        val big = padded(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 1), "delivery", 64 * 1024)
        val decodesBefore = h.decodes.get()
        h.mac.sendMessage(big, null)
        h.mac.sendMedia(byteArrayOf(9))
        h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 2), null)
        runCurrent()

        assertEquals("the video frame went through", 1, h.stream.inputs.count { it is StreamInput.Media })
        assertEquals("the consumer has decoded nothing yet", 1, consumed.size)
        assertEquals("only the small delivery decoded on the loop", 1, h.decodes.get() - decodesBefore)
        assertEquals("Delivery", h.decoded.last())
        gate.complete(Unit)
        runCurrent()
        val raw = consumed[1] as ChatInput.Payload
        assertEquals("delivery", raw.case)
        assertTrue(raw.size > 64 * 1024)
        val delivery = (raw.decode() as PayloadDecoding.Decoded).message as MacMessage.Delivery
        assertEquals(FakeChatMac.appendID(1), delivery.message.uniqueID)
        val small = consumed[2] as ChatInput.Message
        assertEquals(FakeChatMac.appendID(2), (small.message as MacMessage.Delivery).message.uniqueID)
        assertTrue("a decoded message carries its frame size", small.frameBytes in 1..4096)
        assertTrue(h.events.isEmpty())
    }

    /** Every raw case over the raw chat cap is PayloadTooLarge, never decoded, its waiter completed. */
    @Test
    fun everyRawCaseOverTheCapIsTooLarge() = runTest {
        val cap = 8 * 1024
        val h = Harness(this, maxRawChatBytes = cap, rawDeliveryBytes = 1024)
        h.connect()
        val list = padded(FakeChatMac.samplePayload("chats-and-sessions.json"), "chatsAndSessions", cap + 1)
        h.mac.onRequestScripted("listChatsAndSessions") { envelope ->
            h.mac.sendMessage(list, envelope.getValue("requestID").let { (it as JsonPrimitive).content.toLong() })
        }
        val decodesBefore = h.decodes.get()
        val reply = h.client.request(PhoneMessage.ListChatsAndSessions) as MacMessage.ChatPayloadTaken
        assertEquals("chatsAndSessions", reply.case)
        assertTrue(reply.sizeBytes > cap)
        h.mac.sendRaw(envelope(padded(FakeChatMac.history(), "history", cap + 1), 12))
        h.mac.sendRaw(envelope(padded(FakeChatMac.samplePayload("chat-list-changed.json"), "chatListChanged", cap + 1), null))
        h.mac.sendRaw(envelope(padded(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, 0), "delivery", cap + 1), null))
        runCurrent()

        val tooLarge = h.chat.inputs.filterIsInstance<ChatInput.PayloadTooLarge>()
        assertEquals(listOf("chatsAndSessions", "history", "chatListChanged", "delivery"), tooLarge.map { it.case })
        assertEquals(listOf(null, FakeChatMac.SAMPLE_CHAT, null, FakeChatMac.SAMPLE_CHAT), tooLarge.map { it.chatID })
        assertTrue("the list reply names its request", tooLarge[0].requestID != null)
        assertEquals(listOf(12L, null, null), tooLarge.drop(1).map { it.requestID })
        assertEquals(reply.sizeBytes, tooLarge[0].sizeBytes)
        assertTrue(tooLarge.all { it.sizeBytes > cap })
        assertEquals("none was decoded", decodesBefore, h.decodes.get())
        assertTrue(h.chat.payloads.isEmpty())
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    @Test
    fun rawChatCapIsUnderTheNoiseCap() {
        assertTrue(DEFAULT_MAX_RAW_CHAT_BYTES < DEFAULT_MAX_MESSAGE_BYTES)
        assertTrue(DEFAULT_RAW_DELIVERY_BYTES < DEFAULT_MAX_RAW_CHAT_BYTES)
    }

    /** A chat reply the sink did not take fails its request at once, not at its bound; the link stays up. */
    @Test
    fun chatReplyNotTakenFailsAtOnce() = runTest {
        val h = Harness(this, ClientTimeouts(rpc = 15.seconds))
        h.chat.onAccept = { if (it is ChatInput.Payload || it is ChatInput.Message) throw IllegalStateException("sink broke") }
        h.connect()
        for (request in listOf(PhoneMessage.ListChatsAndSessions, PhoneMessage.CreateChat("Orchestrator", NewChatMode.Orchestrator))) {
            val startedAt = currentTime
            val failure = runCatching { h.client.request(request) }.exceptionOrNull()
            assertTrue("$failure", failure is CompanionRequestException)
            assertEquals("the chat sink did not take the reply", failure?.message)
            assertEquals("at once", startedAt, currentTime)
        }
        assertTrue(h.logs.any { it == "the chat sink failed on chatsAndSessions: IllegalStateException" })
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    /** With no chat sink, a chat request fails at once too. */
    @Test
    fun chatReplyWithoutAChatSinkFailsAtOnce() = runTest {
        val h = Harness(this, chatSink = ChatSink { if (it !is ChatInput.LinkUp) throw IllegalStateException() })
        h.connect()
        val startedAt = currentTime
        val failure = runCatching { h.client.request(PhoneMessage.Subscribe(FakeChatMac.SAMPLE_CHAT)) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        assertEquals(startedAt, currentTime)
    }

    @Test
    fun chatSinkStallEndsTheLink() = runTest {
        val h = Harness(this, ClientTimeouts(sinkStall = 30.seconds))
        h.chat.onAccept = { if (it is ChatInput.Payload) delay(60.seconds) }
        h.connect()
        val startedAt = currentTime
        val failure = runCatching { h.client.request(PhoneMessage.ListChatsAndSessions, timeout = 2.minutes) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        assertEquals("fails as the link ends at sinkStall", 30_000L, currentTime - startedAt)
        assertTrue(h.logs.joinToString("\n"), h.logs.any { it.startsWith("the chat sink did not take chatsAndSessions within 30s; ending the link") })
        h.awaitConnected(2)
        runCurrent()
        assertEquals(
            listOf(ChatInput.LinkUp(1, 15, true), ChatInput.LinkDown(1), ChatInput.LinkUp(2, 15, true)),
            h.chat.markers,
        )
    }

    /** A chat inbox whose consumer died refuses at once: chat requests fail, video and the link go on. */
    @Test
    fun deadChatConsumerDoesNotStallTheLink() = runTest {
        val consumerScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + Job())
        val inbox = ChatInbox(consumerScope) {}
        val h = Harness(this, ClientTimeouts(sinkStall = 30.seconds), chatSink = inbox)
        h.connect()
        consumerScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        val startedAt = currentTime
        repeat(10) { h.mac.sendMessage(h.chatMac.append(FakeChatMac.SAMPLE_CHAT, it), null) }
        h.mac.sendMedia(byteArrayOf(1))
        runCurrent()
        assertEquals(startedAt, currentTime)
        assertEquals(1, h.stream.inputs.count { it is StreamInput.Media })
        val failure = runCatching { h.client.request(PhoneMessage.ListChatsAndSessions) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        assertEquals(startedAt, currentTime)
        assertEquals("still the first link", 1, h.relay.phoneAdmissions.size)
        assertTrue(h.client.state.value is ClientState.Connected)
    }

    @Test
    fun chatInputsShowNoContent() {
        val secret = "SENTINEL-chat-text"
        val history = envelope(FakeChatMac.history(), 12).decodeToString().replace("Ask @", "$secret @").toByteArray()
        val decoded = ChatInput.Payload(1, 12, "history", history).decode() as PayloadDecoding.Decoded
        val delivery = decodeEnvelope(envelope(FakeChatMac.samplePayload("delivery-agent-opening.json"), null)).second
        val error = decodeEnvelope(envelope(FakeChatMac.samplePayload("error-delete-refused.json"), null)).second
        for (shown in listOf(
            ChatInput.Payload(1, 12, "history", history).toString(),
            ChatInput.Message(1, null, decoded.message, history.size).toString(),
            ChatInput.Message(1, null, delivery, 100).toString(),
            ChatInput.Message(1, null, error, 100).toString(),
            decoded.toString(),
        )) {
            assertFalse(shown, shown.contains(secret))
            assertFalse(shown, shown.contains("Running the tests"))
            assertFalse(shown, shown.contains("not available"))
        }
    }

    /** [payload] with an ignored `padding` key in its [case] body, so its envelope is at least [atLeast] bytes. */
    private fun padded(payload: JsonObject, case: String, atLeast: Int): JsonObject {
        val body = payload.getValue(case) as JsonObject
        val bare = envelope(payload, 99_999).size
        val padding = JsonPrimitive("p".repeat(maxOf(0, atLeast - bare) + 16))
        return JsonObject(mapOf(case to JsonObject(body + ("padding" to padding))))
    }

    private fun label(input: Any): String = when (input) {
        is StreamInput.LinkUp -> "stream LinkUp ${input.linkId}"
        is StreamInput.LinkDown -> "stream LinkDown ${input.linkId}"
        is StreamInput.Media -> "media ${input.linkId}"
        is StreamInput.Control -> "stream ${input.message.javaClass.simpleName} ${input.linkId}"
        is ChatInput.LinkUp -> "chat LinkUp ${input.linkId}"
        is ChatInput.LinkDown -> "chat LinkDown ${input.linkId}"
        is ChatInput.Message -> "chat ${input.message.javaClass.simpleName} ${input.linkId}"
        is ChatInput.Payload -> "chat Payload ${input.linkId}"
        is ChatInput.PayloadTooLarge -> "chat PayloadTooLarge ${input.linkId}"
        else -> "$input"
    }

    private fun peekAllocation(frame: ByteArray): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(3) { peekEnvelope(frame) }
        val before = threads.currentThreadAllocatedBytes
        peekEnvelope(frame)
        return threads.currentThreadAllocatedBytes - before
    }

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261118
        const val APPENDS = 50
        const val DELIVERIES = 1_000
        const val NESTING = 600

        /** The chat ID HistoryMemoryTest.historyFrame writes. */
        const val HISTORY_CHAT = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"

        /** [payload] in an envelope, keys in the order the Mac's samples list them. */
        fun envelope(payload: JsonObject, requestID: Long?): ByteArray {
            val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            map["payload"] = payload
            if (requestID != null) map["requestID"] = JsonPrimitive(requestID)
            return JsonObject(map).toString().toByteArray(Charsets.UTF_8)
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
