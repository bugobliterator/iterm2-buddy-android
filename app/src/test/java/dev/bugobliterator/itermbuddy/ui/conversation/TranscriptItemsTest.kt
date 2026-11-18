package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.Attachment
import dev.bugobliterator.itermbuddy.core.chat.AttachmentKind
import dev.bugobliterator.itermbuddy.core.chat.Subpart
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ClientAction
import dev.bugobliterator.itermbuddy.core.chat.CommandContent
import dev.bugobliterator.itermbuddy.core.chat.EchoState
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
    fun followingKeepsNewMessagesInViewOrShowsThePill() {
        val cache = TranscriptItemCache()
        val transcript = Transcript(conversation())
        val idle = cache.items(transcript.messages(), typing = false, resolutions = emptyMap())
        val first = transcriptTail(idle)
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = true, before = null, after = first))

        // A message arriving while the agent is not WORKING (no dots to key on): followed at the
        // bottom, a pill when scrolled up.
        transcript.applyDelivery(message(MessageBody.Markdown("A watcher fired")), chat)
        val arrived = transcriptTail(cache.items(transcript.messages(), typing = false, resolutions = emptyMap()))
        assertEquals(FollowAction.PIN, followAction(wasAtBottom = true, before = first, after = arrived))
        assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = first, after = arrived))

        // Deltas while scrolled up, during a turn (the dots are the last row): the pill, every time.
        val working = transcriptTail(cache.items(transcript.messages(), typing = true, resolutions = emptyMap()))
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = false, before = arrived, after = working))
        assertEquals(FollowAction.PIN, followAction(wasAtBottom = true, before = arrived, after = working))
        var before = working
        repeat(3) {
            transcript.applyDelivery(message(MessageBody.Append(" more", transcript.messages().last().uniqueID)), chat)
            val after = transcriptTail(cache.items(transcript.messages(), typing = true, resolutions = emptyMap()))
            assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = before, after = after))
            assertEquals(FollowAction.PIN, followAction(wasAtBottom = true, before = before, after = after))
            before = after
        }
        // Nothing at the end changed: nothing to do.
        val same = transcriptTail(cache.items(transcript.messages(), typing = true, resolutions = emptyMap()))
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = true, before = before, after = same))

        // The owner's own echo, sent from this phone, scrolls to it even from further up.
        val sent = message(MessageBody.PlainText("Thanks"), Participant.USER)
        transcript.applyDelivery(sent, chat)
        val echo = transcriptTail(
            cache.items(transcript.messages(), typing = true, resolutions = emptyMap(), echoes = mapOf(sent.uniqueID to EchoState.SENDING)),
        )
        assertEquals(FollowAction.PIN, followAction(wasAtBottom = false, before = same, after = echo))
    }

    @Test
    fun historyReplacementAndResolutionsDoNotShowThePill() {
        val cache = TranscriptItemCache()
        val messages = conversation() + message(MessageBody.Markdown("Ping @$stableID"))
        val shown = transcriptTail(cache.items(messages, typing = false, resolutions = emptyMap()))

        // A history replaces the transcript: the same messages as new instances. Nothing is news.
        val replaced = messages.map { it.copy() }
        val afterHistory = transcriptTail(cache.items(replaced, typing = false, resolutions = emptyMap()))
        assertFalse("the newest row was rebuilt", shown.newest === afterHistory.newest)
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = false, before = shown, after = afterHistory))

        // A mention in the newest message resolves: its row is rebuilt, its message is the same.
        val resolved = transcriptTail(
            cache.items(replaced, typing = false, resolutions = mapOf(stableID to MentionResolution(stableID, "Build shell", stableID, null))),
        )
        assertFalse("the newest row was rebuilt", afterHistory.newest === resolved.newest)
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = false, before = afterHistory, after = resolved))

        // A streamed delta into the newest message is still news.
        val grown = replaced.dropLast(1) + replaced.last().copy(body = MessageBody.Markdown("Ping @$stableID now"))
        val afterDelta = transcriptTail(cache.items(grown, typing = false, resolutions = emptyMap()))
        assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = resolved, after = afterDelta))
    }

    @Test
    fun onlyThisPhonesSendsPinFromAnywhere() {
        val cache = TranscriptItemCache()
        val transcript = Transcript(conversation())
        val before = transcriptTail(cache.items(transcript.messages(), typing = false, resolutions = emptyMap()))

        // The owner typed on the Mac: a user message, but not sent from here. Scrolled up, a pill.
        transcript.applyDelivery(message(MessageBody.PlainText("Typed on the Mac"), Participant.USER), chat)
        val macTyped = transcriptTail(cache.items(transcript.messages(), typing = false, resolutions = emptyMap()))
        assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = before, after = macTyped))

        // Sent from this phone: its echo pins from anywhere, and the bubble carries its state.
        val sent = message(MessageBody.PlainText("Sent from the phone"), Participant.USER)
        transcript.applyDelivery(sent, chat)
        val sending = transcriptTail(
            cache.items(transcript.messages(), typing = false, resolutions = emptyMap(), echoes = mapOf(sent.uniqueID to EchoState.SENDING)),
        )
        assertEquals(FollowAction.PIN, followAction(wasAtBottom = false, before = macTyped, after = sending))
        assertEquals(EchoState.SENDING, (sending.newest as TranscriptItem.Bubble).echo)

        // The Mac's echo replaces it, delivered: its row changes state, but nothing is news.
        transcript.applyDelivery(sent.copy(), chat)
        val delivered = transcriptTail(
            cache.items(transcript.messages(), typing = false, resolutions = emptyMap(), echoes = mapOf(sent.uniqueID to EchoState.DELIVERED)),
        )
        assertEquals(EchoState.DELIVERED, (delivered.newest as TranscriptItem.Bubble).echo)
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = false, before = sending, after = delivered))
        assertEquals(null, (macTyped.newest as TranscriptItem.Bubble).echo)

        // An old send a history puts back, not delivered, is not a new send: no pin from up the list.
        val old = message(MessageBody.PlainText("Sent before the drop"), Participant.USER)
        transcript.applyDelivery(old, chat)
        val putBack = transcriptTail(
            cache.items(transcript.messages(), typing = false, resolutions = emptyMap(), echoes = mapOf(old.uniqueID to EchoState.NOT_DELIVERED)),
        )
        assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = delivered, after = putBack))
    }

    @Test
    fun attachmentBytesAreNotComparedToFollow() {
        val image = Attachment(inline = true, id = "img", kind = AttachmentKind.File("shot.png", "image/png", 4, byteArrayOf(1, 2, 3, 4)))
        val reply = message(MessageBody.Multipart(listOf(Subpart.Markdown("Here"), Subpart.AttachmentPart(image))))
        val cache = TranscriptItemCache()
        val before = transcriptTail(cache.items(conversation() + reply, typing = false, resolutions = emptyMap()))
        // The same reply from a new history: new byte arrays of the same size.
        val again = reply.copy(
            body = MessageBody.Multipart(
                listOf(Subpart.Markdown("Here"), Subpart.AttachmentPart(image.copy(kind = AttachmentKind.File("shot.png", "image/png", 4, byteArrayOf(1, 2, 3, 4))))),
            ),
        )
        val after = transcriptTail(cache.items(conversation() + again, typing = false, resolutions = emptyMap()))
        assertEquals(before.newestSignature, after.newestSignature)
        assertEquals(FollowAction.NONE, followAction(wasAtBottom = false, before = before, after = after))
        // More text streamed into it is news.
        val grown = again.copy(body = MessageBody.Multipart(listOf(Subpart.Markdown("Here it is"), Subpart.AttachmentPart(image))))
        val streamed = transcriptTail(cache.items(conversation() + grown, typing = false, resolutions = emptyMap()))
        assertEquals(FollowAction.PILL, followAction(wasAtBottom = false, before = after, after = streamed))
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
