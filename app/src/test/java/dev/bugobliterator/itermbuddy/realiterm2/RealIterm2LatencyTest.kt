package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.MediaFrameException
import dev.bugobliterator.itermbuddy.core.stream.NO_RECEIVE_TIME
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamParams
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test

/**
 * The latency report of the real-Mac CI: how long the phone waits, measured against a real
 * iTerm2 3.7.7 through the local relay, with a live stream of the first terminal session open in
 * the app's own [StreamController] (acks and all, as the live view runs it; the video output
 * records instead of decoding).
 *
 * Two rounds, each on a fresh connection: the phone's relay socket with `TCP_NODELAY` (as the app
 * has it) and without. Each round reports, on stdout (`latency:` lines):
 * - `ping` → `pong` round trips, [PINGS] of them, with the stream open and the screen quiet;
 * - key → first frame for [TYPED] characters typed into the shell prompt one `sendKey` each, a
 *   key every [KEY_PACE] (each key's time runs from its send returning to the arrival from the
 *   relay of the first non-key frame of the stream after it; iTerm2 re-sends a quiet screen as a
 *   keyframe 300 ms after the last change, which is not an echo);
 * - the frames received while a command prints a line every 10 ms for a few seconds: their
 *   count, rate, bytes and the distribution of the gaps between them, with pings alongside;
 * - the stream's parameters: what the phone asked for, the config the Mac sent, and the frame
 *   rate and bitrate the Mac derives from the frame size (its own formulas, recomputed here: the
 *   wire does not carry them).
 *
 * Arrival times are the relay queue's stamps ([StreamInput.Media.receivedAtNanos]). The relay
 * runs on the runner, on localhost, so the network adds almost nothing here: these numbers are
 * the Mac's and the phone's own share, and Nagle's effect is a lower bound of what a real link
 * shows. It reports and does not judge; it fails only when a round could not connect or stream.
 * It prints numbers only: never terminal content (the typed text is the test's own).
 */
class RealIterm2LatencyTest {
    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @Test
    fun reportsLiveSessionLatency() = runBlocking {
        val summaries = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (noDelay in listOf(true, false)) {
            val label = "TCP_NODELAY ${if (noDelay) "on" else "off"}"
            line("=== round: phone relay socket $label ===")
            try {
                RealIterm2Rig(tcpNoDelay = noDelay).use { rig -> summaries += measureRound(rig, label) }
            } catch (e: AssertionError) {
                failures += "$label: ${e.message}"
                line("round $label failed: ${e.message}")
            } catch (e: Exception) {
                // A request that failed or timed out: its class only (a message may quote what it carried).
                failures += "$label: ${e.javaClass.simpleName}"
                line("round $label failed: ${e.javaClass.simpleName}")
            }
        }
        line("=== summary (relay on localhost: network effects are small here) ===")
        summaries.forEach { block -> block.lines().forEach(::line) }
        if (failures.isNotEmpty()) failWith("latency rounds failed: $failures")
    }

