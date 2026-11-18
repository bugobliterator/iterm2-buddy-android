package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/** The selection driver against a [ScriptedStreamLink], in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionDriverTest {
    private class Rig(val test: TestScope) {
        val link = ScriptedStreamLink()
        val active = MutableStateFlow<ActiveStream?>(stream(linkId = 1, streamID = 1))
        val selection = MutableStateFlow<SelectionSpan?>(null)
        val logs = CopyOnWriteArrayList<String>()
        val driver = SelectionDriver(link, active, selection, test.backgroundScope, log = { logs += it }, copyTimeout = 5.seconds)

        /** Every message sent so far, in order. */
        fun sent(): List<PhoneMessage> {
            test.runCurrent()
            return link.sent.map { it.message }
        }

        fun gestures(): List<PhoneMessage.SelectionGesture> = sent().filterIsInstance<PhoneMessage.SelectionGesture>()
    }

    @Test
    fun beginCarriesModeAndLaterPhasesKeepIt() = runTest {
        val rig = Rig(this)
        rig.driver.begin(P1, SelectionMode.WORD)
        rig.driver.move(P2)
        rig.driver.end(P3)
        assertEquals(
            listOf(
                PhoneMessage.SelectionGesture(1, SelectionPhase.BEGIN, SelectionMode.WORD, P1),
                PhoneMessage.SelectionGesture(1, SelectionPhase.MOVE, SelectionMode.WORD, P2),
                PhoneMessage.SelectionGesture(1, SelectionPhase.END, SelectionMode.WORD, P3),
            ),
            rig.gestures(),
        )
        assertTrue("each bound to the gesture's link", rig.link.sent.all { it.onLink == 1L })
        // The default mode is character, for all three phases.
        rig.driver.begin(P1)
        rig.driver.end(P1)
        assertEquals(listOf(SelectionMode.CHARACTER, SelectionMode.CHARACTER), rig.gestures().drop(3).map { it.mode })
    }

    @Test
    fun duplicateMovesAreDropped() = runTest {
        val rig = Rig(this)
        rig.driver.begin(P1)
        rig.driver.move(P1)
        rig.driver.move(P2)
        rig.driver.move(P2)
        rig.driver.move(P3)
        rig.driver.move(P3)
        rig.driver.end(P3)
        assertEquals(
            listOf(SelectionPhase.BEGIN to P1, SelectionPhase.MOVE to P2, SelectionPhase.MOVE to P3, SelectionPhase.END to P3),
            rig.gestures().map { it.phase to it.point },
        )
        // A new gesture starts afresh: its first move to the old point is not a duplicate.
        rig.driver.begin(P2)
        rig.driver.move(P3)
        assertEquals(SelectionPhase.MOVE to P3, rig.gestures().last().let { it.phase to it.point })
    }

    @Test
    fun gesturesAreSentInOrder() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.driver.begin(P1)
        rig.driver.move(P2)
        rig.driver.move(P3)
        rig.driver.end(P3)
        rig.driver.selectAll()
        rig.driver.clear()
        val copy = async { rig.driver.copy("guid-one") }
        runCurrent()
        assertTrue("held behind the first send", rig.link.sent.isEmpty())
        assertTrue("the copy waits its turn", rig.link.requests.isEmpty)
        gate.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(
                PhoneMessage.SelectionGesture(1, SelectionPhase.BEGIN, SelectionMode.CHARACTER, P1),
                PhoneMessage.SelectionGesture(1, SelectionPhase.MOVE, SelectionMode.CHARACTER, P2),
                PhoneMessage.SelectionGesture(1, SelectionPhase.MOVE, SelectionMode.CHARACTER, P3),
                PhoneMessage.SelectionGesture(1, SelectionPhase.END, SelectionMode.CHARACTER, P3),
                PhoneMessage.SelectAllInStream(1),
                PhoneMessage.ClearSelection(1),
            ),
            rig.sent(),
        )
        val request = rig.link.requests.receive()
        assertEquals(PhoneMessage.CopySelection("guid-one"), request.message)
        request.reply.complete(MacMessage.SelectionText(""))
        assertEquals("", copy.await())
    }

    @Test
    fun copyReturnsSelectionText() = runTest {
        val rig = Rig(this)
        val copy = async { rig.driver.copy("guid-one") }
        val request = rig.link.requests.receive()
        assertEquals(PhoneMessage.CopySelection("guid-one"), request.message)
        assertEquals(1L, request.onLink)
        request.reply.complete(MacMessage.SelectionText("SENTINEL-selection"))
        assertEquals("SENTINEL-selection", copy.await())
        assertFalse("the text is never logged", rig.logs.any { "SENTINEL" in it })
    }

    @Test
    fun copyErrorIsNull() = runTest {
        val rig = Rig(this)
        // An error reply.
        val errorReply = async { rig.driver.copy("guid-one") }
        rig.link.requests.receive().reply.complete(MacMessage.Error(JsonObject(emptyMap())))
        assertNull(errorReply.await())
        // A failed request.
        val failed = async { rig.driver.copy("guid-one") }
        rig.link.requests.receive().reply.completeExceptionally(CompanionRequestException("link 1 has ended"))
        assertNull(failed.await())
        // No reply within the bound.
        val timedOut = async { rig.driver.copy("guid-one") }
        rig.link.requests.receive()
        advanceTimeBy(5.seconds.inWholeMilliseconds + 1)
        assertNull(timedOut.await())
        // No active stream: nothing is asked.
        rig.active.value = null
        assertNull(rig.driver.copy("guid-one"))
        runCurrent()
        assertTrue(rig.link.requests.isEmpty)
        // The queue goes on after each of them.
        rig.active.value = stream(linkId = 1, streamID = 1)
        rig.driver.clear()
        assertEquals(PhoneMessage.ClearSelection(1), rig.sent().last())
    }

    @Test
    fun rangeFollowsSelectionRangeInputs() = runTest {
        val link = ScriptedStreamLink()
        val controller = StreamController(
            link, FakeVideoOutput { testScheduler.currentTime }, backgroundScope,
            nowMillis = { testScheduler.currentTime }, log = {}, ackContext = EmptyCoroutineContext,
        )
        val driver = SelectionDriver(link, controller.activeStream, controller.selection, backgroundScope)
        assertSame(controller.selection, driver.range)
        live(controller, link)
        val span = SelectionSpan(SelectionPoint(1010, 2), SelectionPoint(1012, 7))
        controller.accept(StreamInput.Control(1, null, MacMessage.SelectionRange(1, span)))
        runCurrent()
        assertEquals(span, driver.range.value)
        controller.accept(StreamInput.Control(1, null, MacMessage.SelectionRange(1, null)))
        runCurrent()
        assertNull(driver.range.value)
    }

    @Test
    fun staleLinkGestureIsNotSent() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.driver.begin(P1)
        rig.driver.move(P2)
        runCurrent()
        // The link ends while the gesture is queued; a new link carries the same stream ID.
        rig.link.currentLink = 2
        rig.active.value = stream(linkId = 2, streamID = 1)
        rig.driver.end(P3)
        rig.driver.selectAll()
        gate.complete(Unit)
        assertEquals("nothing for link 1 goes out on link 2", listOf<PhoneMessage>(PhoneMessage.SelectAllInStream(1)), rig.sent())
        assertTrue(rig.link.sent.all { it.onLink == 2L })
    }

    @Test
    fun selectAllAndClearWithoutBeginUseActiveStream() = runTest {
        val link = ScriptedStreamLink()
        val controller = StreamController(
            link, FakeVideoOutput { testScheduler.currentTime }, backgroundScope,
            nowMillis = { testScheduler.currentTime }, log = {}, ackContext = EmptyCoroutineContext,
        )
        val driver = SelectionDriver(link, controller.activeStream, controller.selection, backgroundScope)
        // Nothing active: nothing is sent.
        driver.selectAll()
        driver.clear()
        runCurrent()
        assertTrue(link.sent.isEmpty())
        live(controller, link, streamID = 7)
        driver.selectAll()
        driver.clear()
        runCurrent()
        val selection = link.sent.filter { it.message is PhoneMessage.SelectAllInStream || it.message is PhoneMessage.ClearSelection }
        assertEquals(listOf(PhoneMessage.SelectAllInStream(7), PhoneMessage.ClearSelection(7)), selection.map { it.message })
        assertTrue(selection.all { it.onLink == 1L })
    }

    @Test
    fun dragDroppedWhenStreamChanges() = runTest {
        val rig = Rig(this)
        rig.driver.begin(P1)
        rig.driver.move(P2)
        runCurrent()
        rig.active.value = stream(linkId = 1, streamID = 2)
        rig.driver.move(P3)
        rig.driver.end(P3)
        assertEquals(
            listOf(PhoneMessage.SelectionGesture(1, SelectionPhase.BEGIN, SelectionMode.CHARACTER, P1), PhoneMessage.SelectionGesture(1, SelectionPhase.MOVE, SelectionMode.CHARACTER, P2)),
            rig.sent(),
        )
        // A move or end with no begin is never sent either.
        rig.driver.move(P1)
        rig.driver.end(P1)
        assertEquals(2, rig.sent().size)
        // Queued for stream 1, a gesture is discarded at dequeue once stream 2 is active.
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.active.value = stream(linkId = 1, streamID = 1)
        rig.driver.begin(P1)
        rig.driver.move(P2)
        runCurrent()
        rig.active.value = stream(linkId = 1, streamID = 2)
        gate.complete(Unit)
        assertEquals("the begin was already being sent; the move is dropped", 3, rig.sent().size)
    }

    private companion object {
        val P1 = SelectionPoint(100, 3)
        val P2 = SelectionPoint(101, 4)
        val P3 = SelectionPoint(102, 5)

        fun stream(linkId: Long, streamID: Long) = ActiveStream(linkId, streamID, "guid-one", 1, null, 100)

        /** Live on link 1 with [streamID], through the controller's sink. */
        suspend fun TestScope.live(controller: StreamController, link: ScriptedStreamLink, streamID: Long = 1) {
            controller.accept(StreamInput.LinkUp(1, FakeMac.MAC_REVISION))
            controller.open(SessionSummary("guid-one", "SENTINEL-name", "subtitle", isBrowser = false))
            val pending = link.requests.receive()
            val started = MacMessage.StreamStarted(streamID, StreamCodec.HEVC)
            controller.accept(StreamInput.Control(1, pending.requestID, started))
            pending.reply.complete(started)
            controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(Tiles.config(0, 40, streamID = streamID))))
            controller.state.first { it is LiveViewState.Live }
            runCurrent()
        }
    }
}
