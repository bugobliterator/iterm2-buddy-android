package dev.bugobliterator.itermbuddy.core.sessions

/**
 * The Mac's sessions as the user sees them: window, tab, pane, and the peers of a
 * pane that hosts a peer group. Upstream `CompanionSessionTree` and
 * `CompanionSessionSummary` (`sources/Companion/Shared/CompanionMessages.swift`).
 *
 * Names, subtitles, role names and titles are terminal content and never logged:
 * every [toString] here shows only GUIDs, flags, lengths and counts.
 */
data class SessionSummary(val guid: String, val name: String, val subtitle: String, val isBrowser: Boolean) {
    override fun toString(): String =
        "SessionSummary(guid=$guid, name(len=${name.length}), subtitle(len=${subtitle.length}), isBrowser=$isBrowser)"
}

/** One member of a pane's peer group: [roleName] is the workgroup label or the session name. */
data class PeerEntry(val roleName: String, val session: SessionSummary) {
    override fun toString(): String = "PeerEntry(roleName(len=${roleName.length}), session=$session)"
}

/** A pane: [session] occupies it; [peers] is empty unless it hosts more than one peer. */
data class PaneEntry(val session: SessionSummary, val peers: List<PeerEntry>) {
    override fun toString(): String = "PaneEntry(session=$session, peers=$peers)"
}

data class TabEntry(val title: String, val panes: List<PaneEntry>) {
    override fun toString(): String = "TabEntry(title(len=${title.length}), panes=$panes)"
}

data class WindowEntry(val title: String, val tabs: List<TabEntry>) {
    override fun toString(): String = "WindowEntry(title(len=${title.length}), tabs=$tabs)"
}

data class SessionTree(val windows: List<WindowEntry>) {
    override fun toString(): String = "SessionTree(windows=$windows)"

    /** Every session in tree order: each pane's session, then its peers. */
    fun sessions(): List<SessionSummary> =
        windows.flatMap { it.tabs }.flatMap { it.panes }.flatMap { pane -> listOf(pane.session) + pane.peers.map { it.session } }
}
