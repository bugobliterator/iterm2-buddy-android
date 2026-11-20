package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.core.input.isInputTarget
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeStreamConfig
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/** The browser driver through a real [InputSender] against a [ScriptedStreamLink], in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserDriverTest {
    private class Rig(val test: TestScope, revision: Int = 15, val scrollKeys: MutableStateFlow<Boolean> = MutableStateFlow(false)) {
        val link = ScriptedStreamLink()
        val active = MutableStateFlow<ActiveStream?>(browserStream(linkId = 1, streamID = 1))
        val editing = MutableStateFlow(false)
        val navigation = MutableStateFlow<BrowserNavigationState?>(null)
        val macRevision = MutableStateFlow(revision)
        val logs = CopyOnWriteArrayList<String>()
        val input = InputSender(link, test.backgroundScope, isCurrent = { isInputTarget(active.value, it) }, log = { logs += it })
        val driver = BrowserDriver(
            input,
            active,
            editing,
            navigation,
            macRevision = macRevision,
            scope = test.backgroundScope,
            nowMillis = { test.testScheduler.currentTime },
            scrollKeys = { scrollKeys.value },
            log = { logs += it },
        )

        /** Every message sent so far, in order. */
        fun sent(): List<PhoneMessage> {
            test.runCurrent()
            return link.sent.map { it.message }
        }

        fun scrolls(): List<PhoneMessage.BrowserScroll> = sent().filterIsInstance<PhoneMessage.BrowserScroll>()
    }

    @Test
    fun tapSendsImagePixels() = runTest {
        val rig = Rig(this)
        rig.driver.tap(1234.5, 67.25)
        assertEquals(listOf(PhoneMessage.BrowserTap(1, 1234.5, 67.25)), rig.sent())
        assertEquals("bound to the stream's link", listOf(1L), rig.link.sent.map { it.onLink })
        rig.driver.tap(Double.NaN, 3.0)
        assertEquals("a non-finite point is not sent", 1, rig.sent().size)
    }

    @Test
    fun scrollFlushesAtInterval() = runTest {
        val rig = Rig(this)
        // 25 Hz: one send per 40 ms, whatever the drag rate.
        rig.driver.scroll(0f, 5f, imagePxPerViewPx = 2f, zoom = 1f, atX = 100f, atY = 200f)
        advanceTimeBy(10.milliseconds)
        rig.driver.scroll(0f, 5f, 2f, 1f, 100f, 200f)
        runCurrent()
        assertEquals("nothing before the first tick", emptyList<PhoneMessage>(), rig.sent())
        advanceTimeBy(30.milliseconds)
        runCurrent()
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 100.0, 200.0, 0.0, 20.0)), rig.scrolls())
        assertEquals(40L, currentTime)
        rig.driver.scroll(-3f, 0f, 1f, 1f, 100f, 200f)
        advanceTimeBy(39.milliseconds)
        assertEquals(1, rig.scrolls().size)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(PhoneMessage.BrowserScroll(1, 100.0, 200.0, -3.0, 0.0), rig.scrolls().last())
        // A steady drag every 8 ms for 400 ms: 10 sends, not 50.
        val before = rig.scrolls().size
        repeat(50) {
            rig.driver.scroll(0f, 1f, 1f, 1f, 100f, 200f)
            advanceTimeBy(8.milliseconds)
        }
        runCurrent()
        val during = rig.scrolls().drop(before)
        assertEquals(10, during.size)
        assertEquals(50.0, during.sumOf { it.deltaY }, 0.0)
        // The ticker stops when idle: nothing more, however long.
        advanceTimeBy(10_000.milliseconds)
        assertEquals(before + 10, rig.scrolls().size)
    }

    @Test
    fun flingCoastsAndStops() = runTest {
        val rig = Rig(this)
        rig.driver.fling(0f, -3000f, imagePxPerViewPx = 1f, zoom = 1f, atX = 10f, atY = 20f, densityPxPerDp = 1f)
        advanceTimeBy(3_000.milliseconds)
        runCurrent()
        val scrolls = rig.scrolls()
        // The shared spline: 1309 px over 1246 ms, sent at 25 Hz in steps that shrink as it slows.
        val curve = FlingCurve(3000f, 1f)
        assertTrue("${scrolls.size} steps", scrolls.size in 20..33)
        assertTrue(scrolls.all { it.deltaY < 0 && it.deltaX == 0.0 && it.x == 10.0 && it.y == 20.0 })
        assertEquals(-curve.distancePx.toDouble(), scrolls.sumOf { it.deltaY }, 1.0)
        assertTrue(scrolls.first().deltaY < 4 * scrolls.last().deltaY)
        advanceTimeBy(10_000.milliseconds)
        assertEquals(scrolls.size, rig.scrolls().size)
    }

    @Test
    fun tapStopsAFlingAndCancelDropsTravel() = runTest {
        val rig = Rig(this)
        rig.driver.fling(0f, 3000f, 1f, 1f, 0f, 0f)
        advanceTimeBy(200.milliseconds)
        rig.driver.tap(5.0, 6.0)
        runCurrent()
        val count = rig.sent().size
        advanceTimeBy(2_000.milliseconds)
        assertEquals("no coasting after a tap", count, rig.sent().size)
        rig.driver.scroll(0f, 30f, 1f, 1f, 0f, 0f)
        rig.driver.cancelScroll()
        advanceTimeBy(200.milliseconds)
        assertEquals("a pinch's cancel drops the drag", count, rig.sent().size)
    }

    @Test
    fun stopFlingStopsCoastingAndSendsNothing() = runTest {
        // A tap off a short page clicks nothing but still stops a fling (iOS SessionView:2192-2193).
        val rig = Rig(this)
        rig.driver.fling(0f, 3000f, 1f, 1f, 0f, 0f)
        advanceTimeBy(200.milliseconds)
        rig.driver.stopFling()
        runCurrent()
        val count = rig.sent().size
        advanceTimeBy(2_000.milliseconds)
        assertEquals(count, rig.sent().size)
        assertTrue(rig.sent().none { it is PhoneMessage.BrowserTap })
    }

    @Test
    fun loadTrimsAndSkipsEmpty() = runTest {
        val rig = Rig(this)
        rig.driver.load("  \n\t ")
        rig.driver.load("")
        rig.driver.load("  iterm2.com/documentation \n")
        assertEquals(listOf(PhoneMessage.BrowserLoad(1, "iterm2.com/documentation")), rig.sent())
    }

    @Test
    fun navigationControlsSendInOrder() = runTest {
        val rig = Rig(this)
        rig.driver.back()
        rig.driver.forward()
        rig.driver.reload()
        rig.driver.stop()
        rig.driver.tap(1.0, 2.0)
        assertEquals(
            listOf(
                PhoneMessage.BrowserGoBack(1),
                PhoneMessage.BrowserGoForward(1),
                PhoneMessage.BrowserReload(1),
                PhoneMessage.BrowserStopLoading(1),
                PhoneMessage.BrowserTap(1, 1.0, 2.0),
            ),
            rig.sent(),
        )
    }

    @Test
    fun navigationFollowsEvents() = runTest {
        val rig = Rig(this)
        assertSame(rig.navigation, rig.driver.navigation)
        assertNull(rig.driver.navigation.value)
        val state = BrowserNavigationState("https://example.invalid/", "Title", canGoBack = true, canGoForward = false, isLoading = true)
        rig.navigation.value = state
        assertEquals(state, rig.driver.navigation.value)
        rig.navigation.value = null
        assertNull(rig.driver.navigation.value)
    }

    @Test
    fun editingFollowsEvents() = runTest {
        val rig = Rig(this)
        assertSame(rig.editing, rig.driver.editing)
        assertFalse(rig.driver.editing.value)
        rig.editing.value = true
        assertTrue(rig.driver.editing.value)
    }

    @Test
    fun nothingSentBelowRevisionFifteen() = runTest {
        val rig = Rig(this, revision = 14)
        assertFalse(rig.driver.inputActive.value)
        exerciseEverything(rig)
        assertEquals(emptyList<PhoneMessage>(), rig.sent())
        // The same driver goes live once a revision 15 Mac is connected.
        rig.macRevision.value = 15
        runCurrent()
        assertTrue(rig.driver.inputActive.value)
        rig.driver.back()
        assertEquals(listOf(PhoneMessage.BrowserGoBack(1)), rig.sent())
    }

    @Test
    fun nothingSentForATerminalStreamOrWithoutAStream() = runTest {
        val rig = Rig(this)
        rig.active.value = browserStream(1, 1).copy(config = terminalConfig())
        runCurrent()
        assertFalse("a terminal stream takes no browser input (iOS browserInputActive)", rig.driver.inputActive.value)
        exerciseEverything(rig)
        rig.active.value = browserStream(1, 1).copy(config = null)
        runCurrent()
        assertFalse("no config yet", rig.driver.inputActive.value)
        exerciseEverything(rig)
        rig.active.value = null
        exerciseEverything(rig)
        assertEquals(emptyList<PhoneMessage>(), rig.sent())
    }

    @Test
    fun tapAndTypedKeyArriveInCallOrder() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        val target = InputTarget(1, 1, "guid-browser")
        val x = KeyEvent(Key.Text("x"))
        // Keyboard up in field A; tap field B and type straight away while an earlier key is held.
        rig.input.key(target, KeyEvent(Key.Text("a")))
        runCurrent()
        rig.driver.tap(10.0, 20.0)
        rig.input.key(target, x)
        gate.complete(Unit)
        assertEquals(
            listOf(
                PhoneMessage.SendKey("guid-browser", KeyEvent(Key.Text("a"))),
                PhoneMessage.BrowserTap(1, 10.0, 20.0),
                PhoneMessage.SendKey("guid-browser", x),
            ),
            rig.sent(),
        )
    }

    @Test
    fun queuedScrollStepsMergeUntilSomethingElseIsQueued() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.driver.back()
        runCurrent()
        // Three ticks' steps queue behind the held back: one merged scroll.
        rig.driver.scroll(0f, 3f, 1f, 1f, 7f, 8f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        rig.driver.scroll(0f, 4f, 1f, 1f, 7f, 8f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        rig.driver.scroll(2f, 5f, 1f, 1f, 7f, 8f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        // A tap queued after it ends the merge; a later step at another point is its own.
        rig.driver.tap(1.0, 1.0)
        rig.driver.scroll(0f, 6f, 1f, 1f, 7f, 8f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        rig.driver.scroll(0f, 1f, 1f, 1f, 9f, 9f)
        advanceTimeBy(40.milliseconds)
        gate.complete(Unit)
        assertEquals(
            listOf(
                PhoneMessage.BrowserGoBack(1),
                PhoneMessage.BrowserScroll(1, 7.0, 8.0, 2.0, 12.0),
                PhoneMessage.BrowserTap(1, 1.0, 1.0),
                PhoneMessage.BrowserScroll(1, 7.0, 8.0, 0.0, 6.0),
                PhoneMessage.BrowserScroll(1, 9.0, 9.0, 0.0, 1.0),
            ),
            rig.sent(),
        )
    }

    @Test
    fun inputActiveFollowsConfigAndRevision() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertTrue(rig.driver.inputActive.value)
        rig.active.value = null
        runCurrent()
        assertFalse(rig.driver.inputActive.value)
        rig.active.value = browserStream(1, 3)
        runCurrent()
        assertTrue(rig.driver.inputActive.value)
        rig.macRevision.value = 14
        runCurrent()
        assertFalse(rig.driver.inputActive.value)
    }

    @Test
    fun inputForAStreamNoLongerActiveIsDiscarded() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.driver.back()
        rig.driver.tap(1.0, 1.0)
        rig.driver.reload()
        runCurrent()
        // The back is in flight; the tap and reload are queued when stream 2 replaces stream 1.
        rig.active.value = browserStream(1, 2)
        gate.complete(Unit)
        assertEquals(listOf(PhoneMessage.BrowserGoBack(1)), rig.sent())
        rig.driver.reload()
        assertEquals(PhoneMessage.BrowserReload(2), rig.sent().last())
        assertTrue(rig.logs.any { it.contains("not active") })
    }

    @Test
    fun scrollTravelDoesNotCrossStreams() = runTest {
        val rig = Rig(this)
        rig.driver.scroll(0f, 30f, 1f, 1f, 0f, 0f)
        rig.active.value = browserStream(2, 1)
        rig.link.currentLink = 2
        advanceTimeBy(100.milliseconds)
        assertEquals("stream 1's drag is not sent to link 2's stream", emptyList<PhoneMessage>(), rig.sent())
        rig.driver.scroll(0f, 4f, 1f, 1f, 0f, 0f)
        advanceTimeBy(40.milliseconds)
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 0.0, 0.0, 0.0, 4.0)), rig.sent())
        assertEquals(listOf(2L), rig.link.sent.map { it.onLink })
    }

    @Test
    fun failedSendIsLoggedAndTheQueueGoesOn() = runTest {
        val rig = Rig(this)
        rig.link.currentLink = 7
        rig.driver.back()
        runCurrent()
        rig.link.currentLink = 1
        rig.driver.forward()
        assertEquals(listOf(PhoneMessage.BrowserGoForward(1)), rig.sent())
        assertTrue(rig.logs.any { it.contains("BrowserGoBack") && it.contains("failed") })
    }

    @Test
    fun logsCarryNoPageContent() = runTest {
        val rig = Rig(this)
        rig.link.currentLink = 7
        rig.driver.load("SENTINEL-typed-address")
        runCurrent()
        assertTrue(rig.logs.isNotEmpty())
        assertTrue(rig.logs.none { it.contains("SENTINEL") })
    }

    private fun TestScope.exerciseEverything(rig: Rig) {
        rig.driver.tap(1.0, 2.0)
        rig.driver.scroll(0f, 50f, 1f, 1f, 0f, 0f)
        rig.driver.fling(0f, 3000f, 1f, 1f, 0f, 0f)
        rig.driver.back()
        rig.driver.forward()
        rig.driver.reload()
        rig.driver.stop()
        rig.driver.load("example")
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
    }

    /*
     * Scrolling with keys: a 1000 px tall page at scale 2, so a line is 72 px (36 CSS px) and a
     * page 920 px (1000 less a 40 CSS px overlap). Drags at 1 image px per view px.
     */
    private fun keysRig(test: TestScope): Rig {
        val rig = Rig(test, scrollKeys = MutableStateFlow(true))
        rig.active.value = browserStream(1, 1).let { it.copy(config = it.config!!.copy(pixelHeight = 1000, scale = 2.0)) }
        return rig
    }

    private fun Rig.keys(): List<SpecialKey> = sent().mapNotNull { ((it as? PhoneMessage.SendKey)?.event?.key as? Key.Special)?.key }

    /** How far [keys] scroll the keys rig's page, encoded px (positive: content down, as a drag's deltaY). */
    private fun travelOf(keys: List<SpecialKey>): Double = keys.sumOf {
        when (it) {
            SpecialKey.UP -> 72.0
            SpecialKey.DOWN -> -72.0
            SpecialKey.PAGE_UP -> 920.0
            SpecialKey.PAGE_DOWN -> -920.0
            else -> 0.0
        }
    }

    @Test
    fun withKeysADragSendsArrowsNotScrolls() = runTest {
        val rig = keysRig(this)
        // 50 px of content moving up: more than half a line, so one Down (22 px ahead of the finger).
        rig.driver.scroll(0f, -50f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        assertTrue(rig.scrolls().isEmpty())
        assertEquals("sent to the page's session", listOf("guid-browser"), rig.sent().map { (it as PhoneMessage.SendKey).sessionGuid })
        // 10 px more the same way is paid out of the overshoot: nothing.
        rig.driver.scroll(0f, -10f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        // 30 px back up: 42 px owed up, short of the 54 px (three quarters of a line) a reversal needs.
        rig.driver.scroll(0f, 30f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        // 12 px more: one Up; the finger is 18 px up from where it began, the page back at 0.
        rig.driver.scroll(0f, 12f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN, SpecialKey.UP), rig.keys())
    }

    @Test
    fun withKeysASlowDragScrollsAsFarAsTheFinger() = runTest {
        val rig = keysRig(this)
        // 1500 px in 3 px steps every 16 ms (about 190 px/s: slower than the keys can go).
        repeat(500) {
            rig.driver.scroll(0f, -3f, 1f, 1f, 10f, 20f)
            advanceTimeBy(16.milliseconds)
            val behind = -3.0 * (it + 1) - travelOf(rig.keys())
            assertTrue("after ${3 * (it + 1)} px the keys are ${-behind} px off", abs(behind) <= 36.0 + 3.0 * 8)
        }
        advanceTimeBy(2_000.milliseconds)
        val keys = rig.keys()
        assertTrue("$keys", keys.all { it == SpecialKey.DOWN })
        // Within half a line of the finger, never twice as far (the old half-line floor sent 2x).
        assertEquals(-1500.0, travelOf(keys), 36.0)
    }

    @Test
    fun withKeysALongDragPagesAtMostOneKeyPerInterval() = runTest {
        val rig = keysRig(this)
        // 2000 px down at once: capped at two pages, sent as two Page Downs 120 ms apart, and nothing after.
        rig.driver.scroll(0f, -2000f, 1f, 1f, 10f, 20f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        assertEquals(listOf(SpecialKey.PAGE_DOWN), rig.keys())
        advanceTimeBy(80.milliseconds)
        runCurrent()
        assertEquals("not before the interval", 1, rig.keys().size)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        assertEquals(listOf(SpecialKey.PAGE_DOWN, SpecialKey.PAGE_DOWN), rig.keys())
        advanceTimeBy(5_000.milliseconds)
        assertEquals(2, rig.keys().size)
        assertTrue(rig.scrolls().isEmpty())
    }

    @Test
    fun withKeysAFlingCoastsInKeysThatThinOutAndStops() = runTest {
        val rig = keysRig(this)
        rig.driver.fling(0f, -3000f, 1f, 1f, 10f, 20f, densityPxPerDp = 1f)
        // When each key went out, sampled every tick.
        val times = mutableListOf<Long>()
        repeat(100) {
            advanceTimeBy(40.milliseconds)
            runCurrent()
            while (times.size < rig.keys().size) times += currentTime
        }
        val keys = rig.keys()
        assertTrue("$keys", keys.isNotEmpty() && keys.all { it == SpecialKey.PAGE_DOWN || it == SpecialKey.DOWN })
        // The curve's 1309 px, within half a line.
        assertEquals(-FlingCurve(3000f, 1f).distancePx.toDouble(), travelOf(keys), 36.0)
        // Evenly paced, never a burst: 120 ms apart while travel is owed, further apart (never closer) as it slows.
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue("$gaps", gaps.all { it >= DEFAULT_KEY_INTERVAL_MILLIS })
        assertTrue("$gaps", gaps.zipWithNext().all { (a, b) -> b >= a })
        advanceTimeBy(10_000.milliseconds)
        assertEquals(keys.size, rig.keys().size)
    }

    @Test
    fun withKeysASidewaysGestureScrollsSidewaysAndAVerticalOneLocksToItsAxis() = runTest {
        val rig = keysRig(this)
        // A sideways swipe locks to x: it scrolls sideways and sends no keys for its small dy.
        rig.driver.touchDown()
        rig.driver.scroll(-300f, 10f, 1f, 1f, 10f, 20f)
        rig.driver.scroll(-300f, 60f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 10.0, 20.0, -600.0, 0.0)), rig.scrolls())
        assertTrue(rig.keys().isEmpty())
        // A vertical swipe with a sideways wobble: no misplaced sideways wheel event.
        rig.driver.touchDown()
        rig.driver.scroll(-3f, -100f, 1f, 1f, 10f, 20f)
        rig.driver.scroll(-30f, -100f, 1f, 1f, 10f, 20f)
        rig.driver.fling(-500f, -50f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(1, rig.scrolls().size)
        assertTrue(rig.keys().isNotEmpty())
    }

    @Test
    fun withKeysASlightlyDiagonalVerticalSwipeLocksToItsSlopTravel() = runTest {
        val rig = keysRig(this)
        // Past the slop the finger had gone 10 px sideways and 8 px up: not twice as wide, so vertical.
        rig.driver.touchDown()
        rig.driver.beginDrag(10f, -8f)
        // Its first events lean sideways, as a thumb's arc does; none of it goes as a sideways wheel.
        rig.driver.scroll(-12f, -5f, 1f, 1f, 10f, 20f)
        rig.driver.scroll(-6f, -60f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertTrue(rig.scrolls().isEmpty())
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        // A swipe more than twice as wide as tall locks sideways.
        rig.driver.touchDown()
        rig.driver.beginDrag(-21f, 10f)
        rig.driver.scroll(-100f, 30f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 10.0, 20.0, -100.0, 0.0)), rig.scrolls())
        assertEquals(1, rig.keys().size)
    }

    @Test
    fun withKeysATapDropsTheBacklog() = runTest {
        val rig = keysRig(this)
        rig.driver.scroll(0f, -2000f, 1f, 1f, 10f, 20f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        assertEquals(listOf(SpecialKey.PAGE_DOWN), rig.keys())
        rig.driver.tap(5.0, 5.0)
        advanceTimeBy(5_000.milliseconds)
        assertEquals("the second page was dropped", listOf(SpecialKey.PAGE_DOWN), rig.keys())
    }

    @Test
    fun withKeysATouchDownStopsAFlingAndItsKeys() = runTest {
        val rig = keysRig(this)
        rig.driver.fling(0f, -8000f, 1f, 1f, 10f, 20f, densityPxPerDp = 1f)
        advanceTimeBy(200.milliseconds)
        runCurrent()
        val before = rig.keys()
        assertTrue(before.isNotEmpty())
        // The finger comes down to flick back the other way: nothing more goes the old way.
        rig.driver.touchDown()
        rig.driver.scroll(0f, 200f, 1f, 1f, 10f, 20f)
        advanceTimeBy(5_000.milliseconds)
        val after = rig.keys().drop(before.size)
        assertTrue("after the touch: $after", after.isNotEmpty() && after.all { it == SpecialKey.UP || it == SpecialKey.PAGE_UP })
        assertEquals(200.0, travelOf(after), 36.0)
    }

    @Test
    fun withKeysATouchDownKeepsASmallRemainder() = runTest {
        val rig = keysRig(this)
        // 20 px: short of half a line, owed nothing; a new touch keeps it, so two slow strokes add up.
        rig.driver.scroll(0f, -20f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        rig.driver.touchDown()
        rig.driver.scroll(0f, -20f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
    }

    @Test
    fun afterATapDragsScrollRawUntilThePageAnswers() = runTest {
        val rig = keysRig(this)
        rig.driver.tap(500.0, 300.0)
        // A swipe straight after the tap: raw scroll, not arrows into a field the tap may have focused.
        rig.driver.touchDown()
        rig.driver.scroll(0f, -100f, 1f, 1f, 10f, 20f)
        advanceTimeBy(80.milliseconds)
        runCurrent()
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 10.0, 20.0, 0.0, -100.0)), rig.scrolls())
        assertTrue(rig.keys().isEmpty())
        // The page says a field is editing: still raw.
        rig.editing.value = true
        runCurrent()
        rig.driver.scroll(0f, -100f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(2, rig.scrolls().size)
        assertTrue(rig.keys().isEmpty())
        // Tapped out of the field: keys at once, no wait.
        rig.driver.tap(500.0, 900.0)
        rig.editing.value = false
        runCurrent()
        rig.driver.touchDown()
        rig.driver.scroll(0f, -100f, 1f, 1f, 10f, 20f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        assertEquals(2, rig.scrolls().size)
    }

    @Test
    fun afterATapThatChangesNothingKeysResumeAfterTheHold() = runTest {
        val rig = keysRig(this)
        rig.driver.tap(500.0, 300.0)
        advanceTimeBy(DEFAULT_TAP_KEY_HOLD_MILLIS.milliseconds)
        rig.driver.touchDown()
        rig.driver.scroll(0f, -100f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(SpecialKey.DOWN), rig.keys())
        assertTrue(rig.scrolls().isEmpty())
    }

    @Test
    fun aQueuedKeyIsWithdrawnWhenThePageStartsEditing() = runTest {
        val rig = keysRig(this)
        val gate = CompletableDeferred<Unit>()
        rig.link.sendGate = gate
        rig.driver.back()
        runCurrent()
        // A key queued behind the held back; then the page reports a field editing before it is sent.
        rig.driver.scroll(0f, -100f, 1f, 1f, 10f, 20f)
        advanceTimeBy(40.milliseconds)
        runCurrent()
        rig.editing.value = true
        gate.complete(Unit)
        assertEquals(listOf(PhoneMessage.BrowserGoBack(1)), rig.sent())
        assertTrue(rig.logs.any { it.contains("withdrew a scrolling key") && it.contains("editing") })
    }

    @Test
    fun whileEditingOrSwitchedOffADragScrollsAsBefore() = runTest {
        val rig = keysRig(this)
        rig.editing.value = true
        rig.driver.scroll(0f, -50f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(listOf(PhoneMessage.BrowserScroll(1, 10.0, 20.0, 0.0, -50.0)), rig.scrolls())
        rig.editing.value = false
        rig.scrollKeys.value = false
        rig.driver.scroll(0f, -50f, 1f, 1f, 10f, 20f)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(2, rig.scrolls().size)
        assertTrue(rig.keys().isEmpty())
    }

    private companion object {
        /** The terminal fixture's config for stream 1. */
        fun terminalConfig(): StreamConfig =
            (decodeStreamConfig(FakeMac.streamConfigPayload(1, emptyMap()).getValue("streamConfig")) as MacMessage.StreamConfigMessage).config

        fun browserStream(linkId: Long, streamID: Long) = ActiveStream(
            linkId,
            streamID,
            "guid-browser",
            1,
            terminalConfig().copy(streamID = streamID, isBrowser = true),
            null,
        )
    }
}
