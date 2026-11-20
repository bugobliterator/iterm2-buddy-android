package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Link extraction, Mac-local hosts, and the tap's smart-select → copy → clear, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkTapTest {
    @Test
    fun linksAreExtractedAndNormalised() {
        val cases = listOf(
            "https://example.com/path" to "https://example.com/path",
            "  https://example.com/path\n" to "https://example.com/path",
            "http://example.com" to "http://example.com",
            "ftp://files.example.com/a.tgz" to "ftp://files.example.com/a.tgz",
            "HTTPS://Example.com/Case" to "https://Example.com/Case",
            "www.example.com" to "https://www.example.com",
            "WWW.example.com/a?b=c#d" to "https://WWW.example.com/a?b=c#d",
            // Trailing sentence punctuation.
            "https://example.com/path." to "https://example.com/path",
            "https://example.com/path," to "https://example.com/path",
            "https://example.com/path;" to "https://example.com/path",
            "https://example.com/path:" to "https://example.com/path",
            "https://example.com/path.)" to "https://example.com/path",
            "https://example.com/path'\"" to "https://example.com/path",
            "https://example.com]" to "https://example.com",
            // Wrapped.
            "<https://example.com/path>" to "https://example.com/path",
            "(https://example.com/path)" to "https://example.com/path",
            "(https://example.com/path)." to "https://example.com/path",
            "\"https://example.com/path\"" to "https://example.com/path",
            "'www.example.com'" to "https://www.example.com",
            // Balanced brackets are the link's own.
            "https://en.wikipedia.org/wiki/Foo_(bar)" to "https://en.wikipedia.org/wiki/Foo_(bar)",
            "(https://en.wikipedia.org/wiki/Foo_(bar))" to "https://en.wikipedia.org/wiki/Foo_(bar)",
            "https://example.com/a[1]" to "https://example.com/a[1]",
            // Hosts, ports, user info, IP literals.
            "http://localhost:8080/x" to "http://localhost:8080/x",
            "http://user@host.example:22/" to "http://user@host.example:22/",
            "http://[::1]:3000/" to "http://[::1]:3000/",
            "http://127.0.0.1" to "http://127.0.0.1",
        )
        for ((text, want) in cases) assertEquals("for <$text>", want, linkFromSelection(text))
    }

    @Test
    fun nonLinksAreNull() {
        val cases = listOf(
            "", "   ", "hello", "example.com", "mailto:a@b.c", "file:///etc/hosts", "ssh://host", "javascript:alert(1)",
            "https://", "https:///path", "https://exa mple.com", "https://example.com/a b", "www.", "www",
            "https://ex%ample.com", "https://host:port/", "https://host:/", "https://.example.com", "https://a..b",
            "http://[::1", "http://[::1]x/", "two https://example.com words", "https://exa\u0007mple.com",
            "https://" + "a".repeat(MAX_LINK_CHARS),
        )
        for (text in cases) assertNull("for <$text>", linkFromSelection(text))
    }

    @Test
    fun macLocalHosts() {
        val local = listOf(
            "http://localhost", "http://LOCALHOST:3000/x", "http://app.localhost/", "http://127.0.0.1:8000",
            "http://127.1.2.3/", "http://[::1]:5173/", "http://0.0.0.0:8080", "http://localhost./",
            "http://[::ffff:127.0.0.1]/", "http://[0:0:0:0:0:0:0:1]/",
        )
        val phone = listOf(
            "https://example.com", "http://128.0.0.1", "http://10.0.0.5:8080", "http://192.168.1.2", "http://[::2]/",
            "http://localhost.example.com", "http://127.0.0.256/", "http://127.0.0/", "http://mac.local/", "http://my-mac/",
        )
        for (url in local) assertTrue(url, isMacLocalLink(url))
        for (url in phone) assertFalse(url, isMacLocalLink(url))
        // The Mac's own names, when known, with or without .local.
        assertTrue(isMacLocalLink("http://my-mac.local:8080/", setOf("My-Mac")))
        assertTrue(isMacLocalLink("http://my-mac/", setOf("my-mac.local")))
        assertFalse(isMacLocalLink("http://other-mac.local/", setOf("my-mac")))
    }

    @Test
    fun hostsAreParsed() {
        assertEquals("example.com", linkHost("https://user:pw@Example.COM:443/a?b#c"))
        assertEquals("::1", linkHost("http://[::1]:80"))
        assertEquals("example.com", linkHost("https://example.com?x"))
        assertNull(linkHost("example.com"))
    }

    private class Rig(val test: TestScope) {
        val link = ScriptedStreamLink()
        val active = MutableStateFlow<ActiveStream?>(ActiveStream(1, 7, GUID, 1, null, 100))
        val range = MutableStateFlow<SelectionSpan?>(null)
        val logs = CopyOnWriteArrayList<String>()
        val driver = SelectionDriver(link, active, range, test.backgroundScope, copyTimeout = 15.seconds)
        val lookup = LinkLookup(driver, active, timeout = 1.seconds, log = { logs += it })

        fun sent(): List<PhoneMessage> {
            test.runCurrent()
            return link.sent.map { it.message }
        }
    }

    @Test
    fun aTapSmartSelectsCopiesThenClears() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        val request = rig.link.requests.receive()
        assertEquals(PhoneMessage.CopySelection(GUID), request.message)
        assertEquals("the request is bounded as the tap is", 1.seconds, request.timeout)
        assertEquals(
            listOf(
                PhoneMessage.SelectionGesture(7, SelectionPhase.BEGIN, SelectionMode.SMART, POINT),
                PhoneMessage.SelectionGesture(7, SelectionPhase.END, SelectionMode.SMART, POINT),
            ),
            rig.sent(),
        )
        request.reply.complete(MacMessage.SelectionText("https://example.com/path."))
        assertEquals(TappedLink("https://example.com/path", macLocal = false), found.await())
        assertEquals(PhoneMessage.ClearSelection(7), rig.sent().last())
        assertEquals(3, rig.sent().size)
        assertTrue("never the link in the log: ${rig.logs}", rig.logs.none { "example" in it })
    }

    @Test
    fun aMacLocalLinkIsMarked() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        rig.link.requests.receive().reply.complete(MacMessage.SelectionText("http://localhost:3000/"))
        assertEquals(TappedLink("http://localhost:3000/", macLocal = true), found.await())
    }

    @Test
    fun plainTextOrNothingSelectedFindsNoLinkButStillClears() = runTest {
        for (text in listOf("", "hello", "  ")) {
            val rig = Rig(this)
            val found = async { rig.lookup.lookup(GUID, POINT) }
            rig.link.requests.receive().reply.complete(MacMessage.SelectionText(text))
            assertNull(found.await())
            assertEquals(PhoneMessage.ClearSelection(7), rig.sent().last())
        }
    }

    @Test
    fun aLateAnswerShowsNothingAndTheClearStillGoes() = runTest {
        val rig = Rig(this)
        val start = currentTime
        val found = async { rig.lookup.lookup(GUID, POINT) }
        val request = rig.link.requests.receive()
        advanceTimeBy(999)
        assertFalse(found.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(found.isCompleted)
        assertNull(found.await())
        assertEquals(1_000L, currentTime - start)
        // The client's own bound (the request's 1 s) fails it; the queue moves on to the clear.
        assertTrue("the clear waits behind the copy", rig.sent().none { it is PhoneMessage.ClearSelection })
        request.reply.completeExceptionally(CompanionRequestException("request timed out"))
        assertEquals(PhoneMessage.ClearSelection(7), rig.sent().last())
        assertTrue(rig.logs.any { "no answer" in it })
    }

    @Test
    fun anErrorReplyFindsNothing() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        rig.link.requests.receive().reply.completeExceptionally(CompanionRequestException("link 1 has ended"))
        assertNull(found.await())
        assertEquals(PhoneMessage.ClearSelection(7), rig.sent().last())
    }

    @Test
    fun aStreamChangeDuringTheLookupFindsNothingAndClearsNothing() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        val request = rig.link.requests.receive()
        rig.active.value = ActiveStream(1, 8, GUID, 2, null, 100)
        request.reply.complete(MacMessage.SelectionText("https://example.com"))
        assertNull(found.await())
        assertTrue("no clear for the new stream", rig.sent().none { it is PhoneMessage.ClearSelection })
    }

    @Test
    fun nothingIsSentWhileASelectionShowsOrForAnotherStream() = runTest {
        val rig = Rig(this)
        rig.range.value = SelectionSpan(POINT, POINT)
        assertNull(rig.lookup.lookup(GUID, POINT))
        rig.range.value = null
        assertNull("another session's stream", rig.lookup.lookup("guid-other", POINT))
        rig.active.value = ActiveStream(1, 7, GUID, 1, browserConfig(7), 100)
        assertNull("a browser stream", rig.lookup.lookup(GUID, POINT))
        rig.active.value = null
        assertNull("no stream", rig.lookup.lookup(GUID, POINT))
        assertTrue(rig.sent().isEmpty())
        assertTrue(rig.link.requests.isEmpty)
    }

    @Test
    fun aLongPressBegunMeanwhileKeepsItsSelection() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        val request = rig.link.requests.receive()
        rig.driver.begin(SelectionPoint(101, 0))
        request.reply.complete(MacMessage.SelectionText("https://example.com"))
        assertEquals("https://example.com", found.await()?.url)
        assertTrue("the owner's selection is not cleared", rig.sent().none { it is PhoneMessage.ClearSelection })
        assertTrue(rig.logs.any { "a selection began meanwhile" in it })
    }

    @Test
    fun aCancelledLookupStillClears() = runTest {
        val rig = Rig(this)
        val found = async { rig.lookup.lookup(GUID, POINT) }
        val request = rig.link.requests.receive()
        found.cancel()
        runCurrent()
        request.reply.complete(MacMessage.SelectionText("https://example.com"))
        assertEquals(PhoneMessage.ClearSelection(7), rig.sent().last())
    }

    @Test
    fun copyCarriesItsOwnTimeoutAndGesturesAreCounted() = runTest {
        val rig = Rig(this)
        assertEquals(0L, rig.driver.gesturesBegun())
        rig.driver.begin(POINT)
        rig.driver.end(POINT)
        assertEquals(1L, rig.driver.gesturesBegun())
        val copy = async { rig.driver.copy(GUID, 3.seconds) }
        val request = rig.link.requests.receive()
        assertEquals(3.seconds, request.timeout)
        request.reply.complete(MacMessage.SelectionText("x"))
        assertEquals("x", copy.await())
        val plain = async { rig.driver.copy(GUID) }
        assertEquals("the driver's own bound by default", 15.seconds, rig.link.requests.receive().also { it.reply.complete(MacMessage.SelectionText("")) }.timeout)
        plain.await()
    }

    private companion object {
        const val GUID = "guid-one"
        val POINT = SelectionPoint(120, 9)

        fun browserConfig(streamID: Long) = StreamConfig(streamID, 1, ByteArray(0), 800, 600, 2.0, 0, 0, null, 0, 0, null, false, false, isBrowser = true)
    }
}
