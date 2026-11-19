package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.BROWSER_INPUT_REVISION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the home is. The tab roots are [ChatList] and [SessionList]; everything else is pushed on
 * a tab's stack. A destination's [toString] shows IDs, the summary's redacted form and title
 * lengths only.
 */
sealed interface HomeDestination {
    /** The Chats tab's root. */
    data object ChatList : HomeDestination

    /** The Sessions tab's root. */
    data object SessionList : HomeDestination

    data object Settings : HomeDestination

    /** New Chat (`IOS/Screens/CreateView.swift`). */
    data object NewChat : HomeDestination

    /** A chat's conversation. A chat ID carries no content. */
    data class Conversation(val chatID: String) : HomeDestination

    /** A workgroup's members; [title] is the mention's name or the menu's label, until the Mac names it. */
    data class Workgroup(val workgroupID: String, val title: String) : HomeDestination {
        override fun toString(): String = "Workgroup(workgroupID=$workgroupID, title(len=${title.length}))"
    }

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
 * The home's navigation, app-scoped (built in [dev.bugobliterator.itermbuddy.HomeGraph]), so it
 * outlives Activity recreation. Two tabs ([HomeTab]), each with its own back stack whose root is
 * its list (`IOS/App/iTerm2CompanionApp.swift:155-176`); [destination] is the top of the
 * selected tab's stack.
 *
 * **Only these calls open and close streams, conversations and workgroup watches**, never
 * composition, so rotation and recreation change nothing. After every call the navigator compares
 * what both stacks hold with what they held before and calls, in this order: [closeStream] then
 * [openStream] when the session shown changed; [openChat] for each conversation that appeared and
 * [closeChat] for each that left both stacks; [startWorkgroup] and [stopWorkgroup] likewise; and
 * [setVisibleChat] with the selected tab's top conversation (or null) when that changed. The
 * callbacks run under the navigator's lock and must not call back into it.
 *
 * Rules (the *Chats Tab and Navigation* task):
 * - A conversation always opens on the Chats tab, selecting it (`AM:2902-2915`).
 * - A session, a workgroup, Settings and New Chat are pushed on the **current** tab's stack
 *   (`AM:1359-1379`), so Back from "Go to session" or a member returns to the conversation or the
 *   workgroup. At most one session view exists: pushing one removes any other, closing its stream.
 * - Leaving a tab whose top is a session view pops it first: no stream runs behind another tab.
 * - The default tab is chosen once, at the first [linkUp]: Chats when AI is available, else
 *   Sessions (`AM:2180-2186`); until then the Chats root shows its connecting state. A tab the
 *   owner selected is never changed by it, nor by later links, except that AI turning off while
 *   the Chats tab shows its root moves to Sessions (`AM:2209-2218`, narrowed: with anything
 *   pushed it stays, read-only).
 *
 * Thread-safe.
 *
 * @param openStream starts watching a session (the stream controller's `open`).
 * @param closeStream stops the stream (the stream controller's `close`).
 * @param openChat a conversation is now on a stack (`HomeGraph.openConversation`).
 * @param closeChat a conversation left both stacks (`HomeGraph.closeConversation`).
 * @param setVisibleChat the conversation the selected tab shows, or null (`ChatRepository.setVisible`).
 * @param startWorkgroup a workgroup view is now on a stack: its watch is made.
 * @param stopWorkgroup a workgroup view left both stacks: its watch ends.
 * @param macRevision the connected Mac's hello revision now (0 when not connected), read when a
 *   session is opened: a browser opens [HomeDestination.BrowserSession] at
 *   [BROWSER_INPUT_REVISION] or above, else the view-only live view; one opened before the
 *   revision is known becomes the browser view when [macRevisionChanged] reports 15.
 */
class HomeNavigator(
    private val openStream: (SessionSummary) -> Unit,
    private val closeStream: () -> Unit,
    private val openChat: (String) -> Unit,
    private val closeChat: (String) -> Unit,
    private val setVisibleChat: (String?) -> Unit,
    private val startWorkgroup: (String) -> Unit = {},
    private val stopWorkgroup: (String) -> Unit = {},
    private val macRevision: () -> Int = { 0 },
) {
    private val lock = Any()
    private val stacks = HashMap<HomeTab, List<HomeDestination>>().apply { for (tab in HomeTab.entries) put(tab, listOf(tab.root)) }
    private val tabFlow = MutableStateFlow(HomeTab.CHATS)
    private val destinationFlow = MutableStateFlow<HomeDestination>(HomeTab.CHATS.root)

    /** The selected tab. */
    val tab: StateFlow<HomeTab> = tabFlow.asStateFlow()

    /** The top of the selected tab's stack. */
    val destination: StateFlow<HomeDestination> = destinationFlow.asStateFlow()

    /** Whether the default tab was chosen (at the first link up) or the owner chose one. */
    private var tabDecided = false

    /** The conversation last reported to [setVisibleChat]. */
    private var visibleChat: String? = null

    /** [tab]'s stack, root first (a copy). */
    fun stack(tab: HomeTab): List<HomeDestination> = synchronized(lock) { stacks.getValue(tab) }

    /** The owner selected [tab] (the bottom bar). A session view on the tab being left is closed first. */
    fun selectTab(tab: HomeTab) = change {
        tabDecided = true
        switchTo(tab)
    }

    /** Pushes [destination] by the rules above; a tab root is selected instead. */
    fun push(destination: HomeDestination) = change { pushLocked(destination) }

    /** Opens [session]'s view on the current tab, titled [title]: the browser view when the Mac takes browser input. */
    fun openSession(session: SessionSummary, title: String = session.name) = change {
        pushLocked(
            if (session.isBrowser && macRevision() >= BROWSER_INPUT_REVISION) {
                HomeDestination.BrowserSession(session, title)
            } else {
                HomeDestination.LiveSession(session, title)
            },
        )
    }

    /**
     * Opens [chatID]'s conversation on the Chats tab. [replacingStack] makes it the only thing
     * above the list (a chat just created, iOS `openConversation(replacingPath: true)`, `AM:2594`).
     */
    fun openConversation(chatID: String, replacingStack: Boolean = false) = change {
        val conversation = HomeDestination.Conversation(chatID)
        if (replacingStack) {
            switchTo(HomeTab.CHATS)
            stacks[HomeTab.CHATS] = listOf(HomeTab.CHATS.root, conversation)
        } else {
            pushLocked(conversation)
        }
    }

    /** Opens a workgroup's members on the current tab. */
    fun openWorkgroup(workgroupID: String, title: String) = push(HomeDestination.Workgroup(workgroupID, title))

    fun openNewChat() = push(HomeDestination.NewChat)

    fun openSettings() = push(HomeDestination.Settings)

    /** Back on the selected tab: pops its top (closing what it held); nothing on its root. */
    fun back() = change {
        val stack = stacks.getValue(tabFlow.value)
        if (stack.size > 1) stacks[tabFlow.value] = stack.dropLast(1)
    }

    /**
     * A chat link came up with the hello's [aiAvailable]. The first one (per pairing) chooses the
     * default tab unless the owner already chose; each with AI off applies the AI-off rule.
     */
    fun linkUp(aiAvailable: Boolean) = change {
        if (!tabDecided) {
            tabDecided = true
            switchTo(if (aiAvailable) HomeTab.CHATS else HomeTab.SESSIONS)
        }
        if (!aiAvailable) leaveChatsRoot()
    }

    /** AI turned on or off on the Mac: off moves from the Chats root to Sessions; on moves nothing. */
    fun aiAvailabilityChanged(available: Boolean) = change {
        if (!available) leaveChatsRoot()
    }

    /**
     * The Mac's revision is now [revision]: a browser shown in the live view becomes the browser
     * view at [BROWSER_INPUT_REVISION] or above, its stream untouched. Never the other way: a
     * disconnect (revision 0) leaves the browser view as it is, so a reconnect does not flap it.
     */
    fun macRevisionChanged(revision: Int) = change {
        if (revision < BROWSER_INPUT_REVISION) return@change
        for (tab in HomeTab.entries) {
            stacks[tab] = stacks.getValue(tab).map {
                if (it is HomeDestination.LiveSession && it.session.isBrowser) HomeDestination.BrowserSession(it.session, it.title) else it
            }
        }
    }

    /**
     * Unpair: closes any stream, every conversation and workgroup watch, empties both stacks and
     * selects the Chats root; the next pairing's first link chooses the default tab again.
     */
    fun reset() = change {
        for (tab in HomeTab.entries) stacks[tab] = listOf(tab.root)
        tabFlow.value = HomeTab.CHATS
        tabDecided = false
    }

    /** Runs [mutation] under the lock, then reconciles what the stacks hold with what they held. */
    private fun change(mutation: () -> Unit) {
        synchronized(lock) {
            val before = Held.of(stacks)
            mutation()
            val after = Held.of(stacks)
            if (before.session?.guid != after.session?.guid) {
                if (before.session != null) closeStream()
                after.session?.let(openStream)
            }
            for (chatID in after.chats - before.chats) openChat(chatID)
            for (chatID in before.chats - after.chats) closeChat(chatID)
            for (workgroupID in after.workgroups - before.workgroups) startWorkgroup(workgroupID)
            for (workgroupID in before.workgroups - after.workgroups) stopWorkgroup(workgroupID)
            val top = stacks.getValue(tabFlow.value).last()
            destinationFlow.value = top
            val visible = (top as? HomeDestination.Conversation)?.chatID
            if (visible != visibleChat) {
                visibleChat = visible
                setVisibleChat(visible)
            }
        }
    }

    private fun pushLocked(destination: HomeDestination) {
        when (destination) {
            HomeDestination.ChatList -> return switchTo(HomeTab.CHATS)
            HomeDestination.SessionList -> return switchTo(HomeTab.SESSIONS)
            is HomeDestination.Conversation -> switchTo(HomeTab.CHATS)
            else -> Unit
        }
        // One session view at most: a new one replaces it wherever it is, and anything else
        // pushed over a session view replaces it too (it is always its stack's top).
        for (tab in HomeTab.entries) stacks[tab] = stacks.getValue(tab).filterNot(::isSession)
        stacks[tabFlow.value] = stacks.getValue(tabFlow.value) + destination
    }

    /** Selects [tab], popping a session view off the tab being left. */
    private fun switchTo(tab: HomeTab) {
        val leaving = tabFlow.value
        if (leaving == tab) return
        val stack = stacks.getValue(leaving)
        if (isSession(stack.last())) stacks[leaving] = stack.dropLast(1)
        tabFlow.value = tab
    }

    /** iOS's rescue off a disabled Chats tab, narrowed to its root. */
    private fun leaveChatsRoot() {
        if (tabFlow.value == HomeTab.CHATS && stacks.getValue(HomeTab.CHATS).size == 1) switchTo(HomeTab.SESSIONS)
    }

    /** What both stacks hold that has a lifetime: the session shown, the conversations and workgroups. */
    private class Held(val session: SessionSummary?, val chats: Set<String>, val workgroups: Set<String>) {
        companion object {
            fun of(stacks: Map<HomeTab, List<HomeDestination>>): Held {
                var session: SessionSummary? = null
                val chats = LinkedHashSet<String>()
                val workgroups = LinkedHashSet<String>()
                for (tab in HomeTab.entries) {
                    for (destination in stacks.getValue(tab)) {
                        when (destination) {
                            is HomeDestination.LiveSession -> session = destination.session
                            is HomeDestination.BrowserSession -> session = destination.session
                            is HomeDestination.Conversation -> chats += destination.chatID
                            is HomeDestination.Workgroup -> workgroups += destination.workgroupID
                            else -> Unit
                        }
                    }
                }
                return Held(session, chats, workgroups)
            }
        }
    }

    override fun toString(): String = synchronized(lock) {
        "HomeNavigator(tab=${tabFlow.value}, chats=${stacks.getValue(HomeTab.CHATS).size}, sessions=${stacks.getValue(HomeTab.SESSIONS).size})"
    }
}

private fun isSession(destination: HomeDestination): Boolean =
    destination is HomeDestination.LiveSession || destination is HomeDestination.BrowserSession

/** The screen stays on only while a live or browser session view shows (owner decision). */
fun keepsScreenOn(destination: HomeDestination): Boolean = isSession(destination)
