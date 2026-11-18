package dev.bugobliterator.itermbuddy.core.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollbackLayoutTest {
    @Test
    fun altScreenHasNoHistory() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, rows = 40, lineHeightPx = 10f, altScreen = true)
        assertEquals(0L, layout.historyLines)
        assertEquals(400f, layout.documentHeightPx)
        assertEquals(0f, layout.liveBandTopPx)
        assertEquals(3000L, layout.absLineAt(0f))
    }

    @Test
    fun liveBandSitsBelowHistory() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, rows = 40, lineHeightPx = 10f, altScreen = false)
        assertEquals(2000L, layout.historyLines)
        assertEquals(20_000f, layout.liveBandTopPx)
        assertEquals(20_400f, layout.documentHeightPx)
        assertEquals(500f, layout.tileTopPx(1050))
        assertEquals(3000L, layout.absLineAt(layout.liveBandTopPx))
        assertEquals("clamped to the last live row", 3039L, layout.absLineAt(1e9f))
    }

    @Test
    fun absLineAtTopIsFirstAbsLine() {
        val layout = ScrollbackLayout(firstAbsLine = 1000, liveTop = 3000, rows = 40, lineHeightPx = 12.5f, altScreen = false)
        assertEquals(1000L, layout.absLineAt(0f))
        assertEquals(1000L, layout.absLineAt(-50f))
        assertEquals(1001L, layout.absLineAt(12.5f))
        assertEquals(0f, layout.tileTopPx(1000))
        // A live top behind the origin (a stale frame) leaves no history, never a negative one.
        assertEquals(0L, ScrollbackLayout(1000, 900, 40, 10f, false).historyLines)
    }
}
