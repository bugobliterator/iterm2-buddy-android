package dev.bugobliterator.itermbuddy.core.sessions

import java.util.Locale

/**
 * Claude Code's state in a terminal session, as iTerm2's `\(status)` interpolation shows it in the
 * session's subtitle (owner decision: the owner sets the profile's Subtitle to `\(status)`).
 *
 * iTerm2's agent hooks set the status (it2cli `AgentHookEvent.swift:229-455`): SessionStart
 * `idle`, UserPromptSubmit `working`, PermissionRequest `waiting`, Stop `done`, StopFailure
 * `error`; seen on a real Mac by `RealIterm2ClaudeStatusTest`. iTerm2's own reading
 * (`WorkgroupIntrospection.recognizedState`, `WorkgroupIntrospection.swift:582-589`) folds done and
 * error into idle; this app keeps them apart so the row can say Done and Error.
 */
enum class ClaudeSessionState { IDLE, WORKING, WAITING, DONE, ERROR }

/**
 * A subtitle that starts with a Claude Code status: its [state], and the [rest] of the subtitle
 * after the status word, trimmed and in its own case (`\(status) \(path)` gives the path), empty
 * when the subtitle is nothing but the status word. [rest] is terminal content: [toString] shows
 * its length only.
 */
data class ClaudeStatus(val state: ClaudeSessionState, val rest: String) {
    override fun toString(): String = "ClaudeStatus(state=$state, rest(len=${rest.length}))"
}

/**
 * The status [subtitle] starts with, or null when it starts with none: trimmed, its first
 * whitespace-delimited token (the whole text when it has no whitespace), lowercased
 * ([Locale.ROOT]), must be exactly `idle`, `working`, `waiting`, `done` or `error`. Pure; never logs.
 */
fun claudeStatusFromSubtitle(subtitle: String): ClaudeStatus? {
    val parts = subtitle.trim().split(WHITESPACE, limit = 2)
    val state = when (parts.first().lowercase(Locale.ROOT)) {
        "idle" -> ClaudeSessionState.IDLE
        "working" -> ClaudeSessionState.WORKING
        "waiting" -> ClaudeSessionState.WAITING
        "done" -> ClaudeSessionState.DONE
        "error" -> ClaudeSessionState.ERROR
        else -> return null
    }
    return ClaudeStatus(state, parts.getOrElse(1) { "" }.trim())
}

/** The state [subtitle] starts with ([claudeStatusFromSubtitle]), or null. */
fun claudeStateFromSubtitle(subtitle: String): ClaudeSessionState? = claudeStatusFromSubtitle(subtitle)?.state

/** [session]'s Claude Code status from its subtitle; a browser session never has one. */
fun claudeStatusOf(session: SessionSummary): ClaudeStatus? =
    if (session.isBrowser) null else claudeStatusFromSubtitle(session.subtitle)

/** [session]'s Claude Code state from its subtitle; a browser session never has one. */
fun claudeStateOf(session: SessionSummary): ClaudeSessionState? = claudeStatusOf(session)?.state

/** Whether any session in the tree, a pane's or a peer's, shows a Claude Code state (idle included). */
fun SessionTree.showsClaudeState(): Boolean = windows.any { window ->
    window.tabs.any { tab ->
        tab.panes.any { pane -> claudeStateOf(pane.session) != null || pane.peers.any { claudeStateOf(it.session) != null } }
    }
}

private val WHITESPACE = Regex("\\s+")
