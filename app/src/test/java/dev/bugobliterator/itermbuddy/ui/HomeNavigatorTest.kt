package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Navigation, not composition, drives streams, conversations and workgroup watches: only
 * navigator calls open and close them. Two tabs, each with its own back stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeNavigatorTest {
    /** Every call the navigator made, in order, as "verb target". */
    private val calls = CopyOnWriteArrayList<String>()
    private var macRevision = 15
    private val navigator = HomeNavigator(
        openStream = { calls += "openStream ${it.guid}" },
        closeStream = { calls += "closeStream" },
        openChat = { calls += "openChat $it" },
        closeChat = { calls += "closeChat $it" },
        setVisibleChat = { calls += "visible $it" },
        startWorkgroup = { calls += "startWorkgroup $it" },
        stopWorkgroup = { calls += "stopWorkgroup $it" },
        macRevision = { macRevision },
    )

    private fun streamCalls() = calls.filter { it.startsWith("openStream") || it == "closeStream" }

    private fun chatCalls() = calls.filter { it.startsWith("openChat") || it.startsWith("closeChat") }

    private fun onSessions() {
        navigator.selectTab(HomeTab.SESSIONS)
        calls.clear()
    }

    @Test
    fun openAndBackDriveController() {
        onSessions()
        navigator.openSession(TERMINAL)
        assertEquals(listOf("openStream ${TERMINAL.guid}"), streamCalls())
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.destination.value)
        navigator.back()
        assertEquals(listOf("openStream ${TERMINAL.guid}", "closeStream"), streamCalls())
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // Back on the list does nothing more.
        navigator.back()
        assertEquals(listOf("openStream ${TERMINAL.guid}", "closeStream"), streamCalls())
    }

    @Test
    fun browserRowOpensBrowserAtRevisionFifteen() {
        onSessions()
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.back()
        // Below 15 the Mac takes no browser input: the page is watched in the live view.
        macRevision = 14
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.back()
        macRevision = 15
        navigator.openSession(TERMINAL)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.destination.value)
        assertEquals(
            listOf("openStream ${BROWSER.guid}", "closeStream", "openStream ${BROWSER.guid}", "closeStream", "openStream ${TERMINAL.guid}"),
            streamCalls(),
        )
    }

    @Test
    fun browserOpenedBeforeTheRevisionIsKnownBecomesTheBrowserView() {
        onSessions()
        macRevision = 0
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.macRevisionChanged(14)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.macRevisionChanged(15)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.destination.value)
        navigator.macRevisionChanged(0)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.destination.value)
        assertEquals("the stream is untouched", listOf("openStream ${BROWSER.guid}"), streamCalls())
    }

    @Test
    fun settingsAndBackNeverTouchTheStream() {
        onSessions()
        navigator.openSettings()
        assertEquals(HomeDestination.Settings, navigator.destination.value)
        navigator.back()
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        assertEquals(emptyList<String>(), streamCalls())
    }

    /**
     * A contract test only: reading [HomeNavigator.destination] from a new collector (as a
     * recreated Activity does) opens and closes nothing. The owner's on-device rotation check is
     * the proof that rotation neither stops nor restarts the stream.
     */
    @Test
    fun recreatingTheUiDoesNotRestart() = runTest {
        navigator.openConversation(CHAT)
        navigator.openSession(TERMINAL)
        calls.clear()
        val seen = CopyOnWriteArrayList<HomeDestination>()
        repeat(2) {
            val collector = backgroundScope.launch { navigator.destination.collect { seen += it } }
            runCurrent()
            collector.cancel()
            runCurrent()
        }
        assertEquals(listOf<HomeDestination>(HomeDestination.LiveSession(TERMINAL, "zsh"), HomeDestination.LiveSession(TERMINAL, "zsh")), seen.toList())
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun conversationOpenAndCloseDriveRepository() {
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        navigator.openConversation(CHAT)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.destination.value)
        assertEquals(listOf("openChat $CHAT", "visible $CHAT"), calls.toList())
        calls.clear()
        navigator.back()
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
        assertEquals(listOf("closeChat $CHAT", "visible null"), calls.toList())
    }

    @Test
    fun conversationOpensOnTheChatsTab() {
        onSessions()
        navigator.openConversation(CHAT)
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT)), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), navigator.stack(HomeTab.SESSIONS))
    }

    @Test
    fun newChatReplacesTheStackWithItsConversation() {
        navigator.openConversation(CHAT)
        navigator.openNewChat()
        assertEquals(HomeDestination.NewChat, navigator.destination.value)
        calls.clear()
        navigator.openConversation(OTHER_CHAT, replacingStack = true)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(OTHER_CHAT)), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf("openChat $OTHER_CHAT", "closeChat $CHAT"), chatCalls())
        assertEquals("visible $OTHER_CHAT", calls.last())
        // Back from the new conversation is the list, as on iOS.
        navigator.back()
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
    }

    @Test
    fun goToSessionReturnsToConversation() {
        navigator.openConversation(CHAT)
        calls.clear()
        navigator.openSession(TERMINAL, "Build shell")
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "Build shell"), navigator.destination.value)
        assertEquals(listOf("openStream ${TERMINAL.guid}", "visible null"), calls.toList())
        calls.clear()
        navigator.back()
        assertEquals(HomeDestination.Conversation(CHAT), navigator.destination.value)
        assertEquals("the conversation stays open underneath", listOf("closeStream", "visible $CHAT"), calls.toList())
    }

    @Test
    fun workgroupMemberReturnsToWorkgroupWithItsWatchKept() {
        navigator.openConversation(CHAT)
        navigator.openWorkgroup(WORKGROUP, "Release crew")
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "Release crew"), navigator.destination.value)
        assertTrue("startWorkgroup $WORKGROUP" in calls)
        calls.clear()
        navigator.openSession(PLANNER, "Planner")
        navigator.back()
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "Release crew"), navigator.destination.value)
        assertEquals("the watch outlives the member's view", listOf("openStream ${PLANNER.guid}", "closeStream"), calls.filter { "Workgroup" in it || "Stream" in it })
        navigator.back()
        assertEquals(listOf("stopWorkgroup $WORKGROUP"), calls.filter { "Workgroup" in it })
    }

    @Test
    fun onlyOneStreamAcrossTabs() {
        navigator.openConversation(CHAT)
        navigator.openSession(TERMINAL)
        calls.clear()
        // A workgroup member opened while a session shows: the old stream goes, then the new one opens.
        navigator.openSession(PLANNER)
        assertEquals(listOf("closeStream", "openStream ${PLANNER.guid}"), streamCalls())
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT), HomeDestination.LiveSession(PLANNER, "claude")), navigator.stack(HomeTab.CHATS))
    }

    @Test
    fun tabSwitchAwayFromLiveSessionClosesIt() {
        navigator.openConversation(CHAT)
        navigator.openSession(TERMINAL)
        calls.clear()
        navigator.selectTab(HomeTab.SESSIONS)
        // The conversation under it stays open (and off screen, as it was under the session).
        assertEquals(listOf("closeStream"), calls.toList())
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT)), navigator.stack(HomeTab.CHATS))
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // Back on Chats, the conversation is on screen again; no stream comes back.
        navigator.selectTab(HomeTab.CHATS)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.destination.value)
        assertEquals(listOf("closeStream", "visible $CHAT"), calls.toList())
        navigator.selectTab(HomeTab.SESSIONS)
        // Selecting the same tab again does nothing.
        calls.clear()
        navigator.selectTab(HomeTab.SESSIONS)
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun stacksAreKeptPerTab() {
        navigator.openConversation(CHAT)
        navigator.selectTab(HomeTab.SESSIONS)
        navigator.openSettings()
        assertEquals(HomeDestination.Settings, navigator.destination.value)
        calls.clear()
        navigator.selectTab(HomeTab.CHATS)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.destination.value)
        assertEquals(listOf("visible $CHAT"), calls.toList())
        navigator.selectTab(HomeTab.SESSIONS)
        assertEquals(HomeDestination.Settings, navigator.destination.value)
        // Back acts on the selected tab's stack only.
        navigator.back()
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT)), navigator.stack(HomeTab.CHATS))
    }

    @Test
    fun defaultTabIsDecidedOnceAtFirstLinkUp() {
        // Until the first link, the Chats root (its connecting state).
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // Decided: AI on at the next link does not move the owner back to Chats.
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)

        val other = HomeNavigator({}, {}, {}, {}, {})
        other.linkUp(aiAvailable = true)
        assertEquals(HomeTab.CHATS, other.tab.value)
    }

    @Test
    fun defaultNeverOverridesAChosenTab() {
        navigator.selectTab(HomeTab.SESSIONS)
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)

        val other = HomeNavigator({}, {}, {}, {}, {})
        other.selectTab(HomeTab.CHATS)
        other.linkUp(aiAvailable = false)
        // Chosen, but AI off on the Chats root still moves to Sessions (iOS's rescue).
        assertEquals(HomeTab.SESSIONS, other.tab.value)
    }

    @Test
    fun laterLinksDoNotMoveTabs() {
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        navigator.selectTab(HomeTab.SESSIONS)
        repeat(3) { navigator.linkUp(aiAvailable = true) }
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)
        navigator.selectTab(HomeTab.CHATS)
        navigator.openConversation(CHAT)
        // A later link with AI off, a conversation open: it stays, read-only.
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.destination.value)
    }

    @Test
    fun aiOffMovesToSessionsOnlyFromChatsRoot() {
        navigator.linkUp(aiAvailable = true)
        navigator.openConversation(CHAT)
        navigator.aiAvailabilityChanged(false)
        assertEquals("a conversation open stays, read-only", HomeDestination.Conversation(CHAT), navigator.destination.value)
        navigator.back()
        // Back to the root with AI still off: nothing moves until AI turns off again.
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)
        assertEquals(HomeDestination.SessionList, navigator.destination.value)
        // On Sessions, or on Chats beyond the root, AI off moves nothing.
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)
        navigator.selectTab(HomeTab.CHATS)
        navigator.openNewChat()
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeDestination.NewChat, navigator.destination.value)
        // AI back on never moves tabs.
        navigator.back()
        navigator.aiAvailabilityChanged(true)
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
    }

    @Test
    fun resetClosesEverything() {
        navigator.linkUp(aiAvailable = true)
        navigator.openConversation(CHAT)
        navigator.openWorkgroup(WORKGROUP, "Release crew")
        navigator.openSession(TERMINAL)
        navigator.selectTab(HomeTab.SESSIONS)
        navigator.openSession(PLANNER)
        calls.clear()
        navigator.reset()
        assertEquals(
            setOf("closeStream", "closeChat $CHAT", "stopWorkgroup $WORKGROUP"),
            calls.filter { !it.startsWith("visible") }.toSet(),
        )
        assertEquals(HomeTab.CHATS, navigator.tab.value)
        assertEquals(HomeDestination.ChatList, navigator.destination.value)
        assertEquals(listOf(HomeDestination.ChatList), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), navigator.stack(HomeTab.SESSIONS))
        // A new pairing decides its default tab again.
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, navigator.tab.value)
    }

    @Test
    fun resetWithNothingOpenClosesNothing() {
        navigator.reset()
        assertEquals(emptyList<String>(), calls.filter { !it.startsWith("visible") })
    }

    @Test
    fun screenKeptOnOnlyInSessionViews() {
        assertTrue(keepsScreenOn(HomeDestination.LiveSession(TERMINAL, "zsh")))
        assertTrue(keepsScreenOn(HomeDestination.BrowserSession(BROWSER, "Widget Docs")))
        for (other in listOf(
            HomeDestination.SessionList,
            HomeDestination.ChatList,
            HomeDestination.Settings,
            HomeDestination.NewChat,
            HomeDestination.Conversation(CHAT),
            HomeDestination.Workgroup(WORKGROUP, "Release crew"),
        )) {
            assertFalse("$other", keepsScreenOn(other))
        }
    }

    @Test
    fun peerRowTitleIsTheViewTitle() {
        onSessions()
        navigator.openSession(PLANNER, "Planner: claude")
        assertEquals(HomeDestination.LiveSession(PLANNER, "Planner: claude"), navigator.destination.value)
    }

    @Test
    fun destinationToStringHidesNames() {
        val summary = SessionSummary("ptys_1", SENTINEL, SENTINEL, false)
        for (destination in listOf(
            HomeDestination.LiveSession(summary, SENTINEL),
            HomeDestination.BrowserSession(summary, SENTINEL),
            HomeDestination.Workgroup(WORKGROUP, SENTINEL),
        )) {
            val text = destination.toString()
            assertFalse(text, text.contains(SENTINEL))
        }
    }

    private companion object {
        const val SENTINEL = "SENTINEL-secret-name"
        const val CHAT = "C0DE0000-0000-4000-8000-000000000001"
        const val OTHER_CHAT = "C0DE0000-0000-4000-8000-000000000002"
        const val WORKGROUP = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
        val TERMINAL = SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget", false)
        val PLANNER = SessionSummary("ptys_1F5Y7R3MQ9KD4", "claude", "planning", false)
        val BROWSER = SessionSummary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", true)
    }
}