    /** One round on [rig]; returns its summary block. */
    private suspend fun measureRound(rig: RealIterm2Rig, label: String): String {
        val video = FakeVideoOutput(::nowMillis).apply {
            configureResult = { config ->
                if (hasCompleteHevcParameterSets(config.codecExtradata)) VideoReadiness.Ready else VideoReadiness.WaitingForConfig
            }
        }
        val frames = FrameLog()
        val client = rig.client(frames)
        val controller = StreamController(
            link = client,
            video = video,
            scope = rig.scope,
            nowMillis = ::nowMillis,
            log = { RealIterm2Rig.report("stream: $it") },
        )
        frames.target = controller
        rig.connect(client)
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val session = rig.firstTerminalSession(client)

        controller.open(session)
        val active = rig.poll { controller.activeStream.value } ?: failWith("no stream adopted: ${controller.state.value}")
        frames.streamID = active.streamID
        rig.poll { controller.state.value as? LiveViewState.Live } ?: failWith("not Live: ${controller.state.value}")
        val config = rig.poll { frames.configs.lastOrNull { it.streamID == active.streamID } }
            ?: failWith("no streamConfig for stream ${active.streamID}")
        rig.poll { frames.media.firstOrNull { it.keyframe } } ?: failWith("no keyframe for stream ${active.streamID}")
        val keys = KeySender(client, session.guid, linkId)

        // A clean prompt: whatever an earlier step left running is interrupted, the screen cleared.
        keys.key("c", KeyModifiers(control = true))
        delay(200)
        keys.key("c", KeyModifiers(control = true))
        keys.text("clear")
        keys.text("\n")
        frames.awaitQuiet(QUIET_BEFORE_KEYS, SETTLE_CAP)

        val params = streamParamsLines(config)
        params.forEach(::line)

        val pings = pingRoundTrips(client, PINGS, PING_GAP)
        line("ping (screen quiet): ${dist(pings)}")

        val keyResult = keyToFrame(keys, frames)
        line("key→frame (${TYPED.length} keys, one every $KEY_PACE): ${dist(keyResult.samples)}; ${keyResult.missed} keys saw no frame within $KEY_FRAME_WAIT")
        line("key→frame: ${keyResult.framesDuring} frames (${keyResult.keyframesDuring} keyframes) arrived while typing ${TYPED.length} keys")
        // Erase what was typed (Ctrl-U) and let the screen settle.
        keys.key("u", KeyModifiers(control = true))
        frames.awaitQuiet(QUIET_BEFORE_KEYS, SETTLE_CAP)

        val scroll = scrollThroughput(keys, frames, client, rig.scope)
        scroll.lines.forEach(::line)

        controller.close()
        rig.poll { controller.state.value as? LiveViewState.Closed }
        return buildString {
            appendLine("[$label]")
            appendLine("  ping: ${dist(pings)}")
            appendLine("  key→frame: ${dist(keyResult.samples)} (missed ${keyResult.missed})")
            appendLine("  scrolling: ${scroll.summary}")
            appendLine("  ping while scrolling: ${dist(scroll.pings)}")
            append("  stream: ${params.first().removePrefix("stream params: ")}")
        }
    }

    /** [count] sequential pings, [gap] apart; each round trip in milliseconds. */
    private suspend fun pingRoundTrips(client: CompanionClient, count: Int, gap: Duration): List<Double> {
        val samples = mutableListOf<Double>()
        repeat(count) {
            val start = System.nanoTime()
            val reply = client.request(PhoneMessage.Ping)
            val end = System.nanoTime()
            if (reply == MacMessage.Pong) samples += millisBetween(start, end) else line("ping got ${reply.javaClass.simpleName}")
            delay(gap)
        }
        return samples
    }

    private class KeyResult(val samples: List<Double>, val missed: Int, val framesDuring: Int, val keyframesDuring: Int)

    /**
     * Types [TYPED] one key every [KEY_PACE]; each key's time is to the first non-key frame that
     * arrived after its send returned.
     */
    private suspend fun keyToFrame(keys: KeySender, frames: FrameLog): KeyResult {
        val samples = mutableListOf<Double>()
        var missed = 0
        val firstIndex = frames.media.size
        for (char in TYPED) {
            val sentAt = keys.text(char.toString())
            val frame = withTimeoutOrNull(KEY_FRAME_WAIT) {
                var found = frames.firstAfter(sentAt)
                while (found == null) {
                    delay(POLL)
                    found = frames.firstAfter(sentAt)
                }
                found
            }
            if (frame == null) missed++ else samples += millisBetween(sentAt, frame.arrivedAt)
            val elapsed = (System.nanoTime() - sentAt).let { it / NANOS_PER_MILLI }.milliseconds
            if (elapsed < KEY_PACE) delay(KEY_PACE - elapsed)
        }
        val during = frames.media.subList(firstIndex, frames.media.size).toList()
        return KeyResult(samples, missed, during.size, during.count { it.keyframe })
    }

    private class ScrollResult(val lines: List<String>, val summary: String, val pings: List<Double>)

