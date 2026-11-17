package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class InputSenderTest {
    /**
     * Runs the sender (in `backgroundScope`, which `advanceUntilIdle` leaves alone) through 10 s of
     * virtual time: past every send bound the tests hit.
     */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    /** A sent (or attempted) message, with the link it was bound to and the virtual time it started. */
    private data class Sent(val message: PhoneMessage, val onLink: Long?, val atMillis: Long)

    /**
     * Records every send. [behaviour] runs inside the send and may suspend or throw; a send
     * that returns normally is recorded in [completed] too.
     */
    private class RecordingLink(private val scope: TestScope) : StreamLink {
        val attempted: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        val completed: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        var behaviour: suspend (PhoneMessage) -> Unit = {}

        override suspend fun send(message: PhoneMessage, onLink: Long?) {
            val sent = Sent(message, onLink, scope.currentTime)
            attempted += sent
            behaviour(message)
            completed += sent
        }

        override suspend fun request(
            message: PhoneMessage,
            onLink: Long?,
            timeout: Duration?,
            onSent: (requestID: Long) -> Unit,
        ): MacMessage = throw UnsupportedOperationException("the input sender never requests")
    }

    private val guid = "ptys_7K2M9Q4R8T1VH"
    private val first = InputTarget(linkId = 1, watchEpoch = 1, sessionGuid = guid)

    private fun text(s: String) = KeyEvent(Key.Text(s))

    private fun sendKey(s: String, target: InputTarget = first) = PhoneMessage.SendKey(target.sessionGuid, text(s))

    private class Harness(val link: RecordingLink, val sender: InputSender, val log: MutableList<String>) {
        @Volatile var current: InputTarget? = null
    }

    private fun TestScope.harness(current: InputTarget? = first): Harness {
        val link = RecordingLink(this)
        val log = Collections.synchronizedList(mutableListOf<String>())
        lateinit var harness: Harness
        val sender = InputSender(link, backgroundScope, isCurrent = { it == harness.current }, log = { log += it })
        harness = Harness(link, sender, log)
        harness.current = current
        return harness
    }

    @Test
    fun keysAreSentInOrderUnderLoad() = runTest {
        val h = harness()
        val threads = (0 until 4).map { t ->
            thread { repeat(250) { i -> h.sender.key(first, text("$t:$i")) } }
        }
        threads.forEach { it.join() }
        settle()
        val texts = h.link.completed.map { ((it.message as PhoneMessage.SendKey).event.key as Key.Text).text }
        assertEquals(1_000, texts.size)
        for (t in 0 until 4) {
            assertEquals("thread $t order", (0 until 250).map { "$t:$it" }, texts.filter { it.startsWith("$t:") })
        }
        assertTrue(h.link.completed.all { it.onLink == first.linkId })

        h.link.completed.clear()
        repeat(1_000) { h.sender.key(first, text("$it")) }
        settle()
        assertEquals((0 until 1_000).map { sendKey("$it") }, h.link.completed.map { it.message })
    }

    @Test
    fun slowSendIsSkippedAfterTwoSeconds() = runTest {
        val h = harness()
        h.link.behaviour = { message -> if (message == sendKey("a")) awaitCancellation() }
        h.sender.key(first, text("a"))
        h.sender.key(first, text("b"))
        settle()
        assertEquals(listOf(sendKey("a"), sendKey("b")), h.link.attempted.map { it.message })
        assertEquals(listOf(sendKey("b")), h.link.completed.map { it.message })
        assertEquals("b waited exactly the 2 s bound", 2_000L, h.link.attempted[1].atMillis)
        assertTrue(h.log.joinToString(), h.log.any { "timed out" in it })
    }

    @Test
    fun pasteTextIsNeverLogged() = runTest {
        val sentinel = "SENTINEL-paste-9f3c"
        val h = harness()
        h.link.behaviour = { message ->
            when ((message as? PhoneMessage.PasteText)?.text) {
                "$sentinel-fails" -> throw CompanionRequestException("could not send $sentinel-fails")
                "$sentinel-hangs" -> awaitCancellation()
                else -> Unit
            }
        }
        h.sender.paste(first, "$sentinel-ok")
        h.sender.pasteThenReturn(first, "$sentinel-fails")
        h.sender.pasteThenReturn(first, "$sentinel-hangs")
        h.sender.key(first, text(sentinel))
        settle()
        h.current = first.copy(watchEpoch = 2)
        h.sender.paste(first, "$sentinel-stale")
        settle()
        assertTrue("something was logged", h.log.size >= 3)
        for (line in h.log) assertFalse(line, "SENTINEL" in line || "paste-9f3c" in line)
    }

    @Test
    fun inputQueuedBeforeReconnectIsDiscarded() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.link.behaviour = { message -> if (message == sendKey("1")) gate.await() }
        for (s in listOf("1", "2", "3", "4")) h.sender.key(first, text(s))
        runCurrent()
        val second = first.copy(linkId = 2)
        h.current = second
        gate.complete(Unit)
        h.sender.key(second, text("5"))
        settle()
        assertEquals(listOf(sendKey("1") to 1L, sendKey("5") to 2L), h.link.attempted.map { it.message to it.onLink })
        assertTrue(h.log.joinToString(), h.log.any { "discarded" in it && "3" in it })
    }

    @Test
    fun inputForPreviousSessionIsDiscarded() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.link.behaviour = { message -> if (message == sendKey("1")) gate.await() }
        h.sender.key(first, text("1"))
        h.sender.key(first, text("2"))
        h.sender.paste(first, "queued paste")
        runCurrent()
        // The same session watched again (a new watch epoch) on the same link.
        h.current = first.copy(watchEpoch = 2)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(sendKey("1")), h.link.attempted.map { it.message })

        // Another session on the same link and epoch is not current either.
        h.sender.key(first.copy(watchEpoch = 2, sessionGuid = "ptys_other"), text("x"))
        settle()
        assertEquals(listOf(sendKey("1")), h.link.attempted.map { it.message })
    }

    @Test
    fun failedPasteCancelsItsReturn() = runTest {
        val h = harness()
        h.link.behaviour = { message -> if (message is PhoneMessage.PasteText) throw CompanionRequestException("not connected") }
        h.sender.pasteThenReturn(first, "make test")
        h.sender.key(first, text("x"))
        settle()
        assertEquals(listOf(PhoneMessage.PasteText(guid, "make test"), sendKey("x")), h.link.attempted.map { it.message })
        assertFalse(h.link.attempted.any { it.message == sendKey("\n") })
    }

    @Test
    fun pasteThatTimesOutCancelsItsReturn() = runTest {
        val h = harness()
        h.link.behaviour = { message -> if (message is PhoneMessage.PasteText) awaitCancellation() }
        h.sender.pasteThenReturn(first, "make test")
        h.sender.key(first, text("x"))
        settle()
        assertEquals(listOf(PhoneMessage.PasteText(guid, "make test"), sendKey("x")), h.link.attempted.map { it.message })
    }

    @Test
    fun returnDroppedWhenTargetChangesDuringPaste() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.link.behaviour = { message -> if (message is PhoneMessage.PasteText) gate.await() }
        h.sender.pasteThenReturn(first, "make test")
        runCurrent()
        h.current = first.copy(watchEpoch = 2)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(PhoneMessage.PasteText(guid, "make test")), h.link.completed.map { it.message })
        assertEquals(1, h.link.attempted.size)
    }

    @Test
    fun pasteThenReturnSendsReturnAfterThePaste() = runTest {
        val h = harness()
        h.sender.pasteThenReturn(first, "make test")
        h.sender.key(first, text("y"))
        settle()
        assertEquals(
            listOf(PhoneMessage.PasteText(guid, "make test") to 1L, sendKey("\n") to 1L, sendKey("y") to 1L),
            h.link.completed.map { it.message to it.onLink },
        )
    }

    /** The whole path, key bar and IME through the sender, against the upstream samples. */
    @Test
    fun sentMessagesMatchGoldenSamples() = runTest {
        val h = harness()
        val ime = ImeTranslator(StickyModifiers(), emit = { h.sender.key(first, it) }, onComposingFlushed = {}, toComposer = {})
        ime.keyBar(KeyEvent(Key.Text("c"), KeyModifiers(control = true)))
        ime.setComposingText("l")
        ime.commitText("ls")
        ime.performEditorAction()
        ime.keyBar(KeyEvent(Key.Special(SpecialKey.TAB)))
        ime.sendKeyEvent(android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.ACTION_DOWN)
        h.sender.paste(first, "make test\ngit status --short\n")
        settle()
        val expected = listOf(
            "send-key-control-c", "send-key-text", "send-key-return", "send-key-special-tab",
            "send-key-special-backspace", "paste-text-multiline",
        ).map { Json.parseToJsonElement(Fixtures.read("messages/live-session/$it.json")).jsonObject.getValue("json") }
        val actual = h.link.completed.map { buildJsonObject { put("payload", it.message.payload()) } }
        assertEquals(expected, actual)
    }

    @Test
    fun isCurrentComparesLinkWatchAndSession() {
        val active = ActiveStream(linkId = 1, streamID = 7, sessionGuid = guid, watchEpoch = 1, config = null, liveTop = null)
        assertTrue(isInputTarget(active, first))
        assertTrue("stream id and live top do not matter", isInputTarget(active.copy(streamID = 8, liveTop = 40), first))
        assertFalse(isInputTarget(null, first))
        assertFalse(isInputTarget(active.copy(linkId = 2), first))
        assertFalse(isInputTarget(active.copy(watchEpoch = 2), first))
        assertFalse(isInputTarget(active.copy(sessionGuid = "ptys_other"), first))
    }
}
