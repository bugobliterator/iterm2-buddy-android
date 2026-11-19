package dev.bugobliterator.itermbuddy.ui.sessions

import dev.bugobliterator.itermbuddy.core.sessions.ClaudeSessionState
import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A session row's Claude Code chip, and the subtitle it replaces. */
class SessionRowClaudeStateTest {
    private val working = SessionSummary("ptys_working", "claude", "working", false)
    private val waiting = SessionSummary("ptys_waiting", "claude", "Waiting", false)
    private val idle = SessionSummary("ptys_idle", "claude", "idle", false)
    private val failed = SessionSummary("ptys_failed", "claude", "error", false)
    private val shell = SessionSummary("ptys_shell", "zsh", "~/src", false)
    private val browser = SessionSummary("ptys_browser", "Docs", "working", true)

    @Test
    fun aTerminalRowWithAStatusShowsItsState() {
        assertEquals(ClaudeSessionState.WORKING, rowClaudeState(working))
        assertEquals(ClaudeSessionState.WAITING, rowClaudeState(waiting))
        assertEquals(ClaudeSessionState.IDLE, rowClaudeState(idle))
        assertEquals(ClaudeSessionState.ERROR, rowClaudeState(failed))
        assertNull(rowClaudeState(shell))
    }

    @Test
    fun aBrowserRowNeverHasAState() {
        assertNull(rowClaudeState(browser))
        assertEquals("a browser's subtitle shows as it is", "working", rowSubtitle(browser))
    }

    @Test
    fun aRecognisedStatusIsNotAlsoTheSubtitle() {
        assertNull(rowSubtitle(working))
        assertNull(rowSubtitle(waiting))
        assertNull("idle shows neither chip nor text", rowSubtitle(idle))
        assertEquals("~/src", rowSubtitle(shell))
        assertNull(rowSubtitle(SessionSummary("ptys_empty", "zsh", "", false)))
    }

    @Test
    fun chipLabelsMatchTheChatList() {
        assertEquals("Working", claudeStateLabel(ClaudeSessionState.WORKING))
        assertEquals("Waiting for you", claudeStateLabel(ClaudeSessionState.WAITING))
        assertEquals("Done", claudeStateLabel(ClaudeSessionState.DONE))
        assertEquals("Error", claudeStateLabel(ClaudeSessionState.ERROR))
        assertNull(claudeStateLabel(ClaudeSessionState.IDLE))
    }

    @Test
    fun searchMatchesWhatTheRowShows() {
        val tree = SessionTree(
            listOf(
                WindowEntry(
                    "Window",
                    listOf(
                        TabEntry(
                            "Tab",
                            listOf(
                                PaneEntry(working, emptyList()),
                                PaneEntry(waiting, emptyList()),
                                PaneEntry(idle, emptyList()),
                                PaneEntry(shell, emptyList()),
                                PaneEntry(browser, emptyList()),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val header = listOf(SessionRow.Window("Window"), SessionRow.Tab("Tab"))
        fun row(summary: SessionSummary) = SessionRow.Session(summary, summary.name, nested = false)
        assertEquals("the chip's label", header + row(waiting), filterSessionRows(tree, "for you"))
        assertEquals("a chip, and a browser's subtitle", header + row(working) + row(browser), filterSessionRows(tree, "working"))
        assertEquals("a hidden idle status is not searched", emptyList<SessionRow>(), filterSessionRows(tree, "idle"))
        assertEquals(header + row(shell), filterSessionRows(tree, "~/src"))
    }
}