    /** Runs a command that prints a line every 10 ms and measures the frames that arrive meanwhile. */
    private suspend fun scrollThroughput(keys: KeySender, frames: FrameLog, client: CompanionClient, scope: CoroutineScope): ScrollResult {
        val firstIndex = frames.media.size
        keys.text(SCROLL_COMMAND)
        val start = keys.text("\n")
        val pings = CopyOnWriteArrayList<Double>()
        val pinger = scope.launch {
            while (isActive) {
                val t0 = System.nanoTime()
                val reply = try {
                    client.request(PhoneMessage.Ping)
                } catch (_: Exception) {
                    null
                }
                if (reply == MacMessage.Pong) pings += millisBetween(t0, System.nanoTime())
                delay(SCROLL_PING_GAP)
            }
        }
        // Until the output stops (no frame for a second) or the cap.
        delay(1.seconds)
        frames.awaitQuiet(1.seconds, SCROLL_CAP)
        pinger.cancel()
        val all = frames.media.subList(firstIndex, frames.media.size).toList().filter { it.arrivedAt >= start }
        // The trailing keyframe iTerm2 sends 300 ms after the last change is not scrolling.
        val scrolling = all.dropLastWhile { it.keyframe }
        if (scrolling.size < 2) {
            val text = "only ${scrolling.size} frames while the command ran"
            return ScrollResult(listOf("scrolling: $text"), text, pings)
        }
        val seconds = (scrolling.last().arrivedAt - scrolling.first().arrivedAt) / 1e9
        val gaps = scrolling.zipWithNext { a, b -> millisBetween(a.arrivedAt, b.arrivedAt) }
        val bytes = scrolling.sumOf { it.size.toLong() }
        val fps = (scrolling.size - 1) / seconds
        val summary = String.format(
            Locale.ROOT,
            "%d frames (%d keyframes) in %.2f s = %.1f fps, %.0f kbit/s; gaps %s",
            scrolling.size, scrolling.count { it.keyframe }, seconds, fps, bytes * 8 / seconds / 1000, dist(gaps),
        )
        val histogram = GAP_BUCKETS.zipWithNext().joinToString(", ") { (low, high) ->
            "${low.toInt()}-${high.toInt()} ms: ${gaps.count { it >= low && it < high }}"
        } + ", ≥${GAP_BUCKETS.last().toInt()} ms: ${gaps.count { it >= GAP_BUCKETS.last() }}"
        val firstFrame = millisBetween(start, scrolling.first().arrivedAt)
        return ScrollResult(
            listOf(
                "scrolling: $summary",
                "scrolling: gap histogram $histogram",
                String.format(Locale.ROOT, "scrolling: Return to first frame %.1f ms; frame sizes %s", firstFrame, dist(scrolling.map { it.size.toDouble() }, "bytes")),
                "ping while scrolling: ${dist(pings)}",
            ),
            summary,
            pings,
        )
    }

    /** What was asked, what the Mac sent, and what the Mac derives (its formulas, recomputed). */
    private fun streamParamsLines(config: StreamConfig): List<String> {
        val requested = StreamParams()
        val pixels = config.pixelWidth.toLong() * config.pixelHeight
        // CompanionHostBridge.swift:1218: min(requested, 60), 30 when not positive.
        val cap = if (requested.maxFrameRate > 0) min(requested.maxFrameRate, MAC_MAX_FPS) else MAC_DEFAULT_FPS
        // CompanionSessionStreamer.swift:246-252: pixels x fps held near 180 Mpx/s, floor 10 fps.
        val effective = if (pixels > 0) max(min(MAC_MIN_FPS, cap), min(cap, MAC_MAX_PIXELS_PER_SECOND / pixels)) else cap
        // CompanionSessionStreamer.swift:224-265: pixels x 0.02 bits x fps, within 1 and 24 Mbit/s.
        val bitrate = max(MAC_MIN_BITRATE, min(MAC_BITRATE_CEILING, pixels * MAC_BITS_PER_PIXEL * max(1.0, effective)))
        return listOf(
            String.format(
                Locale.ROOT,
                "stream params: asked maxFrameRate %.0f, maxBitrate none, media v%s; got %dx%d px (scale %.1f), %dx%d cells; " +
                    "Mac cap %.0f fps, effective %.1f fps, target %.1f Mbit/s (Mac formulas, not on the wire)",
                requested.maxFrameRate, requested.maxMediaFrameVersion, config.pixelWidth, config.pixelHeight, config.scale,
                config.columns, config.rows, cap, effective, bitrate / 1e6,
            ),
        )
    }

    /** Sends keys to [guid] on [linkId]; each call returns when the send returned, in [System.nanoTime]. */
    private class KeySender(private val client: CompanionClient, private val guid: String, private val linkId: Long) {
        suspend fun text(text: String): Long = key(text, KeyModifiers())

