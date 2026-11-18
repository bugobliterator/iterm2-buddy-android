package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import java.util.IdentityHashMap

/** Default for [DecodedTileCache]'s budget: 96 MiB of decoded bitmaps, displayed ones included. */
const val DEFAULT_DECODED_TILE_BYTES: Long = 96L * 1024 * 1024

/** How far [line] is from [wanted]: 0 inside it. */
internal fun distanceFrom(wanted: LongRange, line: Long): Long = when {
    line < wanted.first -> wanted.first - line
    line > wanted.last -> line - wanted.last
    else -> 0
}

/**
 * Eviction order for tiles by first line: the farthest from [wanted] first; ties (inside it, say)
 * the farthest from its centre first, then the higher line.
 */
internal fun <T> evictionOrder(wanted: LongRange, line: (T) -> Long): Comparator<T> {
    val doubledCentre = wanted.first + wanted.last
    return compareByDescending<T> { distanceFrom(wanted, line(it)) }
        .thenByDescending { kotlin.math.abs(2 * line(it) - doubledCentre) }
        .thenByDescending { line(it) }
}

/**
 * The tiles to drop so the rest of [sizes] (bytes by a tile's first absolute line) fit in
 * [budgetBytes], in [evictionOrder] (the farthest from [wanted] first, then the farthest from its
 * centre), until the rest fit. Empty when everything already fits.
 */
fun tilesToEvict(sizes: Map<Long, Long>, wanted: LongRange, budgetBytes: Long): List<Long> {
    var total = sizes.values.sum()
    if (total <= budgetBytes) return emptyList()
    val evicted = ArrayList<Long>()
    for (line in sizes.keys.sortedWith(evictionOrder(wanted) { it })) {
        if (total <= budgetBytes) break
        evicted += line
        total -= sizes.getValue(line)
    }
    return evicted
}

/** A decoded tile's size in bytes: [widthPx] × ([lineCount] × [lineHeightPx]) × 4 (ARGB_8888). */
fun decodedTileBytes(widthPx: Int, lineCount: Int, lineHeightPx: Int): Long =
    widthPx.toLong() * lineCount.toLong() * lineHeightPx.toLong() * 4

/**
 * Decoded history tiles (bitmaps on Android) held within [budgetBytes] (Android-only: iOS lets
 * UIKit hold tile images; here the byte budget needs an owner).
 *
 * Handles are keyed by the [HistoryTile] reply object (identity), not the slot, so a refetched
 * slot (growth, selection) is a new key. The budget counts every retained handle, displayed ones
 * included, plus the bytes reserved by decodes in flight.
 *
 * Eviction only drops the cache's reference: it never recycles a bitmap. It never drops a handle
 * the renderer reports as [displayed], nor one [get] returned since the renderer's last [displayed]
 * report (it may be about to draw it), so every bitmap still drawn stays retained and counted; an
 * evicted bitmap is never drawn again and the garbage collector reclaims it.
 *
 * Thread-safe; [get]'s decode runs outside the lock.
 */
