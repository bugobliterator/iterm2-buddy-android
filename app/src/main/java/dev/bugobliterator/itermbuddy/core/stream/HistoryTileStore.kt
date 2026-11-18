package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The history tile bounds: [reply] is the per-request deadline (`IOS/App/AppModel.swift:652`). */
data class TileTimeouts(val reply: Duration = 20.seconds)

/** Default for [HistoryTileStore]'s encoded budget: 32 MiB of PNG bytes. */
const val DEFAULT_ENCODED_TILE_BYTES: Long = 32L * 1024 * 1024

/** Lines per history slot (`IOS/Screens/SessionView.swift:1370`). */
const val HISTORY_SLOT_LINES = 50

/** One history slot as the scrollback view shows it. */
sealed interface TileState {
    /** Not fetched yet, or being fetched. */
    data object Loading : TileState

    /** The newest tile for the slot; it may be refetching (grown, say) while this shows. */
    class Ready(val tile: HistoryTile) : TileState {
        /** The same reply object: a republished map with no new tile compares equal. */
        override fun equals(other: Any?): Boolean = other is Ready && other.tile === tile

        override fun hashCode(): Int = System.identityHashCode(tile)

        override fun toString(): String = "Ready($tile)"
    }

    /** The fetch failed; the slot waits for [HistoryTileStore.retry]. */
    data object Failed : TileState
}

/**
 * The live stream's history tiles: which 50-line slots to fetch, the request throttle, the PNG
 * cache and every invalidation, mirroring the iOS client (`IOS/App/AppModel.swift:601-658,
 * 4381-4420, 4857-4997`; `IOS/Screens/SessionView.swift:2466-2620`).
 *
 * **Slots.** Slot keys are `base + 50·i`, where `base` is the generation's `firstAbsLine` (or the
 * new origin after a clear). A slot's lines are the history lines it covers:
 * `max(slot, origin) ..< min(slot + 50, origin + historyLines)`, so a slot below a trimmed origin
 * asks only for the lines the Mac still has (Android-only: iOS re-aligns every slot to the new
 * origin, refetching them all). `historyLines` is `max(liveTop − origin, totalLines − rows)`,
 * 0 on the alternate screen (`SessionView.swift:2046-2089`).
 *
 * **Requests.** A wanted slot is requested when it is not cached at its expected count, nothing
 * is queued or in flight for it, and it has not failed. Each request takes a new per-slot token.
 * At most [maxInFlight] run at once, each in its own coroutine, with up to [maxQueued] waiting
 * behind them; a slot that finds the queue full is not queued (iOS rejects the incoming request,
 * `AppModel.swift:4878-4891`) and is asked for again when a request completes.
 *
 * **Acceptance.** Tile replies reach their waiter directly, not through the controller's ordered
 * inbox, so a reply can land before an earlier config or extent the controller has yet to apply.
 * A reply is taken only when its throttle epoch is current (every new generation, clear and
 * [clear] starts a new one, cancelling what is in flight and dropping the queue) and its token is
 * still the slot's (an invalidated slot's token is superseded). It then fails the slot when its
 * `firstAbsLine` differs from the request (origin drift), it has no lines, or its PNG is larger
 * than the whole encoded budget; a failed slot whose expected count changed while in flight is
 * asked for again instead. Otherwise it is cached with the count it asked for, so a short reply
 * cannot loop, and asked for again only if the expected count has since changed.
 *
 * **Encoded bytes** stay within [encodedBudgetBytes]: the farthest from the wanted range go first
 * ([tilesToEvict]). An evicted slot still wanted is not asked for again until the wanted range
 * changes, so a budget smaller than the wanted set cannot loop.
 *
 * Thread-safe: the controller's consumer calls the stream methods, the view calls [want] and
 * [retry]. Logs slots, counts and sizes, never tile content.
 */
