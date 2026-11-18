package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ClientAction
import dev.bugobliterator.itermbuddy.core.chat.CommandContent
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.Transcript
import dev.bugobliterator.itermbuddy.core.chat.applyDelivery
import dev.bugobliterator.itermbuddy.core.chat.ChatListSnapshot
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptItemsTest {
    private val chat = "6A4F2C1E-9B3D-4E8A-B7C5-2D1F0E9A8B7C"
    private val stableID = "ptys_7K2M9Q4R8T1VH"
    private var serial = 0

    private fun id(n: Int) = "11111111-2222-4333-8444-%012d".format(n)

    private fun message(body: MessageBody, author: Participant = Participant.AGENT, uniqueID: String = id(++serial)) =
        ChatMessage(chat, author, body, 1795000000000.0 + serial, uniqueID)

    private fun conversation(): List<ChatMessage> = listOf(
        message(MessageBody.PlainText("Hello"), Participant.USER),
        message(MessageBody.Markdown("Hi **there**")),
        message(MessageBody.Markdown("Ask @$stableID")),
        message(MessageBody.Markdown("Last reply")),
    )

    @Test
    fun noticesAndRequestsAreClassified() {
        val messages = listOf(
            message(MessageBody.PlainText("Hello"), Participant.USER),
            message(MessageBody.Markdown("Hi")),
            message(MessageBody.ClientNotice(ClientAction.Notice("Chat renamed"))),
            message(MessageBody.WatcherEvent("Build finished")),
            message(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("ls"), safe = true)),
            message(MessageBody.SelectSessionRequest(terminal = true)),
            message(MessageBody.ClientNotice(ClientAction.WorkgroupPermissionRequest("R1", "wg-1", "Team", "Run tests"))),
            message(MessageBody.ClientNotice(ClientAction.EnableOrchestrationRequest("R2"))),
            message(MessageBody.ClientNotice(ClientAction.OfferLink(terminal = true, guid = stableID, name = "zsh"))),
            message(MessageBody.ClientNotice(ClientAction.OrchestrationPermissionGranted("wg-1", "Team"))),
            message(MessageBody.ExternalCommand("list_sessions", "Listing sessions", safe = null)),
            message(MessageBody.Hidden("renameChat")),
            message(MessageBody.Commit(id(1))),
            message(MessageBody.Unsupported),
        )
        val items = transcriptItems(messages, typing = false)
        val kinds = items.map {
            when (it) {
                is TranscriptItem.Bubble -> if (it.fromUser) "user" else "agent"
                is TranscriptItem.Notice -> "notice"
                is TranscriptItem.RequestCard -> "request"
                TranscriptItem.Typing -> "typing"
            }
        }
        assertEquals(
            listOf("user", "agent", "notice", "notice", "request", "request", "request", "request", "request", "request", "agent", "agent"),
            kinds,
        )
        // Hidden messages are not shown; every other message is, in order.
        assertEquals(messages.filterNot { it.hidden }.map { it.uniqueID }, items.map { it.key })
    }

    @Test
    fun typingItemOnlyWhileWorking() {
        assertEquals(listOf(AgentState.WORKING), AgentState.entries.filter(::showsTyping))
        assertFalse(showsTyping(null))
        val messages = conversation()
        val working = transcriptItems(messages, typing = showsTyping(AgentState.WORKING))
        assertSame(TranscriptItem.Typing, working.last())
        assertEquals(messages.size + 1, working.size)
        for (state in listOf(AgentState.WAITING, AgentState.DONE, AgentState.IDLE, AgentState.UNKNOWN)) {
            val items = transcriptItems(messages, typing = showsTyping(state))
            assertFalse(state.name, items.any { it === TranscriptItem.Typing })
        }
    }

    @Test
    fun keysAreStableAcrossDeltas() {
        val transcript = Transcript(conversation())
        val before = transcriptItems(transcript.messages(), typing = true).map { it.key }
        val target = transcript.messages().last().uniqueID
        transcript.applyDelivery(message(MessageBody.Append(" and more", target)), chat)
        val after = transcriptItems(transcript.messages(), typing = true).map { it.key }
        assertEquals(before, after)
        assertEquals(target, after[after.size - 2])
        assertEquals(TYPING_ITEM_KEY, after.last())
    }

    @Test
    fun duplicateIdsGetDistinctKeys() {
        val repeated = id(900)
        val messages = listOf(
            message(MessageBody.Markdown("one"), uniqueID = repeated),
            message(MessageBody.Markdown("two"), uniqueID = repeated),
        )
        val keys = transcriptItems(messages, typing = false).map { it.key }
        assertEquals(2, keys.toSet().size)
        assertEquals(repeated, keys.first())
    }

    @Test
    fun streamingDeltaRecomposesOnlyItsBubble() {
        val transcript = Transcript(conversation())
        val cache = TranscriptItemCache()
        val first = cache.items(transcript.messages(), typing = true, resolutions = emptyMap())
        val target = transcript.messages().last().uniqueID
        transcript.applyDelivery(message(MessageBody.Append(" streamed", target)), chat)
        val second = cache.items(transcript.messages(), typing = true, resolutions = emptyMap())
        assertEquals(first.size, second.size)
        val changed = first.indices.filter { first[it] !== second[it] }
        assertEquals(listOf(first.size - 2), changed)

        // A resolution rebuilds only the bubbles that mention it.
        val third = cache.items(
            transcript.messages(),
            typing = true,
            resolutions = mapOf(stableID to MentionResolution(stableID, "Build shell", stableID, null)),
        )
        val renamed = second.indices.filter { second[it] !== third[it] }
        assertEquals(listOf(2), renamed)
        // Nothing changed: every item is the same instance.
        val fourth = cache.items(transcript.messages(), typing = true, resolutions = mapOf(stableID to MentionResolution(stableID, "Build shell", stableID, null)))
        assertTrue(third.indices.all { third[it] === fourth[it] })
    }

    @Test
    fun onlyNewMessagesAreNotedAndReopeningNotesAll() = runTest {
        val transcript = Transcript(conversation())
        val noted = mutableListOf<List<String>>()
        var reopened = 0
        val mentions = object : ConversationMentions {
            override fun reopen() {
                reopened++
            }

            override fun noteMessages(messages: List<ChatMessage>) {
                noted += messages.map { it.uniqueID }
            }
        }
        var version = 0L
        fun shown() = ConversationState.Shown(transcript.messages(), ++version, stale = false, deleted = false, sendable = true)
        val conversation = MutableStateFlow<ConversationState>(shown())
        val reopenings = MutableSharedFlow<Unit>()
        val contents = mutableListOf<ConversationContent>()
        val job = launch {
            conversationContents(
                chat,
                conversation,
                MutableStateFlow(ChatListSnapshot(ChatListState.Loading, emptyList(), aiAvailable = true)),
                MutableStateFlow(emptyMap()),
                MutableStateFlow(12),
                MutableStateFlow(null),
                MutableStateFlow(emptyMap()),
                MutableStateFlow(emptyMap()),
                reopenings,
                mentions,
                StandardTestDispatcher(testScheduler),
            ).collect { contents += it }
        }
        runCurrent()
        val all = transcript.messages().map { it.uniqueID }
        assertEquals(listOf(all), noted)
        // A delta notes only the message it touched.
        transcript.applyDelivery(message(MessageBody.Append(" more", all.last())), chat)
        conversation.value = shown()
        runCurrent()
        assertEquals(listOf(all.last()), noted.last())
        // A reopening (a link up, AI back on) reopens the gate and notes everything again.
        reopenings.emit(Unit)
        runCurrent()
        assertEquals(1, reopened)
        assertEquals(all, noted.last())
        assertEquals(2, contents.size)
        job.cancel()
    }

    @Test
    fun itemsHoldNoContentInToString() {
        val secret = "Sentinel deploy shell"
        val items = transcriptItems(
            listOf(
                message(MessageBody.Markdown(secret)),
                message(MessageBody.WatcherEvent(secret)),
                message(MessageBody.ClassicCommand(CommandContent.ExecuteCommand(secret), safe = false)),
            ),
            typing = true,
        )
        for (item in items) assertFalse(item.toString(), item.toString().contains(secret))
    }
}
