package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.chat.ChatAvailability
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

    private var generation = 0L
    private var aiOffs = 0L

    /** A chat link up with the hello's [aiAvailable], as the repository's availability feed reports it. */
    private fun HomeNavigator.linkUp(aiAvailable: Boolean) =
        availabilityChanged(ChatAvailability(++generation, aiAvailable, aiOffs))

    /** AI turned off (or back on) on the current link. */
    private fun HomeNavigator.aiAvailabilityChanged(available: Boolean) {
        if (!available) aiOffs++
        availabilityChanged(ChatAvailability(generation, available, aiOffs))
    }

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
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.state.value.destination)
        navigator.back()
        assertEquals(listOf("openStream ${TERMINAL.guid}", "closeStream"), streamCalls())
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        // Back on the list does nothing more.
        navigator.back()
        assertEquals(listOf("openStream ${TERMINAL.guid}", "closeStream"), streamCalls())
    }

    @Test
    fun browserRowOpensBrowserAtRevisionFifteen() {
        onSessions()
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        navigator.back()
        // Below 15 the Mac takes no browser input: the page is watched in the live view.
        macRevision = 14
        navigator.openSession(BROWSER)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        navigator.back()
        macRevision = 15
        navigator.openSession(TERMINAL)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), navigator.state.value.destination)
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
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        navigator.macRevisionChanged(14)
        assertEquals(HomeDestination.LiveSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        navigator.macRevisionChanged(15)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        navigator.macRevisionChanged(0)
        assertEquals(HomeDestination.BrowserSession(BROWSER, "Widget Docs"), navigator.state.value.destination)
        assertEquals("the stream is untouched", listOf("openStream ${BROWSER.guid}"), streamCalls())
    }

    @Test
    fun settingsAndBackNeverTouchTheStream() {
        onSessions()
        navigator.openSettings()
        assertEquals(HomeDestination.Settings, navigator.state.value.destination)
        navigator.back()
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
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
            val collector = backgroundScope.launch { navigator.state.collect { seen += it.destination } }
            runCurrent()
            collector.cancel()
            runCurrent()
        }
        assertEquals(listOf<HomeDestination>(HomeDestination.LiveSession(TERMINAL, "zsh"), HomeDestination.LiveSession(TERMINAL, "zsh")), seen.toList())
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun conversationOpenAndCloseDriveRepository() {
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        navigator.openConversation(CHAT)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.state.value.destination)
        assertEquals(listOf("openChat $CHAT", "visible $CHAT"), calls.toList())
        calls.clear()
        navigator.back()
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
        assertEquals(listOf("closeChat $CHAT", "visible null"), calls.toList())
    }

    @Test
    fun conversationOpensOnTheChatsTab() {
        onSessions()
        navigator.openConversation(CHAT)
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT)), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), navigator.stack(HomeTab.SESSIONS))
    }

    @Test
    fun newChatReplacesTheStackWithItsConversation() {
        navigator.openConversation(CHAT)
        navigator.openNewChat()
        assertEquals(HomeDestination.NewChat, navigator.state.value.destination)
        calls.clear()
        navigator.openConversation(OTHER_CHAT, replacingStack = true)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(OTHER_CHAT)), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf("openChat $OTHER_CHAT", "closeChat $CHAT"), chatCalls())
        assertEquals("visible $OTHER_CHAT", calls.last())
        // Back from the new conversation is the list, as on iOS.
        navigator.back()
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
    }

    @Test
    fun goToSessionReturnsToConversation() {
        navigator.openConversation(CHAT)
        calls.clear()
        navigator.openSession(TERMINAL, "Build shell")
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "Build shell"), navigator.state.value.destination)
        assertEquals(listOf("openStream ${TERMINAL.guid}", "visible null"), calls.toList())
        calls.clear()
        navigator.back()
        assertEquals(HomeDestination.Conversation(CHAT), navigator.state.value.destination)
        assertEquals("the conversation stays open underneath", listOf("closeStream", "visible $CHAT"), calls.toList())
    }

    @Test
    fun workgroupMemberReturnsToWorkgroupWithItsWatchKept() {
        navigator.openConversation(CHAT)
        navigator.openWorkgroup(WORKGROUP, "Release crew")
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "Release crew"), navigator.state.value.destination)
        assertTrue("startWorkgroup $WORKGROUP" in calls)
        calls.clear()
        navigator.openSession(PLANNER, "Planner")
        navigator.back()
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "Release crew"), navigator.state.value.destination)
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
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        // Back on Chats, the conversation is on screen again; no stream comes back.
        navigator.selectTab(HomeTab.CHATS)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.state.value.destination)
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
        assertEquals(HomeDestination.Settings, navigator.state.value.destination)
        calls.clear()
        navigator.selectTab(HomeTab.CHATS)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.state.value.destination)
        assertEquals(listOf("visible $CHAT"), calls.toList())
        navigator.selectTab(HomeTab.SESSIONS)
        assertEquals(HomeDestination.Settings, navigator.state.value.destination)
        // Back acts on the selected tab's stack only.
        navigator.back()
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CHAT)), navigator.stack(HomeTab.CHATS))
    }

    @Test
    fun defaultTabIsDecidedOnceAtFirstLinkUp() {
        // Until the first link, the Chats root (its connecting state).
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        // Decided: AI on at the next link does not move the owner back to Chats.
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)

        val other = HomeNavigator({}, {}, {}, {}, {})
        other.linkUp(aiAvailable = true)
        assertEquals(HomeTab.CHATS, other.state.value.tab)
    }

    @Test
    fun defaultNeverOverridesAChosenTab() {
        navigator.selectTab(HomeTab.SESSIONS)
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)

        val other = HomeNavigator({}, {}, {}, {}, {})
        other.selectTab(HomeTab.CHATS)
        other.linkUp(aiAvailable = false)
        // Chosen, but AI off on the Chats root still moves to Sessions (iOS's rescue).
        assertEquals(HomeTab.SESSIONS, other.state.value.tab)
    }

    @Test
    fun laterLinksDoNotMoveTabs() {
        navigator.linkUp(aiAvailable = true)
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        navigator.selectTab(HomeTab.SESSIONS)
        repeat(3) { navigator.linkUp(aiAvailable = true) }
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        navigator.selectTab(HomeTab.CHATS)
        navigator.openConversation(CHAT)
        // A later link with AI off, a conversation open: it stays, read-only.
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeDestination.Conversation(CHAT), navigator.state.value.destination)
    }

    @Test
    fun aiOffMovesToSessionsOnlyFromChatsRoot() {
        navigator.linkUp(aiAvailable = true)
        navigator.openConversation(CHAT)
        navigator.aiAvailabilityChanged(false)
        assertEquals("a conversation open stays, read-only", HomeDestination.Conversation(CHAT), navigator.state.value.destination)
        navigator.back()
        // Back to the root with AI still off: nothing moves until AI turns off again.
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        // On Sessions, or on Chats beyond the root, AI off moves nothing.
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        navigator.selectTab(HomeTab.CHATS)
        navigator.openNewChat()
        navigator.aiAvailabilityChanged(false)
        assertEquals(HomeDestination.NewChat, navigator.state.value.destination)
        // AI back on never moves tabs.
        navigator.back()
        navigator.aiAvailabilityChanged(true)
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
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
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        assertEquals(HomeDestination.ChatList, navigator.state.value.destination)
        assertEquals(listOf(HomeDestination.ChatList), navigator.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), navigator.stack(HomeTab.SESSIONS))
        // A new pairing decides its default tab again.
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
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
        assertEquals(HomeDestination.LiveSession(PLANNER, "Planner: claude"), navigator.state.value.destination)
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

    @Test
    fun tabAndDestinationArePublishedTogether() {
        navigator.openConversation(CHAT)
        navigator.openSession(TERMINAL)
        val seen = navigator.state.value
        assertEquals(HomeTab.CHATS, seen.tab)
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), seen.destination)
        assertEquals("CHATS/2/session:${TERMINAL.guid}", seen.key)
        assertEquals(setOf("CHATS/0/chats", "CHATS/1/conversation:$CHAT", "CHATS/2/session:${TERMINAL.guid}", "SESSIONS/0/sessions"), seen.stackKeys)
        navigator.selectTab(HomeTab.SESSIONS)
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        assertEquals(HomeDestination.SessionList, navigator.state.value.destination)
        assertFalse(navigator.state.value.toString().contains("zsh"))
    }

    @Test
    fun aCallbackThatThrowsStillReconcilesAndPublishes() {
        val opened = CopyOnWriteArrayList<String>()
        val failing = HomeNavigator(
            openStream = { throw IllegalStateException("stream") },
            closeStream = {},
            openChat = { opened += it },
            closeChat = {},
            setVisibleChat = { throw IllegalStateException("visible") },
        )
        val thrown = runCatching { failing.openConversation(CHAT) }.exceptionOrNull()
        assertEquals("visible", thrown?.message)
        assertEquals(listOf(CHAT), opened.toList())
        assertEquals(HomeDestination.Conversation(CHAT), failing.state.value.destination)
        val again = runCatching { failing.openSession(TERMINAL) }.exceptionOrNull()
        assertEquals("the first failure is rethrown, the rest suppressed", "stream", again?.message)
        assertEquals(listOf("visible"), again?.suppressed?.map { it.message })
        assertEquals(HomeDestination.LiveSession(TERMINAL, "zsh"), failing.state.value.destination)
    }

    @Test
    fun aCallFromACallbackIsQueuedAfterTheCurrentOne() {
        val order = CopyOnWriteArrayList<String>()
        lateinit var nesting: HomeNavigator
        nesting = HomeNavigator(
            openStream = {},
            closeStream = {},
            openChat = {
                order += "openChat $it"
                // A callback navigating: applied after this change, with its own comparison.
                if (it == CHAT) nesting.back()
            },
            closeChat = { order += "closeChat $it" },
            setVisibleChat = { order += "visible $it" },
        )
        nesting.openConversation(CHAT)
        assertEquals(listOf("openChat $CHAT", "visible $CHAT", "closeChat $CHAT", "visible null"), order.toList())
        assertEquals(HomeDestination.ChatList, nesting.state.value.destination)
    }

    @Test
    fun nothingIsPushedWhileNotAccepting() {
        var paired = true
        val gated = HomeNavigator({ calls += "openStream" }, {}, { calls += "openChat $it" }, {}, {}, accepting = { paired })
        paired = false
        gated.openConversation(CHAT)
        gated.openConversation(CHAT, replacingStack = true)
        gated.openSession(TERMINAL)
        gated.openWorkgroup(WORKGROUP, "crew")
        gated.openNewChat()
        gated.openSettings()
        assertEquals(listOf(HomeDestination.ChatList), gated.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), gated.stack(HomeTab.SESSIONS))
        assertEquals(emptyList<String>(), calls.toList())
        // Tabs and Back still work.
        gated.selectTab(HomeTab.SESSIONS)
        assertEquals(HomeTab.SESSIONS, gated.state.value.tab)
    }

    @Test
    fun resetRunsItsHookUnderTheLockAfterClosing() {
        val seenByHook = CopyOnWriteArrayList<String>()
        lateinit var hooked: HomeNavigator
        hooked = HomeNavigator(
            openStream = {},
            closeStream = { seenByHook += "closeStream" },
            openChat = {},
            closeChat = { seenByHook += "closeChat $it" },
            setVisibleChat = {},
            onReset = { seenByHook += "hook ${hooked.state.value.destination}" },
        )
        hooked.openConversation(CHAT)
        hooked.openSession(TERMINAL)
        hooked.reset()
        assertEquals(listOf("closeStream", "closeChat $CHAT", "hook ChatList"), seenByHook.toList())
    }

    @Test
    fun theOldPairingsLinksAreIgnoredAfterReset() {
        navigator.linkUp(aiAvailable = true)
        val oldLink = ChatAvailability(generation, aiAvailable = false, aiOffs)
        navigator.reset()
        // A late value from the old pairing neither decides the tab nor moves it.
        navigator.availabilityChanged(oldLink)
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        navigator.availabilityChanged(null)
        navigator.linkUp(aiAvailable = false)
        assertEquals("the new pairing's first link decides", HomeTab.SESSIONS, navigator.state.value.tab)
    }

    @Test
    fun conflatedAiFlipsStillLeaveTheChatsRoot() {
        navigator.linkUp(aiAvailable = true)
        // Off, on, off arrive as one value: the count says AI turned off.
        aiOffs += 2
        navigator.availabilityChanged(ChatAvailability(generation, aiAvailable = false, aiOffs))
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        // Off then on, conflated to "on": AI did turn off, as iOS would have seen it.
        navigator.selectTab(HomeTab.CHATS)
        aiOffs += 1
        navigator.availabilityChanged(ChatAvailability(generation, aiAvailable = true, aiOffs))
        assertEquals(HomeTab.SESSIONS, navigator.state.value.tab)
        // The same value again moves nothing.
        navigator.selectTab(HomeTab.CHATS)
        navigator.availabilityChanged(ChatAvailability(generation, aiAvailable = true, aiOffs))
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
    }

    @Test
    fun anOwnersPushBeforeTheFirstLinkCountsAsChosen() {
        // Settings open on the Chats tab when the first link comes, with AI off: the owner is
        // left where they are (the default would have moved them to Sessions).
        navigator.openSettings()
        navigator.linkUp(aiAvailable = false)
        assertEquals(HomeTab.CHATS, navigator.state.value.tab)
        assertEquals(HomeDestination.Settings, navigator.state.value.destination)
        // Without the push, the same link moves the home to Sessions.
        val other = HomeNavigator({}, {}, {}, {}, {})
        other.linkUp(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, other.state.value.tab)
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