class HistoryTileStore(
    private val link: StreamLink,
    private val scope: CoroutineScope,
    private val timeouts: TileTimeouts = TileTimeouts(),
    private val encodedBudgetBytes: Long = DEFAULT_ENCODED_TILE_BYTES,
    private val log: (String) -> Unit = {},
    private val maxInFlight: Int = 6,
    private val maxQueued: Int = 24,
) {
    private val tilesFlow = MutableStateFlow<Map<Long, TileState>>(emptyMap())

    /** Slots by their first absolute line: the wanted ones, and any others still cached. */
    val tiles: StateFlow<Map<Long, TileState>> = tilesFlow.asStateFlow()

    private val selectionFlow = MutableStateFlow<SelectionSpan?>(null)

    /** The active stream's selection as the Mac last reported it; null when none or no stream. */
    val selection: StateFlow<SelectionSpan?> = selectionFlow.asStateFlow()

    private class Request(
        val slot: Long,
        val start: Long,
        val count: Int,
        val token: Long,
        val epoch: Long,
        val linkId: Long,
        val streamID: Long,
        val generation: Long,
        val scope: CoroutineScope,
    )

    private class Cached(val tile: HistoryTile, val start: Long, val count: Int)

    private sealed interface Outcome {
        class Replied(val message: MacMessage) : Outcome

        class Threw(val error: Exception) : Outcome
    }

    private val lock = Any()

    // ---- Stream geometry (null generation: no config yet) ----
    private var linkId = 0L
    private var streamID = 0L
    private var generation: Long? = null
    private var base = 0L
    private var origin = 0L
    private var totalLines = 0
    private var rows = 0
    private var altScreen = false
    private var liveTop: Long? = null

    // ---- Throttle ----
    private var epoch = 0L
    private var epochScope: CoroutineScope = newEpochScope()
    private val queue = ArrayDeque<Request>()
    private val inFlight = HashSet<Request>()

    // ---- Slots ----
    private val requested = HashMap<Long, Request>()
    private val cached = HashMap<Long, Cached>()
    private val failed = HashSet<Long>()
    private val evicted = HashSet<Long>()
    private val tokens = HashMap<Long, Long>()
    private var nextToken = 0L
    private var wanted: LongRange? = null

    // ---- Selection (SessionView.swift:2584-2618) ----
    private var lastEndpoints: SelectionSpan? = null
    private var selectedSlots: Set<Long> = emptySet()

    /** Requests in flight, for tests. */
    internal val inFlightCount: Int get() = synchronized(lock) { inFlight.size }

    /** Requests queued behind the in-flight window, for tests. */
    internal val queuedCount: Int get() = synchronized(lock) { queue.size }

    /** PNG bytes held, for tests. */
    internal val encodedBytes: Long get() = synchronized(lock) { cached.values.sumOf { it.tile.pngSize.toLong() } }

    /**
     * A `streamConfig` for stream [streamID] on link [linkId] (`AppModel.swift:4381-4401`). A new
     * generation clears the cache, starts a new throttle epoch and snaps the live top; after the
     * stream's first config it drops the selection too (the first keeps one couriered before it).
     * A resend of the same generation is applied as an extent, if it changed one.
     */
    fun onConfig(linkId: Long, streamID: Long, config: StreamConfig) = update {
        if (generation != null && (this.linkId != linkId || this.streamID != streamID)) {
            log("history tiles: stream $streamID on link $linkId replaced an unclosed stream")
            resetStream(keepSelection = true)
        }
        this.linkId = linkId
        this.streamID = streamID
        rows = config.rows
        altScreen = config.altScreen
        if (generation != config.generationId) {
            val initial = generation == null
            flush()
            generation = config.generationId
            base = config.firstAbsLine
            origin = config.firstAbsLine
            totalLines = config.totalLines
            liveTop = snappedLiveTop()
            if (!initial) selectionFlow.value = null
            rebaseSelection()
        } else if (config.firstAbsLine != origin || config.totalLines != totalLines) {
            applyExtent(config.firstAbsLine, config.totalLines, unchangedOriginClears = false)
        }
    }

    /**
     * A `streamExtent` for the active stream (`AppModel.swift:4403-4420`, without iOS's "changed"
     * guard). A clear when [totalLines] dropped or [firstAbsLine] is unchanged (the Mac sends an
     * unchanged origin only when its own count dropped, `MC/CompanionSessionStreamer.swift:336-339`):
     * cache cleared, new throttle epoch, live top snapped. Otherwise a trim: slots below the new
     * origin dropped. Ignored before the first config.
     *
     * @return the snapped live top on a clear, for the controller to apply too; null otherwise.
     */
    fun onExtent(firstAbsLine: Long, totalLines: Int): Long? = update {
        if (generation == null) null else applyExtent(firstAbsLine, totalLines, unchangedOriginClears = true)
    }

    /** The live top from the newest media frame (or the controller's snap). */
    fun onLiveTop(liveTop: Long) {
        if (synchronized(lock) { this.liveTop == liveTop }) return
        update { this.liveTop = liveTop }
    }

    /**
     * A `selectionRange` for the active stream. When its endpoints change, every slot that entered
     * or left the selection, and the slots of the old and new endpoints, are dropped, their tokens
     * superseded and, if wanted, asked for again (the Mac renders the selection into tiles).
     */
    fun onSelectionRange(range: SelectionSpan?) = update {
        selectionFlow.value = range
        if (generation == null) return@update
        val last = lastEndpoints
        if (range?.start == last?.start && range?.end == last?.end) return@update
        val newSlots = slotsOf(range)
        val affected = (newSlots - selectedSlots) + (selectedSlots - newSlots) +
            listOfNotNull(range?.start, range?.end, last?.start, last?.end).mapNotNull { historySlotOf(it.absLine) }
        selectedSlots = newSlots
        lastEndpoints = range
        affected.forEach { invalidate(it) }
        if (affected.isNotEmpty()) log("history tiles: selection invalidated ${affected.size} slots")
    }

    /**
     * The lines the view wants: the visible range plus one viewport each way
     * (`SessionView.swift:2480-2482`). Queued requests for slots no longer wanted are dropped, and
     * a failed slot that leaves the range is retried when it comes back (`SessionView.swift:2569-2573`).
     */
    fun want(firstAbsLine: Long, lastAbsLine: Long) = update {
        val range = minOf(firstAbsLine, lastAbsLine)..maxOf(firstAbsLine, lastAbsLine)
        if (range == wanted) return@update
        wanted = range
        evicted.clear()
        val keep = wantedSlots().toHashSet()
        queue.removeAll { request ->
            (request.slot !in keep).also { if (it) requested.remove(request.slot) }
        }
        failed.retainAll(keep)
    }

    /** Asks for a failed slot again. */
    fun retry(slot: Long) = update {
        failed.remove(slot)
    }

    /** The stream ended or was replaced: everything is dropped and in-flight replies are ignored. */
    fun clear() = update {
        resetStream(keepSelection = false)
    }

    // ---- Internals, under the lock ----

    /** Runs [block] under the lock, then requests what is needed and publishes; launches happen outside the lock. */
    private fun <T> update(block: () -> T): T {
        val starts: List<Request>
        val result = synchronized(lock) {
            val value = block()
            starts = pump()
            publish()
            value
        }
        starts.forEach { launch(it) }
        return result
    }

    private fun resetStream(keepSelection: Boolean) {
        flush()
        generation = null
        liveTop = null
        lastEndpoints = null
        selectedSlots = emptySet()
        if (!keepSelection) selectionFlow.value = null
    }

    private fun applyExtent(first: Long, total: Int, unchangedOriginClears: Boolean): Long? {
        val clears = total < totalLines || (unchangedOriginClears && first == origin)
        if (clears) {
            flush()
            base = first
            origin = first
            totalLines = total
            val snapped = snappedLiveTop()
            liveTop = snapped
            rebaseSelection()
            log("history tiles: cleared to $first+$total")
            return snapped
        }
        origin = first
        totalLines = total
        val below = (cached.keys + requested.keys + failed + evicted).filter { it < first }.toSet()
        below.forEach { invalidate(it) }
        rebaseSelection()
        return null
    }

    /** A new throttle epoch: cancels in-flight requests, drops the queue and the cache. */
    private fun flush() {
        epoch++
        epochScope.cancel()
        epochScope = newEpochScope()
        inFlight.clear()
        queue.clear()
        requested.clear()
        cached.clear()
        failed.clear()
        evicted.clear()
    }

    /** Drops [slot]'s tile and supersedes any request for it. */
    private fun invalidate(slot: Long) {
        cached.remove(slot)
        failed.remove(slot)
        evicted.remove(slot)
        requested.remove(slot)?.let { queue.remove(it) }
        tokens[slot] = ++nextToken
    }

    /** The selection's slots under the current geometry, with nothing to invalidate (the cache is empty). */
    private fun rebaseSelection() {
        val range = selectionFlow.value
        selectedSlots = slotsOf(range)
        lastEndpoints = range
    }

    private fun snappedLiveTop(): Long = origin + maxOf(0, totalLines - rows)

    private fun historyLines(): Long {
        if (generation == null || altScreen) return 0
        val top = liveTop ?: snappedLiveTop()
        return maxOf(0L, top - origin, (totalLines - rows).toLong())
    }

    private fun historyEnd(): Long = origin + historyLines()

    private fun slotOf(line: Long): Long = base + Math.floorDiv(line - base, HISTORY_SLOT_LINES.toLong()) * HISTORY_SLOT_LINES

    private fun historySlotOf(line: Long): Long? = if (line >= origin && line < historyEnd()) slotOf(line) else null

    private fun slotsBetween(lo: Long, hi: Long): List<Long> {
        if (lo > hi) return emptyList()
        return (slotOf(lo)..slotOf(hi) step HISTORY_SLOT_LINES.toLong()).toList()
    }

    private fun slotsOf(range: SelectionSpan?): Set<Long> {
        if (range == null || generation == null) return emptySet()
        val lo = maxOf(origin, minOf(range.start.absLine, range.end.absLine))
        val hi = minOf(historyEnd() - 1, maxOf(range.start.absLine, range.end.absLine))
        return slotsBetween(lo, hi).toSet()
    }

    private fun wantedSlots(): List<Long> {
        val range = wanted ?: return emptyList()
        if (generation == null) return emptyList()
        return slotsBetween(maxOf(range.first, origin), minOf(range.last, historyEnd() - 1))
    }

    private fun startOf(slot: Long): Long = maxOf(slot, origin)

    private fun expected(slot: Long): Int = (minOf(slot + HISTORY_SLOT_LINES, historyEnd()) - startOf(slot)).coerceAtLeast(0).toInt()

    /** Moves queued requests into free in-flight places, then asks for every wanted slot that needs it. */
    private fun pump(): List<Request> {
        val starts = ArrayList<Request>()
        val currentGeneration = generation ?: return starts
        while (inFlight.size < maxInFlight && queue.isNotEmpty()) {
            val next = queue.removeFirst()
            inFlight += next
            starts += next
        }
        for (slot in wantedSlots()) {
            val count = expected(slot)
            if (count <= 0 || slot in requested || slot in failed || slot in evicted) continue
            val have = cached[slot]
            if (have != null && have.count == count && have.start == startOf(slot)) continue
            val full = inFlight.size >= maxInFlight
            if (full && queue.size >= maxQueued) break
            val token = ++nextToken
            tokens[slot] = token
            val request = Request(slot, startOf(slot), count, token, epoch, linkId, streamID, currentGeneration, epochScope)
            requested[slot] = request
            if (full) {
                queue.addLast(request)
            } else {
                inFlight += request
                starts += request
            }
        }
        return starts
    }

    /** Requests of one throttle epoch run here; [flush] cancels them all. */
    private fun newEpochScope(): CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private fun launch(request: Request) {
        request.scope.launch {
            val outcome = try {
                Outcome.Replied(
                    link.request(
                        PhoneMessage.FetchHistoryTile(request.streamID, request.start, request.count, request.generation),
                        onLink = request.linkId,
                        timeout = timeouts.reply,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Threw(e)
            }
            update { finish(request, outcome) }
        }
    }

    private fun finish(request: Request, outcome: Outcome) {
        if (request.epoch != epoch) return
        inFlight.remove(request)
        if (tokens[request.slot] != request.token) return
        requested.remove(request.slot)
        val tile = ((outcome as? Outcome.Replied)?.message as? MacMessage.HistoryTileMessage)?.tile
        val problem = when {
            outcome is Outcome.Threw -> "request failed: ${outcome.error.javaClass.simpleName}"
            tile == null -> "reply ${(outcome as Outcome.Replied).message.javaClass.simpleName}"
            tile.streamID != request.streamID || tile.generationId != request.generation -> "reply for another stream or generation"
            tile.firstAbsLine != request.start -> "origin drift to ${tile.firstAbsLine}"
            tile.lineCount <= 0 -> "no lines"
            tile.pngSize > encodedBudgetBytes -> "PNG of ${tile.pngSize} bytes is over the budget"
            else -> null
        }
        if (problem != null || tile == null) {
            log("history tile ${request.start}+${request.count} failed: $problem")
            cached.remove(request.slot)
            // Grown while in flight: ask again at the new count rather than fail (SessionView.swift:2552-2556).
            if (expected(request.slot) == request.count) failed += request.slot
            return
        }
        failed.remove(request.slot)
        cached[request.slot] = Cached(tile, request.start, request.count)
        evictOverBudget()
    }

    private fun evictOverBudget() {
        val sizes = cached.mapValues { it.value.tile.pngSize.toLong() }
        val victims = tilesToEvict(sizes, wanted ?: LongRange.EMPTY, encodedBudgetBytes)
        if (victims.isEmpty()) return
        val keep = wantedSlots().toHashSet()
        for (slot in victims) {
            cached.remove(slot)
            if (slot in keep) evicted += slot
        }
        log("history tiles: evicted ${victims.size} over the encoded budget")
    }

    private fun publish() {
        val shown = sortedMapOf<Long, TileState>()
        for (slot in wantedSlots()) {
            if (expected(slot) <= 0) continue
            shown[slot] = when {
                slot in failed -> TileState.Failed
                else -> cached[slot]?.let { TileState.Ready(it.tile) } ?: TileState.Loading
            }
        }
        for ((slot, have) in cached) if (slot !in shown) shown[slot] = TileState.Ready(have.tile)
        tilesFlow.value = shown
    }
}
