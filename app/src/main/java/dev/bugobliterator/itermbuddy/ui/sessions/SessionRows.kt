package dev.bugobliterator.itermbuddy.ui.sessions

import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree

/**
 * One row of the home list. Titles are terminal content: every [toString] shows lengths only.
 */
sealed interface SessionRow {
    /** A window's section header. */
    data class Window(val title: String) : SessionRow {
        override fun toString(): String = "Window(title(len=${title.length}))"
    }

    data class Tab(val title: String) : SessionRow {
        override fun toString(): String = "Tab(title(len=${title.length}))"
    }

    /** A tappable session; [nested] puts it under a [SplitPane] (one more indent). */
    data class Session(val summary: SessionSummary, val title: String, val nested: Boolean) : SessionRow {
        override fun toString(): String = "Session(summary=$summary, title(len=${title.length}), nested=$nested)"
    }

    /** A pane hosting a peer group, among other panes: a non-tappable container. */
    data object SplitPane : SessionRow
}

/**
 * The home list's rows, by iOS's rules (`IOS/Views/SessionTreeList.swift:73-119`): per window a
 * header and per tab a row; a pane with no peers is one session row titled with its name; a pane
 * with peers is a container: when the tab has other panes it is a [SessionRow.SplitPane] row
 * with its peers nested under it, else its peers are hoisted to the tab. Peers are titled by
 * [peerTitle].
 */
fun sessionRows(tree: SessionTree): List<SessionRow> = buildList {
    for (window in tree.windows) {
        add(SessionRow.Window(window.title))
        for (tab in window.tabs) {
            add(SessionRow.Tab(tab.title))
            for (pane in tab.panes) {
                if (pane.peers.isEmpty()) {
                    add(SessionRow.Session(pane.session, pane.session.name, nested = false))
                    continue
                }
                val nested = tab.panes.size > 1
                if (nested) add(SessionRow.SplitPane)
                for (peer in pane.peers) add(SessionRow.Session(peer.session, peerTitle(peer), nested))
            }
        }
    }
}

/** `"<role>: <name>"`, or just the role when the name is empty or the same (iOS `peerTitle`). */
fun peerTitle(peer: PeerEntry): String {
    val name = peer.session.name
    return if (name.isEmpty() || name == peer.roleName) peer.roleName else "${peer.roleName}: $name"
}

/** The second line of a session row: its subtitle, or null when it has none. */
fun rowSubtitle(summary: SessionSummary): String? = summary.subtitle.ifEmpty { null }
