package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveProblem
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOverlayTest {
    private val session = SessionSummary("guid-alpha", "alpha", "subtitle", isBrowser = false)

    private fun config(canResize: Boolean? = true): StreamConfig = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = byteArrayOf(0, 0), pixelWidth = 5120, pixelHeight = 2880,
        scale = 2.0, columns = 320, rows = 90, cellGeometry = null, firstAbsLine = 0, totalLines = 90,
        canResize = canResize, altScreen = false, scrollWheelReporting = false, isBrowser = false,
    )

    private val tooLarge = LiveViewState.Failed(session, LiveProblem.TooLarge(5120, 2880, 4096, 2304))
    private val tooLargeText = "The Mac window (5120×2880) is too large for this phone's decoder (max 4096×2304)."

    /** Every state and the overlay it must get, with Fit unavailable. */
    @Test
    fun everyStateHasItsText() {
        val expected: List<Pair<LiveViewState, Overlay?>> = listOf(
            LiveViewState.Closed to null,
            LiveViewState.Starting(session) to Overlay("Starting…", null),
            LiveViewState.Live(session, config()) to null,
            LiveViewState.Live(session, config(), stalled = true) to Overlay("Picture stalled", OverlayAction.RETRY),
            LiveViewState.Reconnecting(session) to Overlay("Reconnecting…", null),
            LiveViewState.Paused(session) to null,
            LiveViewState.Ended(session, StreamEndReason.SESSION_CLOSED) to Overlay("Session closed on the Mac", OverlayAction.BACK),
            LiveViewState.Ended(session, StreamEndReason.DATA_LIMIT_REACHED) to
                Overlay("Stream ended: daily data limit reached", OverlayAction.RESTART),
            LiveViewState.Ended(session, StreamEndReason.ERROR) to Overlay("Stream ended", OverlayAction.RESTART),
            LiveViewState.Ended(session, StreamEndReason.UNKNOWN) to Overlay("Stream ended", OverlayAction.RESTART),
            LiveViewState.Ended(session, StreamEndReason.SUPERSEDED) to null,
            LiveViewState.Ended(session, StreamEndReason.STOPPED_BY_CLIENT) to null,
            LiveViewState.Failed(session, LiveProblem.NoDecoder) to Overlay("This phone has no HEVC video decoder", null),
            tooLarge to Overlay("$tooLargeText $MAC_SIDE_ADVICE", null),
            LiveViewState.Failed(session, LiveProblem.StartFailed(retryable = true)) to Overlay("Can't show session", OverlayAction.RETRY),
            LiveViewState.Failed(session, LiveProblem.StartFailed(retryable = false)) to Overlay("Can't show session", OverlayAction.RETRY),
        )
        // Every end reason is listed.
        assertEquals(
            StreamEndReason.entries.toSet(),
            expected.mapNotNull { (it.first as? LiveViewState.Ended)?.reason }.toSet(),
        )
        for ((state, overlay) in expected) assertEquals("$state", overlay, liveOverlay(state, canFit = false))
    }

    @Test
    fun tooLargeOffersFitOnlyWhenResizable() {
        assertEquals(Overlay(tooLargeText, OverlayAction.FIT), liveOverlay(tooLarge, canFit = true))
        assertEquals(Overlay("$tooLargeText $MAC_SIDE_ADVICE", null), liveOverlay(tooLarge, canFit = false))
        // canFit comes from fitGrid: a config that cannot resize, or does not say, gives none.
        for (canResize in listOf(false, null)) {
            val canFit = fitGrid(1080, 2000, config(canResize), 4096 to 2304) != null
            assertFalse(canFit)
            assertEquals(Overlay("$tooLargeText $MAC_SIDE_ADVICE", null), liveOverlay(tooLarge, canFit))
        }
        assertTrue(fitGrid(1080, 2000, config(true), 4096 to 2304) != null)
        // Fit is offered only for TooLarge: other states ignore canFit.
        assertNull(liveOverlay(LiveViewState.Live(session, config()), canFit = true))
        assertEquals(Overlay("This phone has no HEVC video decoder", null), liveOverlay(LiveViewState.Failed(session, LiveProblem.NoDecoder), true))
    }

    @Test
    fun stalledLiveShowsRetry() {
        assertEquals(OverlayAction.RETRY, liveOverlay(LiveViewState.Live(session, config(), stalled = true), canFit = true)?.action)
        assertNull(liveOverlay(LiveViewState.Live(session, config(), stalled = false), canFit = true))
    }

    @Test
    fun videoShowsOnlyWhenLiveWithAFrameOutputSinceTheLastReset() {
        val states = listOf(
            LiveViewState.Closed,
            LiveViewState.Starting(session),
            LiveViewState.Reconnecting(session),
            LiveViewState.Paused(session),
            LiveViewState.Ended(session, StreamEndReason.SESSION_CLOSED),
            LiveViewState.Failed(session, LiveProblem.NoDecoder),
            tooLarge,
        )
        for (state in states) {
            assertFalse("$state", showsVideo(state, framesOutSinceReset = 0))
            assertFalse("$state", showsVideo(state, framesOutSinceReset = 5))
        }
        assertFalse(showsVideo(LiveViewState.Live(session, config()), framesOutSinceReset = 0))
        assertTrue(showsVideo(LiveViewState.Live(session, config()), framesOutSinceReset = 1))
        // A stalled picture is the watched session's own last frame, shown under its label.
        assertTrue(showsVideo(LiveViewState.Live(session, config(), stalled = true), framesOutSinceReset = 1))
    }

    @Test
    fun browserBeforeRevisionFifteenGetsTheNote() {
        val browser = session.copy(isBrowser = true)
        assertEquals("Browser control comes later", browserNote(browser, macRevision = 13, phoneRevision = 15))
        assertEquals("Browser control comes later", browserNote(browser, macRevision = null, phoneRevision = 15))
        // Both sides need 15: this phone speaks 13 until the Browser Messages task.
        assertEquals("Browser control comes later", browserNote(browser, macRevision = 15, phoneRevision = 13))
        assertNull(browserNote(browser, macRevision = 15, phoneRevision = 15))
        assertNull(browserNote(session, macRevision = 13, phoneRevision = 13))
    }

    @Test
    fun shownConfigIsOnlyThisSessions() {
        val other = SessionSummary("guid-bravo", "bravo", "", isBrowser = false)
        val live = config()
        val kept = config(canResize = true).copy(generationId = 2)
        // Live: the state's config.
        assertEquals(live, shownConfig(session, LiveViewState.Live(session, live), ActiveStream(1, 1, session.guid, 1, kept, 0)))
        // Too large: the active stream keeps its config, for Fit.
        assertEquals(kept, shownConfig(session, tooLarge, ActiveStream(1, 1, session.guid, 1, kept, 0)))
        // Never another session's.
        assertNull(shownConfig(session, LiveViewState.Starting(session), ActiveStream(1, 1, other.guid, 1, kept, 0)))
        assertNull(shownConfig(session, LiveViewState.Live(other, live), null))
    }
}
