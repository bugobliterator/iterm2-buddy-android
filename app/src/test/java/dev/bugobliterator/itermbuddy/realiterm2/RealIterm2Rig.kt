package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.ClientTimeouts
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.chat.ChatSink
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.relayHttpClient
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue

/**
 * What every real-iTerm2 test shares: the environment `ci/real-iterm2/` sets up, the seeded
 * pairing, and the app's own [CompanionClient] connected to iTerm2 as the phone.
 *
 * iTerm2 was seeded with a pairing the Mac had already confirmed, so the phone starts from a
 * CONFIRMED record: no SAS. [connect] waits for Connected (fresh admission, Noise XK, `hello`,
 * the room secret, `/register`). Each test connects once and [close]s in `@After`; the next test
 * reconnects with the same pairing, as the app does after a restart.
 *
 * Reads (see `ci/real-iterm2/README.md`):
 * - `REAL_ITERM2=1`, or [requireRealIterm2] skips the test;
 * - `REAL_ITERM2_PAIRING_FILE`: the KEY=value file `ci/real-iterm2/generate-pairing.sh` wrote;
 * - `REAL_ITERM2_RELAY_URL`: the relay's WebSocket URL, default `ws://127.0.0.1:8787/`. The phone
 *   talks to the relay directly while signing for the https origin iTerm2 uses through the TLS proxy;
 * - `REAL_ITERM2_CONNECT_TIMEOUT_SECONDS` (default 180): the bound on reaching Connected;
 * - `REAL_ITERM2_REQUEST_TIMEOUT_SECONDS` (default 30): the bound on each request, and on each
 *   wait for iTerm2 to show what a test did;
 * - `REAL_ITERM2_CHAT_TIMEOUT_SECONDS` (default 120): the bound on each Agent Chat step, which
 *   waits on iTerm2's agent and the fake model as well as on the link.
 *
 * [report] prints states, counts and sizes; nothing here prints terminal content.
 *
 * @param tcpNoDelay whether the relay socket has `TCP_NODELAY`, as the app's does
 *   ([relayHttpClient]); false only to measure the difference (`RealIterm2LatencyTest`).
 */
