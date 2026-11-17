package dev.bugobliterator.itermbuddy.ui.sessions

import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/** The session list's search: [filterSessionRows] over the tree, before it is shown. */
class SessionSearchTest {
    private val zsh = SessionSummary("ptys_0A1", "zsh", "~/src/widget", false)
    private val review = SessionSummary("ptys_0A2", "Code Review", "reviewing widget.c", false)
    private val tail = SessionSummary("ptys_0A3", "tail", "tail -f build.log", false)
    private val planner = SessionSummary("ptys_0A4", "claude", "planning", false)
    private val tester = SessionSummary("ptys_0A5", "claude", "", false)
    private val docs = SessionSummary("ptys_0A6", "Widget Docs", "", true)

    private val tree = SessionTree(
        listOf(
            WindowEntry(
                "buildhost: ~/src/widget",
                listOf(
                    TabEntry(
                        "widget",
                        listOf(
                            PaneEntry(zsh, listOf(PeerEntry("Implementer", zsh), PeerEntry("Code Review", review))),
                            PaneEntry(tail, emptyList()),
                        ),
                    ),
                    TabEntry("agents", listOf(PaneEntry(planner, listOf(PeerEntry("Planner", planner), PeerEntry("Tester", tester))))),
                ),
            ),
            WindowEntry("Window 2", listOf(TabEntry("Docs tab", listOf(PaneEntry(docs, emptyList()))))),
        ),
    )

    private val window1 = SessionRow.Window("buildhost: ~/src/widget")
    private val widgetTab = SessionRow.Tab("widget")
    private val agentsTab = SessionRow.Tab("agents")
    private val window2 = SessionRow.Window("Window 2")
    private val docsTab = SessionRow.Tab("Docs tab")
    private val zshRow = SessionRow.Session(zsh, "Implementer: zsh", nested = true)
    private val reviewRow = SessionRow.Session(review, "Code Review", nested = true)
    private val tailRow = SessionRow.Session(tail, "tail", nested = false)
    private val plannerRow = SessionRow.Session(planner, "Planner: claude", nested = false)
    private val testerRow = SessionRow.Session(tester, "Tester: claude", nested = false)
    private val docsRow = SessionRow.Session(docs, "Widget Docs", nested = false)

    private fun search(query: String) = filterSessionRows(tree, query)

    @Test
    fun blankQueryIsTheFullList() {
        for (query in listOf("", "   ", "\t\n")) assertEquals(sessionRows(tree), search(query))
    }

    @Test
    fun everyVisibleFieldMatches() {
        // Session name.
        assertEquals(listOf(window1, widgetTab, tailRow), search("tail"))
        // Subtitle.
        assertEquals(listOf(window1, agentsTab, plannerRow), search("planning"))
        // Peer role name (a nested peer keeps its Split Pane).
        assertEquals(listOf(window1, widgetTab, SessionRow.SplitPane, zshRow), search("implementer"))
        // The row title as shown, across role and name.
        assertEquals(listOf(window1, agentsTab, testerRow), search("tester: cl"))
        // Tab title: all of the tab's sessions.
        assertEquals(listOf(window1, agentsTab, plannerRow, testerRow), search("agents"))
        // Window title: all of the window's sessions.
        assertEquals(listOf(window2, docsTab, docsRow), search("window 2"))
    }

    @Test
    fun allTermsMustMatch() {
        assertEquals(listOf(window1, agentsTab, plannerRow, testerRow), search("claude agents"))
        assertEquals(listOf(window1, agentsTab, plannerRow), search("  claude   planner "))
        assertEquals(emptyList<SessionRow>(), search("claude docs"))
        // Terms may match different levels: the window title and a session's subtitle.
        assertEquals(listOf(window1, widgetTab, SessionRow.SplitPane, reviewRow), search("buildhost widget.c"))
    }

    @Test
    fun structureIsKeptAndEmptyHeadersDropped() {
        val rows = search("widget")
        // "widget" is in window 1's title: everything in it shows, in order; window 2's
        // "Widget Docs" session matches by name, so its window and tab stay too.
        assertEquals(
            listOf(window1, widgetTab, SessionRow.SplitPane, zshRow, reviewRow, tailRow, agentsTab, plannerRow, testerRow, window2, docsTab, docsRow),
            rows,
        )
        assertEquals(listOf(window2, docsTab, docsRow), search("docs"))
        assertEquals(emptyList<SessionRow>(), search("nothing-like-this"))
    }

    @Test
    fun splitPaneIsKeptOnlyForAMatchingPeer() {
        assertEquals(listOf(window1, widgetTab, SessionRow.SplitPane, reviewRow), search("review"))
        // The pane's own session matches only as a peer; a non-matching group leaves no Split Pane.
        assertFalse(SessionRow.SplitPane in search("tail"))
    }

    @Test
    fun caseFoldingIgnoresTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            // Turkish lower-cases "I" to a dotless "ı", which would miss "implementer".
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(listOf(window1, widgetTab, SessionRow.SplitPane, zshRow), search("IMPLEMENTER"))
            assertEquals(listOf(window2, docsTab, docsRow), search("WIDGET DOCS"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun queryTextIsNeverInARowsToString() {
        val query = "Implementer"
        for (row in search(query)) assertFalse(row.toString(), query in row.toString() || "zsh" in row.toString())
    }

    @Test
    fun noMatchTextQuotesTheTrimmedQuery() {
        assertEquals("No sessions match \"web srv\".", noMatchText("  web srv "))
    }

    @Test
    fun anEmptyTreeSaysSoWhateverTheQuery() {
        val empty = SessionTree(emptyList())
        assertEquals(EMPTY_TREE, emptyListText(empty, "claude"))
        assertEquals(EMPTY_TREE, emptyListText(empty, ""))
        assertEquals(noMatchText("nothing"), emptyListText(tree, "nothing"))
    }
}
