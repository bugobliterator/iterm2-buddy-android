package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.DecodedTileCache
import dev.bugobliterator.itermbuddy.core.stream.HistoryGeometry
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.TileState
import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollbackTilesTest {
    private val geometry = HistoryGeometry(1, 1, 1000, 1040, 40, false, 2000, contentEpoch = 3)

    private fun tile(first: Long, lines: Int = 50, generation: Long = 1) =
        HistoryTile(1, generation, first, lines, 0, 100_000, ByteArray(8))

    /** A holder whose decodes wait on a gate per tile; handles are "picture@line". */
    private class Rig(val test: TestScope, budget: Long = 1_000_000, val decodable: Boolean = true) {
        val cache = DecodedTileCache<String>(budget)
        val gates = IdentityHashMap<HistoryTile, CompletableDeferred<Unit>>()
        val started = mutableListOf<Long>()
        val tiles = ScrollbackTiles(
            cache = cache,
            scope = test.backgroundScope,
            decodeContext = EmptyCoroutineContext,
            measure = { 100L },
            decode = { tile ->
                started += tile.firstAbsLine
                gates.getOrPut(tile) { CompletableDeferred() }.await()
                if (decodable) "picture@${tile.firstAbsLine}" else null
            },
        )

        fun finish(tile: HistoryTile) {
            test.runCurrent()
            gates.getValue(tile).complete(Unit)
            test.runCurrent()
        }

        val drawn: List<String> get() = tiles.drawn.value.map { it.handle }
    }

    @Test
    fun decodesAtMostTwoAtOnceNearestFirst() = runTest {
        val rig = Rig(this)
        val all = (0 until 4).map { tile(1000L + 50 * it) }
        rig.tiles.update(all.associate { it.firstAbsLine to TileState.Ready(it) }, geometry, 1000L..1199L)
        runCurrent()
        // The wanted centre is line 1099.
        assertEquals(listOf(1100L, 1050L), rig.started)
        rig.finish(all[2])
        assertEquals(listOf("picture@1100"), rig.drawn)
        assertEquals(listOf(1100L, 1050L, 1150L), rig.started)
        rig.finish(all[1])
        rig.finish(all[3])
        rig.finish(all[0])
        assertEquals(listOf("picture@1000", "picture@1050", "picture@1100", "picture@1150"), rig.drawn)
    }

    @Test
    fun refetchedSlotKeepsOldPictureUntilNewOneDecodes() = runTest {
        val rig = Rig(this)
        val old = tile(1000, lines = 30)
        rig.tiles.update(mapOf(1000L to TileState.Ready(old)), geometry, 1000L..1100L)
        rig.finish(old)
        assertEquals(listOf("picture@1000"), rig.drawn)
        // Grown or reselected: invalidated (Loading), then the new reply.
        rig.tiles.update(mapOf(1000L to TileState.Loading), geometry, 1000L..1100L)
        assertEquals("the old picture stays while the slot refetches", listOf("picture@1000"), rig.drawn)
        val fresh = tile(1000, lines = 50)
        rig.tiles.update(mapOf(1000L to TileState.Ready(fresh)), geometry, 1000L..1100L)
        runCurrent()
        assertTrue(rig.tiles.drawn.value.single().tile === old)
        rig.finish(fresh)
        assertEquals(50, rig.tiles.drawn.value.single().tile.lineCount)
    }

    @Test
    fun newContentEpochDropsPictures() = runTest {
        val rig = Rig(this)
        val old = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(old)), geometry, 1000L..1100L)
        rig.finish(old)
        rig.tiles.update(mapOf(1000L to TileState.Loading), geometry.copy(contentEpoch = 4), 1000L..1100L)
        assertEquals("a clear never shows the cleared content", emptyList<String>(), rig.drawn)
    }

    @Test
    fun failedSlotDropsItsPicture() = runTest {
        val rig = Rig(this)
        val old = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(old)), geometry, 1000L..1100L)
        rig.finish(old)
        rig.tiles.update(mapOf(1000L to TileState.Failed), geometry, 1000L..1100L)
        assertEquals(emptyList<String>(), rig.drawn)
    }

    @Test
    fun picturesOutsideWantedAreDropped() = runTest {
        val rig = Rig(this)
        val near = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(near)), geometry, 1000L..1100L)
        rig.finish(near)
        rig.tiles.displayed(rig.tiles.drawn.value.map { it.tile })
        assertEquals(1, rig.cache.retainedCount)
        rig.tiles.update(mapOf(1000L to TileState.Ready(near)), geometry, 1800L..1999L)
        assertEquals(emptyList<String>(), rig.drawn)
        // Still displayed until the renderer's next report.
        assertEquals(1, rig.cache.retainedCount)
        rig.tiles.displayed(emptyList())
        assertEquals(0, rig.cache.retainedCount)
    }

    @Test
    fun tileStartingAboveWantedButOverlappingItIsKept() = runTest {
        val rig = Rig(this)
        val overlapping = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(overlapping)), geometry, 1030L..1100L)
        rig.finish(overlapping)
        assertEquals(listOf("picture@1000"), rig.drawn)
    }

    @Test
    fun undecodableTileWaitsForWantedChange() = runTest {
        val rig = Rig(this, decodable = false)
        val bad = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(bad)), geometry, 1000L..1100L)
        rig.finish(bad)
        rig.tiles.update(mapOf(1000L to TileState.Ready(bad)), geometry, 1000L..1100L)
        runCurrent()
        assertEquals(1, rig.started.size)
        rig.tiles.update(mapOf(1000L to TileState.Ready(bad)), geometry, 1000L..1101L)
        runCurrent()
        assertEquals("asked again on a re-drive", 2, rig.started.size)
    }

    @Test
    fun closeReportsNothingDisplayedAndDropsHandles() = runTest {
        val rig = Rig(this)
        val shown = tile(1000)
        rig.tiles.update(mapOf(1000L to TileState.Ready(shown)), geometry, 1000L..1100L)
        rig.finish(shown)
        rig.tiles.displayed(listOf(shown))
        rig.tiles.close()
        assertEquals(emptyList<String>(), rig.drawn)
        assertEquals(0, rig.cache.retainedCount)
    }

    @Test
    fun wantedIsTheVisibleLinesAndOneViewportEachWay() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, totalLines = 2040, rows = 40, lineHeightPx = 10f, altScreen = false)
        // Document y 5000..6000: lines 1500..1600, a 101-line viewport.
        assertEquals(1399L..1701L, wantedLines(layout, DocumentView(5000f, 6000f, atLiveBottom = false)))
        // The live band is wanted too; the store asks only for history.
        assertEquals(2760L..3179L, wantedLines(layout, DocumentView(19_000f, 20_400f, atLiveBottom = true)))
    }
}
