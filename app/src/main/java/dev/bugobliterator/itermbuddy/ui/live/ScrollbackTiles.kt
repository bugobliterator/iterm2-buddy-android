package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.DecodedTileCache
import dev.bugobliterator.itermbuddy.core.stream.HISTORY_SLOT_LINES
import dev.bugobliterator.itermbuddy.core.stream.HistoryGeometry
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.TileState
import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The lines the scrollback view wants: those [view] shows and one viewport more each way
 * (`IOS/Screens/SessionView.swift:2480-2482`). It may run past the history (the live band, or
 * beyond the document); the store asks only for history lines.
 */
fun wantedLines(layout: ScrollbackLayout, view: DocumentView): LongRange {
    val first = layout.absLineAt(minOf(view.topPx, view.bottomPx))
    val last = layout.absLineAt(maxOf(view.topPx, view.bottomPx))
    val viewport = last - first + 1
    return (first - viewport)..(last + viewport)
}

/** A decoded history tile to draw: [handle] (a bitmap) for [tile], in the slot [slot]. Holds no content in its string form. */
class DrawnTile<T : Any>(val slot: Long, val tile: HistoryTile, val handle: T) {
    override fun toString(): String = "DrawnTile(slot=$slot, tile=$tile)"
}

/**
 * The scrollback view's decoded tiles: which [TileState.Ready] tiles to decode, when, and which
 * pictures to draw ([drawn]), over a [DecodedTileCache] that owns the byte budget.
 *
 * - **Decoding.** Ready tiles whose lines overlap the wanted range are decoded nearest its centre
 *   first, at most two at once, on [decodeContext], each only through [DecodedTileCache.get] (the
 *   bitmap is allocated against its reservation). [measure] gives a tile's decoded size in bytes,
 *   null when it is not a decodable image. A tile that does not decode, or does not fit the budget,
 *   is tried again only after the wanted range changes.
 * - **Keeping.** After every [update] the cache keeps only the current Ready tiles overlapping the
 *   wanted range ([DecodedTileCache.retainOnly]). A slot that is refetching (grown, or its
 *   selection changed) keeps showing its previous picture until the new one is decoded, as iOS
 *   keeps a tile's old image while it loads (`SessionView.swift:2518-2521`); a picture is dropped
 *   when its slot failed or left the wanted range, when it lies below the trimmed origin, and when
 *   the [HistoryGeometry.contentEpoch] or generation changes (iOS removes every tile view then,
 *   `SessionView.swift:2063-2082`).
 * - **Displayed.** The renderer reports what it draws with [displayed] every time it draws tiles,
 *   and [close] reports nothing displayed and drops every handle when the view leaves.
 *
 * Call everything on [scope]'s thread (the main thread in the app); decodes hop to
 * [decodeContext] and back. Logs counts and lines, never tile content.
 */
