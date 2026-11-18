package dev.bugobliterator.itermbuddy.core.sessions

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.TileState
import dev.bugobliterator.itermbuddy.core.stream.Tiles
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unpairing, from this phone or from the Mac, leaves nothing of the old pairing behind: the
 * tree, the navigation, the watch and the chats, through the same wiring `CompanionApp` uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnpairCleanupTest {
    @Test
    fun localAndRemoteUnpairClearEverything() {
        unpairClearsEverything(fromMac = false)
        unpairClearsEverything(fromMac = true)
    }

    private fun unpairClearsEverything(fromMac: Boolean) = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        val loaded = rig.repository.state.value as TreeState.Loaded
        val session = loaded.tree.windows.first().tabs.last().panes.single().session
        rig.navigator.openSession(session, session.name)
        runCurrent()
        assertEquals(HomeDestination.LiveSession(session, session.name), rig.navigator.destination.value)
        assertEquals(SessionsRig.ORIGIN, rig.client.relayOrigin.value)
        assertTrue(rig.controller.state.value is LiveViewState.Live)
        assertNotNull(rig.controller.activeStream.value)
        // History tiles cached: the fixture config has history up to 3999.
        rig.mac.onRequest("fetchHistoryTile") { Tiles.tilePayload(Tiles.fetchBody(it)) }
        rig.controller.historyTiles.want(3900, 3999)
        runCurrent()
        assertEquals(2, rig.controller.historyTiles.tiles.value.values.count { it is TileState.Ready })
        // What the owner typed in the UI: the search, a draft and an armed tray.
        rig.graph.search.set("claude")
        rig.graph.drafts.set(session.guid, "make test")
        rig.graph.inputMemories.of(session.guid).expanded = true

        if (fromMac) {
            rig.mac.sendUnpaired()
            withTimeout(1.minutes) { rig.client.state.first { it is ClientState.Failed } }
        } else {
            rig.client.unpair()
        }
        runCurrent()
        assertEquals(false, rig.client.paired.value)
        assertNull("nothing stored, so no relay to show", rig.client.relayOrigin.value)
        assertEquals(TreeState.Loading, rig.repository.state.value)
        assertEquals(HomeDestination.SessionList, rig.navigator.destination.value)
        assertEquals(LiveViewState.Closed, rig.controller.state.value)
        assertNull(rig.controller.activeStream.value)
        assertEquals(emptyMap<Long, TileState>(), rig.controller.historyTiles.tiles.value)
        assertEquals("", rig.graph.search.query.value)
        assertEquals("", rig.graph.drafts.get(session.guid))
        assertFalse(rig.graph.inputMemories.of(session.guid).expanded)

        // Pair again, with another Mac: it connects, and nothing of the old watch starts.
        val keys = newStaticKeyPair()
        val code = PairingCode(keys.second, "sessions-rig-second-pid", SessionsRig.ORIGIN)
        val second = FakeMac(rig.relay, code, keys.first, backgroundScope)
        rig.answerFetches(second, SessionsRig.PUSH)
        second.start()
        rig.client.pair(code)
        withTimeout(1.minutes) { second.awaitingCode.first { it != null } }
        second.enterCode(accept = true)
        withTimeout(1.minutes) { rig.client.state.first { it is ClientState.Connected } }
        advanceTimeBy(30.seconds)
        assertEquals(true, rig.client.paired.value)
        assertEquals(emptyList<String>(), second.receivedCases().filter { it == "startSessionStream" })
        assertEquals(LiveViewState.Closed, rig.controller.state.value)
        assertEquals(TreeState.Loaded(SessionsRig.treeOf(SessionsRig.PUSH), refreshing = false), rig.repository.state.value)
    }

    @Test
    fun localAndRemoteUnpairClearChats() {
        unpairClearsChats(fromMac = false)
        unpairClearsChats(fromMac = true)
    }

    private fun unpairClearsChats(fromMac: Boolean) = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        advanceTimeBy(5.seconds)
        val chatID = FakeChatMac.SAMPLE_CHAT
        rig.chats.open(chatID)
        val conversation = rig.chats.conversation(chatID)
        rig.chatMac.streamReply(chatID, appends = 2)
        advanceTimeBy(5.seconds)
        assertTrue(rig.chats.list.value is ChatListState.Loaded)
        assertTrue(conversation.value is ConversationState.Shown)
        assertNotNull(rig.chatSeen.seen(chatID))
        assertEquals("open but never on screen", AgentState.DONE, rig.chats.activity.value[chatID])

        if (fromMac) {
            rig.mac.sendUnpaired()
            withTimeout(1.minutes) { rig.client.state.first { it is ClientState.Failed } }
        } else {
            rig.client.unpair()
        }
        advanceTimeBy(5.seconds)
        assertEquals(ChatListState.Loading, rig.chats.list.value)
        assertEquals(emptyList<ChatRow>(), rig.chats.rows.value)
        assertEquals(emptyMap<String, AgentState>(), rig.chats.activity.value)
        assertSame("the screen's flow is reset, not replaced", conversation, rig.chats.conversation(chatID))
        assertEquals(ConversationState.Loading, conversation.value)
        assertNull(rig.chats.macRevision.value)
        assertNull("the seen marks belong to the old pairing", rig.chatSeen.seen(chatID))
    }

    @Test
    fun searchSurvivesOpeningASessionAndComingBack() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        val loaded = rig.repository.state.value as TreeState.Loaded
        val session = loaded.tree.windows.first().tabs.last().panes.single().session
        rig.graph.search.set("vim")
        rig.navigator.openSession(session, session.name)
        runCurrent()
        rig.navigator.back()
        runCurrent()
        assertEquals(HomeDestination.SessionList, rig.navigator.destination.value)
        assertEquals("vim", rig.graph.search.query.value)
        assertFalse("the holder never shows the query", "vim" in rig.graph.search.toString())
    }

    @Test
    fun everyCleanupRunsEachTimePairedTurnsFalse() = runTest {
        val paired = MutableStateFlow<Boolean?>(null)
        val ran = CopyOnWriteArrayList<String>()
        clearOnUnpair(paired, backgroundScope, listOf({ ran += "navigator" }, { ran += "repository" }))
        runCurrent()
        assertEquals(emptyList<String>(), ran)
        paired.value = true
        runCurrent()
        assertEquals(emptyList<String>(), ran)
        paired.value = false
        runCurrent()
        assertEquals(listOf("navigator", "repository"), ran)
        paired.value = true
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals(listOf("navigator", "repository", "navigator", "repository"), ran)
    }

    @Test
    fun aThrowingCleanupDoesNotSkipTheOthers() = runTest {
        val paired = MutableStateFlow<Boolean?>(true)
        val ran = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        clearOnUnpair(paired, backgroundScope, listOf({ error("boom") }, { ran += "repository" }), log = { logs += it })
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals(listOf("repository"), ran)
        assertEquals(1, logs.size)
        paired.value = true
        runCurrent()
        paired.value = false
        runCurrent()
        assertEquals("still collecting after a failure", listOf("repository", "repository"), ran)
    }
}
