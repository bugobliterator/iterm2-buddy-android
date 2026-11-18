package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollbackLayoutTest {
    @Test
    fun altScreenHasNoHistory() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, totalLines = 2040, rows = 40, lineHeightPx = 10f, altScreen = true)
        assertEquals(0L, layout.historyLines)
        assertEquals(400f, layout.documentHeightPx)
        assertEquals(0f, layout.liveBandTopPx)
        assertEquals(3000L, layout.absLineAt(0f))
    }

    @Test
    fun liveBandSitsBelowHistory() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, totalLines = 2040, rows = 40, lineHeightPx = 10f, altScreen = false)
        assertEquals(2000L, layout.historyLines)
        assertEquals(20_000f, layout.liveBandTopPx)
        assertEquals(20_400f, layout.documentHeightPx)
        assertEquals(500f, layout.tileTopPx(1050))
        assertEquals(3000L, layout.absLineAt(layout.liveBandTopPx))
        assertEquals("clamped to the last live row", 3039L, layout.absLineAt(1e9f))
    }

    @Test
    fun absLineAtTopIsFirstAbsLine() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, totalLines = 2040, rows = 40, lineHeightPx = 12.5f, altScreen = false)
        assertEquals(1000L, layout.absLineAt(0f))
        assertEquals(1000L, layout.absLineAt(-50f))
        assertEquals(1001L, layout.absLineAt(12.5f))
        assertEquals(0f, layout.tileTopPx(1000))
        // A live top behind the origin (a stale frame) leaves no history, never a negative one.
        assertEquals(0L, ScrollbackLayout(1000, 900, 0, 40, 10f, false).historyLines)
    }

    @Test
    fun extentIsTheFloorOfHistory() {
        // A stale live top below the extent: the history is sized from the extent, as the store's.
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 1500, totalLines = 1040, rows = 40, lineHeightPx = 10f, altScreen = false)
        assertEquals(1000L, layout.historyLines)
        assertEquals(1000L, layout.absLineAt(0f))
        assertEquals(2000L, layout.absLineAt(layout.liveBandTopPx))
    }

    @Test
    fun noGapBetweenTilesAndLiveBand() {
        // 1280×800 letterboxed in a 1080×2000 view: 675 px tall for 40 rows, 16.875 px a line.
        val fitHeight = 675f
        val rows = 40
        val layout = ScrollbackLayout(firstAbsLine = 1003, liveTop = 1240, totalLines = 277, rows = rows, lineHeightPx = fitHeight / rows, altScreen = false)
        assertEquals(237L, layout.historyLines)
        // Slots at 1003 + 50·i; the last one holds the 37 lines up to the live top.
        val tiles = (1003L until 1240L step 50).map { it to minOf(50L, 1240L - it).toInt() }
        assertEquals(1203L to 37, tiles.last())
        var previousBottom = 0f
        for ((first, lineCount) in tiles) {
            assertEquals("tiles abut", previousBottom, layout.tileTopPx(first))
            previousBottom = layout.tileBottomPx(first, lineCount)
        }
        assertEquals(layout.liveBandTopPx, previousBottom)
    }
}
