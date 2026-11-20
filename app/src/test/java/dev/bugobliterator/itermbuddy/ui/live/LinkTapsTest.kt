package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.stream.TappedLink
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live view's link taps (bubble shown, replaced, dismissed) and the bubble's text, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkTapsTest {
    private class Rig(test: TestScope) {
        val answers = ArrayDeque<CompletableDeferred<TappedLink?>>()
        val asked = ArrayList<SelectionPoint>()
        val cancelled = ArrayList<SelectionPoint>()
        val taps = LinkTaps(test.backgroundScope) { point ->
            asked += point
            val answer = CompletableDeferred<TappedLink?>().also { answers.addLast(it) }
            try {
                answer.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += point
                throw e
            }
        }
    }

    @Test
    fun aLinkShowsTheBubbleAtTheTap() = runTest {
        val rig = Rig(this)
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        assertNull("nothing until the Mac answers", rig.taps.bubble.value)
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        val bubble = rig.taps.bubble.value!!
        assertEquals(LINK, bubble.link)
        assertEquals(10f to 20f, bubble.x to bubble.y)
        assertEquals(listOf(P1), rig.asked)
    }

    @Test
    fun noLinkNoBubble() = runTest {
        val rig = Rig(this)
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        rig.answers.removeFirst().complete(null)
        runCurrent()
        assertNull(rig.taps.bubble.value)
        assertFalse("nothing was shown", rig.taps.dismiss())
    }

    @Test
    fun dismissHidesTheBubbleAndSaysSo() = runTest {
        val rig = Rig(this)
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        assertTrue(rig.taps.dismiss())
        assertNull(rig.taps.bubble.value)
        assertFalse(rig.taps.dismiss())
    }

    @Test
    fun aLookupUnderWayIsCancelledByADismissOrANewerTap() = runTest {
        val rig = Rig(this)
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        // A drag (dismiss) cancels it: its late answer never shows.
        assertFalse("no bubble was up", rig.taps.dismiss())
        runCurrent()
        assertEquals(listOf(P1), rig.cancelled)
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        assertNull(rig.taps.bubble.value)
        // A newer tap replaces the one under way.
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        rig.taps.tap(P2, 30f, 40f)
        runCurrent()
        assertEquals(listOf(P1, P1), rig.cancelled)
        rig.answers.removeFirst().complete(LINK)
        rig.answers.removeFirst().complete(LINK.copy(url = "https://example.org/"))
        runCurrent()
        assertEquals("https://example.org/", rig.taps.bubble.value?.link?.url)
        assertEquals(30f, rig.taps.bubble.value?.x)
        // cancel() stops a lookup but leaves a shown bubble.
        rig.taps.tap(P1, 1f, 1f)
        runCurrent()
        assertNull("a tap hides the old bubble at once", rig.taps.bubble.value)
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        val shown = rig.taps.bubble.value
        rig.taps.cancel()
        assertEquals(shown, rig.taps.bubble.value)
    }

    @Test
    fun eachBubbleIsNew() = runTest {
        val rig = Rig(this)
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        val first = rig.taps.bubble.value!!.id
        rig.taps.tap(P1, 10f, 20f)
        runCurrent()
        rig.answers.removeFirst().complete(LINK)
        runCurrent()
        assertTrue(rig.taps.bubble.value!!.id != first)
    }

    @Test
    fun bubbleContent() {
        assertEquals(LinkBubbleContent(null, "Open link"), linkBubbleContent(LINK, browserSession = "web"))
        assertEquals(LinkBubbleContent("Mac-only link", "Open in web"), linkBubbleContent(LOCAL, browserSession = "web"))
        assertEquals(LinkBubbleContent("Mac-only link — no Browser session open", null), linkBubbleContent(LOCAL, browserSession = null))
    }

    @Test
    fun linksAreElidedInThePathWithTheHostWhole() {
        assertEquals("https://example.com/path", elideLink("https://example.com/path"))
        val long = "https://example.com/" + "a".repeat(60) + "/end"
        val elided = elideLink(long, max = 30)
        assertEquals(30, elided.length)
        assertTrue(elided, elided.startsWith("https://example.com/aaaa") && elided.endsWith("a/end") && "…" in elided)
        // A host longer than the room is shown whole, never cut.
        val host = "https://" + "sub.".repeat(10) + "example.com"
        assertEquals("$host/…", elideLink("$host/some/long/path", max = 20))
        assertEquals("$host:8080", elideLink("$host:8080", max = 20))
        assertEquals("http://localhost:3000?…", elideLink("http://localhost:3000?" + "q".repeat(40), max = 22))
    }

    private companion object {
        val P1 = SelectionPoint(10, 1)
        val P2 = SelectionPoint(11, 2)
        val LINK = TappedLink("https://example.com/path", macLocal = false)
        val LOCAL = TappedLink("http://localhost:3000/", macLocal = true)
    }
}