class DecodedTileCache<T : Any>(
    private val budgetBytes: Long = DEFAULT_DECODED_TILE_BYTES,
    private val maxConcurrentDecodes: Int = 2,
) {
    private class Entry<T>(val handle: T, val bytes: Long)

    private val lock = Any()
    private val retained = IdentityHashMap<HistoryTile, Entry<T>>()
    private val reserved = IdentityHashMap<HistoryTile, Long>()

    /** Decodes in flight whose tile stopped being current: dropped when they finish. */
    private val abandoned = IdentityHashMap<HistoryTile, Unit>()
    private val shown = IdentityHashMap<HistoryTile, Unit>()

    /** Handles [get] returned since the last [displayed] report: kept like displayed ones. */
    private val pinned = IdentityHashMap<HistoryTile, Unit>()

    /** Tiles no longer current that are kept only while displayed. */
    private val outgoing = IdentityHashMap<HistoryTile, Unit>()
    private var wanted: LongRange = Long.MIN_VALUE..Long.MAX_VALUE

    /** Bytes of every retained handle, displayed ones included. */
    val retainedBytes: Long get() = synchronized(lock) { retained.values.sumOf { it.bytes } }

    /** Bytes reserved by decodes in flight. */
    val reservedBytes: Long get() = synchronized(lock) { reserved.values.sum() }

    /** How many handles are retained. */
    val retainedCount: Int get() = synchronized(lock) { retained.size }

    /**
     * The handle for [tile]: the retained one, or a new one from [decode] once [bytes] are
     * reserved (evicting the undisplayed handles farthest from the wanted range first). Null when
     * [bytes] exceed the whole budget (never decoded), when [maxConcurrentDecodes] decodes are in
     * flight or one for [tile] is, when it still does not fit, or when [tile] stopped being
     * current while it decoded (the handle is dropped at once, never retained or returned).
     */
    suspend fun get(tile: HistoryTile, bytes: Long, decode: suspend () -> T): T? {
        synchronized(lock) {
            retained[tile]?.let {
                pinned[tile] = Unit
                return it.handle
            }
            if (bytes > budgetBytes || reserved.size >= maxConcurrentDecodes || reserved.containsKey(tile)) return null
            if (!makeRoom(bytes)) return null
            reserved[tile] = bytes
        }
        var handle: T? = null
        try {
            handle = decode()
        } finally {
            synchronized(lock) {
                reserved.remove(tile)
                val dropped = abandoned.remove(tile) != null
                if (handle != null && !dropped) {
                    retained[tile] = Entry(handle, bytes)
                    pinned[tile] = Unit
                } else {
                    handle = null
                }
            }
        }
        return handle
    }

    /**
     * Keeps only the handles of [current] tiles inside [wanted] (a tile outside it or no longer
     * current is dropped unless displayed, and then dropped once it is not); a decode in flight
     * for a tile no longer current is dropped when it finishes. [wanted] also orders eviction.
     */
    fun retainOnly(current: Collection<HistoryTile>, wanted: LongRange) {
        synchronized(lock) {
            this.wanted = wanted
            val keep = IdentityHashMap<HistoryTile, Unit>().apply { current.forEach { put(it, Unit) } }
            for (tile in reserved.keys) if (!keep.containsKey(tile)) abandoned[tile] = Unit
            val iterator = retained.keys.iterator()
            while (iterator.hasNext()) {
                val tile = iterator.next()
                val stays = keep.containsKey(tile) && distanceFrom(wanted, tile.firstAbsLine) == 0L
                when {
                    stays -> outgoing.remove(tile)
                    kept(tile) -> outgoing[tile] = Unit
                    else -> iterator.remove()
                }
            }
        }
    }

    /**
     * The renderer's drawn set, reported whenever it changes: these handles are never evicted. It
     * also ends the pins of handles [get] returned before it. A handle that was kept only because
     * it was displayed or pinned is dropped once it is neither.
     */
    fun displayed(tiles: Collection<HistoryTile>) {
        synchronized(lock) {
            shown.clear()
            pinned.clear()
            tiles.forEach { shown[it] = Unit }
            val iterator = outgoing.keys.iterator()
            while (iterator.hasNext()) {
                val tile = iterator.next()
                if (!shown.containsKey(tile)) {
                    retained.remove(tile)
                    iterator.remove()
                }
            }
        }
    }

    /** Drops every handle; decodes in flight are dropped when they finish. */
    fun clear() {
        synchronized(lock) {
            retained.clear()
            outgoing.clear()
            pinned.clear()
            for (tile in reserved.keys) abandoned[tile] = Unit
        }
    }

    private fun kept(tile: HistoryTile): Boolean = shown.containsKey(tile) || pinned.containsKey(tile)

    /** Evicts undisplayed handles, farthest from [wanted] first, until [bytes] more fit. */
    private fun makeRoom(bytes: Long): Boolean {
        var used = retained.values.sumOf { it.bytes } + reserved.values.sum()
        if (used + bytes <= budgetBytes) return true
        val candidates = retained.keys.filter { !kept(it) }.sortedWith(evictionOrder(wanted) { it.firstAbsLine })
        for (tile in candidates) {
            if (used + bytes <= budgetBytes) break
            used -= retained.remove(tile)?.bytes ?: 0
            outgoing.remove(tile)
        }
        return used + bytes <= budgetBytes
    }
}
