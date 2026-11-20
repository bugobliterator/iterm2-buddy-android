package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a Mac-local link (one only the Mac can reach, [isMacLocalLink]) in an iTerm2 Browser
 * session: the owner's most recently viewed browser session ([shown], [target]), whose screen the
 * caller opens; once that session's browser stream takes input ([inputActive], a revision 15 Mac)
 * the URL goes out as the stream's `browserLoad` through [load] (the [BrowserDriver]'s, as the
 * address bar's Go).
 *
 * One link waits at a time: a newer [open] replaces it, and it is dropped when no matching stream
 * takes input within [waitBound] (the owner went elsewhere, the Mac never streamed it) or on
 * [cancel]. Thread-safe. Logs say what happened, never the URL.
 */
class BrowserLinkOpener(
    private val active: StateFlow<ActiveStream?>,
    private val inputActive: StateFlow<Boolean>,
    private val load: (String) -> Unit,
    private val scope: CoroutineScope,
    private val waitBound: Duration = DEFAULT_BROWSER_LINK_WAIT,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()

    /** Browser session GUIDs, most recently viewed first. */
    private val recent = ArrayList<String>()
    private var pending: Job? = null

    /** The owner is viewing browser session [sessionGuid]. */
    fun shown(sessionGuid: String) = synchronized(lock) {
        recent.remove(sessionGuid)
        recent.add(0, sessionGuid)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
    }

    /** Where a Mac-local link opens among [sessions] (the session tree's): see [chooseLinkBrowserSession]. */
    fun target(sessions: List<SessionSummary>): SessionSummary? = chooseLinkBrowserSession(sessions, synchronized(lock) { recent.toList() })

    /** Loads [url] in [sessionGuid]'s browser stream once it takes input (the caller opens its screen). */
    fun open(sessionGuid: String, url: String) {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                val ready = withTimeoutOrNull(waitBound) {
                    combine(active, inputActive) { stream, input -> input && stream?.sessionGuid == sessionGuid && stream.config?.isBrowser == true }
                        .first { it }
                }
                if (ready == null) {
                    log("browser link for $sessionGuid dropped: no browser stream took input within $waitBound")
                } else {
                    log("browser link (${url.length} chars) sent to $sessionGuid")
                    load(url)
                }
            }
        }
    }

    /** Drops the waiting link, if any. */
    fun cancel() = synchronized(lock) {
        pending?.cancel()
        pending = null
    }

    private companion object {
        const val MAX_RECENT = 32
    }
}

/**
 * The browser session a Mac-local link opens in: of [sessions]' browser sessions, the most
 * recently viewed ([recent], newest first), else the first in the tree's order; null when there
 * is none.
 */
fun chooseLinkBrowserSession(sessions: List<SessionSummary>, recent: List<String>): SessionSummary? {
    val browsers = sessions.filter { it.isBrowser }
    if (browsers.isEmpty()) return null
    val byGuid = browsers.associateBy { it.guid }
    return recent.firstNotNullOfOrNull { byGuid[it] } ?: browsers.first()
}

/** How long a Mac-local link waits for its browser session's stream to take input. */
val DEFAULT_BROWSER_LINK_WAIT: Duration = 20.seconds