class ScrollbackTiles<T : Any>(
    private val cache: DecodedTileCache<T>,
    private val scope: CoroutineScope,
    private val decodeContext: CoroutineContext,
    private val measure: (HistoryTile) -> Long?,
    private val decode: suspend (HistoryTile) -> T?,
    private val log: (String) -> Unit = {},
    private val maxDecodes: Int = 2,
) {
    private class Picture<T : Any>(val tile: HistoryTile, val handle: T, val epoch: Long)

    private class NotDecodable : Exception("not a decodable image")

    private val drawnFlow = MutableStateFlow<List<DrawnTile<T>>>(emptyList())

    /** The pictures to draw, by slot. */
    val drawn: StateFlow<List<DrawnTile<T>>> = drawnFlow.asStateFlow()

    private var tiles: Map<Long, TileState> = emptyMap()
    private var geometry: HistoryGeometry? = null
    private var wanted: LongRange = LongRange.EMPTY
    private val pictures = HashMap<Long, Picture<T>>()
    private val decoding = IdentityHashMap<HistoryTile, Job>()
    private val skipped = IdentityHashMap<HistoryTile, Unit>()
    private var closed = false

    /** The store's [tiles] and [geometry], and the [wanted] lines ([wantedLines]); called on every change of any. */
    fun update(tiles: Map<Long, TileState>, geometry: HistoryGeometry?, wanted: LongRange) {
        if (closed) return
        if (wanted != this.wanted) skipped.clear()
        this.tiles = tiles
        this.geometry = geometry
        this.wanted = wanted
        pump()
    }

    /** The tiles the renderer drew this time: never evicted while drawn. */
    fun displayed(tiles: Collection<HistoryTile>) = cache.displayed(tiles)

    /** The view left: nothing displayed, every handle and decode dropped. */
    fun close() {
        closed = true
        decoding.values.forEach { it.cancel() }
        decoding.clear()
        pictures.clear()
        drawnFlow.value = emptyList()
        cache.displayed(emptyList())
        cache.clear()
    }

    /** Lines a tile may start at and still overlap [wanted]: tiles hold at most [HISTORY_SLOT_LINES]. */
    private fun keepRange(): LongRange =
        if (wanted.isEmpty()) LongRange.EMPTY else (wanted.first - (HISTORY_SLOT_LINES - 1))..wanted.last

    private fun pump() {
        val geometry = geometry
        val keep = keepRange()
        val ready = if (geometry == null) {
            emptyList()
        } else {
            tiles.values.mapNotNull { (it as? TileState.Ready)?.tile }.filter { it.firstAbsLine in keep && sameContent(it, geometry) }
        }
        cache.retainOnly(ready, keep)
        val iterator = pictures.entries.iterator()
        while (iterator.hasNext()) {
            val (slot, picture) = iterator.next()
            val state = tiles[slot]
            val stays = geometry != null && picture.epoch == geometry.contentEpoch && sameContent(picture.tile, geometry) &&
                picture.tile.firstAbsLine in keep && state != null && state != TileState.Failed
            if (!stays) iterator.remove()
        }
        val centre = if (wanted.isEmpty()) 0L else wanted.first + (wanted.last - wanted.first) / 2
        val candidates = tiles.entries
            .mapNotNull { (slot, state) -> (state as? TileState.Ready)?.tile?.let { slot to it } }
            .filter { (slot, tile) -> tile in ready && pictures[slot]?.tile !== tile && !decoding.containsKey(tile) && !skipped.containsKey(tile) }
            .sortedBy { (_, tile) -> kotlin.math.abs(tile.firstAbsLine - centre) }
        for ((slot, tile) in candidates) {
            if (decoding.size >= maxDecodes) break
            start(slot, tile)
        }
        publish()
    }

    private operator fun List<HistoryTile>.contains(tile: HistoryTile): Boolean = any { it === tile }

    private fun sameContent(tile: HistoryTile, geometry: HistoryGeometry): Boolean =
        tile.streamID == geometry.streamID && tile.generationId == geometry.generationId && tile.firstAbsLine >= geometry.firstAbsLine

    private fun start(slot: Long, tile: HistoryTile) {
        decoding[tile] = scope.launch {
            val handle = try {
                withContext(decodeContext) {
                    val bytes = measure(tile) ?: throw NotDecodable()
                    cache.get(tile, bytes) { decode(tile) ?: throw NotDecodable() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("history tile ${tile.firstAbsLine}+${tile.lineCount} not drawn: ${e.javaClass.simpleName}")
                null
            }
            decoding.remove(tile)
            if (closed) return@launch
            val geometry = geometry
            val current = (tiles[slot] as? TileState.Ready)?.tile === tile
            if (handle == null) {
                skipped[tile] = Unit
            } else if (current && geometry != null && sameContent(tile, geometry)) {
                pictures[slot] = Picture(tile, handle, geometry.contentEpoch)
            }
            pump()
        }
    }

    private fun publish() {
        drawnFlow.value = pictures.entries.sortedBy { it.key }.map { (slot, picture) -> DrawnTile(slot, picture.tile, picture.handle) }
    }
}
