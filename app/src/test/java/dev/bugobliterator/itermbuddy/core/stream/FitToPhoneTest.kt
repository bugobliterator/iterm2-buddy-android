package dev.bugobliterator.itermbuddy.core.stream

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
    fun correctionNarrowsACappedFitToFillTheViewport() {
        // A tall phone: 1080×2200 at 16×32 cells asks 60 columns × 61 rows; a Mac whose screen
        // holds 45 rows gives 60×45, wider than the phone. 45 × 1080/2200 × 32/16 = 44.18 → 44.
        val requested = fitGrid(1080, 2200, config(), null)
        assertEquals(Grid(60, 61), requested)
        assertEquals(Grid(44, 45), correctedFitGrid(Grid(60, 61), Grid(60, 45), 1080, 2200, config(columns = 60, rows = 45)))
        // The cell comes from the reporting config: no geometry, the image over the grid (16×32 again).
        val derived = config(cell = null, pixelWidth = 960, pixelHeight = 1440, columns = 60, rows = 45)
        assertEquals(Grid(44, 45), correctedFitGrid(Grid(60, 61), Grid(60, 45), 1080, 2200, derived))
    }

    @Test
    fun correctionKeepsAtLeastFortyColumns() {
        // 20 rows fill only 19 columns' width: kept at 40.
        assertEquals(Grid(FIT_MIN_CORRECTED_COLUMNS, 20), correctedFitGrid(Grid(60, 61), Grid(60, 20), 1080, 2200, config()))
        assertEquals(40, FIT_MIN_CORRECTED_COLUMNS)
        // Already at or below 40 columns: nothing narrower to ask for.
        assertNull(correctedFitGrid(Grid(40, 61), Grid(40, 20), 1080, 2200, config()))
    }

    @Test
    fun noCorrectionUnlessRowsWereCapped() {
        // All the rows: nothing to correct.
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 61), 1080, 2200, config()))
        // More rows than asked (never expected): nothing either.
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 70), 1080, 2200, config()))
        // Capped by a row or two (under 5 %): left alone; by 4 rows (6.6 %): corrected.
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 60), 1080, 2200, config()))
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 58), 1080, 2200, config()))
        assertEquals(Grid(55, 57), correctedFitGrid(Grid(60, 61), Grid(60, 57), 1080, 2200, config()))
        // No viewport or no cell: none.
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 45), 0, 2200, config()))
        assertNull(correctedFitGrid(Grid(60, 61), Grid(60, 45), 1080, 2200, config(cell = null, columns = 0, rows = 0)))
    }

    @Test
    fun fitGridStepsRowsThenColumnsDownForAnAreaLimitedDecoder() {
        // Grid(60, 55) at 16×32 is 960×1760 = 1,689,600 px; this decoder takes at most 1,000,000 px.
        val areaLimited = { w: Int, h: Int -> w.toLong() * h <= 1_000_000 }
        // Rows first: 960 × 32 × 32 = 983,040 fits, 33 rows do not.
        assertEquals(Grid(60, 32), fitGrid(1080, 2000, config(), null, areaLimited))
        // Columns only once rows are down to 1: 16 × c × 32 ≤ 10,000 gives c = 19.
        assertEquals(Grid(19, 1), fitGrid(1080, 2000, config(), null, { w, h -> w.toLong() * h <= 10_000 }))
        // Margins count, rounded up: 2 × 0.5 px on each axis.
        val seen = mutableListOf<Pair<Int, Int>>()
        fitGrid(1080, 2000, config(cell = CellGeometry(16.0, 32.0, 0.25, 0.25)), null) { w, h -> seen += w to h; true }
        assertEquals(listOf(961 to 1761), seen)
        // Nothing supported: no grid; a decoder that takes the first grid changes nothing.
        assertNull(fitGrid(1080, 2000, config(), null) { _, _ -> false })
        assertEquals(Grid(60, 55), fitGrid(1080, 2000, config(), 4096 to 2304) { _, _ -> true })
    }
}
