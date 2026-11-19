package dev.bugobliterator.itermbuddy.ui.workgroup

import dev.bugobliterator.itermbuddy.core.chat.SessionState
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupFailure
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupInfo
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupMember
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupState
import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkgroupRulesTest {
    private val implementerID = "ptys_7K2M9Q4R8T1VH"
    private val browserID = "ptys_3HX6W0ZD5N2BT"

    private fun member(
        guid: String? = implementerID,
        status: String? = null,
        detail: String? = null,
        state: SessionState = SessionState.IDLE,
        role: String = "Implementer",
        sessionName: String? = if (guid == null) null else "zsh",
    ) = WorkgroupMember(role, guid, sessionName, status, detail, state)

    @Test
    fun statusLines() {
        // Not started: a placeholder, whatever else the Mac sent.
        assertEquals("Hasn’t started yet", memberStatusLine(member(guid = null, status = "Reviewing", detail = "x")))
        // Status and detail joined by ": ", either alone, or nothing.
        assertEquals("Reviewing: widget.c, 3 of 7 hunks", memberStatusLine(member(status = "Reviewing", detail = "widget.c, 3 of 7 hunks")))
        assertEquals("Needs input", memberStatusLine(member(status = "Needs input")))
        assertEquals("widget.c", memberStatusLine(member(detail = "widget.c")))
        assertNull(memberStatusLine(member()))
    }

    @Test
    fun stateLabels() {
        assertEquals("Working", memberStateLabel(member(state = SessionState.WORKING)))
        assertEquals("Waiting", memberStateLabel(member(state = SessionState.WAITING)))
        assertEquals("Idle", memberStateLabel(member(state = SessionState.IDLE)))
        assertEquals("Unknown", memberStateLabel(member(state = SessionState.UNKNOWN)))
        // A member that has not started reads as such, whatever its state, with a dashed circle.
        assertEquals("Not started", memberStateLabel(member(guid = null, state = SessionState.WORKING)))
        assertEquals(MemberGlyph.NOT_STARTED, memberGlyph(member(guid = null, state = SessionState.WORKING)))
        assertEquals(MemberGlyph.WORKING, memberGlyph(member(state = SessionState.WORKING)))
        assertEquals(MemberGlyph.WAITING, memberGlyph(member(state = SessionState.WAITING)))
        assertEquals(MemberGlyph.IDLE, memberGlyph(member(state = SessionState.IDLE)))
        assertEquals(MemberGlyph.UNKNOWN, memberGlyph(member(state = SessionState.UNKNOWN)))
        // The session name shows only when it says more than the role.
        assertEquals("zsh", memberSessionName(member()))
        assertNull(memberSessionName(member(sessionName = "Implementer")))
        assertNull(memberSessionName(member(guid = null)))
    }

    private val implementer = SessionSummary(implementerID, "zsh", "~/src", isBrowser = false)
    private val docs = SessionSummary(browserID, "Docs", "example.com", isBrowser = true)
    private val tree = SessionTree(
        listOf(
            WindowEntry(
                "Window",
                listOf(
                    TabEntry(
                        "Tab",
                        listOf(
                            PaneEntry(implementer, emptyList()),
                            PaneEntry(docs, listOf(PeerEntry("Docs", docs), PeerEntry("Other", implementer))),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun memberWithoutSessionIsNotTappable() {
        assertNull(memberSession(member(guid = null), tree))
        assertNull(memberSession(member(guid = null), null))
        // A listed member opens its session from the tree, browser flag included, titled with its role.
        assertEquals(MemberSession(implementer, "Implementer"), memberSession(member(), tree))
        assertEquals(MemberSession(docs, "Reviewer"), memberSession(member(guid = browserID, role = "Reviewer"), tree))
        // Stable IDs compare in canonical form.
        assertEquals(MemberSession(implementer, "Implementer"), memberSession(member(guid = implementerID.lowercase()), tree))
        // Not in the tree (or no tree yet): opened by its own ID as a terminal session.
        val unlisted = "ptys_1F5Y7R3MQ9KD4"
        val opened = memberSession(member(guid = unlisted), tree)!!
        assertEquals(unlisted, opened.session.guid)
        assertFalse(opened.session.isBrowser)
        assertEquals("Implementer", opened.title)
        assertEquals(implementerID, memberSession(member(), null)!!.session.guid)
        assertFalse("toString shows no names", "Implementer" in opened.toString())
    }

    @Test
    fun titlesAndFailureTexts() {
        val info = WorkgroupInfo("wg-1", "Widget Review", emptyList())
        assertEquals("Widget Review", workgroupTitle(WorkgroupState.Shown(info, refreshing = false, stale = false), "fallback"))
        assertEquals("fallback", workgroupTitle(WorkgroupState.Loading, "fallback"))
        assertEquals("That workgroup no longer exists.", workgroupFailureText(WorkgroupFailure.GONE))
        assertTrue(workgroupFailureText(WorkgroupFailure.UNREACHABLE).isNotEmpty())
    }
}