class RealIterm2Rig(tcpNoDelay: Boolean = true) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = relayHttpClient(tcpNoDelay) { connectTimeout(5, TimeUnit.SECONDS) }

    /** Every line the client logged, in order. */
    val clientLog = CopyOnWriteArrayList<String>()

    /** Every state the client went through, in order. */
    val states = CopyOnWriteArrayList<ClientState>()

    val requestTimeout: Duration by lazy { secondsFromEnv("REAL_ITERM2_REQUEST_TIMEOUT_SECONDS", DEFAULT_REQUEST_TIMEOUT) }

    val chatTimeout: Duration by lazy { secondsFromEnv("REAL_ITERM2_CHAT_TIMEOUT_SECONDS", DEFAULT_CHAT_TIMEOUT) }

    private val linkIdFlow = MutableStateFlow<Long?>(null)

    /** The id of the link that is up, from the client's [StreamInput.LinkUp]; null while none is. */
    val linkId: StateFlow<Long?> = linkIdFlow.asStateFlow()

    private var built: CompanionClient? = null
    private var store: InMemoryPairingStore? = null

    /**
     * Connects a [CompanionClient] with the seeded pairing ([client]) and returns it once
     * Connected; fails the test if it is not Connected within the connect timeout.
     */
    suspend fun connect(streamSink: StreamSink? = null): CompanionClient = connect(client(streamSink))

    /**
     * Builds the client from the seeded pairing without starting it, so a stream controller can
     * be built on it before any link comes up, as the app wires them. [streamSink] gets every
     * stream input, as the app's stream controller does; the rig notes each link's id on the way.
     * [chatSink] gets every chat input, as the app's chat repository does (`wireHome`).
     */
    fun client(streamSink: StreamSink? = null, chatSink: ChatSink? = null): CompanionClient {
        check(built == null) { "a client is already built" }
        val pairing = SeededPairing.load(File(requiredEnv("REAL_ITERM2_PAIRING_FILE")))
        val relayUrl = System.getenv("REAL_ITERM2_RELAY_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY_URL

        val code = PairingCode(pairing.macStaticPublic, pairing.pairingId, pairing.relayOrigin)
        val endpoints = RelayEndpoints(code.relayOrigin, relayUrl, httpBaseFor(relayUrl))
        report("pairing ID ${code.pairingID}, origin ${code.relayOrigin}, relay ${endpoints.webSocketUrl}")
        val store = InMemoryPairingStore()
        store.save(PairingRecord(code, pairing.phoneStaticPrivate, pairing.roomSecret, PairingStage.PENDING_CONFIRMATION))
        store.save(store.load()!!.withStage(PairingStage.CONFIRMED))
        this.store = store

        val sink = StreamSink { input ->
            when (input) {
                is StreamInput.LinkUp -> linkIdFlow.value = input.linkId
                is StreamInput.LinkDown -> linkIdFlow.compareAndSet(input.linkId, null)
                else -> Unit
            }
            streamSink?.accept(input)
        }
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
            streamSink = sink,
            chatSink = chatSink,
        )
        built = client
        return client
    }

    /** Starts [client] (from [client]) and returns it once Connected and its link is up; fails the test otherwise. */
    suspend fun connect(client: CompanionClient): CompanionClient {
        check(client === built) { "connect the client the rig built" }
        val connectTimeout = secondsFromEnv("REAL_ITERM2_CONNECT_TIMEOUT_SECONDS", DEFAULT_CONNECT_TIMEOUT)
        scope.launch { client.state.collect { states += it; report("state: $it") } }
        client.start()

        val settled = withTimeoutOrNull(connectTimeout) {
            client.state.first { it is ClientState.Connected || it is ClientState.Failed }
        }
        if (settled !is ClientState.Connected) {
            failWith("not Connected within $connectTimeout (ended at $settled); states: $states")
        }
        withTimeoutOrNull(requestTimeout) { linkId.first { it != null } }
            ?: failWith("Connected but no link came up within $requestTimeout")
        return client
    }

    /** The stage of the stored pairing: REGISTERED once the client has connected. */
    fun storedStage(): PairingStage? = store?.load()?.stage

    /** The session tree; fails the test on any other reply. */
    suspend fun sessionTree(client: CompanionClient): SessionTree {
        val reply = client.request(PhoneMessage.FetchSessionTree)
        return (reply as? MacMessage.SessionTreeMessage)?.tree ?: failWith("fetchSessionTree got $reply")
    }

    /** The first terminal (not browser) session in the tree; fails the test when there is none. */
    suspend fun firstTerminalSession(client: CompanionClient): SessionSummary {
        val tree = sessionTree(client)
        val sessions = sessionsOf(tree)
        report("session tree: ${tree.windows.size} windows, ${sessions.size} sessions")
        return sessions.firstOrNull { !it.isBrowser } ?: failWith("no terminal session: $sessions")
    }

    /**
     * Polls [check] every [POLL_INTERVAL] until it returns non-null, for at most [timeout]
     * (the request timeout by default); null when it never did.
     */
    suspend fun <T : Any> poll(timeout: Duration = requestTimeout, check: suspend () -> T?): T? =
        withTimeoutOrNull(timeout) {
            var found = check()
            while (found == null) {
                delay(POLL_INTERVAL)
                found = check()
            }
            found
        }

    /**
     * Stops the client and drops the connection. No unpair: that would wipe the pairing on the
     * Mac, and the next test reconnects with it.
     */
    override fun close() {
        built?.let { client ->
            client.cancel()
            runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT) { client.state.first { it !is ClientState.Connected } } }
        }
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

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

    companion object {
        const val DEFAULT_RELAY_URL = "ws://127.0.0.1:8787/"
        val DEFAULT_CONNECT_TIMEOUT = 180.seconds
        val DEFAULT_REQUEST_TIMEOUT = 30.seconds
        val DEFAULT_CHAT_TIMEOUT = 120.seconds
        val POLL_INTERVAL = 250.milliseconds
        private val CLOSE_TIMEOUT = 5.seconds
        private const val KEY_HEX_DIGITS = 64
        private const val HEX_RADIX = 16

        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        /** Skips the test unless `REAL_ITERM2=1`: only `.github/workflows/real-iterm2.yml` sets it. */
        fun requireRealIterm2() {
            assumeTrue("REAL_ITERM2 is not 1; .github/workflows/real-iterm2.yml runs this", System.getenv("REAL_ITERM2") == "1")
        }

        fun sessionsOf(tree: SessionTree): List<SessionSummary> =
            tree.windows.flatMap { it.tabs }.flatMap { it.panes }.flatMap { pane -> listOf(pane.session) + pane.peers.map { it.session } }

        fun failWith(message: String): Nothing = throw AssertionError(message)

        /**
         * Puts the session's terminal back to plain keys: kitty keyboard flags popped (all of them,
         * `CSI < 99 u`) and then zeroed (`CSI = 0 ; 1 u`; zeroing first and popping after could
         * restore a program's pushed flags), modifyOtherKeys and bracketed paste off, a sane tty,
         * and a clear screen. A program the key-bytes probe stopped (Claude Code) can leave kitty
         * flags on, and then typed keys arrive as CSI u sequences the shell mangles (run 27:
         * `echo https://…` became `chouhttps9:58;2u…`), so commands are pasted ([pasteCommand]).
         */
        const val RESTORE_TERMINAL = "printf '\\033[<99u\\033[=0;1u\\033[>4m\\033[?2004l'; stty sane; clear"

        /** Return as a key: plain `\r` under any kitty flags short of "report all keys" (8), which no probe sets. */
        val RETURN_KEY = KeyEvent(Key.Text("\n"))

        /**
         * [command] for the shell: a Return first (whatever was half typed runs or fails, and the
         * line is empty), then the command pasted (`pasteText`: it does not go through iTerm2's key
         * mapper, so a keyboard mode left on cannot mangle it), then Return.
         */
        fun pasteCommand(sender: InputSender, target: InputTarget, command: String) {
            sender.key(target, RETURN_KEY)
            sender.paste(target, command)
            sender.key(target, RETURN_KEY)
        }

        /** [RESTORE_TERMINAL] through [pasteCommand], then a pause for the shell to run it. */
        suspend fun restoreTerminal(sender: InputSender, target: InputTarget) {
            pasteCommand(sender, target, RESTORE_TERMINAL)
            delay(RESTORE_PAUSE)
        }

        val RESTORE_PAUSE = 1.seconds

        fun report(line: String) = println("real-iterm2: $line")

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size > PNG_SIGNATURE.size && bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

        fun requiredEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set; ci/real-iterm2/README.md lists what the test reads")

        private fun secondsFromEnv(name: String, default: Duration): Duration =
            System.getenv(name)?.takeIf { it.isNotBlank() }?.let { text ->
                (text.toLongOrNull()?.takeIf { it > 0 } ?: error("$name must be a positive number of seconds, got '$text'")).seconds
            } ?: default

        /** `ws://host:port/` → `http://host:port` (and wss → https): where `/register` goes. */
        private fun httpBaseFor(webSocketUrl: String): String = when {
            webSocketUrl.startsWith("ws://") -> "http://" + webSocketUrl.removePrefix("ws://")
            webSocketUrl.startsWith("wss://") -> "https://" + webSocketUrl.removePrefix("wss://")
            else -> error("REAL_ITERM2_RELAY_URL must start with ws:// or wss://, got '$webSocketUrl'")
        }.trimEnd('/')
    }
}
