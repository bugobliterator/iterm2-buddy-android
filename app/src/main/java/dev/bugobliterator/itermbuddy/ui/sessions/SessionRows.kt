package dev.bugobliterator.itermbuddy.ui.sessions

import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import java.util.Locale

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

/**
 * The rows of [tree] that match the search [query], keeping the tree's structure: each matching
 * session under its window and tab headers, windows and tabs with no match left out, and a
 * [SessionRow.SplitPane] only when one of its peers matches (nesting as in the full list).
 *
 * The query is split on whitespace and every term must appear (case-insensitive, `Locale.ROOT`)
 * somewhere among the texts shown for the session: its window title, tab title, name, subtitle,
 * peer role name and row title (`"Planner: claude"`). So a window or tab title that matches
 * shows all of its sessions. A blank query gives [sessionRows]. Nothing here is logged.
 */
fun filterSessionRows(tree: SessionTree, query: String): List<SessionRow> {
    val terms = query.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return sessionRows(tree)
    fun matches(fields: List<String>): Boolean {
        val folded = fields.map { it.lowercase(Locale.ROOT) }
        return terms.all { term -> folded.any { term in it } }
    }
    return buildList {
        for (window in tree.windows) {
            val windowRows = mutableListOf<SessionRow>()
            for (tab in window.tabs) {
                val context = listOf(window.title, tab.title)
                val tabRows = mutableListOf<SessionRow>()
                for (pane in tab.panes) {
                    if (pane.peers.isEmpty()) {
                        if (matches(context + listOf(pane.session.name, pane.session.subtitle))) {
                            tabRows += SessionRow.Session(pane.session, pane.session.name, nested = false)
                        }
                        continue
                    }
                    val nested = tab.panes.size > 1
                    val peers = pane.peers.filter { peer ->
                        matches(context + listOf(peer.roleName, peer.session.name, peer.session.subtitle, peerTitle(peer)))
                    }
                    if (peers.isEmpty()) continue
                    if (nested) tabRows += SessionRow.SplitPane
                    for (peer in peers) tabRows += SessionRow.Session(peer.session, peerTitle(peer), nested)
                }
                if (tabRows.isNotEmpty()) {
                    windowRows += SessionRow.Tab(tab.title)
                    windowRows += tabRows
                }
            }
            if (windowRows.isNotEmpty()) {
                add(SessionRow.Window(window.title))
                addAll(windowRows)
            }
        }
    }
}

private val WHITESPACE = Regex("\\s+")
