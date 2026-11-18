package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.HistoryTile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles are plain objects standing in for bitmaps: the cache only counts their declared bytes,
 * so no real memory is allocated. A handle is never recycled; the tests check the cache drops it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileMemoryBudgetTest {
    private class Handle(val name: String)

    private fun tile(firstAbsLine: Long) = HistoryTile(1, 1, firstAbsLine, 50, 0, 100_000, ByteArray(1))

    private fun <T : Any> DecodedTileCache<T>.used() = retainedBytes + reservedBytes

    @Test
    fun wideTallTilesStayWithinBudget() {
        val each = decodedTileBytes(widthPx = 4096, lineCount = 50, lineHeightPx = 32)
        assertEquals(25L * MIB, each)
        val sizes = (0L until 300L step 50).associateWith { each }
        val evicted = tilesToEvict(sizes, wanted = 100L..149L, budgetBytes = 96 * MIB)
        assertEquals("farthest first", listOf(250L, 0L, 200L), evicted)
        assertEquals(listOf(50L, 100L, 150L), (sizes.keys - evicted.toSet()).sorted())
        assertEquals(emptyList<Long>(), tilesToEvict(sizes.filterKeys { it < 150 }, 0L..49L, 96 * MIB))
    }

    @Test
    fun blockedDecodesAndCachedBitmapsStayWithinBudget() = runTest {
        val cache = DecodedTileCache<Handle>(budgetBytes = 96 * MIB, maxConcurrentDecodes = 2)
        val budget = 96 * MIB
        val retainedA = tile(0)
        val retainedB = tile(50)
        assertTrue(cache.get(retainedA, 25 * MIB) { Handle("a") } != null)
        assertTrue(cache.used() <= budget)
        assertTrue(cache.get(retainedB, 25 * MIB) { Handle("b") } != null)
        assertTrue(cache.used() <= budget)
        val gate = CompletableDeferred<Unit>()
        val blocked = listOf(tile(100), tile(150)).map { t ->
            async { cache.get(t, 25 * MIB) { gate.await(); Handle("${t.firstAbsLine}") } }
        }
        runCurrent()
        assertTrue(cache.used() <= budget)
        assertEquals(50 * MIB, cache.reservedBytes)
        val decodes = AtomicInteger()
        assertNull("two decodes in flight", cache.get(tile(200), 25 * MIB) { decodes.incrementAndGet(); Handle("x") })
        assertEquals(0, decodes.get())
        assertTrue(cache.used() <= budget)
        gate.complete(Unit)
        blocked.forEach { assertTrue(it.await() != null) }
        assertEquals(0L, cache.reservedBytes)
        assertTrue(cache.used() <= budget)
    }

    @Test
    fun displayedHandlesAreCountedAndNeverEvicted() = runTest {
        // 24 MiB handles, so four fit in 96 MiB exactly.
        val each = 24 * MIB
        val cache = DecodedTileCache<Handle>(budgetBytes = 96 * MIB)
        val tiles = (0L until 200L step 50).map { tile(it) }
        val handles = tiles.map { t -> cache.get(t, each) { Handle("${t.firstAbsLine}") }!! }
        cache.displayed(tiles.take(3))
        assertBudget(cache)
        val incoming = tile(200)
        val handle = cache.get(incoming, each) { Handle("incoming") }
        assertTrue(handle != null)
        assertBudget(cache)
        // Only the undisplayed one went; the displayed ones are still the same handles.
        tiles.take(3).forEachIndexed { i, t -> assertSame(handles[i], cache.get(t, each) { Handle("again") }) }
        cache.displayed(tiles.take(3) + incoming)
        assertNull("no room: all four retained are displayed", cache.get(tiles[3], each) { Handle("redecoded") })
        assertNull(cache.get(tile(250), each) { Handle("more") })
        assertBudget(cache)
    }

    @Test
    fun replacedTileDropsOldHandle() = runTest {
        val cache = DecodedTileCache<Handle>(budgetBytes = 96 * MIB)
        val replyA = tile(1000)
        val replyB = tile(1000)
        val handleA = cache.get(replyA, MIB) { Handle("a") }!!
        val handleB = cache.get(replyB, MIB) { Handle("b") }!!
        assertNotSame("a new reply for the same slot decodes anew", handleA, handleB)
        cache.retainOnly(listOf(replyB), 1000L..1049L)
        assertEquals(1, cache.retainedCount)
        assertEquals(MIB, cache.retainedBytes)

        // A decode of an older reply finishes after a newer one became current: dropped at once.
        val replyC = tile(1000)
        val gate = CompletableDeferred<Unit>()
        val late = async { cache.get(replyA, MIB) { gate.await(); Handle("a-late") } }
        runCurrent()
        cache.retainOnly(listOf(replyC), 1000L..1049L)
        gate.complete(Unit)
        assertNull(late.await())
        assertEquals(0, cache.retainedCount)
        assertEquals(0L, cache.reservedBytes)
    }

    @Test
    fun oversizedTileIsNotDecoded() = runTest {
        val cache = DecodedTileCache<Handle>(budgetBytes = 96 * MIB)
        val decodes = AtomicInteger()
        assertNull(cache.get(tile(0), 100 * MIB) { decodes.incrementAndGet(); Handle("big") })
        assertEquals(0, decodes.get())
        assertEquals(0L, cache.retainedBytes + cache.reservedBytes)
    }

    private fun TestScope.assertBudget(cache: DecodedTileCache<Handle>) {
        runCurrent()
        assertTrue("used ${cache.used()}", cache.used() <= 96 * MIB)
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
