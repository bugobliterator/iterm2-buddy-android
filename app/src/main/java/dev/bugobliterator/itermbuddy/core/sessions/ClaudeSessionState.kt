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
 * The state [subtitle] names, or null when it names none: trimmed and lowercased
 * ([Locale.ROOT]), its first whitespace-delimited token (the whole text when it has no
 * whitespace) must be exactly `idle`, `working`, `waiting`, `done` or `error`. Pure; never logs.
 */
fun claudeStateFromSubtitle(subtitle: String): ClaudeSessionState? {
    val token = subtitle.trim().lowercase(Locale.ROOT).split(WHITESPACE, limit = 2).first()
    return when (token) {
        "idle" -> ClaudeSessionState.IDLE
        "working" -> ClaudeSessionState.WORKING
        "waiting" -> ClaudeSessionState.WAITING
        "done" -> ClaudeSessionState.DONE
        "error" -> ClaudeSessionState.ERROR
        else -> null
    }
}

/** [session]'s Claude Code state from its subtitle; a browser session never has one. */
fun claudeStateOf(session: SessionSummary): ClaudeSessionState? =
    if (session.isBrowser) null else claudeStateFromSubtitle(session.subtitle)

/** Whether any session in the tree, a pane's or a peer's, shows a Claude Code state (idle included). */
fun SessionTree.showsClaudeState(): Boolean = windows.any { window ->
    window.tabs.any { tab ->
        tab.panes.any { pane -> claudeStateOf(pane.session) != null || pane.peers.any { claudeStateOf(it.session) != null } }
    }
}

private val WHITESPACE = Regex("\\s+")
