package dev.bugobliterator.itermbuddy.ui.chats

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.DEFUNCT_SESSION_TEXT
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.ChatListSnapshot
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The chat list's rules: search, rendered snippets, row actions, state labels and what the list shows. */
class ChatListRulesTest {
    private val buildShell = "ptys_7K2M9Q4R8T1VH"
    private val goneShell = "ptys_3HX6W0ZD5N2BT"
    private val workgroup = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"

    private val resolutions = mapOf(
        buildShell to MentionResolution(buildShell, "Build shell", buildShell, null),
        workgroup to MentionResolution(workgroup, "Release crew", null, "5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"),
        goneShell to MentionResolution(goneShell, null, null, null),
    )

    private fun row(
        id: String,
        title: String,
        snippet: String?,
        muted: Boolean? = false,
        state: AgentState = AgentState.IDLE,
        unread: Boolean = false,
    ) = ChatRow(ChatListEntry(ChatSummary(id, title, 1.0, 2.0, false, null, null, emptyList(), null), snippet, muted), state, unread)

    private val deploy = row("chat-deploy", "Deploy pipeline", "Ask @$buildShell to run the tests")
    private val kittens = row("chat-kittens", "Kittens", "**bold** reply about yarn")
    private val orchestrator = row("chat-orchestrator", "Orchestrator", null)
    private val rows = listOf(deploy, kittens, orchestrator)

    private fun ids(rows: List<ChatRow>) = rows.map { it.entry.chat.id }

    @Test
    fun searchMatchesTitleAndRenderedSnippet() {
        assertEquals(rows, filterChatRows(rows, "", resolutions))
        assertEquals(rows, filterChatRows(rows, " \t ", resolutions))
        // Title, case-insensitively.
        assertEquals(listOf(kittens), filterChatRows(rows, "KITTENS", resolutions))
        // The snippet as shown: the mention's name matches, its raw ID does not.
        assertEquals(listOf(deploy), filterChatRows(rows, "build shell", resolutions))
        assertEquals(emptyList<ChatRow>(), filterChatRows(rows, "ptys_7K2", resolutions))
        // Unresolved, the raw ID is what shows, so it matches.
        assertEquals(listOf(deploy), filterChatRows(rows, "ptys_7k2m9q4r8t1vh", emptyMap()))
        // Every term must match the same row, across title and snippet.
        assertEquals(listOf(deploy), filterChatRows(rows, "pipeline tests", resolutions))
        assertEquals(emptyList<ChatRow>(), filterChatRows(rows, "orchestrator yarn", resolutions))
        // A chat without a snippet matches on its title.
        assertEquals(listOf(orchestrator), filterChatRows(rows, "orch", resolutions))
        assertEquals(listOf(deploy), filterChatRows(rows, "PIPELINE", resolutions))
    }

    @Test
    fun snippetMentionsShowNames() {
        assertEquals(
            "Ask Build shell and Release crew, not $DEFUNCT_SESSION_TEXT.",
            renderedSnippet("Ask @$buildShell and @$workgroup, not @$goneShell.", resolutions),
        )
        // Unanswered mentions stay as written; a bare stable ID (no "@") is not a mention in a snippet.
        val unknown = "ptys_9MZQ4R8T1VH2K"
        assertEquals("See @$unknown and $buildShell", renderedSnippet("See @$unknown and $buildShell", resolutions))
        // Several mentions of the same session, and one at the very end.
        assertEquals("Build shell/Build shell", renderedSnippet("@$buildShell/@$buildShell", resolutions))
        assertNull(renderedSnippet(null, resolutions))
        assertNull(renderedSnippet("", resolutions))
    }

    @Test
    fun muteHiddenBelowRevisionFive() {
        val unmuted = row("a", "t", null, muted = false).entry
        val muted = row("b", "t", null, muted = true).entry
        val unknown = row("c", "t", null, muted = null).entry
        assertEquals(listOf(ChatRowAction.DELETE), chatRowActions(unmuted, macRevision = null))
        assertEquals(listOf(ChatRowAction.DELETE), chatRowActions(muted, macRevision = 4))
        assertEquals(listOf(ChatRowAction.MUTE, ChatRowAction.DELETE), chatRowActions(unmuted, macRevision = 5))
        assertEquals(listOf(ChatRowAction.UNMUTE, ChatRowAction.DELETE), chatRowActions(muted, macRevision = 15))
        assertEquals(listOf(ChatRowAction.MUTE, ChatRowAction.DELETE), chatRowActions(unknown, macRevision = 5))
    }

