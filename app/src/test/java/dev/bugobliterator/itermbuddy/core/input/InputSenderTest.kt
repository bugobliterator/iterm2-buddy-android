package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LatencyMeter
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private fun TestScope.harness(
        current: InputTarget? = first,
        typeChunkBytes: Int = InputSender.TYPE_CHUNK_BYTES,
        pasteKeys: () -> Boolean = { false },
        latency: LatencyMeter? = null,
    ): Harness {
        val link = RecordingLink(this)
        val log = Collections.synchronizedList(mutableListOf<String>())
        lateinit var harness: Harness
        val sender = InputSender(
            link, backgroundScope, isCurrent = { it == harness.current }, log = { log += it }, typeChunkBytes = typeChunkBytes,
            pasteKeys = pasteKeys, latency = latency,
        )
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
    fun eachSentKeyIsTimedFromWhenItsSendReturned() = runTest {
        val meter = LatencyMeter(nowNanos = { currentTime * 1_000_000 })
        backgroundScope.launch { meter.measure { false } }
        val h = harness(latency = meter)
        // Each send takes 5 ms (handed to the socket at its end).
        h.link.behaviour = { delay(5) }
        h.sender.key(first, text("a"))
        h.sender.paste(first, "not a key")
        runCurrent()
        advanceTimeBy(30)
        // The key was sent at 5 ms; the paste is not a key and is not timed.
        meter.mediaArrived(currentTime * 1_000_000)
        assertEquals(25_000_000L, meter.stats.value.keyToFrameLast)
        assertEquals(1, h.link.completed.count { it.message is PhoneMessage.SendKey })
        // A key whose send failed is not timed.
        h.link.behaviour = { throw CompanionRequestException("gone") }
        h.sender.key(first, text("b"))
        settle()
        meter.mediaArrived(currentTime * 1_000_000)
        assertEquals(25_000_000L, meter.stats.value.keyToFrameLast)
    }

    @Test
    fun scrollWheelIsSentInOrderWithKeys() = runTest {
        val h = harness()
        h.sender.key(first, text("a"))
        h.sender.scrollWheel(first, streamID = 7, up = true, lines = 2)
        h.sender.key(first, text("b"))
        h.sender.scrollWheel(first, streamID = 7, up = false, lines = 1)
        settle()
        assertEquals(
            listOf(sendKey("a"), PhoneMessage.ReportScrollWheel(7, true, 2), sendKey("b"), PhoneMessage.ReportScrollWheel(7, false, 1)),
            h.link.completed.map { it.message },
        )
        assertTrue(h.link.completed.all { it.onLink == first.linkId })
    }

    @Test
    fun staleScrollWheelIsDiscarded() = runTest {
        val h = harness()
        h.current = first.copy(watchEpoch = 2)
        h.sender.scrollWheel(first, streamID = 7, up = true, lines = 1)
        settle()
        assertEquals(emptyList<Sent>(), h.link.attempted)
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
            val text = (message as? PhoneMessage.PasteText)?.text
                ?: ((message as? PhoneMessage.SendKey)?.event?.key as? Key.Text)?.text
            when (text) {
                "$sentinel-fails" -> throw CompanionRequestException("could not send $sentinel-fails")
                "$sentinel-hangs" -> awaitCancellation()
                else -> Unit
            }
        }
        h.sender.paste(first, "$sentinel-ok")
        h.sender.paste(first, "$sentinel-fails")
        h.sender.typeThenReturn(first, "$sentinel-fails")
        h.sender.typeThenReturn(first, "$sentinel-hangs")
        h.sender.key(first, text(sentinel))
        settle()
        h.current = first.copy(watchEpoch = 2)
        h.sender.paste(first, "$sentinel-stale")
        settle()
        assertTrue("something was logged", h.log.size >= 3)
        for (line in h.log) assertFalse(line, "SENTINEL" in line || "paste-9f3c" in line)
    }

    /** Discard and timeout logs give the kind and counts only: no special key's name, no length. */
    @Test
    fun discardAndTimeoutLogsGiveKindAndCountOnly() = runTest {
        val h = harness()
        h.link.behaviour = { awaitCancellation() }
        val special = KeyEvent(Key.Special(SpecialKey.PAGE_DOWN))
        h.sender.key(first, special)
        settle()
        h.current = first.copy(watchEpoch = 2)
        h.sender.key(first, special)
        h.sender.paste(first, "abc")
        settle()
        val timedOut = h.log.single { "timed out" in it }
        val discarded = h.log.filter { "discarded" in it }
        assertTrue(timedOut, timedOut.startsWith("key for "))
        assertEquals(2, discarded.size)
        assertTrue(discarded[0], discarded[0].startsWith("discarded stale key for ") && discarded[0].endsWith("; 1 discarded in all"))
        assertTrue(discarded[1], discarded[1].startsWith("discarded stale paste for ") && discarded[1].endsWith("; 2 discarded in all"))
        for (line in h.log) {
            assertFalse(line, line.contains("pageDown", ignoreCase = true) || line.contains("PAGE_DOWN") || "len=" in line)
        }
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
    fun failedTypeCancelsItsReturn() = runTest {
        val h = harness()
        h.link.behaviour = { message -> if (message == sendKey("make test")) throw CompanionRequestException("not connected") }
        h.sender.typeThenReturn(first, "make test")
        h.sender.key(first, text("x"))
        settle()
        assertEquals(listOf(sendKey("make test"), sendKey("x")), h.link.attempted.map { it.message })
        assertFalse(h.link.attempted.any { it.message == sendKey("\n") })
    }

    @Test
    fun typeThatTimesOutCancelsItsReturn() = runTest {
        val h = harness()
        h.link.behaviour = { message -> if (message == sendKey("make test")) awaitCancellation() }
        h.sender.typeThenReturn(first, "make test")
        h.sender.key(first, text("x"))
        settle()
        assertEquals(listOf(sendKey("make test"), sendKey("x")), h.link.attempted.map { it.message })
    }

    @Test
    fun returnDroppedWhenTargetChangesDuringTyping() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.link.behaviour = { message -> if (message == sendKey("make test")) gate.await() }
        h.sender.typeThenReturn(first, "make test")
        runCurrent()
        h.current = first.copy(watchEpoch = 2)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(sendKey("make test")), h.link.completed.map { it.message })
        assertEquals(1, h.link.attempted.size)
    }

    @Test
    fun typeThenReturnTypesTheDraftThenReturn() = runTest {
        val h = harness()
        h.sender.typeThenReturn(first, "make test")
        h.sender.key(first, text("y"))
        settle()
        assertEquals(
            listOf(sendKey("make test") to 1L, sendKey("\n") to 1L, sendKey("y") to 1L),
            h.link.completed.map { it.message to it.onLink },
        )
        assertFalse("a single-line Paste ⏎ never uses pasteText", h.link.attempted.any { it.message is PhoneMessage.PasteText })
    }

    /** Owner decision 2026-11-18: a single-line draft is typed, then Return. */
    @Test
    fun singleLineDraftIsTypedThenReturned() = runTest {
        val h = harness()
        h.sender.typeThenReturn(first, "git status")
        settle()
        assertEquals(listOf(sendKey("git status"), sendKey("\n")), h.link.completed.map { it.message })
    }

    /** Owner decision 2026-11-18: a multi-line draft is one pasteText, no sendKey, no Return. */
    @Test
    fun multiLineDraftIsPastedWithNoReturn() = runTest {
        val h = harness()
        h.sender.typeThenReturn(first, "make test\ngit status --short\n")
        settle()
        assertEquals(
            listOf(PhoneMessage.PasteText(guid, "make test\ngit status --short\n") to 1L),
            h.link.attempted.map { it.message to it.onLink },
        )
        assertFalse(h.link.attempted.any { it.message is PhoneMessage.SendKey })
    }

    @Test
    fun carriageReturnLineFeedDraftCountsAsMultiLine() = runTest {
        val h = harness()
        h.sender.typeThenReturn(first, "echo a\r\necho b")
        h.sender.typeThenReturn(first, "echo c\recho d")
        settle()
        assertEquals(
            listOf(PhoneMessage.PasteText(guid, "echo a\r\necho b"), PhoneMessage.PasteText(guid, "echo c\recho d")),
            h.link.attempted.map { it.message },
        )
        assertTrue(isMultiLineDraft("a\r\nb"))
        assertFalse(isMultiLineDraft("a b"))
    }

    /** The multi-line Paste ⏎ is exactly the upstream `pasteText` sample. */
    @Test
    fun multiLinePasteReturnMatchesTheGoldenSample() = runTest {
        val h = harness()
        h.sender.typeThenReturn(first, "make test\ngit status --short\n")
        settle()
        val expected = Json.parseToJsonElement(Fixtures.read("messages/live-session/paste-text-multiline.json")).jsonObject.getValue("json")
        val actual = h.link.completed.map { buildJsonObject { put("payload", it.message.payload()) } }
        assertEquals(listOf(expected), actual)
    }

    @Test
    fun largeDraftIsTypedInOrderedChunks() = runTest {
        val h = harness(typeChunkBytes = 8)
        val draft = "abc👍🏽defgh ij"
        h.sender.typeThenReturn(first, draft)
        settle()
        val typed = h.link.completed.map { ((it.message as PhoneMessage.SendKey).event.key as Key.Text).text }
        assertEquals(listOf("abc", "👍🏽", "defgh ij", "\n"), typed)
        assertEquals(draft, typed.dropLast(1).joinToString(""))
        assertTrue(h.link.completed.all { (it.message as PhoneMessage.SendKey).event.modifiers == KeyModifiers() })
    }

    @Test
    fun failedChunkStopsTheRestAndTheReturn() = runTest {
        val h = harness(typeChunkBytes = 4)
        h.link.behaviour = { message -> if (message == sendKey("efgh")) throw CompanionRequestException("not connected") }
        h.sender.typeThenReturn(first, "abcdefghijkl")
        settle()
        assertEquals(listOf(sendKey("abcd"), sendKey("efgh")), h.link.attempted.map { it.message })
    }

    @Test
    fun targetChangeBetweenChunksStopsTyping() = runTest {
        val h = harness(typeChunkBytes = 4)
        val gate = CompletableDeferred<Unit>()
        h.link.behaviour = { message -> if (message == sendKey("abcd")) gate.await() }
        h.sender.typeThenReturn(first, "abcdefgh")
        runCurrent()
        h.current = first.copy(linkId = 2)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(sendKey("abcd")), h.link.attempted.map { it.message })
    }

    @Test
    fun typingChunksKeepGraphemesWholeAndUnderTheLimit() {
        val family = "👨‍👩‍👧‍👦" // 25 UTF-8 bytes, one grapheme
        val text = "x".repeat(10) + family + "é\r\n" + "y".repeat(30)
        val chunks = splitForTyping(text, maxBytes = 16)
        assertEquals(text, chunks.joinToString(""))
        assertTrue("a grapheme over the limit is sent whole, alone", family in chunks)
        assertTrue("\r\n stays together", chunks.none { it.endsWith("\r") })
        for (chunk in chunks.filter { it != family }) assertTrue(chunk, chunk.toByteArray().size <= 16)
        assertEquals("flags pair up", listOf("🇬🇧", "🇫🇷"), splitForTyping("🇬🇧🇫🇷", maxBytes = 8))
        assertEquals("a combining mark stays on its letter", listOf("e\u0301", "e\u0301"), splitForTyping("e\u0301e\u0301", maxBytes = 3))
        assertEquals(listOf("hello"), splitForTyping("hello"))
        assertEquals(emptyList<String>(), splitForTyping(""))
    }

    @Test
    fun defaultChunkKeepsEnvelopesWellUnderTheRelayFrame() {
        // Worst case JSON escaping is 6 bytes per input byte (\u00XX): still under 256 KiB.
        assertTrue(InputSender.TYPE_CHUNK_BYTES * 6 < 256 * 1024 / 2)
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
        // Paste ⏎ types the draft: the same shapes as typing "ls" then Return.
        h.sender.typeThenReturn(first, "ls")
        settle()
        val expected = listOf(
            "send-key-control-c", "send-key-text", "send-key-return", "send-key-special-tab",
            "send-key-special-backspace", "paste-text-multiline", "send-key-text", "send-key-return",
        ).map { Json.parseToJsonElement(Fixtures.read("messages/live-session/$it.json")).jsonObject.getValue("json") }
        val actual = h.link.completed.map { buildJsonObject { put("payload", it.message.payload()) } }
        assertEquals(expected, actual)
    }

    // --- Pasted keys (Settings: "Send / * + = as paste") -----------------------------------

    private fun pasteText(s: String, target: InputTarget = first) = PhoneMessage.PasteText(target.sessionGuid, s)

    /** "a/b*c+d=e" as the runs the sender sends with the setting on. */
    private val mixedRuns = listOf(
        sendKey("a"), pasteText("/"), sendKey("b"), pasteText("*"), sendKey("c"), pasteText("+"), sendKey("d"), pasteText("="), sendKey("e"),
    )

    @Test
    fun pastedKeysAreSplitIntoRunsInOrder() = runTest {
        val h = harness(pasteKeys = { true })
        h.sender.key(first, text("a/b*c+d=e"))
        h.sender.key(first, text("//"))
        h.sender.key(first, text("x"))
        h.sender.paste(first, "y/")
        h.sender.key(first, text("="))
        settle()
        assertEquals(
            mixedRuns + listOf(pasteText("/"), pasteText("/"), sendKey("x"), pasteText("y/"), pasteText("=")),
            h.link.completed.map { it.message },
        )
        assertTrue(h.link.completed.all { it.onLink == first.linkId })
    }

    @Test
    fun withTheSettingOffEveryKeyIsAKey() = runTest {
        val h = harness(pasteKeys = { false })
        h.sender.key(first, text("a/b*c+d=e"))
        h.sender.typeThenReturn(first, "ls /tmp")
        settle()
        assertEquals(listOf(sendKey("a/b*c+d=e"), sendKey("ls /tmp"), sendKey("\n")), h.link.completed.map { it.message })
    }

    @Test
    fun theSettingIsReadWhenTheKeyIsSent() = runTest {
        var on = false
        val h = harness(pasteKeys = { on })
        h.sender.key(first, text("/"))
        settle()
        on = true
        h.sender.key(first, text("/"))
        settle()
        on = false
        h.sender.key(first, text("/"))
        settle()
        assertEquals(listOf(sendKey("/"), pasteText("/"), sendKey("/")), h.link.completed.map { it.message })
    }

    @Test
    fun onlyTheFourCharactersArePasted() = runTest {
        val h = harness(pasteKeys = { true })
        val others = (0x20..0x7e).map { it.toChar() }.filter { it !in "/*+=" }.joinToString("") + "é😀\t"
        h.sender.key(first, text(others))
        for (c in listOf("-", ".", "?", "_", "8", "<")) h.sender.key(first, text(c))
        settle()
        assertEquals(
            listOf(sendKey(others)) + listOf("-", ".", "?", "_", "8", "<").map { sendKey(it) },
            h.link.completed.map { it.message },
        )
        assertEquals("/*+=", PASTED_KEYS)
    }

    @Test
    fun aKeyWithAModifierStaysAKey() = runTest {
        val h = harness(pasteKeys = { true })
        val modified = listOf(
            KeyEvent(Key.Text("/"), KeyModifiers(control = true)),
            KeyEvent(Key.Text("*"), KeyModifiers(leftOption = true)),
            KeyEvent(Key.Text("+"), KeyModifiers(rightOption = true)),
            KeyEvent(Key.Text("a/="), KeyModifiers(shift = true)),
        )
        modified.forEach { h.sender.key(first, it) }
        h.sender.key(first, KeyEvent(Key.Special(SpecialKey.TAB)))
        settle()
        assertEquals(
            modified.map { PhoneMessage.SendKey(guid, it) } + PhoneMessage.SendKey(guid, KeyEvent(Key.Special(SpecialKey.TAB))),
            h.link.completed.map { it.message },
        )
    }

    @Test
    fun anArmedStickyModifierKeepsTheKey() = runTest {
        val h = harness(pasteKeys = { true })
        val modifiers = StickyModifiers()
        val ime = ImeTranslator(modifiers, emit = { h.sender.key(first, it) }, onComposingFlushed = {}, toComposer = {})
        modifiers.tap(ModifierKey.CONTROL, nowMillis = 0)
        ime.commitText("/")
        ime.commitText("/")
        settle()
        assertEquals(
            listOf(PhoneMessage.SendKey(guid, KeyEvent(Key.Text("/"), KeyModifiers(control = true))), pasteText("/")),
            h.link.completed.map { it.message },
        )
    }

    @Test
    fun aBrowserKeyIsNeverPasted() = runTest {
        val h = harness(pasteKeys = { true })
        h.sender.key(first, text("a/b"), pasteKeys = false)
        settle()
        assertEquals(listOf(sendKey("a/b")), h.link.completed.map { it.message })
    }

    @Test
    fun aTypedDraftPastesTheFourCharactersThenReturns() = runTest {
        val h = harness(pasteKeys = { true }, typeChunkBytes = 4)
        h.sender.typeThenReturn(first, "ls /tmp/x")
        // A multi-line draft is one paste, as before.
        h.sender.typeThenReturn(first, "a/b\nc")
        settle()
        assertEquals(
            listOf(
                // Pieces of at most 4 bytes: "ls /", "tmp/", "x"; each split at the pasted keys.
                sendKey("ls "), pasteText("/"), sendKey("tmp"), pasteText("/"), sendKey("x"), sendKey("\n"),
                pasteText("a/b\nc"),
            ),
            h.link.completed.map { it.message },
        )
    }

    @Test
    fun runsStopWhenTheTargetChanges() = runTest {
        val h = harness(pasteKeys = { true })
        val moved = first.copy(watchEpoch = 2)
        h.link.behaviour = { message -> if (message == pasteText("/")) h.current = moved }
        h.sender.key(first, text("a/b"))
        h.sender.typeThenReturn(first, "c/d")
        settle()
        assertEquals("the run after the target changed is not sent", listOf(sendKey("a"), pasteText("/")), h.link.attempted.map { it.message })
        assertTrue(h.log.any { "stopped at run 3/3" in it })
        assertTrue("the typed draft was stale at dequeue", h.log.any { "discarded stale typed draft" in it })
    }

    @Test
    fun aFailedRunStopsTheItemAndATypedDraftIsNotReturned() = runTest {
        val h = harness(pasteKeys = { true })
        h.link.behaviour = { message -> if (message == pasteText("*")) throw CompanionRequestException("refused") }
        h.sender.key(first, text("a*b"))
        h.sender.typeThenReturn(first, "c*d")
        h.sender.key(first, text("e"))
        settle()
        assertEquals(
            listOf(sendKey("a"), pasteText("*"), sendKey("c"), pasteText("*"), sendKey("e")),
            h.link.attempted.map { it.message },
        )
        assertTrue(h.log.any { "Return not sent" in it })
        assertFalse("no text in the logs", h.log.any { "a*b" in it || "c*d" in it })
    }

    @Test
    fun aSlowRunIsSkippedAndTheRestOfTheItemDropped() = runTest {
        val h = harness(pasteKeys = { true })
        h.link.behaviour = { message -> if (message == pasteText("+")) awaitCancellation() }
        h.sender.key(first, text("1+1"))
        h.sender.key(first, text("2"))
        settle()
        assertEquals(listOf(sendKey("1"), sendKey("2")), h.link.completed.map { it.message })
        assertEquals(listOf(sendKey("1"), pasteText("+"), sendKey("2")), h.link.attempted.map { it.message })
        assertEquals(2_000L, h.link.attempted[2].atMillis - h.link.attempted[1].atMillis)
    }

    @Test
    fun theTraceLogsEachDecisionAsCodePoints() = runTest {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val previous = InputTrace.current
        InputTrace.current = InputTrace({ true }, { 0L }, { lines += it })
        try {
            val h = harness(pasteKeys = { true })
            h.sender.key(first, text("a/b"))
            settle()
        } finally {
            InputTrace.current = previous
        }
        val decisions = lines.filter { "pasted key" in it || "typed run" in it }
        assertEquals(3, decisions.size)
        assertTrue(decisions[0], "typed run U+0061[a] as sendKey" in decisions[0])
        assertTrue(decisions[1], "pasted key U+002F[/] as pasteText" in decisions[1])
        assertTrue(decisions[2], "typed run U+0062[b] as sendKey" in decisions[2])
        assertFalse("never plain text", lines.any { "a/b" in it })
    }

    @Test
    fun splitPastedKeysKeepsTheTextBetween() {
        assertEquals(emptyList<TextRun>(), splitPastedKeys(""))
        assertEquals(listOf(TextRun("abc", false)), splitPastedKeys("abc"))
        assertEquals(
            listOf(TextRun("/", true), TextRun("😀", false), TextRun("=", true), TextRun("=", true), TextRun("é", false)),
            splitPastedKeys("/😀==é"),
        )
        assertEquals("TextRun(len=3, pasted=false)", TextRun("abc", false).toString())
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
