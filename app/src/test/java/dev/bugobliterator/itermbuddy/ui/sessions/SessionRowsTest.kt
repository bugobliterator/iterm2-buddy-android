package dev.bugobliterator.itermbuddy.ui.sessions

import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The home list's rows, by iOS's rules (`IOS/Views/SessionTreeList.swift:73-119`). */
class SessionRowsTest {
    @Test
    fun sampleTreeFlattensLikeIos() {
        val tree = SessionsRig.treeOf(SessionsRig.REPLY)
        val zsh = SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget", false)
        val review = SessionSummary("ptys_3HX6W0ZD5N2BT", "Code Review", "reviewing widget.c", false)
        val tail = SessionSummary("ptys_9PJ4C8EGA6S0J", "tail", "tail -f build.log", false)
        val planner = SessionSummary("ptys_1F5Y7R3MQ9KD4", "claude", "planning", false)
        val tester = SessionSummary("ptys_6V0T2B8NW4HEQ", "claude", "", false)
        val vim = SessionSummary("ptys_8M3B5X1QJ7TW1", "vim", "notes.md", false)
        val docs = SessionSummary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", true)
        val expected = listOf(
            SessionRow.Window("demo@buildhost: ~/src/widget"),
            SessionRow.Tab("widget"),
            // The tab has another pane, so the peer group is a Split Pane with its peers nested.
            SessionRow.SplitPane,
            SessionRow.Session(zsh, "Implementer: zsh", nested = true),
            SessionRow.Session(review, "Code Review", nested = true),
            SessionRow.Session(tail, "tail", nested = false),
            SessionRow.Tab("agents"),
            // The peer group is the tab's only pane: its peers are hoisted to the tab.
            SessionRow.Session(planner, "Planner: claude", nested = false),
            SessionRow.Session(tester, "Tester: claude", nested = false),
            SessionRow.Tab("Tab 3"),
            SessionRow.Session(vim, "vim", nested = false),
            SessionRow.Window("Window 2"),
            SessionRow.Tab("Widget Docs"),
            SessionRow.Session(docs, "Widget Docs", nested = false),
        )
        assertEquals(expected, sessionRows(tree))
    }

    @Test
    fun emptyTreeHasNoRows() {
        assertEquals(emptyList<SessionRow>(), sessionRows(SessionTree(emptyList())))
    }

    @Test
    fun rowToStringHidesTitles() {
        val summary = SessionSummary("ptys_1", SENTINEL, SENTINEL, false)
        val other = SessionSummary("ptys_2", SENTINEL, SENTINEL, true)
        val tree = SessionTree(
            listOf(
                WindowEntry(
                    SENTINEL,
                    listOf(
                        TabEntry(SENTINEL, listOf(PaneEntry(summary, listOf(PeerEntry(SENTINEL, summary), PeerEntry(SENTINEL, other))), PaneEntry(other, emptyList()))),
                        TabEntry(SENTINEL, listOf(PaneEntry(summary, listOf(PeerEntry(SENTINEL, summary), PeerEntry(SENTINEL, other))))),
                    ),
                ),
            ),
        )
        val rows = sessionRows(tree)
        assertEquals(9, rows.size)
        for (row in rows) assertFalse(row.toString(), row.toString().contains(SENTINEL))
        assertFalse(rows.toString().contains(SENTINEL))
    }

    @Test
    fun peerTitleDropsRepeatedName() {
        fun peer(role: String, name: String) = PeerEntry(role, SessionSummary("ptys_1", name, "", false))
        assertEquals("claude", peerTitle(peer("claude", "claude")))
        assertEquals("planner: zsh", peerTitle(peer("planner", "zsh")))
        assertEquals("planner", peerTitle(peer("planner", "")))
    }

    private companion object {
        const val SENTINEL = "SENTINEL-rm-rf-secret"
    }
}
