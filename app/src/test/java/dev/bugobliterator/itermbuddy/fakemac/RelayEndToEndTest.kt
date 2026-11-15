package dev.bugobliterator.itermbuddy.fakemac

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RelayConnection
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The client and [FakeMac] pairing through the real relay (`node bin/relay.js` at b8aa2f9) in
 * open mode. Skipped unless `RELAY_DIR` names a relay checkout with its dependencies installed;
 * `scripts/relay-it.sh` sets that up and runs this test.
 */
class RelayEndToEndTest {
    private val relayDir: String? = System.getenv("RELAY_DIR")
    private lateinit var workDir: File
    private val relays = mutableListOf<RelayProcess>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun requireRelay() {
        assumeTrue("RELAY_DIR is not set; run scripts/relay-it.sh", !relayDir.isNullOrEmpty())
        workDir = Files.createTempDirectory("relay-e2e").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        relays.forEach { it.stop() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    @Test
    fun pairsThroughRealRelay() = runBlocking {
        var relay = startRelay(File(workDir, "relay.db"), port = 0)
        val port = relay.port
        val endpoints = RelayEndpoints(RELAY_ORIGIN, "ws://127.0.0.1:$port/", "http://127.0.0.1:$port")

        val (macPrivate, macPublic) = newStaticKeyPair()
        val code = PairingCode(macPublic, "e2e-${UUID.randomUUID()}", RELAY_ORIGIN)
        val room = roomName(code)
        val store = InMemoryPairingStore()
        val logs = CopyOnWriteArrayList<String>()
        val client = CompanionClient(
            store,
            http,
            scope,
            endpointsFor = { endpoints },
            timeouts = ClientTimeouts(admission = 5.seconds, register = 5.seconds),
            random = ShortJitter,
            log = { logs += it },
        )
        val mac = FakeMac(endpoints, code, macPrivate, http, scope)
        mac.start()

        // Pairing: SAS, confirmation, hello, room secret, /register on the same relay.
        client.pair(code)
        val shown = awaitState(client) { it is ClientState.ShowCode } as ClientState.ShowCode
        assertEquals(shown.code, withTimeout(STEP) { mac.awaitingCode.first { it != null } })
        mac.enterCode(accept = true)
        awaitState(client) { it is ClientState.Connected }
        val record = store.load()!!
        assertEquals(PairingStage.REGISTERED, record.stage)
        assertArrayEquals(record.roomSecret, mac.roomSecret)
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
        assertEquals("the room is locked", AdmissionResult.Rejected("signature required"), probeUnsigned(endpoints, room))

        // The Mac's socket goes: the relay closes the phone with 1001 "peer gone"; both come back.
        mac.dropSocket()
        awaitState(client) { it !is ClientState.Connected }
        awaitState(client) { it is ClientState.Connected }
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))

        // Restart on the same database: the verifier survives, so reconnecting at all proves both
        // sides joined signed (the relay refuses an unsigned join with "signature required").
        relay.stop()
        awaitState(client) { it !is ClientState.Connected }
        relay = startRelay(File(workDir, "relay.db"), port)
        assertEquals("the verifier survived", AdmissionResult.Rejected("signature required"), probeUnsigned(endpoints, room))
        awaitState(client) { it is ClientState.Connected }
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))

        // Restart on an empty database: the relay lost the verifier; the client registers again.
        relay.stop()
        awaitState(client) { it !is ClientState.Connected }
        relay = startRelay(File(workDir, "relay-empty.db"), port)
        awaitState(client) { it is ClientState.Connected }
        // Register runs before Connected, so the empty relay holds the verifier again by now.
        assertEquals("re-locked", AdmissionResult.Rejected("signature required"), probeUnsigned(endpoints, room))
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
        assertEquals(PairingStage.REGISTERED, store.load()!!.stage)
        println("client log:\n" + logs.joinToString("\n") + "\nrelay log:\n" + relays.flatMap { it.output }.joinToString("\n"))
    }

    private suspend fun awaitState(client: CompanionClient, predicate: (ClientState) -> Boolean): ClientState =
        withTimeout(STEP) { client.state.first(predicate) }

    /** An unsigned phone join: "signature required" proves the room holds a verifier. */
    private suspend fun probeUnsigned(endpoints: RelayEndpoints, room: String): AdmissionResult {
        val probe = RelayConnection(http, endpoints, room)
        return try {
            probe.admit(Proof.Fresh)
        } finally {
            probe.close()
        }
    }

    private fun startRelay(db: File, port: Int): RelayProcess = RelayProcess(File(relayDir!!), db, port).also { relays += it }

    /** `node bin/relay.js` in open mode on 127.0.0.1; [port] 0 picks a free one. */
    private class RelayProcess(dir: File, db: File, requestedPort: Int) {
        val output = CopyOnWriteArrayList<String>()
        private val lines = LinkedBlockingQueue<String>()
        private val process: Process = ProcessBuilder("node", "bin/relay.js")
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                environment()["RELAY_HOST"] = "127.0.0.1"
                environment()["RELAY_PORT"] = requestedPort.toString()
                environment()["RELAY_DB"] = db.path
                environment()["ATTEST_REQUIRED"] = "false"
                environment()["RELAY_ORIGIN"] = RELAY_ORIGIN
            }
            .start()
        val port: Int

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine {
                    output += it
                    lines += it
                }
            }.apply { isDaemon = true }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_SECONDS)
            var found: Int? = null
            while (found == null && System.nanoTime() < deadline) {
                val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                found = LISTENING.find(line)?.groupValues?.get(1)?.toInt()
            }
            if (found == null) {
                stop()
                error("relay did not start:\n" + output.joinToString("\n"))
            }
            port = found
        }

        fun stop() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        }
    }

    /** Backoff jitter capped at 250 ms, so reconnects in real time stay quick. */
    private object ShortJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextLong(from: Long, until: Long): Long = from + minOf(until - from - 1, 250)
    }

    private companion object {
        const val RELAY_ORIGIN = "https://relay.test"
        const val STARTUP_SECONDS = 15L
        val STEP = 30.seconds
        val LISTENING = Regex("listening on 127\\.0\\.0\\.1:(\\d+)")
    }
}
