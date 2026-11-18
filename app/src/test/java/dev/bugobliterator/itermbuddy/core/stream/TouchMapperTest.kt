package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMapperTest {
    /** 80×25 cells of 16×32 px with 8 px margins left and top: a 1296×816 image. */
    private fun terminal(cell: CellGeometry? = CellGeometry(16.0, 32.0, 8.0, 8.0)) = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = ByteArray(0),
        pixelWidth = 1296, pixelHeight = 816, scale = 2.0, columns = 80, rows = 25,
        cellGeometry = cell, firstAbsLine = 0, totalLines = 25, canResize = true,
        altScreen = false, scrollWheelReporting = false, isBrowser = false,
    )

    @Test
    fun mapsWithMargins() {
        val config = terminal()
        assertEquals(SelectionPoint(0, 0), cellAt(8f, 8f, config, liveTop = 0))
        assertEquals(SelectionPoint(0, 0), cellAt(23.9f, 8f, config, liveTop = 0))
        assertEquals(SelectionPoint(0, 1), cellAt(24f, 8f, config, liveTop = 0))
        assertEquals(SelectionPoint(1, 1), cellAt(24f, 40f, config, liveTop = 0))
    }

    @Test
    fun clampsToGrid() {
        val config = terminal()
        assertEquals("in the left and top margins", SelectionPoint(0, 0), cellAt(2f, 2f, config, liveTop = 0))
        assertEquals("past the right and bottom", SelectionPoint(24, 79), cellAt(5000f, 5000f, config, liveTop = 0))
        assertEquals("off the image above and left", SelectionPoint(0, 0), cellAt(-50f, -50f, config, liveTop = 0))
    }

    @Test
    fun addsLiveTop() {
        assertEquals(SelectionPoint(1000 + 2, 3), cellAt(8f + 16 * 3 + 1, 8f + 32 * 2 + 1, terminal(), liveTop = 1000))
    }

    @Test
    fun nullForBrowserAndMissingGeometry() {
        assertNull(cellAt(100f, 100f, terminal(cell = null), liveTop = 0))
        val browser = terminal(cell = null).copy(columns = 0, rows = 0, isBrowser = true)
        assertNull(cellAt(100f, 100f, browser, liveTop = 0))
        assertNull(cellAt(100f, 100f, terminal().copy(isBrowser = true), liveTop = 0))
        assertNull("no usable cell size", cellAt(100f, 100f, terminal(cell = CellGeometry(0.0, 32.0, 0.0, 0.0)), liveTop = 0))
        assertNull(cellAt(Float.NaN, 100f, terminal(), liveTop = 0))
        assertNull(historyCellAt(5, 100f, terminal(cell = null)))
        assertNull(historyCellAt(5, 100f, browser))
    }

    @Test
    fun historyMapsInTileSpaceWithoutMargins() {
        // A tile has no margins and spans the band's width: 80 columns over the image's 1296 px.
        val config = terminal()
        assertEquals(SelectionPoint(7, 0), historyCellAt(7, 0f, config))
        assertEquals("the video's margin is a column of the tile", SelectionPoint(7, 0), historyCellAt(7, 16.1f, config))
        assertEquals(SelectionPoint(7, 1), historyCellAt(7, 16.3f, config))
        assertEquals(SelectionPoint(7, 40), historyCellAt(7, 648f, config))
        assertEquals(SelectionPoint(7, 79), historyCellAt(7, 1295.9f, config))
    }

    @Test
    fun historyClampsColumns() {
        val config = terminal()
        assertEquals(SelectionPoint(3, 0), historyCellAt(3, -40f, config))
        assertEquals(SelectionPoint(3, 79), historyCellAt(3, 99999f, config))
    }
}
