package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `resizeActiveSessionForLegibility` (`AppModel.swift:5122-5153`), with the decoder caps. */
class FitToPhoneTest {
    private fun config(
        cell: CellGeometry? = CellGeometry(16.0, 32.0, 0.0, 0.0),
        canResize: Boolean? = true,
        isBrowser: Boolean = false,
        pixelWidth: Int = 1280,
        pixelHeight: Int = 800,
        columns: Int = 80,
        rows: Int = 25,
    ): StreamConfig = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = byteArrayOf(0, 0), pixelWidth = pixelWidth,
        pixelHeight = pixelHeight, scale = 2.0, columns = columns, rows = rows, cellGeometry = cell,
        firstAbsLine = 0, totalLines = rows, canResize = canResize, altScreen = false,
        scrollWheelReporting = false, isBrowser = isBrowser,
    )

    @Test
    fun portraitUsesSixtyColumns() {
        // 1080 / 60 = 18 px per cell, 18 × 32/16 = 36 px tall, floor(2000 / 36) = 55.
        assertEquals(Grid(60, 55), fitGrid(1080, 2000, config(), null))
    }

    @Test
    fun landscapeUsesOneHundredTwentyColumns() {
        // 2400 / 120 = 20 px per cell, 40 px tall, floor(1000 / 40) = 25.
        assertEquals(Grid(120, 25), fitGrid(2400, 1000, config(), null))
        // A square viewport is not wider than tall: portrait.
        assertEquals(60, fitGrid(1000, 1000, config(), null)?.columns)
    }

    @Test
    fun fallsBackToImageOverGrid() {
        // No cellGeometry: 1280/80 = 16 by 800/25 = 32, the same aspect as above.
        assertEquals(Grid(60, 55), fitGrid(1080, 2000, config(cell = null), null))
        // A degenerate reported geometry falls back too, as iOS.
        assertEquals(Grid(60, 55), fitGrid(1080, 2000, config(cell = CellGeometry(0.0, 32.0, 0.0, 0.0)), null))
        // Nothing to derive a cell from: no grid.
        assertNull(fitGrid(1080, 2000, config(cell = null, columns = 0, rows = 0), null))
        assertNull(fitGrid(1080, 2000, config(cell = null, pixelWidth = 0), null))
    }

    @Test
    fun nullForBrowser() {
        assertNull(fitGrid(1080, 2000, config(isBrowser = true), null))
        assertNull(fitGrid(1080, 2000, config(isBrowser = true, cell = null, columns = 0, rows = 0), 4096 to 2304))
    }

    @Test
    fun nullWhenCannotResize() {
        assertNull(fitGrid(1080, 2000, config(canResize = false), null))
        assertNull(fitGrid(2400, 1000, config(canResize = false), 4096 to 2304))
    }

    @Test
    fun nullWhenResizeCapabilityAbsent() {
        assertNull(fitGrid(1080, 2000, config(canResize = null), null))
    }

    @Test
    fun nullForAnEmptyViewport() {
        assertNull(fitGrid(0, 2000, config(), null))
        assertNull(fitGrid(1080, 0, config(), null))
    }

    @Test
    fun fitGridCapsToDecoderLimit() {
        val cell = CellGeometry(40.0, 80.0, 20.0, 20.0)
        // Landscape: 120 columns capped to floor((4096 − 40) / 40) = 101; rows from 101 columns are
        // floor(1900 / (2000/101 × 2)) = 47, capped to floor((2304 − 40) / 80) = 28.
        val grid = fitGrid(2000, 1900, config(cell = cell), 4096 to 2304)
        assertEquals(Grid(101, 28), grid)
        checkNotNull(grid)
        assertTrue(grid.columns * 40 + 2 * 20 <= 4096)
        assertTrue(grid.rows * 80 + 2 * 20 <= 2304)
    }

    @Test
    fun fitGridCapsColumnsBeforeRows() {
        val cell = CellGeometry(80.0, 160.0, 0.0, 0.0)
        // 60 columns capped to floor(4096 / 80) = 51; rows from 51: floor(2000 / (1080/51 × 2)) = 47, not 55.
        assertEquals(Grid(51, 47), fitGrid(1080, 2000, config(cell = cell), 4096 to 8192))
    }

    @Test
    fun capsNeverGoBelowOne() {
        val cell = CellGeometry(40.0, 80.0, 20.0, 20.0)
        assertEquals(Grid(1, 1), fitGrid(1080, 2000, config(cell = cell), 10 to 10))
    }

    @Test
    fun fitSendsResizeOnTheActiveStreamsLink() = runTest {
        val link = ScriptedStreamLink().apply { currentLink = 3 }
        val stream = ActiveStream(3, 7, "guid-alpha", 1, config(), 0)
        assertEquals(Grid(60, 55), fitToPhone(link, stream, 1080, 2000, null))
        val sent = link.sent.single()
        assertEquals(PhoneMessage.ResizeSession("guid-alpha", 60, 55), sent.message)
        assertEquals(3L, sent.onLink)
    }

    @Test
    fun fitSendsNothingWithoutAGrid() = runTest {
        val link = ScriptedStreamLink()
        assertNull(fitToPhone(link, ActiveStream(1, 1, "guid-alpha", 1, null, null), 1080, 2000, null))
        assertNull(fitToPhone(link, ActiveStream(1, 1, "guid-alpha", 1, config(canResize = false), 0), 1080, 2000, null))
        assertNull(fitToPhone(link, ActiveStream(1, 1, "guid-alpha", 1, config(isBrowser = true), 0), 1080, 2000, null))
        assertTrue(link.sent.isEmpty())
    }
}
