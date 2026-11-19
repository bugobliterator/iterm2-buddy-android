package dev.bugobliterator.itermbuddy.core.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Claude Code's state read from a session subtitle set to iTerm2's `\(status)`. */
class ClaudeSessionStateTest {
    @Test
    fun theStatusesTheRealMacShowed() {
        // RealIterm2ClaudeStatusTest (run 44495889912): the subtitle as each hook fired.
        assertEquals(ClaudeSessionState.IDLE, claudeStateFromSubtitle("idle"))
        assertEquals(ClaudeSessionState.WORKING, claudeStateFromSubtitle("working"))
        assertEquals(ClaudeSessionState.WAITING, claudeStateFromSubtitle("waiting"))
        assertEquals(ClaudeSessionState.DONE, claudeStateFromSubtitle("done"))
        assertEquals(ClaudeSessionState.ERROR, claudeStateFromSubtitle("error"))
    }

    @Test
    fun caseAndSurroundingWhitespaceDoNotMatter() {
        assertEquals(ClaudeSessionState.WORKING, claudeStateFromSubtitle("Working"))
        assertEquals(ClaudeSessionState.WAITING, claudeStateFromSubtitle("  WAITING\n"))
        assertEquals(ClaudeSessionState.DONE, claudeStateFromSubtitle("\tDone "))
    }

    @Test
    fun theFirstTokenDecides() {
        assertEquals(ClaudeSessionState.WORKING, claudeStateFromSubtitle("working on widget.c"))
        assertEquals(ClaudeSessionState.ERROR, claudeStateFromSubtitle("Error\t(rate limited)"))
        assertEquals(ClaudeSessionState.IDLE, claudeStateFromSubtitle("idle  ~/src"))
        assertNull("a status later in the text is not the first token", claudeStateFromSubtitle("vim working"))
        assertNull("the token must be the whole word", claudeStateFromSubtitle("workingdir"))
        assertNull(claudeStateFromSubtitle("done."))
    }

    @Test
    fun anythingElseIsNoState() {
        for (subtitle in listOf("", "   ", "~/src", "vim", "~/src/widget", "planning", "tail -f build.log", "waitingroom")) {
            assertNull("\"$subtitle\"", claudeStateFromSubtitle(subtitle))
        }
    }
}
