package dev.bugobliterator.itermbuddy.core.sessions

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import dev.bugobliterator.itermbuddy.core.chat.DeleteFromOutcome
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupState
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import dev.bugobliterator.itermbuddy.ui.HomeTab
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.ui.chats.ChatListContent
import dev.bugobliterator.itermbuddy.ui.chats.NewChatStatus
import dev.bugobliterator.itermbuddy.ui.conversation.DELETE_FROM_FAILED_TEXT
import dev.bugobliterator.itermbuddy.ui.conversation.deleteFromRefusalText
import dev.bugobliterator.itermbuddy.ui.workgroup.memberSession
import dev.bugobliterator.itermbuddy.ui.workgroup.showWorkgroupRoute
import dev.bugobliterator.itermbuddy.ui.workgroup.workgroupRouteActions
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import dev.bugobliterator.itermbuddy.ui.conversation.GoToSession
import dev.bugobliterator.itermbuddy.ui.conversation.LinkTarget
import dev.bugobliterator.itermbuddy.ui.conversation.WorkgroupLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The Chats tab as `CompanionApp` wires it ([dev.bugobliterator.itermbuddy.wireHome]): the
 * navigator drives the chat repository, the workgroup watches and the stream; the default tab
 * follows the first link; the shared list and conversation contents reset on unpair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsNavigationWiringTest {
    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    private suspend fun TestScope.connected(aiAvailable: Boolean = true, listSharingStopMillis: Long = 5_000): SessionsRig {
        val rig = SessionsRig(this, listSharingStopMillis = listSharingStopMillis)
        rig.answerFetches()
        rig.mac.helloAiAvailable = aiAvailable
        rig.connect()
        settle()
        return rig
    }

    @Test
    fun defaultTabFollowsTheFirstLinkUp() = runTest {
        val withAi = connected(aiAvailable = true)
        assertEquals(HomeTab.CHATS, withAi.navigator.state.value.tab)
        assertEquals(HomeDestination.ChatList, withAi.navigator.state.value.destination)
        val withoutAi = connected(aiAvailable = false)
        assertEquals(HomeTab.SESSIONS, withoutAi.navigator.state.value.tab)
    }

    @Test
    fun aiTurningOffOnTheChatsRootMovesToSessions() = runTest {
        val rig = connected()
        rig.chatMac.flipAi(false)
        settle()
        assertEquals(HomeTab.SESSIONS, rig.navigator.state.value.tab)
        // The owner may go back to Chats: the AI-off panel shows, and AI back on moves nothing.
        rig.navigator.selectTab(HomeTab.CHATS)
        rig.chatMac.flipAi(true)
        settle()
        assertEquals(HomeTab.CHATS, rig.navigator.state.value.tab)
    }

    @Test
    fun navigatorOpensAndClosesConversationsInTheRepository() = runTest {
        val rig = connected()
        val chatID = FakeChatMac.SAMPLE_CHAT
        rig.navigator.openConversation(chatID)
        settle()
        assertTrue(rig.chats.conversation(chatID).value is ConversationState.Shown)
        val content = rig.graph.conversationState(chatID)
        val seen = CopyOnWriteArrayList<ConversationContent?>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.collect { seen += it } }
        settle()
        assertEquals(chatID, content.value?.chatID)
        // The Workgroups menu names the chat's claimed workgroup: its scope ID was resolved on open.
        assertEquals(listOf(WorkgroupLink(WORKGROUP, "Widget Review")), content.value?.workgroups)
        assertSame("one shared content per open conversation", content, rig.graph.conversationState(chatID))
        collector.cancel()
        rig.navigator.back()
        settle()
        assertNotSame("closing the conversation drops its shared content", content, rig.graph.conversationState(chatID))
    }

    @Test
    fun sharedChatContentsResetOnUnpair() = runTest {
        val rig = connected(listSharingStopMillis = 1_000)
        val chatID = FakeChatMac.SAMPLE_CHAT
        val listCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.graph.chatList.collect { } }
        rig.navigator.openConversation(chatID)
        val conversation = rig.graph.conversationState(chatID)
        settle()
        assertTrue(rig.graph.chatListNow() is ChatListContent.Rows)
        assertNotNull(conversation.value)

        rig.client.unpair()
        runCurrent()
        assertEquals("the old pairing's rows are gone at once", ChatListContent.Loading, rig.graph.chatListNow())
        assertNotSame(conversation, rig.graph.conversationState(chatID))
        assertNull(rig.graph.conversationState(chatID).value)
        listCollector.cancel()
        // Sharing stops after the injected time; a later reader starts from Loading too.
        advanceTimeBy(2.seconds)
        assertEquals(ChatListContent.Loading, rig.graph.chatListNow())
    }

    /** A visit longer than the sharing stop: coming back shows the content as it was, on the first frame. */
    @Test
    fun backAfterALongVisitShowsThePreviousContentAtOnce() = runTest {
        val rig = connected(listSharingStopMillis = 1_000)
        val chatID = FakeChatMac.SAMPLE_CHAT
        val listCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.graph.chatList.collect { } }
        settle()
        val rows = rig.graph.chatListNow() as ChatListContent.Rows
        listCollector.cancel()

        rig.navigator.openConversation(chatID)
        val conversation = rig.graph.conversationState(chatID)
        val conversationCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { conversation.collect { } }
        settle()
        val shown = conversation.value ?: throw AssertionError("no conversation content")
        conversationCollector.cancel()
        // A member's live view for well over the sharing stop: nobody collects either content.
        rig.navigator.openSession(SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "", false), "zsh")
        advanceTimeBy(30.seconds)
        runCurrent()
        rig.navigator.back()
        assertSame("the same shared content, still open", conversation, rig.graph.conversationState(chatID))
        assertEquals(shown, conversation.value)
        rig.navigator.back()
        val first = rig.graph.chatListNow() as ChatListContent.Rows
        assertEquals(rows, first)
        // Collected again, the presenter kept its rows: the same models, so nothing re-renders.
        val again = CopyOnWriteArrayList<ChatListContent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.graph.chatList.collect { again += it } }
        settle()
        assertTrue(again.none { it == ChatListContent.Loading })
        assertSame(rows.rows.first(), (rig.graph.chatListNow() as ChatListContent.Rows).rows.first())
        collector.cancel()
    }

    @Test
    fun anOpenRacingTheUnpairLeavesNoDestination() = runTest {
        val rig = connected()
        val chatID = FakeChatMac.SAMPLE_CHAT
        rig.navigator.openConversation(chatID)
        rig.graph.newChat.create(NewChatMode.Orchestrator)
        rig.client.unpair()
        runCurrent()
        // Late opens from the old pairing: a mention tap, the create's reply.
        rig.navigator.openConversation(chatID)
        rig.navigator.openSession(SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "", false), "zsh")
        settle()
        assertEquals(listOf(HomeDestination.ChatList), rig.navigator.stack(HomeTab.CHATS))
        assertEquals(listOf(HomeDestination.SessionList), rig.navigator.stack(HomeTab.SESSIONS))
        assertNull(rig.controller.activeStream.value)
        assertEquals(NewChatStatus.Idle, rig.graph.newChat.status.value)
    }

    @Test
    fun liveViewClosesOnTabSwitchAndOnUnpair() = runTest {
        val rig = connected()
        rig.navigator.openConversation(FakeChatMac.SAMPLE_CHAT)
        rig.navigator.openSession(SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "", false), "zsh")
        settle()
        assertTrue(rig.controller.state.value !is LiveViewState.Closed)
        rig.navigator.selectTab(HomeTab.SESSIONS)
        settle()
        assertEquals(LiveViewState.Closed, rig.controller.state.value)

        rig.navigator.selectTab(HomeTab.CHATS)
        rig.navigator.openSession(SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "", false), "zsh")
        settle()
        assertTrue(rig.controller.state.value !is LiveViewState.Closed)
        rig.client.unpair()
        settle()
        assertEquals(LiveViewState.Closed, rig.controller.state.value)
    }

    /** A conversation under a live view stays open (subscribed, kept) but not on screen; Back makes it visible again. */
    @Test
    fun conversationUnderALiveViewStaysOpenAndIsVisibleAfterBack() = runTest {
        val rig = connected()
        val chatID = FakeChatMac.SAMPLE_CHAT
        rig.navigator.openConversation(chatID)
        settle()
        rig.navigator.openSession(SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "", false), "zsh")
        rig.chatMac.streamReply(chatID, appends = 1)
        settle()
        assertTrue("still open underneath", rig.chats.conversation(chatID).value is ConversationState.Shown)
        assertEquals("a turn ending off screen is Done", AgentState.DONE, rig.chats.activity.value[chatID])
        rig.navigator.back()
        settle()
        assertTrue(rig.chats.conversation(chatID).value is ConversationState.Shown)
        assertEquals("on screen again: read", AgentState.IDLE, rig.chats.activity.value[chatID])
        rig.chatMac.streamReply(chatID, appends = 1)
        settle()
        assertEquals("a turn ending while visible is not Done", AgentState.IDLE, rig.chats.activity.value[chatID])
    }

    @Test
    fun workgroupWatchIsKeptWhileItsDestinationIsStacked() = runTest {
        val rig = connected()
        rig.navigator.openConversation(FakeChatMac.SAMPLE_CHAT)
        assertNull(rig.graph.workgroupWatch(WORKGROUP))
        rig.navigator.openWorkgroup(WORKGROUP, "Release crew")
        val watch = rig.graph.workgroupWatch(WORKGROUP) ?: throw AssertionError("no watch for a stacked workgroup")
        // Driven as the route drives it: shown, a member tapped, the route leaves, Back, shown again.
        var shown = showWorkgroupRoute(watch)
        val actions = workgroupRouteActions(watch, rig.navigator::back, rig.navigator::openSession)
        settle()
        val members = (watch.state.value as WorkgroupState.Shown).info.members
        val fetches = rig.mac.receivedCases().count { it == "fetchWorkgroupInfo" }
        actions.openMember(memberSession(members.first(), (rig.repository.state.value as TreeState.Loaded).tree)!!)
        shown.close()
        assertTrue(rig.navigator.state.value.destination is HomeDestination.LiveSession)
        advanceTimeBy(20.seconds)
        assertEquals("no polling while the route is away", fetches, rig.mac.receivedCases().count { it == "fetchWorkgroupInfo" })
        rig.navigator.back()
        assertSame(watch, rig.graph.workgroupWatch(WORKGROUP))
        shown = showWorkgroupRoute(watch)
        assertTrue("no Loading on return", watch.state.value is WorkgroupState.Shown)
        actions.back()
        shown.close()
        assertNull(rig.graph.workgroupWatch(WORKGROUP))
        assertEquals(HomeDestination.Conversation(FakeChatMac.SAMPLE_CHAT), rig.navigator.state.value.destination)
    }

    @Test
    fun conversationActionsReachTheNavigatorAndRepository() = runTest {
        val rig = connected()
        val chatID = FakeChatMac.SAMPLE_CHAT
        rig.navigator.openConversation(chatID)
        settle()
        val actions = rig.graph.conversationActions(chatID)
        val conversation = HomeDestination.Conversation(chatID)

        // A session mention opens the view its tree row calls for (a browser here).
        actions.openMention(LinkTarget.Session(BROWSER_GUID, "Widget Docs"))
        val browser = rig.navigator.state.value.destination as HomeDestination.BrowserSession
        assertEquals(BROWSER_GUID, browser.session.guid)
        assertTrue(browser.session.isBrowser)
        actions.back()
        assertEquals(conversation, rig.navigator.state.value.destination)
        // An unknown session opens a terminal view by its ID, which the Mac resolves.
        actions.openMention(LinkTarget.Session("ptys_0000000000000", "gone"))
        assertEquals(HomeDestination.LiveSession(SessionSummary("ptys_0000000000000", "gone", "", false), "gone"), rig.navigator.state.value.destination)
        actions.back()

        actions.openMention(LinkTarget.Workgroup(WORKGROUP, "Release crew"))
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "Release crew"), rig.navigator.state.value.destination)
        rig.navigator.back()
        actions.openWorkgroup(WorkgroupLink(WORKGROUP, "From the menu"))
        assertEquals(HomeDestination.Workgroup(WORKGROUP, "From the menu"), rig.navigator.state.value.destination)
        rig.navigator.back()

        val target = GoToSession(SessionSummary("ptys_1F5Y7R3MQ9KD4", "claude", "planning", false), "Planner: claude")
        actions.goToSession(target)
        assertEquals(HomeDestination.LiveSession(target.session, target.title), rig.navigator.state.value.destination)
        actions.back()
        assertEquals(conversation, rig.navigator.state.value.destination)

        actions.setMuted(true)
        settle()
        val entry = (rig.chats.list.value as ChatListState.Loaded).entries.single { it.chat.id == chatID }
        assertEquals(true, entry.muted)
        assertEquals(1, rig.mac.receivedCases().count { it == "setChatMuted" })

        // A refusal comes back to the screen's dialog, never thrown.
        val refused = actions.deleteFromHere("00000000-0000-4000-8000-000000000000")
        assertEquals(DeleteFromOutcome.NOT_FOUND, refused)
        assertEquals(DELETE_FROM_FAILED_TEXT, deleteFromRefusalText(refused))
        assertEquals(0, rig.mac.receivedCases().count { it == "deleteMessages" })
        actions.retry()
        settle()
    }

    @Test
    fun newChatOpensItsConversationInPlaceOfTheStack() = runTest {
        val rig = connected()
        rig.graph.chatListActions.openChat(FakeChatMac.SAMPLE_CHAT)
        rig.navigator.back()
        rig.graph.chatListActions.newChat()
        assertEquals(HomeDestination.NewChat, rig.navigator.state.value.destination)
        rig.graph.newChat.create(NewChatMode.Orchestrator)
        settle()
        assertEquals(listOf(HomeDestination.ChatList, HomeDestination.Conversation(CREATED_CHAT)), rig.navigator.stack(HomeTab.CHATS))
    }

    private companion object {
        const val WORKGROUP = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
        const val BROWSER_GUID = "ptys_4Q8S1D6KZ3MAC"
        const val CREATED_CHAT = "2F3E4D5C-6B7A-4980-A1B2-C3D4E5F60718"
    }
}
