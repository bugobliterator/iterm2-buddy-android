package dev.bugobliterator.itermbuddy.core.sessions

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.HomeGraph
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.InMemoryChatSeenStore
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import dev.bugobliterator.itermbuddy.wireHome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * The app's home graph as [wireHome] builds it for `CompanionApp`, on the in-process [FakeRelay],
 * [FakeMac] and [FakeChatMac] in virtual time: a real [CompanionClient], a real [StreamController]
 * with a [FakeVideoOutput], the [SessionTreeRepository], the [ChatRepository] (its seen marks in
 * [chatSeen]) and the navigator.
 *
 * @param events where the repository reads pushes; null for the client's own `events`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionsRig(
    private val test: TestScope,
    events: ((CompanionClient) -> Flow<MacMessage>)? = null,
) {
    private val macKeys = newStaticKeyPair()
    val code = PairingCode(macKeys.second, "sessions-rig-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")
    val store = InMemoryPairingStore()
    val relay = FakeRelay(ORIGIN)
    val logs = CopyOnWriteArrayList<String>()
    val video = FakeVideoOutput { test.testScheduler.currentTime }
    private val connections = MutableStateFlow(0)

    val chatSeen = InMemoryChatSeenStore()

    val graph: HomeGraph = wireHome(
        test.backgroundScope,
        newClient = { sink, chatSink ->
            CompanionClient(
                store,
                relay,
                test.backgroundScope,
                { endpoints },
                ClientTimeouts(),
                Random(SEED),
                test.testScheduler.timeSource,
                EmptyCoroutineContext,
                { logs += it },
                sink,
                chatSink,
            )
        },
        newController = { link ->
            StreamController(
                link,
                video,
                test.backgroundScope,
                nowMillis = { test.testScheduler.currentTime },
                log = { logs += it },
                ackContext = EmptyCoroutineContext,
            )
        },
        newRepository = { client ->
            SessionTreeRepository(client, test.backgroundScope, events = events?.invoke(client) ?: client.events, log = { logs += it })
        },
        newChats = { link ->
            ChatRepository(link, test.backgroundScope, chatSeen, nowMillis = { test.testScheduler.currentTime }, log = { logs += it })
        },
    )
    val client get() = graph.client
    val repository get() = graph.repository
    val navigator get() = graph.navigator
    val controller get() = graph.controller
    val chats get() = graph.chats
    val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)
    val chatMac = FakeChatMac(mac, test.backgroundScope)

    init {
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            client.state.collect { if (it is ClientState.Connected) connections.value++ }
        }
    }

    /** Has [mac] answer every `fetchSessionTree` with the reply sample. */
    fun answerFetches(mac: FakeMac = this.mac, sample: String = REPLY) {
        mac.onRequest("fetchSessionTree") { payloadOf(sample) }
    }

    suspend fun awaitConnected(times: Int = 1) {
        withTimeout(30.minutes) { connections.first { it >= times } }
        test.testScheduler.runCurrent()
    }

    /** A stored, registered pairing the Mac has pinned; then connects. */
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

    /** How many `fetchSessionTree` requests [mac] has received. */
    fun fetches(mac: FakeMac = this.mac): Int = mac.receivedCases().count { it == "fetchSessionTree" }

    companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261117
        const val REPLY = "messages/live-session/session-tree-reply.json"
        const val PUSH = "messages/live-session/session-tree-push.json"

        /** The payload object (`{"sessionTree": ...}`) of a live-session sample. */
        fun payloadOf(path: String): JsonObject =
            Json.parseToJsonElement(Fixtures.read(path)).jsonObject.getValue("json").jsonObject.getValue("payload").jsonObject

        /** The tree a sample decodes to. */
        fun treeOf(path: String): SessionTree {
            val json = Json.parseToJsonElement(Fixtures.read(path)).jsonObject.getValue("json")
            return (decodeEnvelope(json.toString().toByteArray()).second as MacMessage.SessionTreeMessage).tree
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
