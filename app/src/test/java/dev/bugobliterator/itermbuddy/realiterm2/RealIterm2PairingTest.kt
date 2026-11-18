package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingRecord
import dev.bugobliterator.itermbuddy.store.PairingStage
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 1 of the real-Mac CI: the app's own [CompanionClient], as the phone, against a real
 * iTerm2 3.7.7 on a macOS runner (`.github/workflows/real-iterm2.yml`, `ci/real-iterm2/`).
 *
 * iTerm2 was seeded with a pairing the Mac had already confirmed, so the phone starts from a
 * CONFIRMED record: no SAS. From there the client must reach Connected (fresh admission, Noise
 * XK, `hello`, the room secret, `/register`), answer a ping, get a session tree with at least one
 * session, and get rendered content for a terminal session with `fetchSessionContent`.
 *
 * Runs only under `realIterm2Test` with `REAL_ITERM2=1`; skipped otherwise. Reads:
 * - `REAL_ITERM2_PAIRING_FILE`: the KEY=value file `ci/real-iterm2/generate-pairing.sh` wrote;
 * - `REAL_ITERM2_RELAY_URL`: the relay's WebSocket URL, default `ws://127.0.0.1:8787/`. The phone
 *   talks to the relay directly while signing for the https origin iTerm2 uses through the TLS proxy;
 * - `REAL_ITERM2_CONNECT_TIMEOUT_SECONDS` (default 180) and `REAL_ITERM2_REQUEST_TIMEOUT_SECONDS`
 *   (default 30).
 *
 * It prints states, the client's diagnostics, counts and sizes, never terminal content.
 */
