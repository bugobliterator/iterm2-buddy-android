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
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
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

    /**
     * A 3 MiB keyframe crosses the relay as about 49 Noise chunks of at most 65535 bytes (the relay
     * splices frames up to 256 KiB), then 300 P-frames of 40 KiB at 30 fps: one chunk each. That is
     * about 80 relay frames in the busiest second, well under the relay's 500 frames/s per room.
     */
    @Test
    fun streamsLargeKeyframeThroughRealRelay() = runBlocking {
        val relay = startRelay(File(workDir, "relay.db"), port = 0)
        val endpoints = RelayEndpoints(RELAY_ORIGIN, "ws://127.0.0.1:${relay.port}/", "http://127.0.0.1:${relay.port}")
        val (macPrivate, macPublic) = newStaticKeyPair()
        val code = PairingCode(macPublic, "e2e-${UUID.randomUUID()}", RELAY_ORIGIN)
        val logs = CopyOnWriteArrayList<String>()
        val inputs = CopyOnWriteArrayList<StreamInput>()
        val mediaCount = MutableStateFlow(0)
        val client = CompanionClient(
            InMemoryPairingStore(),
            http,
            scope,
            endpointsFor = { endpoints },
            timeouts = ClientTimeouts(admission = 5.seconds, register = 5.seconds),
            random = ShortJitter,
            log = { logs += it },
            streamSink = { input ->
                inputs += input
                if (input is StreamInput.Media) mediaCount.value++
            },
        )
        val mac = FakeMac(endpoints, code, macPrivate, http, scope)
        mac.start()
        client.pair(code)
        val shown = awaitState(client) { it is ClientState.ShowCode } as ClientState.ShowCode
        assertEquals(shown.code, withTimeout(STEP) { mac.awaitingCode.first { it != null } })
        mac.enterCode(accept = true)
        awaitState(client) { it is ClientState.Connected }

        val frames = ArrayList<ByteArray>()
        frames += mediaFrame(sequence = 0, keyframe = true, payloadBytes = KEYFRAME_BYTES)
        for (sequence in 1..P_FRAMES) frames += mediaFrame(sequence, keyframe = false, payloadBytes = P_FRAME_BYTES)
        for ((index, frame) in frames.withIndex()) {
            mac.sendMedia(frame)
            if (index > 0) delay(FRAME_INTERVAL_MILLIS)
        }
        withTimeout(STEP) { mediaCount.first { it >= frames.size } }

        val media = inputs.filterIsInstance<StreamInput.Media>()
        assertEquals(frames.size, media.size)
        frames.forEachIndexed { index, frame -> assertArrayEquals("frame $index", frame, media[index].copyBytes()) }
        assertEquals("one link, never closed: ${inputs.filter { it !is StreamInput.Media }}", listOf<StreamInput>(StreamInput.LinkUp(1, FakeMac.MAC_REVISION)), inputs.filter { it !is StreamInput.Media })
        assertTrue(client.state.value is ClientState.Connected)
        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
        val relayLog = relay.output.joinToString("\n")
        assertTrue("RELAY_LOG is on, so the next check means something:\n$relayLog", relayLog.contains("hello role=phone"))
        assertFalse(relayLog, relayLog.contains("frame rate exceeded") || relayLog.contains("frame too large"))
    }

    /** A version 2 media frame (*notes* → Media frame): a 34-byte header, then [payloadBytes] of seeded noise. */
    private fun mediaFrame(sequence: Int, keyframe: Boolean, payloadBytes: Int): ByteArray {
        val payload = Random(sequence).nextBytes(payloadBytes)
        return ByteBuffer.allocate(V2_HEADER_BYTES + payloadBytes)
            .put(2.toByte())
            .put(if (keyframe) 1.toByte() else 0.toByte())
            .putInt(1)
            .putInt(sequence)
            .putLong(sequence * FRAME_INTERVAL_MILLIS)
            .putInt(1)
            .putLong(0)
            .putInt(payloadBytes)
            .put(payload)
            .array()
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
                // The relay's per-event diagnostics (room.js dlog), so frame-limit closes would show.
                environment()["RELAY_LOG"] = "true"
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
        const val KEYFRAME_BYTES = 3 * 1024 * 1024
        const val P_FRAME_BYTES = 40 * 1024
        const val P_FRAMES = 300
        const val FRAME_INTERVAL_MILLIS = 33L
        const val V2_HEADER_BYTES = 34
        val LISTENING = Regex("listening on 127\\.0\\.0\\.1:(\\d+)")
    }
}
