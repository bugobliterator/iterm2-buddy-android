package dev.bugobliterator.itermbuddy.core

import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingRecord
import dev.bugobliterator.itermbuddy.store.PairingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * The connect flow's failure lines name the exception's class, never its message: an I/O error's
 * message can carry hosts, paths or what was being read, and the log persists on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExceptionLogRedactionTest {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "redaction-test-pid", ORIGIN)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")
    private val logs = CopyOnWriteArrayList<String>()

    /** Wraps the fake relay's sockets: sends can fail, and incoming can fail, with [SECRET]. */
    private class BreakableTransport(private val relay: FakeRelay, private val test: TestScope) : RelayTransport {
        @Volatile var failSends = false

        val sockets = CopyOnWriteArrayList<BreakableSocket>()

        inner class BreakableSocket(private val inner: RelaySocket) : RelaySocket {
            private val forwarded = Channel<ByteArray>(Channel.UNLIMITED)

            init {
                test.backgroundScope.launch {
                    for (frame in inner.incoming) forwarded.send(frame)
                    forwarded.close()
                }
            }

            override suspend fun admit(proof: Proof): AdmissionResult = inner.admit(proof)

            override val incoming: ReceiveChannel<ByteArray> get() = forwarded

            override suspend fun send(frame: ByteArray) {
                if (failSends) throw IOException(SECRET)
                inner.send(frame)
            }

            override fun close() {
                inner.close()
                forwarded.close()
            }

            fun breakIncoming() {
                forwarded.close(IOException(SECRET))
            }
        }

        override fun open(endpoints: RelayEndpoints, roomName: String): RelaySocket =
            BreakableSocket(relay.open(endpoints, roomName)).also { sockets += it }

        override suspend fun register(endpoints: RelayEndpoints, roomName: String, registrationToken: String, verifier: ByteArray): RegisterResult =
            relay.register(endpoints, roomName, registrationToken, verifier)
    }

    private fun TestScope.client(transport: RelayTransport, store: PairingStore = InMemoryPairingStore()) = CompanionClient(
        store,
        transport,
        backgroundScope,
        { endpoints },
        ClientTimeouts(),
        Random(1),
        testScheduler.timeSource,
        EmptyCoroutineContext,
        { logs += it },
    )

    private fun assertNoSecret() = assertFalse(logs.joinToString("\n"), logs.any { SECRET in it })

    @Test
    fun aHandshakeIoFailureLogsItsClassOnly() = runTest {
        val relay = FakeRelay(ORIGIN)
        val transport = BreakableTransport(relay, this)
        FakeMac(relay, code, macKeys.first, backgroundScope).start()
        transport.failSends = true
        client(transport).pair(code)
        withTimeout(1.minutes) { while (logs.none { it.startsWith("handshake failed") }) delay(10) }
        assertTrue(logs.joinToString("\n"), logs.contains("handshake failed: IOException"))
        assertNoSecret()
    }

    @Test
    fun aConnectionIoFailureLogsItsClassOnly() = runTest {
        val relay = FakeRelay(ORIGIN)
        val transport = BreakableTransport(relay, this)
        val mac = FakeMac(relay, code, macKeys.first, backgroundScope)
        mac.start()
        val client = client(transport)
        client.pair(code)
        mac.awaitingCode.first { it != null }
        mac.enterCode(accept = true)
        withTimeout(30.minutes) { client.state.first { it is ClientState.Connected } }
        transport.sockets.last().breakIncoming()
        withTimeout(1.minutes) { while (logs.none { it.startsWith("connection failed") }) delay(10) }
        assertTrue(logs.joinToString("\n"), logs.contains("connection failed: IOException"))
        assertNoSecret()
    }

    @Test
    fun anUnexpectedConnectFlowFailureLogsItsClassOnly() = runTest {
        val store = object : PairingStore by InMemoryPairingStore() {
            override fun load(): PairingRecord? = throw IllegalStateException(SECRET)
        }
        val client = client(BreakableTransport(FakeRelay(ORIGIN), this), store)
        client.start()
        val failed = withTimeout(1.minutes) { client.state.first { it is ClientState.Failed } } as ClientState.Failed
        assertTrue(logs.joinToString("\n"), logs.contains("connect flow failed: IllegalStateException"))
        assertFalse(SECRET in failed.message)
        assertNoSecret()
    }

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SECRET = "wss://relay.example/room/SECRET-ROOM-NAME"
    }
}