        suspend fun key(text: String, modifiers: KeyModifiers): Long {
            client.send(PhoneMessage.SendKey(guid, KeyEvent(Key.Text(text), modifiers)), onLink = linkId)
            return System.nanoTime()
        }
    }

    /** One media frame of the watched stream: when it arrived from the relay, its size and whether it is a keyframe. */
    private class Arrival(val arrivedAt: Long, val size: Int, val keyframe: Boolean)

    /** Forwards every input to [target] and records the watched stream's configs and media arrivals. Holds no frame bytes. */
    private class FrameLog : StreamSink {
        @Volatile var target: StreamSink? = null

        /** The watched stream; frames of others are not recorded. */
        @Volatile var streamID: Long? = null
        val configs = CopyOnWriteArrayList<StreamConfig>()
        val media = CopyOnWriteArrayList<Arrival>()

        @Volatile var lastArrival: Long = System.nanoTime()

        override suspend fun accept(input: StreamInput) {
            when (input) {
                is StreamInput.Control -> (input.message as? MacMessage.StreamConfigMessage)?.let { configs += it.config }
                is StreamInput.Media -> try {
                    val frame = input.parse()
                    val watched = streamID
                    if (watched == null || frame.streamID == watched) {
                        val at = input.receivedAtNanos.takeIf { it != NO_RECEIVE_TIME } ?: System.nanoTime()
                        media += Arrival(at, input.size, frame.isKeyframe)
                        lastArrival = at
                    }
                } catch (_: MediaFrameException) {
                    // Counted by the controller's own handling; not a timing sample.
                }
                else -> Unit
            }
            checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
        }

        /** The first non-key frame that arrived after [nanos]. */
        fun firstAfter(nanos: Long): Arrival? = media.firstOrNull { it.arrivedAt > nanos && !it.keyframe }

        /** Waits until no frame has arrived for [quiet], at most [cap]. */
        suspend fun awaitQuiet(quiet: Duration, cap: Duration) {
            withTimeoutOrNull(cap) {
                while (System.nanoTime() - lastArrival < quiet.inWholeNanoseconds) delay(POLL)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val PINGS = 20

        /** Twenty letters typed at the prompt (erased with Ctrl-U after). */
        const val TYPED = "latencyprobeabcdefgh"
        const val SCROLL_COMMAND = "for i in \$(seq 1 400); do echo latency-scroll-\$i; sleep 0.01; done"

        val PING_GAP = 100.milliseconds
        val SCROLL_PING_GAP = 250.milliseconds

        /** Under the 300 ms after which iTerm2 re-sends a quiet screen as a keyframe. */
        val KEY_PACE = 250.milliseconds
        val KEY_FRAME_WAIT = 2.seconds
        val QUIET_BEFORE_KEYS = 600.milliseconds
        val SETTLE_CAP = 10.seconds
        val SCROLL_CAP = 20.seconds
        val POLL = 2.milliseconds

        val GAP_BUCKETS = listOf(0.0, 20.0, 30.0, 40.0, 50.0, 75.0, 100.0, 200.0)

        // The Mac's stream constants (iTerm2 3.7.7).
        const val MAC_MAX_FPS = 60.0
        const val MAC_DEFAULT_FPS = 30.0
        const val MAC_MIN_FPS = 10.0
        const val MAC_MAX_PIXELS_PER_SECOND = 180_000_000.0
        const val MAC_BITS_PER_PIXEL = 0.02
        const val MAC_MIN_BITRATE = 1_000_000.0
        const val MAC_BITRATE_CEILING = 24_000_000.0

        fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

        fun millisBetween(from: Long, to: Long): Double = (to - from) / 1e6

        fun line(text: String) = println("latency: $text")

        /** n, min, median, p90 (nearest rank) and max, in [unit] (milliseconds by default). */
        fun dist(values: List<Double>, unit: String = "ms"): String {
            if (values.isEmpty()) return "n=0"
            val sorted = values.sorted()
            fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return String.format(
                Locale.ROOT,
                "n=%d min %.1f med %.1f p90 %.1f max %.1f %s",
                sorted.size, sorted.first(), rank(0.5), rank(0.9), sorted.last(), unit,
            )
        }
    }
}
