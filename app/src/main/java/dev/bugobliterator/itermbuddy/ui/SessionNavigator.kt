package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.BROWSER_INPUT_REVISION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the home is. A session destination's [toString] shows the summary's redacted form and the title's length only. */
sealed interface HomeDestination {
    data object SessionList : HomeDestination

    data object Settings : HomeDestination

    /** [title] is the row's title (a peer reads "Planner: claude", as on iOS). */
    data class LiveSession(val session: SessionSummary, val title: String) : HomeDestination {
        override fun toString(): String = "LiveSession(session=$session, title(len=${title.length}))"
    }

    /** The browser view: a browser row on a Mac that takes browser input (revision 15). */
    data class BrowserSession(val session: SessionSummary, val title: String) : HomeDestination {
        override fun toString(): String = "BrowserSession(session=$session, title(len=${title.length}))"
    }
}

/**
 * The home's navigation, app-scoped (built in `CompanionApp`), so it outlives Activity
 * recreation. **Only these calls open and close the stream**: a row tap ([openSession]), Back
 * ([back]: the system back gesture, the top bar's ←, the live view's Back action) and [reset].
 * Compose only renders [destination]; nothing in composition (enter, leave, `DisposableEffect`)
 * opens or closes a stream, so rotation and Activity recreation neither stop nor restart it.
 * Backgrounding is the stream controller's `pause`/`resume`, driven by the process lifecycle.
 *
 * @param open starts watching a session (the stream controller's `open`).
 * @param close stops the stream and drops the watch (the stream controller's `close`).
 * @param macRevision the connected Mac's hello revision now (0 when not connected), read when a
 *   row is opened: a browser row opens [HomeDestination.BrowserSession] at
 *   [BROWSER_INPUT_REVISION] or above, else the view-only live view. A browser opened before the
 *   revision is known (the tree is kept while reconnecting, revision 0) becomes the browser view
 *   when [macRevisionChanged] reports 15.
 */
class SessionNavigator(
    private val open: (SessionSummary) -> Unit,
    private val close: () -> Unit,
    private val macRevision: () -> Int,
) {
    private val lock = Any()
    private val destinationFlow = MutableStateFlow<HomeDestination>(HomeDestination.SessionList)

    val destination: StateFlow<HomeDestination> = destinationFlow.asStateFlow()

    /**
     * Opens [session]'s view, titled [title] (the row's title), and starts its stream. A browser
     * session gets the browser view when the Mac takes browser input, else the live view (video
     * only).
     */
    fun openSession(session: SessionSummary, title: String = session.name) {
        synchronized(lock) {
            open(session)
            destinationFlow.value = if (session.isBrowser && macRevision() >= BROWSER_INPUT_REVISION) {
                HomeDestination.BrowserSession(session, title)
            } else {
                HomeDestination.LiveSession(session, title)
            }
        }
    }

    /**
     * The Mac's revision is now [revision]: a browser shown in the live view becomes the browser
     * view at [BROWSER_INPUT_REVISION] or above, its stream untouched. Never the other way: a
     * disconnect (revision 0) leaves the browser view as it is, so a reconnect does not flap it.
     */
    fun macRevisionChanged(revision: Int) {
        synchronized(lock) {
            val now = destinationFlow.value
            if (now is HomeDestination.LiveSession && now.session.isBrowser && revision >= BROWSER_INPUT_REVISION) {
                destinationFlow.value = HomeDestination.BrowserSession(now.session, now.title)
            }
        }
    }

    /** Shows Settings; from a session view the stream is closed first. */
    fun openSettings() {
        synchronized(lock) {
            if (isSession(destinationFlow.value)) close()
            destinationFlow.value = HomeDestination.Settings
        }
    }

    /** Back to the list; from a session view this closes the stream. Does nothing on the list. */
    fun back() {
        synchronized(lock) {
            val now = destinationFlow.value
            if (now == HomeDestination.SessionList) return
            if (isSession(now)) close()
            destinationFlow.value = HomeDestination.SessionList
        }
    }

    /** Closes any stream and returns to the list (on unpair). */
    fun reset() {
        synchronized(lock) {
            close()
            destinationFlow.value = HomeDestination.SessionList
        }
    }

    private fun isSession(destination: HomeDestination): Boolean =
        destination is HomeDestination.LiveSession || destination is HomeDestination.BrowserSession
}

/** The screen stays on only while a live or browser session view shows (owner decision). */
fun keepsScreenOn(destination: HomeDestination): Boolean =
    destination is HomeDestination.LiveSession || destination is HomeDestination.BrowserSession
