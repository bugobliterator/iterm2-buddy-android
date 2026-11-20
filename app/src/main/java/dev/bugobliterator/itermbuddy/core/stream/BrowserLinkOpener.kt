package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a Mac-local link was dropped without being sent; [text] is the brief notice. */
enum class BrowserLinkDrop(val text: String) {
    /** The owner left the browser session's view before its stream took input. */
    LEFT("Link not opened: the Browser session was left before it loaded"),

    /** No matching stream took input within the bound. */
    TIMED_OUT("Link not opened: the Browser session didn't start in time"),
}

/**
 * Opens a Mac-local link (one only the Mac can reach, [isMacLocalLink]) in an iTerm2 Browser
 * session: the owner's most recently viewed browser session ([viewing], [target]), whose screen the
 * caller opens; once that session's browser stream takes input ([inputActive], a revision 15 Mac)
 * the URL goes out as the stream's `browserLoad` through [load] (the [BrowserDriver]'s, as the
 * address bar's Go).
 *
 * One link waits at a time: a newer [open] replaces it, and [cancel] drops it, both silently. It
 * is dropped with a notice in [drops] when the owner leaves that session's view after it was
 * shown ([viewing] reports another view) or when no matching stream takes input within
 * [waitBound]. Thread-safe. Logs say what happened, never the URL.
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

    /** The waiting link: its session, its job, and whether that session's view has shown since. */
    private class Pending(val sessionGuid: String) {
        var job: Job? = null
        var seen = false
    }

    private var pending: Pending? = null
    private val dropFlow = MutableSharedFlow<BrowserLinkDrop>(extraBufferCapacity = 4)

    /** A link dropped without being sent, for a brief notice (none for a replaced or cancelled one). */
    val drops: SharedFlow<BrowserLinkDrop> = dropFlow.asSharedFlow()

    /**
     * The view shown now is browser session [sessionGuid]'s, or not a browser session's (null): it
     * becomes the most recently viewed, and a waiting link whose view was shown and is now left is
     * dropped ([BrowserLinkDrop.LEFT]).
     */
    fun viewing(sessionGuid: String?) = synchronized(lock) {
        if (sessionGuid != null) {
            recent.remove(sessionGuid)
            recent.add(0, sessionGuid)
            while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        }
        val waiting = pending ?: return@synchronized
        if (sessionGuid == waiting.sessionGuid) {
            waiting.seen = true
        } else if (waiting.seen) {
            waiting.job?.cancel()
            pending = null
            log("browser link for ${waiting.sessionGuid} dropped: its view was left before the stream took input")
            dropFlow.tryEmit(BrowserLinkDrop.LEFT)
        }
    }

    /** Where a Mac-local link opens among [sessions] (the session tree's): see [chooseLinkBrowserSession]. */
    fun target(sessions: List<SessionSummary>): SessionSummary? = chooseLinkBrowserSession(sessions, synchronized(lock) { recent.toList() })

    /** Loads [url] in [sessionGuid]'s browser stream once it takes input (the caller opens its screen). */
    fun open(sessionGuid: String, url: String) {
        synchronized(lock) {
            pending?.job?.cancel()
            val next = Pending(sessionGuid)
            pending = next
            next.job = scope.launch {
                val ready = withTimeoutOrNull(waitBound) {
                    combine(active, inputActive) { stream, input -> input && stream?.sessionGuid == sessionGuid && stream.config?.isBrowser == true }
                        .first { it }
                }
                synchronized(lock) {
                    if (pending !== next) return@launch
                    pending = null
                }
                if (ready == null) {
                    log("browser link for $sessionGuid dropped: no browser stream took input within $waitBound")
                    dropFlow.tryEmit(BrowserLinkDrop.TIMED_OUT)
                } else {
                    log("browser link (${url.length} chars) sent to $sessionGuid")
                    load(url)
                }
            }
        }
    }

    /** Drops the waiting link, if any, silently. */
    fun cancel() = synchronized(lock) {
        pending?.job?.cancel()
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
