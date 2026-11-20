package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Fit to phone while its toggle is on ([AutoFitter]), in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoFitterTest {
    private fun config(
        columns: Int = 80,
        rows: Int = 25,
        canResize: Boolean? = true,
        isBrowser: Boolean = false,
        streamID: Long = 1,
        generation: Long = 1,
    ) = StreamConfig(
        streamID = streamID, generationId = generation, codecExtradata = byteArrayOf(0, 0), pixelWidth = columns * 16,
        pixelHeight = rows * 32, scale = 2.0, columns = columns, rows = rows,
        cellGeometry = if (isBrowser) null else CellGeometry(16.0, 32.0, 0.0, 0.0), firstAbsLine = 0, totalLines = rows,
        canResize = canResize, altScreen = false, scrollWheelReporting = false, isBrowser = isBrowser,
    )

    private class Rig(test: TestScope, on: Boolean = true) {
        val link = ScriptedStreamLink()
        val enabled = MutableStateFlow(on)
        val active = MutableStateFlow<ActiveStream?>(null)
        val logs = CopyOnWriteArrayList<String>()
        val notices = CopyOnWriteArrayList<FitNotice>()
        val fitter = AutoFitter(
            link = link,
            enabled = enabled,
            active = active,
            decoderMax = { 4096 to 2304 },
            sizeSupported = null,
            scope = test.backgroundScope,
            settleDelay = SETTLE,
            resultTimeout = RESULT,
            log = { logs += it },
        )

        init {
            test.backgroundScope.launch { fitter.notices.collect { notices += it } }
        }

        fun open(config: StreamConfig?, guid: String = GUID, linkId: Long = 1) {
            link.currentLink = linkId
            active.value = ActiveStream(linkId, config?.streamID ?: 1, guid, 1, config, 0)
        }

        /** The Mac applied a resize: a new generation with this grid. */
        fun macGrid(columns: Int, rows: Int) {
            val stream = checkNotNull(active.value)
            val previous = checkNotNull(stream.config)
            active.value = stream.copy(config = previous.copy(columns = columns, rows = rows, generationId = previous.generationId + 1))
        }

        fun view(width: Int, height: Int, guid: String = GUID) = fitter.setViewport(FitViewport(guid, width, height))

        fun resizes(): List<PhoneMessage.ResizeSession> = link.sent.mapNotNull { it.message as? PhoneMessage.ResizeSession }
    }

    @Test
    fun openingALiveViewFitsOnceTheViewportSettles() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE - 1.milliseconds)
        runCurrent()
        assertEquals("not before the viewport held still", emptyList<PhoneMessage>(), rig.resizes())
        advanceTimeBy(2.milliseconds)
        runCurrent()
        // 1080 / 60 = 18 px cells, 36 px tall: floor(2000 / 36) = 55.
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 60, 55)), rig.resizes())
        assertEquals(1L, rig.link.sent.single().onLink)
        // The Mac applies it: nothing more.
        rig.macGrid(60, 55)
        advanceTimeBy(RESULT * 2)
        assertEquals(1, rig.resizes().size)
        assertEquals(emptyList<FitNotice>(), rig.notices)
        assertTrue(rig.logs.toString(), rig.logs.any { it == "fit to phone for $GUID: fit requested 60x55, got 60x55 (was 80x25)" })
    }

    @Test
    fun aStreamThatGoesLiveLaterIsFitted() = runTest {
        val rig = Rig(this)
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        rig.open(null) // started, no config yet
        runCurrent()
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        rig.open(config())
        runCurrent()
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 60, 55)), rig.resizes())
    }

    @Test
    fun rotationRefitsOnceAfterTheLayoutSettles() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        rig.macGrid(60, 55)
        runCurrent()
        // A rotation's layout passes, each within the settle delay of the last.
        for ((w, h) in listOf(1080 to 1500, 1500 to 1300, 1900 to 1080, 2000 to 1080)) {
            rig.view(w, h)
            advanceTimeBy(SETTLE / 4)
        }
        runCurrent()
        assertEquals(1, rig.resizes().size)
        advanceTimeBy(SETTLE)
        runCurrent()
        // Landscape: 2000 / 120 = 16.67 px cells, 33.3 px tall: floor(1080 / 33.3) = 32.
        assertEquals(PhoneMessage.ResizeSession(GUID, 120, 32), rig.resizes().last())
        assertEquals(2, rig.resizes().size)
    }

    @Test
    fun theKeyboardDoesNotRefit() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        rig.macGrid(60, 55)
        runCurrent()
        // The view measures its fit viewport with the keyboard hidden ([fitViewport] takes no IME
        // inset), so the keyboard showing and hiding reports the same viewport again.
        repeat(3) {
            rig.view(1080, 2000)
            advanceTimeBy(SETTLE / 2)
        }
        // Frames moving the live top, and a config resent at the same grid, do not refit either.
        rig.active.value = rig.active.value?.copy(liveTop = 42)
        rig.macGrid(60, 55)
        advanceTimeBy(SETTLE * 4)
        assertEquals(1, rig.resizes().size)
    }

    @Test
    fun cannotResizeOrBrowserSendsNothingAndSaysWhy() = runTest {
        val rig = Rig(this)
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        rig.open(config(canResize = false))
        runCurrent()
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        assertEquals(listOf(FitNotice(GUID, FIT_NOTICE_CANNOT_RESIZE)), rig.notices)
        // Once per session while on.
        rig.open(config(canResize = false, streamID = 2))
        rig.open(config(canResize = null, streamID = 3))
        runCurrent()
        assertEquals(1, rig.notices.size)
        // A browser: nothing at all.
        rig.view(1080, 2000, guid = "guid-web")
        advanceTimeBy(SETTLE * 2)
        rig.open(config(columns = 0, rows = 0, isBrowser = true), guid = "guid-web")
        advanceTimeBy(RESULT * 2)
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        assertEquals(1, rig.notices.size)
    }

    @Test
    fun aGridAlreadyAtTheTargetIsNotResent() = runTest {
        val rig = Rig(this)
        rig.open(config(columns = 60, rows = 55))
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        // Reopened, reconnected: still equal.
        rig.open(config(columns = 60, rows = 55, streamID = 2))
        rig.open(config(columns = 60, rows = 55, streamID = 3), linkId = 2)
        advanceTimeBy(SETTLE * 2)
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
    }

    @Test
    fun offDoesNothingAndTurningOffUndoesNothing() = runTest {
        val rig = Rig(this, on = false)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 4)
        rig.view(2000, 1080)
        advanceTimeBy(SETTLE * 4)
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        // On, then off with the fit's result outstanding: nothing more is sent, no notice comes.
        rig.enabled.value = true
        runCurrent()
        assertEquals(1, rig.resizes().size)
        rig.enabled.value = false
        rig.macGrid(120, 20) // capped: would be corrected while on
        advanceTimeBy(RESULT * 2)
        assertEquals(1, rig.resizes().size)
        assertEquals(emptyList<FitNotice>(), rig.notices)
    }

    @Test
    fun turningOnWhileAViewIsOpenFitsAtOnce() = runTest {
        val rig = Rig(this, on = false)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        rig.enabled.value = true
        runCurrent()
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 60, 55)), rig.resizes())
        // Off and on again after the owner resized the window on the Mac: fits again.
        rig.macGrid(60, 55)
        runCurrent()
        rig.enabled.value = false
        runCurrent()
        rig.macGrid(100, 30)
        runCurrent()
        rig.enabled.value = true
        runCurrent()
        assertEquals(2, rig.resizes().size)
        // A cannot-resize session when it turns on: the toggle's own hint says so, not a notice.
        rig.enabled.value = false
        rig.open(config(canResize = false, streamID = 9))
        runCurrent()
        rig.enabled.value = true
        runCurrent()
        assertEquals(emptyList<FitNotice>(), rig.notices)
    }

    @Test
    fun aCappedFitGetsOneCorrectiveStep() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE * 2)
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 60, 61)), rig.resizes())
        // The Mac's screen holds 45 rows: 60×45, wider than the phone.
        rig.macGrid(60, 45)
        runCurrent()
        // floor(45 × 1080 / 2200 × 32 / 16) = 44 columns, the same rows.
        assertEquals(PhoneMessage.ResizeSession(GUID, 44, 45), rig.resizes().last())
        assertEquals(2, rig.resizes().size)
        // Capped again (never expected): no third step.
        rig.macGrid(44, 40)
        advanceTimeBy(RESULT * 2)
        assertEquals(2, rig.resizes().size)
        assertEquals(emptyList<FitNotice>(), rig.notices)
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: fit requested 60x61, got 60x45 (was 80x25)"))
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: corrective sent 44x45 (was 60x45, viewport 1080x2200)"))
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: corrective requested 44x45, got 44x40 (was 60x45)"))
    }

    @Test
    fun aCorrectedSessionIsNotRefittedWhenReopened() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE * 2)
        rig.macGrid(60, 45)
        runCurrent()
        rig.macGrid(44, 45)
        runCurrent()
        assertEquals(2, rig.resizes().size)
        // Reopened (a new stream) and reconnected (a new link): the Mac's grid is where the last
        // fit to the same target ended, so nothing loops.
        rig.open(config(columns = 44, rows = 45, streamID = 2))
        runCurrent()
        rig.open(config(columns = 44, rows = 45, streamID = 3), linkId = 2)
        advanceTimeBy(RESULT * 2)
        assertEquals(2, rig.resizes().size)
        // Another session is fitted on its own.
        rig.view(1080, 2200, guid = "guid-beta")
        rig.open(config(streamID = 4), guid = "guid-beta", linkId = 2)
        advanceTimeBy(SETTLE * 2)
        assertEquals(PhoneMessage.ResizeSession("guid-beta", 60, 61), rig.resizes().last())
        assertEquals(2L, rig.link.sent.last().onLink)
    }

    @Test
    fun aMacThatDoesNotResizeIsReportedOnceWithoutALoop() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(1, rig.resizes().size)
        advanceTimeBy(RESULT - 1.milliseconds)
        runCurrent()
        assertEquals(emptyList<FitNotice>(), rig.notices)
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(listOf(FitNotice(GUID, FIT_NOTICE_NO_CHANGE)), rig.notices)
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: fit requested 60x55, the grid stayed 80x25 for $RESULT"))
        // Reopened at the same grid: not sent again.
        rig.open(config(streamID = 2))
        advanceTimeBy(RESULT * 2)
        assertEquals(1, rig.resizes().size)
        assertEquals(1, rig.notices.size)
    }

    @Test
    fun aLinkThatEndedSendsNothingAndAViewThatLeftIsNotFitted() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.link.currentLink = 2 // the stream's link 1 has ended
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE * 2)
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: fit 60x55 not sent: CompanionRequestException"))
        // The view left before the next stream: nothing to fit to.
        rig.fitter.clearViewport(GUID)
        rig.open(config(streamID = 2), linkId = 2)
        advanceTimeBy(SETTLE * 2)
        assertEquals(emptyList<PhoneMessage>(), rig.resizes())
        // Another view's leaving does not clear this one's viewport.
        rig.view(1080, 2000)
        rig.fitter.clearViewport("guid-other")
        advanceTimeBy(SETTLE * 2)
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 60, 55)), rig.resizes())
    }

    @Test
    fun aLateConfigOfAnEarlierFitIsNotTakenAsTheResult() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(2000, 1080)
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(listOf(PhoneMessage.ResizeSession(GUID, 120, 32)), rig.resizes())
        // A quick rotation before the Mac's landscape config arrives.
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 60, 61), rig.resizes().last())
        // The landscape fit's late config (capped to 29 rows): not the portrait's result, no correction.
        rig.macGrid(120, 29)
        runCurrent()
        assertEquals(2, rig.resizes().size)
        // The portrait result: corrected from it, with the frozen cell.
        rig.macGrid(60, 45)
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 44, 45), rig.resizes().last())
        assertEquals(3, rig.resizes().size)
        assertTrue(rig.logs.toString(), rig.logs.none { "got 120x29" in it })
    }

    @Test
    fun anIntermediateOrForeignGridIsNotAResult() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE)
        runCurrent()
        // An intermediate grid (rows first): kept waiting.
        rig.macGrid(80, 45)
        runCurrent()
        assertEquals(1, rig.resizes().size)
        rig.macGrid(60, 45)
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 44, 45), rig.resizes().last())
        // A resize from the Mac (or another phone) while the corrective step waits: never its result.
        rig.macGrid(100, 30)
        advanceTimeBy(RESULT + 1.milliseconds)
        runCurrent()
        assertEquals(2, rig.resizes().size)
        assertEquals("the grid moved: no notice", emptyList<FitNotice>(), rig.notices)
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: corrective requested 44x45, no matching result in $RESULT; the grid is 100x30"))
        // Kept: the same stream does not fight it.
        rig.macGrid(100, 30)
        advanceTimeBy(RESULT * 2)
        assertEquals(2, rig.resizes().size)
    }

    @Test
    fun refitFitsAgainNowEvenAfterACappedOutcome() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE)
        runCurrent()
        rig.macGrid(60, 45)
        runCurrent()
        rig.macGrid(44, 45)
        runCurrent()
        assertEquals(2, rig.resizes().size)
        rig.fitter.refit(GUID)
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 60, 61), rig.resizes().last())
        // Another session's refit, or one while off, sends nothing.
        rig.fitter.refit("guid-other")
        rig.enabled.value = false
        runCurrent()
        rig.fitter.refit(GUID)
        runCurrent()
        assertEquals(3, rig.resizes().size)
    }

    @Test
    fun atMostTwoFitsPerViewportPerStream() = runTest {
        val rig = Rig(this)
        rig.open(config())
        val portrait = 1080 to 2000
        val landscape = 2000 to 1080
        for ((w, h) in listOf(portrait, landscape, portrait, landscape, portrait)) {
            rig.view(w, h)
            advanceTimeBy(SETTLE)
            runCurrent()
            // The Mac applies whatever was asked.
            rig.resizes().last().let { rig.macGrid(it.columns, it.rows) }
            runCurrent()
        }
        assertEquals(4, rig.resizes().size)
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: not sending 60x55: 2 fits already sent for viewport 1080x2000 on this stream"))
        // A new stream starts counting again.
        rig.open(config(columns = 120, rows = 32, streamID = 2))
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 60, 55), rig.resizes().last())
    }

    @Test
    fun aFontSizeChangeOnTheMacIsNotFollowed() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE)
        runCurrent()
        rig.macGrid(60, 55)
        runCurrent()
        // A bigger font: the cell is 20×40 now; the frozen 16×32 cell keeps the target 60x55.
        val stream = checkNotNull(rig.active.value)
        rig.active.value = stream.copy(config = stream.config?.copy(cellGeometry = CellGeometry(20.0, 40.0, 0.0, 0.0), generationId = 9, rows = 44))
        advanceTimeBy(RESULT * 2)
        assertEquals(1, rig.resizes().size)
        // Even a viewport change uses the frozen cell: 1080x2200 is 61 rows at 16×32.
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(PhoneMessage.ResizeSession(GUID, 60, 61), rig.resizes().last())
    }

    @Test
    fun theStreamEndingDropsTheWaitingFitAndItsTimer() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE)
        runCurrent()
        rig.active.value = null
        advanceTimeBy(RESULT * 2)
        assertEquals("no 'did not resize' for a stream that went", emptyList<FitNotice>(), rig.notices)
        assertTrue(rig.logs.toString(), rig.logs.any { "dropped the waiting 60x55" in it })
        // The view leaving does the same.
        rig.open(config(streamID = 2))
        runCurrent()
        assertEquals(2, rig.resizes().size)
        rig.fitter.clearViewport(GUID)
        advanceTimeBy(RESULT * 2)
        assertEquals(emptyList<FitNotice>(), rig.notices)
    }

    @Test
    fun aResultWhoseConfigCannotResizeIsStillLogged() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2200)
        advanceTimeBy(SETTLE)
        runCurrent()
        // The window went full screen as the resize landed.
        val stream = checkNotNull(rig.active.value)
        rig.active.value = stream.copy(config = stream.config?.copy(columns = 60, rows = 45, generationId = 2, canResize = false))
        runCurrent()
        assertEquals("no correction for a window that cannot resize", 1, rig.resizes().size)
        assertTrue(rig.logs.toString(), rig.logs.contains("fit to phone for $GUID: fit requested 60x61, got 60x45 (was 80x25) (canResize false)"))
    }

    @Test
    fun turningOnAgainRetriesAfterAMacThatDidNotResize() = runTest {
        val rig = Rig(this)
        rig.open(config())
        rig.view(1080, 2000)
        advanceTimeBy(SETTLE)
        runCurrent()
        advanceTimeBy(RESULT * 2)
        assertEquals(1, rig.notices.size)
        rig.open(config(streamID = 2))
        runCurrent()
        assertEquals("remembered while on", 1, rig.resizes().size)
        rig.enabled.value = false
        runCurrent()
        rig.enabled.value = true
        runCurrent()
        assertEquals(2, rig.resizes().size)
    }

    private companion object {
        const val GUID = "guid-alpha"
        val SETTLE = 400.milliseconds
        val RESULT = 4.seconds
    }
}
