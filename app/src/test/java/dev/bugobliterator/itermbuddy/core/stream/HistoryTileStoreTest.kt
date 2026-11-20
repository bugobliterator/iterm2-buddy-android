package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.Tiles.ready
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * The history tile store against a [ScriptedStreamLink], in virtual time: the store alone, or
 * behind a [StreamController] fed through its sink for the cases that involve the live top.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryTileStoreTest {
    private class Rig(val test: TestScope, budget: Long = DEFAULT_ENCODED_TILE_BYTES) {
        val link = ScriptedStreamLink()
        val logs = CopyOnWriteArrayList<String>()
        val store = HistoryTileStore(link, test.backgroundScope, encodedBudgetBytes = budget, log = { logs += it })

        /** Every request sent so far and not yet taken, in order. */
        fun take(): List<ScriptedStreamLink.Pending> {
            test.runCurrent()
            return generateSequence { link.requests.tryReceive().getOrNull() }.toList()
        }

        fun config(first: Long, total: Int, rows: Int = 40, generation: Long = 1) {
            store.onConfig(1, 1, Tiles.config(first, total, rows, generation))
            test.runCurrent()
        }

        fun answer(pending: ScriptedStreamLink.Pending, lineCount: Int? = null, firstAbsLine: Long? = null, png: Int = 16) {
            val request = pending.fetch
            pending.reply.complete(Tiles.reply(request, lineCount ?: request.lineCount, firstAbsLine ?: request.firstAbsLine, png))
            test.runCurrent()
        }

        val tiles: Map<Long, TileState> get() = store.tiles.value
    }

    private class ControllerRig(val test: TestScope) {
        val link = ScriptedStreamLink()
        val video = FakeVideoOutput { test.testScheduler.currentTime }
        val controller = StreamController(
            link, video, test.backgroundScope, nowMillis = { test.testScheduler.currentTime }, log = {},
            ackContext = EmptyCoroutineContext,
        )
        val store get() = controller.historyTiles

        /** Live on link 1, stream 1, with [config]; [beforeConfig] arrive between `streamStarted` and it. */
        suspend fun live(config: StreamConfig, beforeConfig: List<MacMessage> = emptyList()) {
            controller.accept(StreamInput.LinkUp(1, FakeMac.MAC_REVISION))
            controller.open(SESSION)
            val pending = link.requests.receive()
            val started = MacMessage.StreamStarted(1, StreamCodec.HEVC)
            controller.accept(StreamInput.Control(1, pending.requestID, started))
            pending.reply.complete(started)
            beforeConfig.forEach { controller.accept(StreamInput.Control(1, null, it)) }
            controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(config)))
            controller.state.first { it is LiveViewState.Live }
            test.runCurrent()
        }

        suspend fun mediaWithLiveTop(pts: Long, liveTop: Long) {
            controller.accept(Tiles.media(1, Tiles.frame(1, pts, liveTop, keyframe = pts == 0L)))
            test.runCurrent()
        }

        suspend fun extent(first: Long, total: Int) {
            controller.accept(StreamInput.Control(1, null, MacMessage.StreamExtent(1, first, total)))
            test.runCurrent()
        }

        fun tileRequests(): List<ScriptedStreamLink.Pending> {
            test.runCurrent()
            return generateSequence { link.requests.tryReceive().getOrNull() }.filter { it.message is PhoneMessage.FetchHistoryTile }.toList()
        }

        fun answer(pending: ScriptedStreamLink.Pending) {
            pending.reply.complete(Tiles.reply(pending.fetch))
            test.runCurrent()
        }
    }

    @Test
    fun geometryFollowsConfigExtentAndLiveTop() = runTest {
        val rig = Rig(this)
        assertNull(rig.store.geometry.value)
        rig.config(first = 1000, total = 1040)
        val initial = checkNotNull(rig.store.geometry.value)
        assertEquals(HistoryGeometry(1, 1, 1000, 1040, 40, false, 2000, initial.contentEpoch), initial)
        assertEquals(1000L, initial.layout(10f).historyLines)

        rig.store.onLiveTop(2010)
        assertEquals(2010L, rig.store.geometry.value?.liveTop)

        // A trim: same content epoch, so tiles drawn for it stay valid.
        rig.store.onExtent(1100, 1050)
        val trimmed = checkNotNull(rig.store.geometry.value)
        assertEquals(1100L, trimmed.firstAbsLine)
        assertEquals(1050, trimmed.totalLines)
        assertEquals(initial.contentEpoch, trimmed.contentEpoch)

        // A clear: a new content epoch and the snapped live top.
        rig.store.onExtent(1100, 50)
        val cleared = checkNotNull(rig.store.geometry.value)
        assertEquals(1110L, cleared.liveTop)
        assertTrue(cleared.contentEpoch != initial.contentEpoch)

        rig.store.clear()
        assertNull(rig.store.geometry.value)
    }

    @Test
    fun wantedRangeRequestsAlignedTiles() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 1040)
        rig.store.want(1120, 1260)
        val sent = rig.take().map { it.fetch }
        assertEquals(listOf(1100L, 1150L, 1200L, 1250L), sent.map { it.firstAbsLine })
        assertEquals(setOf(50), sent.map { it.lineCount }.toSet())
        assertEquals(setOf(1L), sent.map { it.generationId }.toSet())
        assertEquals(setOf(1L), sent.map { it.streamID }.toSet())
        assertEquals(0, rig.take().size)
        assertEquals(listOf(1100L, 1150L, 1200L, 1250L), rig.tiles.keys.toList())
        assertTrue(rig.tiles.values.all { it == TileState.Loading })
    }

    @Test
    fun partialTailTileIsCappedAndRefetchedOnGrowth() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 160) // live top 1120
        rig.store.want(1100, 1119)
        val first = rig.take().single()
        assertEquals(1100L, first.fetch.firstAbsLine)
        assertEquals(20, first.fetch.lineCount)
        rig.answer(first)
        assertTrue(rig.tiles[1100] is TileState.Ready)
        assertEquals(emptyList<Long>(), rig.take().map { it.fetch.firstAbsLine })
        rig.store.onLiveTop(1135)
        val grown = rig.take().single()
        assertEquals(1100L, grown.fetch.firstAbsLine)
        assertEquals(35, grown.fetch.lineCount)
        assertTrue("the old tile shows while the grown one is fetched", rig.tiles[1100] is TileState.Ready)
    }

    @Test
    fun growthDuringFetchRefetchesOnReply() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 160)
        rig.store.want(1100, 1119)
        val held = rig.take().single()
        rig.store.onLiveTop(1135)
        assertEquals("one is in flight", 0, rig.take().size)
        rig.answer(held)
        val again = rig.take().single()
        assertEquals(1100L, again.fetch.firstAbsLine)
        assertEquals(35, again.fetch.lineCount)
    }

    @Test
    fun zeroLineRepliesDoNotLoop() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 1040)
        rig.store.want(1000, 1149)
        val sent = rig.take()
        assertEquals(3, sent.size)
        sent.forEach { rig.answer(it, lineCount = 0, png = 0) }
        advanceTimeBy(10.seconds)
        assertEquals(0, rig.take().size)
        assertEquals(setOf<TileState>(TileState.Failed), rig.tiles.values.toSet())
    }

    @Test
    fun atMostSixInFlightAndTwentyFourQueued() = runTest {
        val rig = Rig(this)
        rig.config(first = 0, total = 5040) // 5000 history lines: 100 slots
        rig.store.want(0, 1999) // 40 slots
        val window = rig.take()
        assertEquals(6, window.size)
        assertEquals(6, rig.store.inFlightCount)
        assertEquals(24, rig.store.queuedCount)
        assertEquals((0L until 300L step 50).toList(), window.map { it.fetch.firstAbsLine })
        // A completion pulls the oldest queued request into the freed place.
        rig.answer(window.first())
        val next = rig.take().single()
        assertEquals(300L, next.fetch.firstAbsLine)
        assertEquals(6, rig.store.inFlightCount)
        assertTrue(rig.store.queuedCount <= 24)
        // Drain everything: each slot is asked for exactly once.
        val all = ArrayList(window.map { it.fetch.firstAbsLine } + next.fetch.firstAbsLine)
        var open = window.drop(1) + next
        while (open.isNotEmpty()) {
            open.forEach { rig.answer(it) }
            open = rig.take()
            assertTrue(rig.store.inFlightCount <= 6)
            assertTrue(rig.store.queuedCount <= 24)
            all += open.map { it.fetch.firstAbsLine }
        }
        assertEquals((0L until 2000L step 50).toList(), all.sorted())
        assertEquals(40, rig.tiles.ready().size)
    }

    @Test
    fun aCoastsPrefetchIsAskedForNearestTheViewFirst() = runTest {
        val rig = Rig(this)
        rig.config(first = 0, total = 5040) // 100 slots
        // The view around lines 3000..3199; a coast's prefetch reaches up to line 1000.
        rig.store.want(1000, 3199, focusFirst = 3000, focusLast = 3199)
        val window = rig.take()
        // The view's four slots, then the nearest above it.
        assertEquals(listOf(3000L, 3050L, 3100L, 3150L, 2950L, 2900L), window.map { it.fetch.firstAbsLine })
        // Answered one at a time, the queue keeps going outward from the view.
        val order = ArrayList(window.map { it.fetch.firstAbsLine })
        var open = window
        while (open.isNotEmpty()) {
            open.forEach { rig.answer(it) }
            open = rig.take()
            order += open.map { it.fetch.firstAbsLine }
        }
        val farOnes = order.drop(4)
        assertEquals("$order", farOnes.sortedDescending(), farOnes)
        assertEquals((1000L until 3200L step 50).toList(), order.sorted())
    }

    @Test
    fun aNearerSlotDisplacesTheFarthestQueuedOne() = runTest {
        val rig = Rig(this)
        rig.config(first = 0, total = 5040)
        // Everything wanted, the view at the bottom: 6 in flight and 24 queued, all near the bottom.
        rig.store.want(0, 4999, focusFirst = 4800, focusLast = 4999)
        val old = rig.take()
        assertEquals(6, old.size)
        assertEquals(24, rig.store.queuedCount)
        // The view moved to the top (same range): the top slots take the places of the farthest queued ones.
        rig.store.want(0, 4999, focusFirst = 0, focusLast = 199)
        assertEquals(24, rig.store.queuedCount)
        rig.answer(old.first())
        assertEquals("the top's first slot goes next", 0L, rig.take().single().fetch.firstAbsLine)
        rig.answer(old[1])
        assertEquals(50L, rig.take().single().fetch.firstAbsLine)
    }

    @Test
    fun theCoastsFarRequestsAreDroppedWhenItStops() = runTest {
        val rig = Rig(this)
        rig.config(first = 0, total = 5040)
        rig.store.want(1000, 4999, focusFirst = 4800, focusLast = 4999)
        val old = rig.take()
        assertEquals(24, rig.store.queuedCount)
        // The coast stopped: only the view's wanted range is left, and the queue holds nothing outside it.
        rig.store.want(4600, 4999)
        assertTrue(rig.store.queuedCount < 24)
        old.forEach { rig.answer(it) }
        var open = rig.take()
        val asked = ArrayList<Long>()
        while (open.isNotEmpty()) {
            asked += open.map { it.fetch.firstAbsLine }
            open.forEach { rig.answer(it) }
            open = rig.take()
        }
        assertTrue("$asked", asked.all { it in 4600L..4999L })
    }

    @Test
    fun staleGenerationReplyIsDropped() = runTest {
        val rig = Rig(this)
        // The old reply reaches the store even though its request was cancelled by the flush.
        rig.link.repliesOutliveCancellation = true
        rig.config(first = 1000, total = 1040)
        rig.store.want(1000, 1049)
        val old = rig.take().single()
        rig.config(first = 1000, total = 1040, generation = 2)
        val fresh = rig.take().single()
        assertEquals(2L, fresh.fetch.generationId)
        rig.answer(old)
        assertEquals(TileState.Loading, rig.tiles[1000])
        rig.answer(fresh)
        assertEquals(2L, (rig.tiles[1000] as TileState.Ready).tile.generationId)
    }

    @Test
    fun mismatchedReplyFails() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 1040)
        rig.store.want(1000, 1049)
        rig.answer(rig.take().single(), firstAbsLine = 1001)
        assertEquals(TileState.Failed, rig.tiles[1000])
        assertEquals(0, rig.take().size)
    }

    @Test
    fun failedSlotWaitsForRetry() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 1040)
        rig.store.want(1000, 1049)
        val pending = rig.take().single()
        pending.reply.complete(MacMessage.Error(JsonObject(emptyMap())))
        runCurrent()
        assertEquals(TileState.Failed, rig.tiles[1000])
        advanceTimeBy(60.seconds)
        rig.store.want(1000, 1049)
        rig.store.onLiveTop(2000)
        assertEquals(0, rig.take().size)
        rig.store.retry(1000)
        val retried = rig.take().single()
        assertEquals(1000L, retried.fetch.firstAbsLine)
        assertEquals(TileState.Loading, rig.tiles[1000])

        // A scroll or zoom (the wanted range changes) tries failed slots again.
        retried.reply.complete(MacMessage.Error(JsonObject(emptyMap())))
        runCurrent()
        assertEquals(TileState.Failed, rig.tiles[1000])
        rig.store.want(990, 1049)
        val scrolled = rig.take().single()
        assertEquals(1000L, scrolled.fetch.firstAbsLine)

        // So does a selection change, wherever it is.
        scrolled.reply.complete(MacMessage.Error(JsonObject(emptyMap())))
        runCurrent()
        assertEquals(TileState.Failed, rig.tiles[1000])
        rig.store.onSelectionRange(Tiles.span(1990, 1995))
        assertEquals(1000L, rig.take().single().fetch.firstAbsLine)
    }

    @Test
    fun failedSlotIsRetriedOnlyWhenItsCountChanges() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 160) // live top 1120
        rig.store.want(1050, 1119)
        val sent = rig.take()
        assertEquals(listOf(1050L to 50, 1100L to 20), sent.map { it.fetch.firstAbsLine to it.fetch.lineCount })
        sent.forEach { it.reply.complete(MacMessage.Error(JsonObject(emptyMap()))) }
        runCurrent()
        assertEquals(setOf<TileState>(TileState.Failed), rig.tiles.values.toSet())
        rig.store.onLiveTop(1121)
        val grown = rig.take().single()
        assertEquals(1100L to 21, grown.fetch.firstAbsLine to grown.fetch.lineCount)
        assertEquals(TileState.Failed, rig.tiles[1050])
        assertEquals(TileState.Loading, rig.tiles[1100])
    }

    @Test
    fun generationChangeClearsAndKeepsInitialSelection() = runTest {
        val rig = Rig(this)
        // Couriered after streamStarted, before the first config.
        rig.store.onSelectionRange(Tiles.span(1010, 1120))
        rig.config(first = 1000, total = 1040)
        assertEquals(Tiles.span(1010, 1120), rig.store.selection.value)
        rig.store.want(1000, 1199)
        rig.take().forEach { rig.answer(it) }
        assertEquals(listOf(1000L, 1050L, 1100L, 1150L), rig.tiles.ready())
        rig.store.onSelectionRange(null)
        assertEquals(listOf(1000L, 1050L, 1100L), rig.take().map { it.fetch.firstAbsLine })
        assertEquals(listOf(1150L), rig.tiles.ready())
        rig.store.onSelectionRange(Tiles.span(1010, 1020))
        rig.take().forEach { rig.answer(it) }
        rig.config(first = 1000, total = 1040, generation = 2)
        assertEquals(emptyList<Long>(), rig.tiles.ready())
        assertNull(rig.store.selection.value)
    }

    @Test
    fun selectionChangeRefetchesAffectedTiles() = runTest {
        val rig = Rig(this)
        rig.config(first = 1000, total = 1040)
        rig.store.want(1000, 1199)
        rig.take().forEach { rig.answer(it) }
        assertEquals(listOf(1000L, 1050L, 1100L, 1150L), rig.tiles.ready())

        rig.store.onSelectionRange(Tiles.span(1010, 1120))
        val first = rig.take()
        assertEquals(listOf(1000L, 1050L, 1100L), first.map { it.fetch.firstAbsLine })
        first.forEach { rig.answer(it) }

        rig.store.onSelectionRange(Tiles.span(1010, 1170))
        val extended = rig.take()
        assertEquals(listOf(1000L, 1100L, 1150L), extended.map { it.fetch.firstAbsLine })
        extended.forEach { rig.answer(it) }

        rig.store.onSelectionRange(null)
        val cleared = rig.take()
        assertEquals(listOf(1000L, 1050L, 1100L, 1150L), cleared.map { it.fetch.firstAbsLine })
        // An in-flight reply for a slot invalidated again is dropped: its token is superseded.
        rig.store.onSelectionRange(Tiles.span(1060, 1070))
        val superseded = cleared.single { it.fetch.firstAbsLine == 1050L }
        val replacement = rig.take()
        assertEquals("1050 is in flight: the new request waits for nothing but its own token", listOf(1050L), replacement.map { it.fetch.firstAbsLine })
        rig.answer(superseded)
        assertEquals(TileState.Loading, rig.tiles[1050])
        rig.answer(replacement.single())
        assertTrue(rig.tiles[1050] is TileState.Ready)
        cleared.filter { it.fetch.firstAbsLine != 1050L }.forEach { rig.answer(it) }

        // The same range twice: nothing refetched.
        rig.store.onSelectionRange(Tiles.span(1060, 1070))
        assertEquals(0, rig.take().size)
    }

    @Test
    fun encodedCacheStaysWithinBudget() = runTest {
        // Scaled down 1024× from 4 MiB PNGs in a 32 MiB budget, so parallel forks stay small.
        val png = 4 * 1024
        val budget = 32L * 1024
        val rig = Rig(this, budget)
        rig.config(first = 0, total = 5040)
        rig.store.want(0, 999) // 20 slots
        var open = rig.take()
        while (open.isNotEmpty()) {
            open.forEach {
                rig.answer(it, png = png)
                assertTrue(rig.store.encodedBytes <= budget)
            }
            open = rig.take()
        }
        // Every slot is inside the wanted range: the ones farthest from its centre (499.5) went.
        assertEquals((300L until 700L step 50).toList(), rig.tiles.ready())
        // The view moves down: the slots farthest from the new range go first.
        rig.store.want(1000, 1399)
        val moved = ArrayList<Long>()
        open = rig.take()
        while (open.isNotEmpty()) {
            moved += open.map { it.fetch.firstAbsLine }
            open.forEach {
                rig.answer(it, png = png)
                assertTrue(rig.store.encodedBytes <= budget)
            }
            open = rig.take()
        }
        assertEquals((1000L until 1400L step 50).toList(), moved)
        assertEquals((1000L until 1400L step 50).toList(), rig.tiles.ready())
    }

    @Test
    fun oversizedPngFailsTheSlot() = runTest {
        val rig = Rig(this, budget = 32L * 1024)
        rig.config(first = 0, total = 5040)
        rig.store.want(0, 49)
        rig.answer(rig.take().single(), png = 33 * 1024)
        assertEquals(TileState.Failed, rig.tiles[0])
        assertEquals(0L, rig.store.encodedBytes)
        advanceTimeBy(10.seconds)
        assertEquals(0, rig.take().size)
    }

    @Test
    fun extentShrinkClearsAndSnapsLiveTop() = runTest {
        val rig = ControllerRig(this)
        rig.link.repliesOutliveCancellation = true
        rig.live(Tiles.config(firstAbsLine = 0, totalLines = 5040, rows = 40))
        assertEquals(5000L, rig.controller.activeStream.value?.liveTop)
        rig.store.want(4900, 4999)
        val sent = rig.tileRequests()
        assertEquals(2, sent.size)
        rig.answer(sent.first())
        assertEquals(listOf(4900L), rig.store.tiles.value.ready())
        rig.extent(0, 40)
        assertEquals(emptyMap<Long, TileState>(), rig.store.tiles.value)
        rig.answer(sent.last())
        assertEquals("the delayed reply is dropped", emptyMap<Long, TileState>(), rig.store.tiles.value)
        assertEquals(0L, rig.controller.activeStream.value?.liveTop)
        assertEquals(0, rig.tileRequests().size)
    }

    @Test
    fun clearToSameSizeAfterGrowthIsAClear() = runTest {
        val rig = ControllerRig(this)
        rig.link.repliesOutliveCancellation = true
        rig.live(Tiles.config(firstAbsLine = 0, totalLines = 40, rows = 40))
        rig.mediaWithLiveTop(0, 3000)
        assertEquals(3000L, rig.controller.activeStream.value?.liveTop)
        rig.store.want(2900, 2999)
        val sent = rig.tileRequests()
        assertEquals(listOf(2900L, 2950L), sent.map { it.fetch.firstAbsLine })
        rig.answer(sent.first())
        assertEquals(listOf(2900L), rig.store.tiles.value.ready())
        // The Mac's count dropped back to the size it last reported: the same extent again.
        rig.extent(0, 40)
        assertEquals(emptyMap<Long, TileState>(), rig.store.tiles.value)
        rig.answer(sent.last())
        assertEquals(emptyMap<Long, TileState>(), rig.store.tiles.value)
        assertEquals(0L, rig.controller.activeStream.value?.liveTop)
    }

    @Test
    fun extentTrimDropsTilesBelowOrigin() = runTest {
        val rig = ControllerRig(this)
        rig.live(Tiles.config(firstAbsLine = 0, totalLines = 1000, rows = 40))
        rig.mediaWithLiveTop(0, 960)
        rig.store.want(0, 199)
        rig.tileRequests().forEach { rig.answer(it) }
        assertEquals(listOf(0L, 50L, 100L, 150L), rig.store.tiles.value.ready())
        rig.mediaWithLiveTop(33, 961)
        rig.extent(1, 1000)
        val tiles = rig.store.tiles.value
        assertEquals(listOf(50L, 100L, 150L), tiles.ready())
        assertEquals(TileState.Loading, tiles[0])
        assertEquals(961L, rig.controller.activeStream.value?.liveTop)
        // Slot 0 is asked for again from the new origin: the lines the Mac still has.
        val head = rig.tileRequests().single().fetch
        assertEquals(1L, head.firstAbsLine)
        assertEquals(49, head.lineCount)
    }

    @Test
    fun configResendBeforeItsExtentIsNotAClear() = runTest {
        val rig = ControllerRig(this)
        rig.live(Tiles.config(firstAbsLine = 0, totalLines = 1000, rows = 40))
        rig.mediaWithLiveTop(0, 970)
        rig.store.want(0, 199)
        rig.tileRequests().forEach { rig.answer(it) }
        assertEquals(listOf(0L, 50L, 100L, 150L), rig.store.tiles.value.ready())
        // The Mac's control lane overtakes its coalescing lane: the resent config already carries
        // the trimmed origin, then the extent for the same trim arrives with the same values.
        rig.controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(Tiles.config(firstAbsLine = 1, totalLines = 1000, rows = 40))))
        runCurrent()
        assertEquals(listOf(0L, 50L, 100L, 150L), rig.store.tiles.value.ready())
        rig.extent(1, 1000)
        assertEquals("a trim, not a clear", listOf(50L, 100L, 150L), rig.store.tiles.value.ready())
        assertEquals("not snapped to 961", 970L, rig.controller.activeStream.value?.liveTop)
        val head = rig.tileRequests().single().fetch
        assertEquals(1L to 49, head.firstAbsLine to head.lineCount)
    }

    @Test
    fun selectionBetweenStartAndFirstConfigIsKept() = runTest {
        val rig = ControllerRig(this)
        val span = Tiles.span(1010, 1120)
        rig.live(Tiles.config(firstAbsLine = 1000, totalLines = 1040, rows = 40), beforeConfig = listOf(MacMessage.SelectionRange(1, span)))
        assertEquals(span, rig.controller.selection.value)
        rig.store.want(1000, 1199)
        rig.tileRequests().forEach { rig.answer(it) }
        assertEquals(listOf(1000L, 1050L, 1100L, 1150L), rig.store.tiles.value.ready())
        // Clearing it refetches the slots it covered: the first config kept it, with its slots.
        rig.controller.accept(StreamInput.Control(1, null, MacMessage.SelectionRange(1, null)))
        assertEquals(listOf(1000L, 1050L, 1100L), rig.tileRequests().map { it.fetch.firstAbsLine })
        assertNull(rig.controller.selection.value)
    }

    private companion object {
        val SESSION = SessionSummary("guid-tiles", "SENTINEL-tiles", "subtitle", isBrowser = false)

        val ScriptedStreamLink.Pending.fetch: PhoneMessage.FetchHistoryTile get() = message as PhoneMessage.FetchHistoryTile
    }
}
