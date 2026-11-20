package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The live diagnostics' latency numbers, all in nanoseconds of [LatencyMeter]'s clock; null until
 * measured. Each `…Last` is the newest sample and each `…Median` the median of the recent ones.
 *
 * - [pingLast], [pingMedian]: `ping` to `pong` round trip on the stream's link.
 * - [keyToFrameLast], [keyToFrameMedian]: from a typed key's `sendKey` being handed to the socket
 *   to the arrival of the first media frame of the active stream after it.
 * - [frameIntervalLast], [frameIntervalMedian]: between consecutive media frames' arrivals;
 *   [framesPerSecond] is how many arrived in the second before the latest update (each ping
 *   updates it too, so it falls to 0 once frames stop).
 * - [receiveToSubmitLast], [receiveToSubmitMedian]: from a media frame's arrival from the relay
 *   to its submit to the decoder (the phone's own pipeline before the decoder).
 *
 * Numbers only: nothing here holds or names terminal content.
 */
data class LatencyStats(
    val pingLast: Long? = null,
    val pingMedian: Long? = null,
    val keyToFrameLast: Long? = null,
    val keyToFrameMedian: Long? = null,
    val frameIntervalLast: Long? = null,
    val frameIntervalMedian: Long? = null,
    val framesPerSecond: Int? = null,
    val receiveToSubmitLast: Long? = null,
    val receiveToSubmitMedian: Long? = null,
)

/**
 * Measures the live session's latency for the diagnostics overlay ([LatencyStats]). It records
 * only while [measure] runs (the overlay is shown): otherwise every hook is one volatile read.
 *
 * Hooks, from any thread: [keySent] (the input sender, once a typed key's `sendKey` is handed to
 * the socket), [mediaArrived] (the stream controller, for each media frame of the active stream,
 * with its arrival time from the relay, [StreamInput.Media.receivedAtNanos]) and [mediaSubmitted]
 * (the controller, once the decoder took that frame). [measure] also pings.
 *
 * A key waits at most [keyMatchWindow] for a frame: a key that changes nothing on screen (an
 * arrow at the end of a line) must not be matched with a frame much later. With output already
 * scrolling, the first frame after a key may predate the Mac seeing it, so key-to-frame is a
 * lower bound then; on a quiet screen it is the typing echo.
 *
 * @param nowNanos a monotonic clock in nanoseconds; production `System::nanoTime`, the clock the
 *   relay queue stamps arrivals with.
 * @param window how many recent samples each median covers.
 */
class LatencyMeter(
    private val nowNanos: () -> Long = System::nanoTime,
    private val window: Int = DEFAULT_WINDOW,
    private val keyMatchWindow: Duration = 2.seconds,
) {
    private val lock = Any()
    private val measuring = AtomicInteger()

    private val pings = Samples(window)
    private val keyToFrame = Samples(window)
    private val intervals = Samples(window)
    private val receiveToSubmit = Samples(window)

    /** Send times of keys not yet matched with a frame, oldest first; at most [window]. */
    private val pendingKeys = ArrayDeque<Long>()

    /** Arrival times of the frames in the last second, oldest first. */
    private val recentArrivals = ArrayDeque<Long>()
    private var lastArrival: Long? = null

    private val statsFlow = MutableStateFlow(LatencyStats())

    /** The numbers so far; kept after [measure] ends, cleared when it starts again. */
    val stats: StateFlow<LatencyStats> = statsFlow.asStateFlow()

    /** Whether the hooks record: while at least one [measure] runs. */
    val enabled: Boolean get() = measuring.get() > 0

    /** A typed key's `sendKey` was handed to the socket just now. */
    fun keySent() {
        if (!enabled) return
        val now = nowNanos()
        synchronized(lock) {
            if (pendingKeys.size >= window) pendingKeys.removeFirst()
            pendingKeys.addLast(now)
        }
    }

    /** A media frame of the active stream arrived at [receivedAtNanos] ([NO_RECEIVE_TIME]: now). */
    fun mediaArrived(receivedAtNanos: Long) {
        if (!enabled) return
        val now = nowNanos()
        val at = if (receivedAtNanos == NO_RECEIVE_TIME) now else receivedAtNanos
        synchronized(lock) {
            lastArrival?.let { if (at >= it) intervals.add(at - it) }
            lastArrival = at
            recentArrivals.addLast(at)
            val matchWindow = keyMatchWindow.inWholeNanoseconds
            // Keys sent before this frame arrived; a key sent after its arrival (but handled
            // first) waits for the next frame.
            while (pendingKeys.isNotEmpty() && pendingKeys.first() <= at) {
                val sentAt = pendingKeys.removeFirst()
                if (at - sentAt <= matchWindow) keyToFrame.add(at - sentAt)
            }
            publish(now)
        }
    }

    /** The decoder took the media frame that arrived at [receivedAtNanos] (unknown: nothing recorded). */
    fun mediaSubmitted(receivedAtNanos: Long) {
        if (!enabled || receivedAtNanos == NO_RECEIVE_TIME) return
        val now = nowNanos()
        synchronized(lock) {
            if (now >= receivedAtNanos) receiveToSubmit.add(now - receivedAtNanos)
            publish(now)
        }
    }

    /** One ping's round trip, measured by [measure]. */
    private fun roundTrip(nanos: Long) {
        synchronized(lock) {
            pings.add(nanos)
            publish(nowNanos())
        }
    }

    /**
     * Records while it runs, until cancelled, and pings every [interval]: [ping] sends one and
     * returns whether a `pong` came back (false when there is nothing to ping, such as no live
     * stream; a failure counts as false). Starting clears the numbers.
     */
    suspend fun measure(interval: Duration = DEFAULT_PING_INTERVAL, ping: suspend () -> Boolean) {
        if (measuring.getAndIncrement() == 0) reset()
        try {
            while (true) {
                val start = nowNanos()
                val answered = try {
                    ping()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (answered) roundTrip(nowNanos() - start)
                delay(interval)
            }
        } finally {
            measuring.decrementAndGet()
        }
    }

    private fun reset() {
        synchronized(lock) {
            pings.clear()
            keyToFrame.clear()
            intervals.clear()
            receiveToSubmit.clear()
            pendingKeys.clear()
            recentArrivals.clear()
            lastArrival = null
            statsFlow.value = LatencyStats()
        }
    }

    /** Caller holds [lock]; [now] ages out arrivals older than a second for [LatencyStats.framesPerSecond]. */
    private fun publish(now: Long) {
        while (recentArrivals.isNotEmpty() && now - recentArrivals.first() >= ONE_SECOND_NANOS) recentArrivals.removeFirst()
        statsFlow.value = LatencyStats(
            pingLast = pings.last,
            pingMedian = pings.median(),
            keyToFrameLast = keyToFrame.last,
            keyToFrameMedian = keyToFrame.median(),
            frameIntervalLast = intervals.last,
            frameIntervalMedian = intervals.median(),
            framesPerSecond = if (lastArrival == null) null else recentArrivals.size,
            receiveToSubmitLast = receiveToSubmit.last,
            receiveToSubmitMedian = receiveToSubmit.median(),
        )
    }

    /** The newest [capacity] samples. */
    private class Samples(private val capacity: Int) {
        private val values = ArrayDeque<Long>()

        val last: Long? get() = values.lastOrNull()

        fun add(value: Long) {
            if (values.size >= capacity) values.removeFirst()
            values.addLast(value)
        }

        fun clear() = values.clear()

        /** The median (the lower middle for an even count), or null with no samples. */
        fun median(): Long? = if (values.isEmpty()) null else values.sorted()[(values.size - 1) / 2]
    }

    companion object {
        const val DEFAULT_WINDOW = 32
        val DEFAULT_PING_INTERVAL: Duration = 2.seconds
        private const val ONE_SECOND_NANOS = 1_000_000_000L
    }
}

/**
 * One diagnostics ping on [active]'s link: true when the Mac answered `pong` within [timeout],
 * false with no active stream. Throws what [StreamLink.request] throws.
 */
suspend fun pingActiveLink(link: StreamLink, active: ActiveStream?, timeout: Duration = 5.seconds): Boolean {
    val stream = active ?: return false
    return link.request(PhoneMessage.Ping, onLink = stream.linkId, timeout = timeout) == MacMessage.Pong
}

/** The overlay's latency lines: milliseconds with one decimal, "—" for what is not measured yet. */
fun latencyStatsText(stats: LatencyStats): String {
    fun ms(nanos: Long?): String = nanos?.let { "%.1f".format(java.util.Locale.ROOT, it / 1_000_000.0) } ?: "—"
    fun pair(last: Long?, median: Long?): String = "${ms(last)} (med ${ms(median)}) ms"
    return "ping ${pair(stats.pingLast, stats.pingMedian)}\n" +
        "key→frame ${pair(stats.keyToFrameLast, stats.keyToFrameMedian)}\n" +
        "rx ${stats.framesPerSecond ?: "—"} fps · gap ${pair(stats.frameIntervalLast, stats.frameIntervalMedian)}\n" +
        "rx→submit ${pair(stats.receiveToSubmitLast, stats.receiveToSubmitMedian)}"
}