    @Test
    fun stateLabels() {
        assertEquals("Working", agentStateLabel(AgentState.WORKING))
        assertEquals("Waiting for you", agentStateLabel(AgentState.WAITING))
        assertEquals("Done", agentStateLabel(AgentState.DONE))
        assertNull(agentStateLabel(AgentState.IDLE))
        assertNull(agentStateLabel(AgentState.UNKNOWN))
    }

    @Test
    fun rowsKeepMacOrder() {
        val macOrder = listOf(orchestrator, kittens, deploy)
        val state = ChatListState.Loaded(macOrder.map { it.entry }, refreshing = false)
        val content = chatListContent(state, macOrder, aiAvailable = true, macRevision = 15, query = "", resolutions = resolutions)
        assertEquals(ids(macOrder), (content as ChatListContent.Rows).rows.map { it.chatID })
        // Filtering keeps the Mac's order too.
        val filtered = chatListContent(state, macOrder, true, 15, "o", resolutions) as ChatListContent.Rows
        assertEquals(listOf("chat-orchestrator", "chat-kittens", "chat-deploy"), filtered.rows.map { it.chatID })
        val two = chatListContent(state, macOrder, true, 15, "p", resolutions) as ChatListContent.Rows
        // "p" is in "reply" and "Deploy pipeline", not in "Orchestrator".
        assertEquals(listOf("chat-kittens", "chat-deploy"), two.rows.map { it.chatID })
    }

    @Test
    fun rowModelCarriesWhatTheRowShows() {
        val working = row("w", "Working chat", "Ask @$buildShell", muted = true, state = AgentState.WORKING, unread = true)
        val state = ChatListState.Loaded(listOf(working.entry), refreshing = true, refreshFailed = true)
        val content = chatListContent(state, listOf(working), true, 5, "", resolutions) as ChatListContent.Rows
        assertTrue(content.refreshing)
        assertTrue(content.refreshFailed)
        val model = content.rows.single()
        assertEquals("Working chat", model.title)
        assertEquals("Ask Build shell", model.snippet)
        assertTrue(model.muted)
        assertTrue(model.unread)
        assertEquals(AgentState.WORKING, model.state)
        assertEquals("Working", model.stateLabel)
        assertEquals(2.0, model.lastModifiedMillis, 0.0)
        assertEquals(listOf(ChatRowAction.UNMUTE, ChatRowAction.DELETE), model.actions)
    }

    @Test
    fun listStates() {
        val loaded = ChatListState.Loaded(rows.map { it.entry }, refreshing = false)
        assertEquals(ChatListContent.Loading, chatListContent(ChatListState.Loading, emptyList(), true, null, "", emptyMap()))
        assertEquals(ChatListContent.Failed(retryable = true), chatListContent(ChatListState.Failed(true), emptyList(), true, 15, "", emptyMap()))
        assertEquals(ChatListContent.AiOff, chatListContent(ChatListState.AiUnavailable, emptyList(), false, 15, "", emptyMap()))
        // AI off wins over whatever list is kept (iOS checks it first, HomeView.swift:16-19).
        assertEquals(ChatListContent.AiOff, chatListContent(loaded, rows, false, 15, "", emptyMap()))
        val empty = ChatListState.Loaded(emptyList(), refreshing = true)
        assertEquals(ChatListContent.Empty(refreshing = true, refreshFailed = false), chatListContent(empty, emptyList(), true, 15, "kittens", emptyMap()))
        val noMatch = chatListContent(loaded, rows, true, 15, "  zebra ", emptyMap())
        assertEquals(ChatListContent.NoMatch("zebra", refreshing = false, refreshFailed = false), noMatch)
        assertEquals("No chats match \"zebra\".", (noMatch as ChatListContent.NoMatch).text)
    }

    @Test
    fun rowsBehindTheListShowLoadingNeverEmpty() {
        // Entries listed but rows not yet built for them: Loading, never a "No chats yet" flash.
        val loaded = ChatListState.Loaded(rows.map { it.entry }, refreshing = false)
        assertEquals(ChatListContent.Loading, chatListContent(loaded, emptyList(), true, 15, "", emptyMap()))
        assertEquals(ChatListContent.Loading, chatListContent(loaded, listOf(deploy), true, 15, "", emptyMap()))
        // No entries: Empty, whatever rows linger.
        val none = ChatListState.Loaded(emptyList(), refreshing = false)
        assertEquals(ChatListContent.Empty(refreshing = false, refreshFailed = false), chatListContent(none, rows, true, 15, "", emptyMap()))
    }

