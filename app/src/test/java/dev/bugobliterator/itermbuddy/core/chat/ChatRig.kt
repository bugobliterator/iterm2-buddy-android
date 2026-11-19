package dev.bugobliterator.itermbuddy.core.chat

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.RequestNotSentException
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A [ChatRepository] on a real [CompanionClient], the in-process [FakeRelay], [FakeMac] and
 * [FakeChatMac], in virtual time. The Mac's chat list is [chats] (answered to every
 * `listChatsAndSessions`, pushed by [pushList]); its subscribe answers can be held, withheld or
 * replaced per chat. Caps are scaled down: [RAW_CAP] stands for the 8 MiB raw chat cap,
 * [NOISE_CAP] for the 32 MiB Noise cap, [RAW_DELIVERY] for the 256 KiB raw delivery threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatRig(
    private val test: TestScope,
    timeouts: ChatTimeouts = ChatTimeouts(),
    concurrency: Int = 2,
    val seen: ChatSeenStore = InMemoryChatSeenStore(),
    budgets: ChatBudgets = ChatBudgets(),
    /** The phone's clock against the test scheduler's; the Mac's chat times are unrelated to it. */
    phoneClockOffsetMillis: Long = 0,
    /** The `uniqueID`s the phone gives the messages it sends; seeded, so a run repeats. */
    newUuid: () -> UUID = Random(SEED).let { random -> { UUID(random.nextLong(), random.nextLong()) } },
    /** The phone's wall clock, for a sent message's `sentDate`. */
    wallClockMillis: () -> Long = { MAC_TIME.toLong() + test.testScheduler.currentTime },
    /** Whether conversations nobody collects are still snapshotted: tests read `conversation(id).value` directly. */
    snapshotUncollected: Boolean = true,
) {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "chat-repository-test-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")
    val relay = FakeRelay(ORIGIN)
    val store = InMemoryPairingStore()
    val logs = CopyOnWriteArrayList<String>()

    /** How long each chat-sink accept took, in virtual milliseconds. */
    val acceptMillis = CopyOnWriteArrayList<Long>()
    private val connections = MutableStateFlow(0)

    val client: CompanionClient = CompanionClient(
        store,
        relay,
        test.backgroundScope,
        { endpoints },
        ClientTimeouts(maxMessageBytes = NOISE_CAP),
        Random(SEED),
        test.testScheduler.timeSource,
        EmptyCoroutineContext,
        { logs += it },
        null,
        { input ->
            val start = test.testScheduler.currentTime
            repository.sink.accept(input)
            acceptMillis += test.testScheduler.currentTime - start
        },
        RAW_CAP,
        rawDeliveryBytes = RAW_DELIVERY,
    )

    /** Chats whose subscribes fail as if the envelope could not be written: the Mac never sees them. */
    val notSent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When set, a `publish` fails as if its frame could not be written. */
    @Volatile var failPublishes = false

    /** When set, the Mac echoes each `publish` back as a `delivery` (`HB:1812-1823`). */
    @Volatile var echoPublishes = false

    /** When set, a `publish` waits for it before going to the client, as behind the session's send lock. */
    @Volatile var publishGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** The link each `publish` was sent on (`onLink`), in order. */
    val publishLinks = CopyOnWriteArrayList<Long?>()

    /** The client as the repository's link, except for [notSent] and [failPublishes]. */
    private val link = object : StreamLink {
        override suspend fun send(message: PhoneMessage, onLink: Long?) {
            if (message is PhoneMessage.Publish) {
                publishLinks += onLink
                publishGate?.await()
                if (failPublishes) throw RequestNotSentException("the frame could not be sent")
            }
            client.send(message, onLink)
        }

        override suspend fun request(
            message: PhoneMessage,
            onLink: Long?,
            timeout: Duration?,
            onSent: (requestID: Long) -> Unit,
        ): MacMessage {
            if (message is PhoneMessage.Subscribe && message.chatID in notSent) {
                onSent(NOT_SENT_REQUEST_ID)
                throw RequestNotSentException("the frame could not be sent")
            }
            return client.request(message, onLink, timeout, onSent)
        }
    }

    val repository = ChatRepository(
        link,
        test.backgroundScope,
        seen,
        timeouts,
        budgets,
        concurrency,
        nowMillis = { test.testScheduler.currentTime + phoneClockOffsetMillis },
        log = { logs += it },
        newUuid = newUuid,
        wallClockMillis = wallClockMillis,
        snapshotUncollected = snapshotUncollected,
    )

    val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)
    val chatMac = FakeChatMac(mac, test.backgroundScope)

    /** The Mac's chats, most recent first: (chat ID, `lastModifiedDate`). */
    @Volatile var chats: List<Pair<String, Double>> = emptyList()

    /** Chat IDs in the order the Mac received subscribes for them. */
    val subscribes = CopyOnWriteArrayList<String>()

    /** How many `listChatsAndSessions` requests the Mac received. */
    val listRequests = AtomicInteger()

    /** The next subscribe for a chat is answered this much later (its own coroutine). */
    val holdNext = ConcurrentHashMap<String, Duration>()

    /** Chats whose subscribes are never answered. */
    val neverAnswer: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Replaces the `history` reply for a chat: the whole envelope, given the request ID. */
    val historyFrames = ConcurrentHashMap<String, (Long?) -> ByteArray>()

    /** Replaces the reply to `listChatsAndSessions`: the whole envelope, given the request ID. */
    @Volatile var listFrame: ((Long?) -> ByteArray)? = null

    /** Each `listChatsAndSessions` reply is sent this much later (its own coroutine). */
    @Volatile var listHold: Duration? = null

    /** Runs after a chat's history and snapshot are sent. */
    @Volatile var afterSubscribe: suspend (String) -> Unit = {}

    private val inFlight = AtomicInteger()

    /** The most subscribes the Mac held unanswered at once. */
    val maxInFlight = AtomicInteger()

    /** The bodies of the `publish` messages the Mac received, in order. */
    fun publishes(): List<JsonObject> = mac.receivedBodies("publish")

    /** Sends the Mac's echo of [publish] (a `publish` body): `delivery` of its message (`HB:1812-1823`). */
    suspend fun echo(publish: JsonObject) {
        val message = publish.getValue("message").jsonObject
        val chatID = publish.getValue("toChatID")
        val delivery = mapOf("chatID" to chatID, "message" to message, "partial" to JsonPrimitive(false))
        mac.sendMessage(JsonObject(mapOf("delivery" to JsonObject(delivery))), null)
    }

    init {
        mac.onRequestScripted("publish") { envelope ->
            // requireAI drops a publish (no request ID) silently while AI is off (HB:2289-2293).
            if (chatMac.refusedWithoutAi(requestIDOf(envelope))) return@onRequestScripted
            if (echoPublishes) echo(envelope.getValue("payload").jsonObject.getValue("publish").jsonObject)
        }
        val unconfined = UnconfinedTestDispatcher(test.testScheduler)
        test.backgroundScope.launch(unconfined) {
            client.state.collect { if (it is ClientState.Connected) connections.value++ }
        }
        mac.onRequestScripted("listChatsAndSessions") { envelope ->
            listRequests.incrementAndGet()
            val id = requestIDOf(envelope)
            // Built when it is sent, as the Mac's main actor does: AI turned off meanwhile lists no chats.
            val frame = { listFrame?.invoke(id) ?: envelope(listPayload("chatsAndSessions"), id) }
            val hold = listHold
            if (hold == null) {
                mac.sendRaw(frame())
            } else {
                test.backgroundScope.launch {
                    delay(hold)
                    try {
                        mac.sendRaw(frame())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The link went away while the reply was held back.
                    }
                }
            }
        }
        mac.onRequestScripted("subscribe") { envelope ->
            val id = requestIDOf(envelope)
            val chatID = envelope.getValue("payload").jsonObject.getValue("subscribe").jsonObject.getValue("chatID").jsonPrimitive.content
            if (chatMac.refusedWithoutAi(id)) return@onRequestScripted
            chatMac.subscribed += chatID
            subscribes += chatID
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            val hold = holdNext.remove(chatID)
            when {
                chatID in neverAnswer -> Unit
                hold == null -> answerSubscribe(chatID, id)
                else -> test.backgroundScope.launch {
                    delay(hold)
                    try {
                        answerSubscribe(chatID, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The link went away while the reply was held back.
                    }
                }
            }
        }
    }

    private suspend fun answerSubscribe(chatID: String, id: Long?) {
        val frame = historyFrames[chatID]
        if (frame != null) mac.sendRaw(frame(id)) else mac.sendMessage(FakeChatMac.history(chatID), id)
        inFlight.decrementAndGet()
        if (chatMac.agentTyping) mac.sendMessage(FakeChatMac.retarget(FakeChatMac.samplePayload("typing-status-on.json"), chatID), null)
        if (chatMac.turnInProgress) mac.sendMessage(FakeChatMac.retarget(FakeChatMac.samplePayload("turn-lifecycle-started.json"), chatID), null)
        afterSubscribe(chatID)
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

    /** How many times the client has connected. */
    val connectionCount: Int get() = connections.value

    suspend fun awaitConnected(times: Int = 1) {
        withTimeout(30.minutes) { connections.first { it >= times } }
    }

    /** Ends the link from the Mac's side and waits for the next one. */
    suspend fun reconnect() {
        val before = connections.value
        mac.dropSocket()
        awaitConnected(before + 1)
    }

    /** The chat list as the Mac sends it with payload [case]: no chats while AI is off (`HB:448-455`). */
    fun listPayload(case: String): JsonObject = listPayloadOf(case, if (chatMac.aiAvailable) chats else emptyList())

    /** Pushes `chatListChanged` with [chats]. */
    suspend fun pushList() = mac.sendRaw(envelope(listPayload("chatListChanged"), null))

    /** Sends the `agent-chat` sample [name], every chat ID in it set to [chatID]. */
    suspend fun sendSample(name: String, chatID: String) =
        mac.sendMessage(FakeChatMac.retarget(FakeChatMac.samplePayload(name), chatID), null)

    /** How many subscribes the Mac received for [chatID]. */
    fun subscribesFor(chatID: String): Int = subscribes.count { it == chatID }

    companion object {
        /** A chat list payload with [case] listing [chats] ((chat ID, `lastModifiedDate`)). */
        fun listPayloadOf(case: String, chats: List<Pair<String, Double>>): JsonObject {
            val sample = FakeChatMac.samplePayload("chats-and-sessions.json").getValue("chatsAndSessions").jsonObject
            val template = sample.getValue("chats").jsonArray[1].jsonObject
            val entries = chats.map { (id, lastModified) -> entry(template, id, lastModified) }
            val body = LinkedHashMap<String, JsonElement>()
            body["chats"] = JsonArray(entries)
            if (case == "chatsAndSessions") body["sessions"] = sample.getValue("sessions")
            return JsonObject(mapOf(case to JsonObject(body)))
        }

        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261119
        const val RAW_CAP = 32 * 1024
        const val RAW_DELIVERY = 16 * 1024
        const val NOISE_CAP = 256 * 1024
        const val NOT_SENT_REQUEST_ID = 1_000_000L

        /** The Mac's `lastModifiedDate` of the sample chats, give or take. */
        const val MAC_TIME = 1_795_000_400_000.0

        fun chatId(index: Int): String = "C0DE0000-0000-4000-8000-%012d".format(index)

        private fun entry(template: JsonObject, id: String, lastModified: Double): JsonObject {
            val chat = template.getValue("chat").jsonObject
            val changed = chat + mapOf(
                "id" to JsonPrimitive(id),
                "lastModifiedDate" to JsonPrimitive(lastModified),
                "title" to JsonPrimitive("Chat $id"),
            )
            return JsonObject(template + ("chat" to JsonObject(changed)))
        }

        fun requestIDOf(envelope: JsonObject): Long? = (envelope["requestID"] as? JsonPrimitive)?.long

        /** [payload] in an envelope with [requestID]. */
        fun envelope(payload: JsonObject, requestID: Long?): ByteArray {
            val map = LinkedHashMap<String, JsonElement>()
            map["payload"] = payload
            if (requestID != null) map["requestID"] = JsonPrimitive(requestID)
            return JsonObject(map).toString().toByteArray(Charsets.UTF_8)
        }

        /** [payload] with an ignored `padding` key in its [case] body, so its envelope is at least [atLeast] bytes. */
        fun padded(payload: JsonObject, case: String, atLeast: Int): JsonObject {
            val body = payload.getValue(case) as JsonObject
            val bare = envelope(payload, 99_999).size
            val padding = JsonPrimitive("p".repeat(maxOf(0, atLeast - bare) + 16))
            return JsonObject(mapOf(case to JsonObject(body + ("padding" to padding))))
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

        val SETTLE: Duration = 2.seconds
    }
}