class RealIterm2PairingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private val clientLog = CopyOnWriteArrayList<String>()

    @Before
    fun requireRealIterm2() {
        assumeTrue("REAL_ITERM2 is not 1; .github/workflows/real-iterm2.yml runs this", System.getenv("REAL_ITERM2") == "1")
    }

    @After
    fun tearDown() {
        // No unpair: that would wipe the pairing on the Mac. Cancelling drops the connection only.
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun seededPairingReachesTheSessionTree() = runBlocking {
        val pairing = SeededPairing.load(File(requiredEnv("REAL_ITERM2_PAIRING_FILE")))
        val relayUrl = System.getenv("REAL_ITERM2_RELAY_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY_URL
        val connectTimeout = secondsFromEnv("REAL_ITERM2_CONNECT_TIMEOUT_SECONDS", DEFAULT_CONNECT_TIMEOUT)
        val requestTimeout = secondsFromEnv("REAL_ITERM2_REQUEST_TIMEOUT_SECONDS", DEFAULT_REQUEST_TIMEOUT)

        val code = PairingCode(pairing.macStaticPublic, pairing.pairingId, pairing.relayOrigin)
        val endpoints = RelayEndpoints(code.relayOrigin, relayUrl, httpBaseFor(relayUrl))
        report("pairing ID ${code.pairingID}, origin ${code.relayOrigin}, relay ${endpoints.webSocketUrl}")
        val store = InMemoryPairingStore()
        store.save(PairingRecord(code, pairing.phoneStaticPrivate, pairing.roomSecret, PairingStage.PENDING_CONFIRMATION))
        store.save(store.load()!!.withStage(PairingStage.CONFIRMED))

        val client = CompanionClient(
            store,
            http,
            scope,
            endpointsFor = { origin -> endpoints.takeIf { origin == code.relayOrigin } },
            timeouts = ClientTimeouts(rpc = requestTimeout),
            log = { line ->
                clientLog += line
                report("client: $line")
            },
        )
        val states = CopyOnWriteArrayList<ClientState>()
        scope.launch { client.state.collect { states += it; report("state: $it") } }
        client.start()

        val settled = withTimeoutOrNull(connectTimeout) {
            client.state.first { it is ClientState.Connected || it is ClientState.Failed }
        }
        if (settled !is ClientState.Connected) {
            failWith("not Connected within $connectTimeout (ended at $settled); states: $states")
        }
        assertEquals("the room is registered once connected", PairingStage.REGISTERED, store.load()!!.stage)

        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
        report("ping: pong")

        val treeReply = client.request(PhoneMessage.FetchSessionTree)
        val tree = (treeReply as? MacMessage.SessionTreeMessage)?.tree ?: failWith("fetchSessionTree got $treeReply")
        val sessions = sessionsOf(tree)
        report("session tree: ${tree.windows.size} windows, ${sessions.size} sessions")
        assertTrue("the session tree has no session", sessions.isNotEmpty())

        val terminal = sessions.firstOrNull { !it.isBrowser } ?: failWith("every session is a browser: $sessions")
        val contentReply = client.request(PhoneMessage.FetchSessionContent(terminal.guid, firstLine = 0, lineCount = CONTENT_LINES))
        val content = (contentReply as? MacMessage.SessionContentMessage)?.content
            ?: failWith("fetchSessionContent got $contentReply")
        report("session content: ${content.lineCount} lines from line ${content.firstLine}, PNG of ${content.pngSize} bytes")
        assertEquals(terminal.guid, content.guid)
        assertTrue("no lines rendered", content.lineCount > 0)
        val png = content.pngData
        assertTrue("the content is not a PNG (${png.size} bytes)", png.size > PNG_SIGNATURE.size && png.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        assertTrue("still Connected", client.state.value is ClientState.Connected)
    }

    private fun sessionsOf(tree: SessionTree): List<SessionSummary> =
        tree.windows.flatMap { it.tabs }.flatMap { it.panes }.flatMap { pane -> listOf(pane.session) + pane.peers.map { it.session } }

    private fun failWith(message: String): Nothing = throw AssertionError(message)

    private fun report(line: String) = println("real-iterm2: $line")

    /** The throwaway pairing `GenerateCiPairing.java` minted, read as Java properties (KEY=value). */
    private class SeededPairing(
        val pairingId: String,
        val relayOrigin: String,
        val macStaticPublic: ByteArray,
        val phoneStaticPrivate: ByteArray,
        val roomSecret: ByteArray,
    ) {
        companion object {
            fun load(file: File): SeededPairing {
                check(file.isFile) { "no pairing file at $file" }
                val properties = Properties().apply { file.inputStream().use(::load) }
                fun value(key: String): String = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("$file has no $key")
                return SeededPairing(
                    pairingId = value("PAIRING_ID"),
                    relayOrigin = value("RELAY_ORIGIN"),
                    macStaticPublic = hexBytes(value("MAC_STATIC_PUBLIC_KEY_HEX"), "MAC_STATIC_PUBLIC_KEY_HEX"),
                    phoneStaticPrivate = hexBytes(value("PHONE_STATIC_PRIVATE_KEY_HEX"), "PHONE_STATIC_PRIVATE_KEY_HEX"),
                    roomSecret = hexBytes(value("ROOM_SECRET_HEX"), "ROOM_SECRET_HEX"),
                )
            }

            /** 32 bytes from 64 hex digits; the error names the key, never the value. */
            private fun hexBytes(hex: String, key: String): ByteArray {
                require(hex.length == KEY_HEX_DIGITS && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    "$key is not 32 bytes of hex"
                }
                return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
            }
        }
    }

    private companion object {
        const val DEFAULT_RELAY_URL = "ws://127.0.0.1:8787/"
        val DEFAULT_CONNECT_TIMEOUT = 180.seconds
        val DEFAULT_REQUEST_TIMEOUT = 30.seconds

        /** Enough for a prompt; the Mac clamps the range to the lines the session has. */
        const val CONTENT_LINES = 10
        const val KEY_HEX_DIGITS = 64
        const val HEX_RADIX = 16
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        fun requiredEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set; ci/real-iterm2/README.md lists what the test reads")

        fun secondsFromEnv(name: String, default: Duration): Duration =
            System.getenv(name)?.takeIf { it.isNotBlank() }?.let { text ->
                (text.toLongOrNull()?.takeIf { it > 0 } ?: error("$name must be a positive number of seconds, got '$text'")).seconds
            } ?: default

        /** `ws://host:port/` → `http://host:port` (and wss → https): where `/register` goes. */
        fun httpBaseFor(webSocketUrl: String): String = when {
            webSocketUrl.startsWith("ws://") -> "http://" + webSocketUrl.removePrefix("ws://")
            webSocketUrl.startsWith("wss://") -> "https://" + webSocketUrl.removePrefix("wss://")
            else -> error("REAL_ITERM2_RELAY_URL must start with ws:// or wss://, got '$webSocketUrl'")
        }.trimEnd('/')
    }
}