    @Test
    fun unchangedRowsKeepTheirModels() {
        val hashed = mutableListOf<Int>()
        val presenter = ChatListPresenter { bytes -> bytes?.let { hashed += it.remaining(); chatIconRef(it) } }
        fun withIcon(id: String, icon: String, state: AgentState = AgentState.IDLE, modified: Double = 2.0) = ChatRow(
            ChatListEntry(ChatSummary(id, "title $id", 1.0, modified, false, null, null, emptyList(), icon.toByteArray()), "snippet $id", false),
            state,
            false,
        )
        val first = listOf(withIcon("a", "icon-a"), withIcon("b", "icon-bb"))
        fun present(rows: List<ChatRow>) =
            (presenter.present(ChatListState.Loaded(rows.map { it.entry }, false), rows, true, 15, "", resolutions) as ChatListContent.Rows).rows
        val before = present(first)
        assertEquals(listOf(6, 7), hashed)
        // The same rows again (a new list value, new arrays): the same model instances, nothing hashed.
        val again = present(listOf(withIcon("a", "icon-a"), withIcon("b", "icon-bb")))
        assertSame(before[0], again[0])
        assertSame(before[1], again[1])
        assertEquals(listOf(6, 7), hashed)
        // A row whose state and time moved gets a new model, but its unchanged icon is not hashed again.
        val moved = present(listOf(withIcon("a", "icon-a", AgentState.WORKING, modified = 3.0), withIcon("b", "icon-bb")))
        assertNotSame(before[0], moved[0])
        assertSame(before[0].icon, moved[0].icon)
        assertSame(before[1], moved[1])
        assertEquals(listOf(6, 7), hashed)
        // A new icon is hashed.
        val newIcon = present(listOf(withIcon("a", "icon-new"), withIcon("b", "icon-bb")))
        assertNotEquals(before[0].icon, newIcon[0].icon)
        assertEquals(listOf(6, 7, 8), hashed)
    }

    @Test
    fun contentIsComputedOffTheCallingThread() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "chat-list-test") }
        try {
            val threads = CopyOnWriteArrayList<Thread>()
            val listThread = executor.submit<Thread> { Thread.currentThread() }.get()
            val withIcon = ChatRow(
                ChatListEntry(ChatSummary("i", "Icon chat", 1.0, 2.0, false, null, null, emptyList(), byteArrayOf(1, 2)), null, false),
                AgentState.IDLE,
                false,
            )
            val snapshot = ChatListSnapshot(ChatListState.Loaded(listOf(withIcon.entry), false), listOf(withIcon), aiAvailable = true)
            val content = chatListContents(
                flowOf(snapshot),
                flowOf(15),
                flowOf(""),
                flowOf(emptyMap()),
                executor.asCoroutineDispatcher(),
                iconRef = { threads += Thread.currentThread(); chatIconRef(it) },
            ).first()
            assertEquals(listOf("i"), (content as ChatListContent.Rows).rows.map { it.chatID })
            assertEquals(listOf(listThread), threads)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun contentNeverShowsInToString() {
        val secret = row("s", "SENTINEL-title", "SENTINEL-snippet @$buildShell")
        val state = ChatListState.Loaded(listOf(secret.entry), refreshing = false)
        val content = chatListContent(state, listOf(secret), true, 15, "SENTINEL", resolutions)
        val noMatch = chatListContent(state, listOf(secret), true, 15, "SENTINEL-query-only", resolutions)
        for (text in listOf(content.toString(), (content as ChatListContent.Rows).rows.single().toString(), noMatch.toString())) {
            assertFalse(text, "SENTINEL" in text || "Build shell" in text)
        }
    }

    @Test
    fun chatSearchHoldsTheQueryWithoutShowingIt() {
        val search = ChatSearch()
        search.set("SENTINEL query")
        assertEquals("SENTINEL query", search.query.value)
        assertFalse(search.toString(), "SENTINEL" in search.toString())
        assertTrue(search.toString(), "14" in search.toString())
        search.clear()
        assertEquals("", search.query.value)
    }
}
