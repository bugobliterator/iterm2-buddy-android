package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.MAC_TIME
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.NOISE_CAP
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.RAW_CAP
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.SETTLE
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.chatId
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.envelope
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.padded
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.RequestNotSentException
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat repository end to end: a real [dev.bugobliterator.itermbuddy.core.CompanionClient] on
 * the fake relay, `FakeMac` and `FakeChatMac`, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {
    private suspend fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun ChatRig.shown(chatID: String): ConversationState.Shown {
        val state = repository.conversation(chatID).value
        assertTrue("$chatID: $state", state is ConversationState.Shown)
        return state as ConversationState.Shown
    }

    private fun ChatRig.row(chatID: String): ChatRow = repository.rows.value.single { it.entry.chat.id == chatID }

    private fun ChatRig.listedIDs(): List<String> =
        (repository.list.value as ChatListState.Loaded).entries.map { it.chat.id }

    private fun ConversationState.Shown.textOf(uniqueID: String): String? =
        when (val body = messages.firstOrNull { it.uniqueID == uniqueID }?.body) {
            is MessageBody.Markdown -> body.text
            is MessageBody.PlainText -> body.text
            null -> null
            else -> error("unexpected body $body")
        }

    // Chat list and AI availability

    @Test
    fun listIsFetchedOnEveryLinkAndReplacedByPushes() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        assertEquals(ChatListState.Loading, rig.repository.list.value)
        rig.connect()
        settle()
        assertEquals(listOf(A, B), rig.listedIDs())
        assertFalse((rig.repository.list.value as ChatListState.Loaded).refreshing)
        assertEquals(1, rig.listRequests.get())
        assertEquals(FakeMacRevision, rig.repository.macRevision.value)
        assertTrue(rig.repository.aiAvailable.value)

        rig.chats = listOf(C to MAC_TIME + 5, A to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals("replaced wholesale, in the Mac's order", listOf(C, A), rig.listedIDs())

        rig.reconnect()
        settle()
        assertEquals(2, rig.listRequests.get())
        assertEquals(listOf(C, A), rig.listedIDs())
    }

    @Test
    fun aiOffShowsUnavailableAndSendsNoChatRequest() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.mac.helloAiAvailable = false
        rig.connect()
        rig.repository.open(A)
        settle()
        assertEquals(ChatListState.AiUnavailable, rig.repository.list.value)
        assertFalse(rig.repository.aiAvailable.value)
        assertEquals(ConversationState.Failed(retryable = false), rig.repository.conversation(A).value)
        val chatCases = setOf("listChatsAndSessions", "subscribe", "createChat", "resolveMentions", "unsubscribe")
        assertEquals(emptyList<String>(), rig.mac.receivedCases().filter { it in chatCases })
    }

    @Test
    fun aiBackOnResubscribesWantedChats() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        assertEquals(listOf(A, B), rig.subscribes.toList())
        assertTrue(rig.shown(A).sendable)

        rig.chatMac.flipAi(false)
        settle()
        assertEquals(ChatListState.AiUnavailable, rig.repository.list.value)
        assertFalse(rig.repository.aiAvailable.value)
        val readOnly = rig.shown(A)
        assertFalse("kept, read-only", readOnly.sendable)
        assertEquals(sampleHistory.size, readOnly.messages.size)

        rig.chatMac.flipAi(true)
        settle()
        assertEquals(2, rig.listRequests.get())
        assertEquals(listOf(A, B), rig.listedIDs())
        assertEquals("the Mac dropped every subscription", listOf(A, B, A, B), rig.subscribes.toList())
        assertTrue(rig.shown(A).sendable)
    }

    @Test
    fun aiUnavailableReplyActsAsAiOff() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.listFrame = { id -> envelope(FakeChatMac.samplePayload("error-ai-unavailable.json"), id) }
        rig.connect()
        settle()
        assertEquals(ChatListState.AiUnavailable, rig.repository.list.value)
        assertFalse(rig.repository.aiAvailable.value)
        assertEquals(emptyList<String>(), rig.subscribes.toList())
    }

    // Subscriptions

    @Test
    fun subscriptionsAreReissuedOnEveryLink() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        settle()
        assertEquals(listOf(A, B), rig.subscribes.toList())
        rig.reconnect()
        settle()
        assertEquals(listOf(A, B, A, B), rig.subscribes.toList())
        rig.reconnect()
        settle()
        assertEquals(3, rig.subscribesFor(A))
        assertEquals(3, rig.subscribesFor(B))
    }

    @Test
    fun everyListedChatIsSubscribed() = runTest {
        val rig = ChatRig(this)
        val ids = (1..7).map(::chatId)
        rig.chats = ids.map { it to MAC_TIME }
        rig.connect()
        settle()
        assertEquals(ids, rig.subscribes.toList())
        assertEquals(emptyList<JsonObject>(), rig.chatMac.sent("unsubscribe"))
    }

    @Test
    fun subscribesAreBoundedInConcurrency() = runTest {
        val rig = ChatRig(this)
        val ids = (1..6).map(::chatId)
        rig.chats = ids.map { it to MAC_TIME }
        ids.forEach { rig.holdNext[it] = 3.seconds }
        rig.connect()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("two out at once", 2, rig.subscribes.size)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(ids, rig.subscribes.toList())
        assertEquals(2, rig.maxInFlight.get())
    }

    @Test
    fun unsubscribeNeverCutsAWantedChat() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.repository.close(A)
        settle()
        assertEquals("a listed chat stays watched", emptyList<JsonObject>(), rig.chatMac.sent("unsubscribe"))

        rig.chats = listOf(A to MAC_TIME)
        rig.pushList()
        settle()
        val cut = rig.chatMac.sent("unsubscribe").map { (it["chatID"] as JsonPrimitive).content }
        assertEquals("only a chat that left the list", listOf(B), cut)
    }

    @Test
    fun listChurnDoesNotResubscribe() = runTest {
        val rig = ChatRig(this)
        val ids = (1..5).map(::chatId)
        rig.chats = ids.map { it to MAC_TIME }
        rig.connect()
        settle()
        repeat(50) { round ->
            rig.chats = ids.shuffled(kotlin.random.Random(round)).mapIndexed { i, id -> id to MAC_TIME + round * 10 + i }
            rig.pushList()
            advanceTimeBy(100.milliseconds)
        }
        settle()
        ids.forEach { assertEquals(it, 1, rig.subscribesFor(it)) }
        assertEquals(emptyList<JsonObject>(), rig.chatMac.sent("unsubscribe"))
    }

    @Test
    fun lateHistoryAfterTimeoutIsApplied() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.holdNext[A] = 20.seconds
        rig.connect()
        rig.repository.open(A)
        advanceTimeBy(16.seconds)
        runCurrent()
        assertEquals(ConversationState.Failed(retryable = true), rig.repository.conversation(A).value)
        advanceTimeBy(5.seconds)
        runCurrent()
        val shown = rig.shown(A)
        assertEquals(sampleHistory.size, shown.messages.size)
        assertTrue("the late history counts as subscribed on this link", shown.sendable)
        assertEquals(1, rig.subscribesFor(A))
    }

    @Test
    fun acceptNeverWaitsForAReply() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.holdNext[A] = 60.seconds
        rig.afterSubscribe = { chatID -> if (chatID == B) rig.chatMac.streamReply(B, appends = DELIVERIES) }
        val snapshots = CopyOnWriteArrayList<ConversationState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.repository.conversation(B).collect { snapshots += it } }
        rig.connect()
        rig.repository.open(B)
        settle()

        val reply = rig.shown(B).textOf(OPENING_ID)
        assertNotNull(reply)
        assertEquals("every delivery applied", DELIVERIES, Regex(Regex.escape(APPENDED)).findAll(reply!!).count())
        assertTrue("every delivery went through the sink: ${rig.acceptMillis.size}", rig.acceptMillis.size > DELIVERIES)
        assertEquals("accepts only enqueue: the longest accept, in virtual ms", 0L, rig.acceptMillis.max())
        assertTrue(rig.logs.joinToString("\n"), rig.logs.none { "did not take" in it || "ending the link" in it })
        assertTrue("snapshots are rate-limited: ${snapshots.size}", snapshots.size < 20)
        assertEquals(AgentState.UNKNOWN, rig.repository.activity.value[A] ?: AgentState.UNKNOWN)

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals("the late history landed", AgentState.IDLE, rig.repository.activity.value[A])
        assertEquals(1, rig.subscribesFor(A))
        assertEquals(1, rig.awaitedLinks())
    }

    private fun ChatRig.awaitedLinks(): Int = logs.count { it.startsWith("chat link up") }

    // Transcripts

    @Test
    fun resubscribeOnReconnectReplacesTranscript() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chatMac.streamReply(A, appends = 3)
        settle()
        assertEquals(sampleHistory.size + 1, rig.shown(A).messages.size)

        rig.holdNext[A] = 5.seconds
        rig.reconnect()
        advanceTimeBy(1.seconds)
        runCurrent()
        val waiting = rig.shown(A)
        assertTrue("served from cache while the refetch is out", waiting.stale)
        assertFalse(waiting.sendable)
        advanceTimeBy(10.seconds)
        runCurrent()
        val replaced = rig.shown(A)
        assertEquals("the history replaced the transcript", sampleHistory.map { it.uniqueID }, replaced.messages.map { it.uniqueID })
        assertFalse(replaced.stale)
        assertTrue(replaced.sendable)
    }

    @Test
    fun deltaBeforeHistoryOnNewLinkIsNotApplied() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.holdNext[A] = 5.seconds
        rig.reconnect()
        runCurrent()
        rig.sendSample("delivery-agent-opening.json", A)
        rig.mac.sendMessage(rig.chatMac.append(A, 0), null)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull("not merged into the old link's transcript", rig.shown(A).textOf(OPENING_ID))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(sampleHistory.map { it.uniqueID }, rig.shown(A).messages.map { it.uniqueID })
    }

    @Test
    fun secondHistoryReplacesWithoutDoubleAppend() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chatMac.streamReply(A, appends = 3)
        settle()
        assertEquals(3, Regex(Regex.escape(APPENDED)).findAll(rig.shown(A).textOf(OPENING_ID)!!).count())

        rig.historyFrames[A] = { id -> envelope(historyWithReply(A, appends = 3), id) }
        rig.repository.retry(A)
        settle()
        val shown = rig.shown(A)
        assertEquals(2, rig.subscribesFor(A))
        assertEquals(sampleHistory.size + 1, shown.messages.size)
        assertEquals(3, Regex(Regex.escape(APPENDED)).findAll(shown.textOf(OPENING_ID)!!).count())
    }

    @Test
    fun ownEchoIsUpsertedNotDuplicated() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.sendSample("delivery-user-echo.json", A)
        rig.sendSample("delivery-user-echo.json", A)
        settle()
        val shown = rig.shown(A)
        assertEquals(sampleHistory.size + 1, shown.messages.size)
        assertEquals(1, shown.messages.count { it.uniqueID == ECHO_ID })
    }

    @Test
    fun deliveryForUncachedChatIsDropped() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        settle()
        rig.sendSample("delivery-agent-opening.json", B)
        settle()
        assertEquals(ConversationState.Loading(), rig.repository.conversation(B).value)
        rig.repository.open(B)
        settle()
        assertNull("only the history, never a one-message transcript", rig.shown(B).textOf(OPENING_ID))
        assertEquals(sampleHistory.size, rig.shown(B).messages.size)
    }

    @Test
    fun corruptPayloadFailsOnlyItsChat() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        val nested = "[".repeat(600) + "\"SENTINEL-chat-text\"" + "]".repeat(600)
        rig.historyFrames[A] = { id ->
            """{"payload":{"history":{"chatID":"$A","maxSeq":1,"messages":$nested}},"requestID":$id}""".toByteArray()
        }
        rig.connect()
        rig.repository.open(A)
        rig.repository.open(B)
        settle()
        assertEquals(ConversationState.Failed(retryable = true), rig.repository.conversation(A).value)
        assertEquals(sampleHistory.size, rig.shown(B).messages.size)
        assertTrue(rig.logs.none { "SENTINEL" in it })

        rig.historyFrames.remove(A)
        rig.repository.retry(A)
        settle()
        assertEquals(sampleHistory.size, rig.shown(A).messages.size)
    }

    // Deletion

    @Test
    fun emptyHistoryIsNotDeletion() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.historyFrames[A] = { id -> envelope(FakeChatMac.retarget(FakeChatMac.samplePayload("history-empty.json"), A), id) }
        rig.connect()
        rig.repository.open(A)
        settle()
        val shown = rig.shown(A)
        assertEquals(0, shown.messages.size)
        assertFalse(shown.deleted)
        assertTrue(shown.sendable)
    }

    @Test
    fun chatMissingFromListIsDeleted() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chats = listOf(B to MAC_TIME)
        rig.pushList()
        settle()
        val shown = rig.shown(A)
        assertTrue(shown.deleted)
        assertFalse(shown.sendable)
        assertEquals("kept for reading", sampleHistory.size, shown.messages.size)
        assertEquals(
            "This chat was deleted on your Mac. You can keep reading it until you leave, but nothing new can be sent.",
            ChatRepository.DELETED_CHAT_NOTICE,
        )

        rig.repository.close(A)
        settle()
        assertEquals("readable only until the owner leaves it", 0, rig.repository.heldTranscripts)
        assertEquals(ConversationState.Loading(NotSendableReason.DELETED), rig.repository.conversation(A).value)
    }

    @Test
    fun chatBackInListClearsDeleted() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chats = listOf(B to MAC_TIME)
        rig.pushList()
        settle()
        assertTrue(rig.shown(A).deleted)
        rig.chats = listOf(A to MAC_TIME + 1, B to MAC_TIME)
        rig.pushList()
        settle()
        assertFalse(rig.shown(A).deleted)
        assertTrue(rig.shown(A).sendable)
    }

    // Unread

    @Test
    fun ownSendDoesNotMarkUnread() = runTest {
        // The phone's clock is an hour ahead of the Mac's: only Mac times are ever compared.
        val rig = ChatRig(this, phoneClockOffsetMillis = MAC_TIME.toLong() + 3_600_000)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        settle()
        assertFalse(rig.row(A).unread)

        val message = UserTextMessage(A, "hello", MAC_TIME.toLong() + 3_600_000, ECHO_ID)
        rig.client.send(PhoneMessage.Publish(message))
        rig.repository.noteOwnSend(A)
        settle()
        rig.chats = listOf(A to MAC_TIME + 500, B to MAC_TIME - 1)
        rig.pushList()
        settle()
        assertFalse("the bump was the owner's own send", rig.row(A).unread)

        rig.chats = listOf(A to MAC_TIME + 900, B to MAC_TIME - 1)
        rig.pushList()
        settle()
        assertTrue("a later change is news", rig.row(A).unread)
    }

    @Test
    fun macTypedMessageDoesNotMarkUnread() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.sendSample("delivery-user-echo.json", A)
        rig.chats = listOf(A to MAC_TIME + 100)
        rig.pushList()
        settle()
        assertFalse("typed by the owner on the Mac", rig.row(A).unread)

        rig.sendSample("delivery-agent-opening.json", A)
        rig.chats = listOf(A to MAC_TIME + 200)
        rig.pushList()
        settle()
        assertTrue("the agent answered", rig.row(A).unread)
    }

    @Test
    fun turnEndedWhileVisibleIsRead() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        rig.repository.setVisible(A)
        settle()
        rig.chatMac.streamReply(A, appends = 2)
        rig.chatMac.streamReply(B, appends = 2)
        rig.chats = listOf(A to MAC_TIME + 100, B to MAC_TIME + 50)
        rig.pushList()
        settle()
        assertEquals(AgentState.IDLE, rig.row(A).state)
        assertFalse(rig.row(A).unread)
        assertEquals(AgentState.DONE, rig.row(B).state)
        assertTrue(rig.row(B).unread)
    }

    @Test
    fun backgroundedConversationIsNotVisible() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        rig.repository.setVisible(A)
        rig.repository.setForeground(false)
        settle()
        rig.chatMac.streamReply(A, appends = 2)
        rig.chats = listOf(A to MAC_TIME + 100)
        rig.pushList()
        settle()
        assertEquals(AgentState.DONE, rig.row(A).state)
        assertTrue(rig.row(A).unread)

        rig.repository.setForeground(true)
        settle()
        assertEquals("seen once back", AgentState.IDLE, rig.row(A).state)
        assertFalse(rig.row(A).unread)
    }

    @Test
    fun listBumpMarksUnsubscribedChatUnread() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.neverAnswer += A
        rig.connect()
        settle()
        assertFalse(rig.row(A).unread)
        rig.chats = listOf(A to MAC_TIME + 100)
        rig.pushList()
        settle()
        assertTrue(rig.row(A).unread)
        assertEquals(AgentState.UNKNOWN, rig.row(A).state)
    }

    // Activity

    @Test
    fun parkedTurnShowsWaitingWithoutFurtherInput() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.sendSample("turn-lifecycle-started.json", A)
        rig.sendSample("typing-status-on.json", A)
        runCurrent()
        rig.sendSample("typing-status-off.json", A)
        runCurrent()
        advanceTimeBy(1_400.milliseconds)
        runCurrent()
        assertEquals(AgentState.WORKING, rig.row(A).state)
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals("the repository ticks the tracker at its deadline", AgentState.WAITING, rig.row(A).state)
    }

    // Too large

    @Test
    fun historyOverCapShowsTooLargeAndKeepsLiveStates() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.historyFrames[A] = { id -> envelope(padded(FakeChatMac.history(A), "history", RAW_CAP + 1), id) }
        rig.connect()
        rig.repository.open(A)
        settle()
        assertEquals(ConversationState.TooLarge, rig.repository.conversation(A).value)
        rig.sendSample("turn-lifecycle-started.json", A)
        rig.sendSample("typing-status-on.json", A)
        settle()
        assertEquals("its subscription still feeds its state", AgentState.WORKING, rig.row(A).state)

        rig.reconnect()
        settle()
        assertEquals("too large for the process", 1, rig.subscribesFor(A))
        assertEquals(2, rig.subscribesFor(B))
        assertEquals(ConversationState.TooLarge, rig.repository.conversation(A).value)

        rig.historyFrames.remove(A)
        rig.repository.retry(A)
        settle()
        assertEquals(2, rig.subscribesFor(A))
        assertEquals(sampleHistory.size, rig.shown(A).messages.size)
    }

    @Test
    fun tooLargeHistoryIsNotResubscribedInALoop() = runTest {
        val rig = ChatRig(this, concurrency = 1)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1, C to MAC_TIME - 2)
        rig.historyFrames[A] = { id -> envelope(padded(FakeChatMac.history(A), "history", NOISE_CAP + 1024), id) }
        rig.connect()
        rig.repository.open(A)
        rig.awaitConnected(2)
        settle()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(ConversationState.TooLarge, rig.repository.conversation(A).value)
        assertEquals(1, rig.subscribesFor(A))
        assertEquals(1, rig.subscribesFor(B))
        assertEquals(1, rig.subscribesFor(C))
    }

    @Test
    fun oversizeWithConcurrentSubscribesIsolatesTheCulprit() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1, C to MAC_TIME - 2)
        rig.historyFrames[A] = { id -> envelope(padded(FakeChatMac.history(A), "history", NOISE_CAP + 1024), id) }
        rig.connect()
        rig.repository.open(A)
        rig.awaitConnected(3)
        settle()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(ConversationState.TooLarge, rig.repository.conversation(A).value)
        // Link 1: A and B out together; link 2: the suspect A first and alone; link 3: suspect B, then C.
        assertEquals(listOf(A, B, A, B, C), rig.subscribes.toList())
        assertEquals(setOf(A), rig.repository.tooLargeChats())
    }

    @Test
    fun lateAnswerClearsATimedOutSubscribe() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.holdNext[A] = 20.seconds
        rig.holdNext[B] = 30.seconds
        rig.historyFrames[B] = { id -> envelope(padded(FakeChatMac.history(B), "history", NOISE_CAP + 1024), id) }
        rig.connect()
        rig.awaitConnected(2)
        settle()
        assertEquals("A's late history answered it, so B alone ended the link", setOf(B), rig.repository.tooLargeChats())
        assertEquals(listOf(A, B, A), rig.subscribes.toList())
    }

    @Test
    fun lostAppendResubscribesOnce() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        val big = padded(rig.chatMac.append(A, 0), "delivery", RAW_CAP + 1)
        rig.holdNext[A] = 5.seconds
        rig.mac.sendRaw(envelope(big, null))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue("an append was lost", rig.shown(A).stale)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertFalse(rig.shown(A).stale)
        assertEquals(2, rig.subscribesFor(A))

        rig.mac.sendRaw(envelope(big, null))
        settle()
        assertEquals("once per link", 2, rig.subscribesFor(A))
    }

    @Test
    fun listOverCapFailsWithoutARefetchLoop() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.listFrame = { id -> envelope(padded(rig.listPayload("chatsAndSessions"), "chatsAndSessions", RAW_CAP + 1), id) }
        rig.connect()
        settle()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(ChatListState.Failed(retryable = true), rig.repository.list.value)
        assertEquals(1, rig.listRequests.get())

        rig.listFrame = null
        rig.repository.refresh()
        settle()
        assertEquals(listOf(A), rig.listedIDs())
    }

    // Memory

    @Test
    fun closedTranscriptsAreEvictedBeyondThree() = runTest {
        val rig = ChatRig(this)
        val ids = (1..5).map(::chatId)
        rig.chats = ids.map { it to MAC_TIME }
        rig.connect()
        settle()
        for (id in ids.take(4)) {
            rig.repository.open(id)
            settle()
            assertEquals(sampleHistory.size, rig.shown(id).messages.size)
            rig.repository.close(id)
            settle()
        }
        val first = rig.repository.conversation(ids[0])
        assertEquals("evicted", ConversationState.Loading(), first.value)
        ids.subList(1, 4).forEach { assertEquals(sampleHistory.size, rig.shown(it).messages.size) }
        assertEquals(3, rig.repository.heldTranscripts)

        val before = rig.subscribes.size
        rig.repository.open(ids[1])
        settle()
        assertEquals("a cached transcript needs no refetch", before, rig.subscribes.size)
        rig.repository.open(ids[0])
        settle()
        assertEquals("an evicted one is fetched again", before + 1, rig.subscribes.size)
        assertEquals(sampleHistory.size, rig.shown(ids[0]).messages.size)
        assertSame("the screen keeps its flow across the eviction", first, rig.repository.conversation(ids[0]))
        assertTrue(first.value is ConversationState.Shown)
    }

    @Test
    fun watchedOnlyHistoryKeepsNoTranscript() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        settle()
        assertEquals(listOf(A, B), rig.subscribes.toList())
        assertEquals(0, rig.repository.heldTranscripts)
        assertEquals(ConversationState.Loading(), rig.repository.conversation(A).value)
        assertEquals(AgentState.IDLE, rig.row(A).state)
    }

    // Unpair

    @Test
    fun clearDropsEverythingAndIgnoresOldLinks() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.historyFrames[B] = { id -> envelope(padded(FakeChatMac.history(B), "history", RAW_CAP + 1), id) }
        rig.connect()
        rig.repository.open(A)
        settle()
        assertNotNull(rig.seen.seen(A))
        assertEquals(setOf(B), rig.repository.tooLargeChats())

        rig.repository.close(A)
        rig.repository.clear()
        rig.chats = listOf(C to MAC_TIME)
        rig.pushList()
        rig.sendSample("turn-lifecycle-started.json", A)
        settle()
        assertEquals("inputs from the cleared link are ignored", ChatListState.Loading, rig.repository.list.value)
        assertEquals(emptyList<ChatRow>(), rig.repository.rows.value)
        assertEquals(emptyMap<String, AgentState>(), rig.repository.activity.value)
        assertEquals(ConversationState.Loading(NotSendableReason.NOT_CONNECTED), rig.repository.conversation(A).value)
        assertEquals(0, rig.repository.heldTranscripts)
        assertNull(rig.seen.seen(A))
        assertNull(rig.seen.seen(B))
        assertEquals(emptySet<String>(), rig.repository.tooLargeChats())
        assertNull(rig.repository.macRevision.value)

        rig.historyFrames.remove(B)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.reconnect()
        settle()
        assertEquals(listOf(A, B), rig.listedIDs())
        assertEquals("no longer too large", 2, rig.subscribesFor(B))
    }


    // Review fixes

    @Test
    fun aiOffEmptyListKeepsMarksAndTranscripts() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chatMac.streamReply(B, appends = 1)
        settle()
        assertEquals(AgentState.DONE, rig.row(B).state)

        rig.chatMac.flipAi(false)
        rig.chats = emptyList()
        rig.pushList()
        settle()
        assertEquals(ChatListState.AiUnavailable, rig.repository.list.value)
        assertEquals("the empty list while AI is off is not applied", listOf(A, B), rig.repository.rows.value.map { it.entry.chat.id })
        assertNotNull(rig.seen.seen(A))
        assertNotNull(rig.seen.seen(B))
        assertEquals("the Done latch survives", AgentState.DONE, rig.row(B).state)
        val shown = rig.shown(A)
        assertFalse("not deleted", shown.deleted)
        assertEquals(sampleHistory.size, shown.messages.size)
    }

    @Test
    fun quickCloseAndReopenIsNotStuck() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.repository.open(A)
        val flow = rig.repository.conversation(A)
        rig.repository.close(A)
        rig.repository.open(A)
        settle()
        assertSame(flow, rig.repository.conversation(A))
        assertTrue("${flow.value}", flow.value is ConversationState.Shown)
    }

    @Test
    fun neverSentSubscribeIsNotBlamedForAnOversizedLink() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.notSent += B
        rig.historyFrames[A] = { id -> envelope(padded(FakeChatMac.history(A), "history", NOISE_CAP + 1024), id) }
        rig.connect()
        rig.awaitConnected(2)
        settle()
        assertEquals("A was the only subscribe the Mac saw", setOf(A), rig.repository.tooLargeChats())
        assertEquals(listOf(A), rig.subscribes.toList())
    }

    @Test
    fun listOverNoiseCapStopsRefetching() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.listFrame = { id -> envelope(padded(rig.listPayload("chatsAndSessions"), "chatsAndSessions", NOISE_CAP + 1024), id) }
        rig.connect()
        rig.awaitConnected(3)
        settle()
        advanceTimeBy(5.minutes)
        runCurrent()
        // Link 1: suspected; link 2: requested alone, ends it alone again: latched.
        assertEquals("no reconnect loop", 3, rig.connectionCount)
        assertEquals(2, rig.listRequests.get())
        assertEquals(ChatListState.Failed(retryable = true), rig.repository.list.value)

        rig.listFrame = null
        rig.repository.refresh()
        settle()
        assertEquals(listOf(A), rig.listedIDs())
    }

    @Test
    fun firstAppearancesAreMarkedInOneWrite() = runTest {
        val store = CountingSeenStore()
        val rig = ChatRig(this, seen = store)
        rig.chats = (1..10).map { chatId(it) to MAC_TIME }
        rig.connect()
        settle()
        assertEquals(listOf(10), store.batches)
        assertEquals(0, store.singles)
    }

    @Test
    fun failedRefetchKeepsTheCachedList() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.listFrame = { id -> envelope(FakeChatMac.samplePayload("error-unknown-session.json"), id) }
        rig.reconnect()
        settle()
        val loaded = rig.repository.list.value as ChatListState.Loaded
        assertEquals(listOf(A), loaded.entries.map { it.chat.id })
        assertTrue(loaded.refreshFailed)
        assertFalse(loaded.refreshing)
    }

    @Test
    fun historyInFlightWhenAiGoesOffIsSubscribedAgainWhenBack() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.holdNext[A] = 5.seconds
        rig.connect()
        rig.repository.open(A)
        advanceTimeBy(1.seconds)
        runCurrent()
        rig.chatMac.flipAi(false)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertFalse("read-only while AI is off", rig.shown(A).sendable)
        rig.chatMac.flipAi(true)
        settle()
        assertEquals("the Mac dropped it with AI: subscribed again", 2, rig.subscribesFor(A))
        assertTrue(rig.shown(A).sendable)
    }

    @Test
    fun chatLeavingTheListLiftsItsTooLargeMark() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.historyFrames[B] = { id -> envelope(padded(FakeChatMac.history(B), "history", RAW_CAP + 1), id) }
        rig.connect()
        settle()
        assertEquals(setOf(B), rig.repository.tooLargeChats())
        rig.chats = listOf(A to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals("one list without it is not enough", setOf(B), rig.repository.tooLargeChats())
        rig.pushList()
        settle()
        assertEquals(emptySet<String>(), rig.repository.tooLargeChats())
        rig.historyFrames.remove(B)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.pushList()
        settle()
        assertEquals(2, rig.subscribesFor(B))
    }

    @Test
    fun undecodableRawDeliveryIsALostAppend() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        val nested = "[".repeat(600) + "1" + "]".repeat(600)
        val padding = "p".repeat(ChatRig.RAW_DELIVERY + 1)
        for (chatID in listOf(A, B)) {
            val frame = """{"payload":{"delivery":{"chatID":"$chatID","padding":"$padding","message":$nested,"partial":true}}}"""
            rig.mac.sendRaw(frame.toByteArray())
        }
        settle()
        assertEquals("the open chat refetches its history", 2, rig.subscribesFor(A))
        assertEquals("a watched chat holds no transcript to repair", 1, rig.subscribesFor(B))
        assertFalse(rig.shown(A).stale)
    }

    @Test
    fun overCapDeliveryForAWatchedChatIsNotResubscribed() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.mac.sendRaw(envelope(padded(rig.chatMac.append(A, 0), "delivery", RAW_CAP + 1), null))
        settle()
        assertEquals(1, rig.subscribesFor(A))
    }

    @Test
    fun openChatLeavingTheListKeepsItsStateUntilClosedAndListedWithoutItAgain() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        assertEquals(AgentState.IDLE, rig.repository.activity.value[A])
        rig.chats = listOf(B to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals(AgentState.IDLE, rig.repository.activity.value[A])
        assertNotNull(rig.seen.seen(A))
        rig.repository.close(A)
        settle()
        assertEquals("its transcript goes with the owner", 0, rig.repository.heldTranscripts)
        assertNotNull("one list without it", rig.seen.seen(A))
        rig.pushList()
        settle()
        assertNull(rig.repository.activity.value[A])
        assertNull(rig.seen.seen(A))
    }

    @Test
    fun messagesRemovedTruncatesTheTranscript() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.sendSample("messages-removed.json", A)
        rig.sendSample("messages-removed.json", A)
        settle()
        val removed = setOf("11111111-2222-4333-8444-000000000300", "11111111-2222-4333-8444-000000000350", "11111111-2222-4333-8444-000000000400")
        assertEquals(sampleHistory.map { it.uniqueID }.filterNot { it in removed }, rig.shown(A).messages.map { it.uniqueID })
    }

    @Test
    fun chatCreatedIsInsertedOnceAndWatched() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        val created = FakeChatMac.samplePayload("chat-created.json")
        rig.mac.sendMessage(created, null)
        rig.mac.sendMessage(created, null)
        settle()
        assertEquals(listOf(CREATED, A), rig.listedIDs())
        assertEquals(1, rig.subscribesFor(CREATED))
        assertFalse(rig.row(CREATED).unread)
    }

    @Test
    fun oversizedLinkEndWithNothingUnansweredNamesNoChat() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        settle()
        rig.mac.sendRaw(envelope(padded(rig.chatMac.append(A, 0), "delivery", NOISE_CAP + 1024), null))
        rig.awaitConnected(2)
        settle()
        assertEquals(emptySet<String>(), rig.repository.tooLargeChats())
        assertEquals(listOf(A, B, A, B), rig.subscribes.toList())
        assertEquals(2, rig.listRequests.get())
    }

    @Test
    fun shownEqualityIgnoresMessages() {
        val one = ChatMessage(A, Participant.AGENT, MessageBody.Markdown("one"), 1.0, ECHO_ID)
        val first = ConversationState.Shown(listOf(one), version = 7, stale = false, deleted = false, sendable = true)
        assertEquals(first, ConversationState.Shown(emptyList(), version = 7, stale = false, deleted = false, sendable = true))
        assertNotEquals(first, ConversationState.Shown(listOf(one), version = 8, stale = false, deleted = false, sendable = true))
        assertNotEquals(first, ConversationState.Shown(listOf(one), version = 7, stale = true, deleted = false, sendable = true))
    }

    @Test
    fun suspectListLatchesEvenWhenItsReplyOutlastsTheTimeout() = runTest {
        val rig = ChatRig(this, timeouts = ChatTimeouts(list = 2.seconds))
        rig.chats = listOf(A to MAC_TIME)
        rig.listHold = 5.seconds
        rig.listFrame = { id -> envelope(padded(rig.listPayload("chatsAndSessions"), "chatsAndSessions", NOISE_CAP + 1024), id) }
        rig.repository.open(A)
        rig.connect()
        rig.awaitConnected(3)
        settle()
        advanceTimeBy(5.minutes)
        runCurrent()
        // Link 1: A answered at once, the list ends the link alone at 5 s: suspected. Link 2: the
        // list alone; its 2 s timeout lets A go meanwhile (answered); at 5 s it ends the link
        // alone again: latched. Link 3: A, no list.
        assertEquals("no reconnect loop", 3, rig.connectionCount)
        assertEquals(2, rig.listRequests.get())
        assertEquals("the timed-out suspect list stopped holding A back", listOf(A, A, A), rig.subscribes.toList())
        assertEquals(ChatListState.Failed(retryable = true), rig.repository.list.value)
    }

    @Test
    fun oversizedListWithAnOpenChatSuspectsTheListFirst() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.listFrame = { id -> envelope(padded(rig.listPayload("chatsAndSessions"), "chatsAndSessions", NOISE_CAP + 1024), id) }
        rig.repository.open(A)
        rig.connect()
        rig.awaitConnected(3)
        settle()
        advanceTimeBy(5.minutes)
        runCurrent()
        // Link 1: the list and A out together; link 2: the suspect list first and alone, which
        // ends it by itself; link 3: no list, the suspect A alone, answered.
        assertEquals(3, rig.connectionCount)
        assertEquals(2, rig.listRequests.get())
        assertEquals(listOf(A, A), rig.subscribes.toList())
        assertEquals("the chat is not blamed for the list", emptySet<String>(), rig.repository.tooLargeChats())
        assertEquals(sampleHistory.size, rig.shown(A).messages.size)
        assertEquals(ChatListState.Failed(retryable = true), rig.repository.list.value)
    }

    @Test
    fun aListThatLandsClearsItsSuspicion() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.repository.open(A)
        rig.historyFrames[A] = { id -> envelope(padded(FakeChatMac.history(A), "history", NOISE_CAP + 1024), id) }
        // Every list reply is held a second. Link 1: A's oversized history ends the link with the
        // list still out, so both are suspects.
        rig.listHold = 1.seconds
        rig.connect()
        rig.awaitConnected(3)
        settle()
        advanceTimeBy(5.minutes)
        runCurrent()
        // Link 2: the list first and alone, which lands; only then the suspect A, alone, which
        // ends the link by itself (had A gone beside the held list, the list would be suspected
        // again); link 3: the list and B.
        assertEquals(setOf(A), rig.repository.tooLargeChats())
        assertEquals(3, rig.listRequests.get())
        assertEquals(listOf(A, A, B), rig.subscribes.toList())
        assertEquals(listOf(A, B), rig.listedIDs())
    }

    @Test
    fun refusedSubscribeIsSettledBeforeAQueuedLinkDown() = runTest {
        var nextID = 1L
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(
                message: PhoneMessage,
                onLink: Long?,
                timeout: Duration?,
                onSent: (requestID: Long) -> Unit,
            ): MacMessage {
                // The client's linkFor: the session has ended, its LinkDown not yet consumed.
                if (message is PhoneMessage.Subscribe && message.chatID == B) throw RequestNotSentException("not connected")
                onSent(nextID++)
                awaitCancellation()
            }
        }
        val repository = ChatRepository(link, backgroundScope, InMemoryChatSeenStore(), nowMillis = { testScheduler.currentTime })
        repository.sink.accept(ChatInput.LinkUp(1, 15, aiAvailable = true))
        runCurrent()
        val list = envelope(ChatRig.listPayloadOf("chatsAndSessions", listOf(A to MAC_TIME, B to MAC_TIME - 1)), 1)
        repository.sink.accept(ChatInput.Payload(1, 1, "chatsAndSessions", list))
        repository.sink.accept(ChatInput.LinkDown(1, messageTooLarge = true))
        runCurrent()
        assertEquals("B never reached the Mac: A alone ended the link", setOf(A), repository.tooLargeChats())
    }

    @Test
    fun deadLinkStopsPlanningWithoutFailingConversations() = runTest {
        var attempts = 0
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(
                message: PhoneMessage,
                onLink: Long?,
                timeout: Duration?,
                onSent: (requestID: Long) -> Unit,
            ): MacMessage {
                if (message is PhoneMessage.Subscribe) {
                    // The session has ended; its LinkDown is not consumed yet.
                    attempts++
                    throw RequestNotSentException("not connected")
                }
                onSent(1)
                awaitCancellation()
            }
        }
        val repository = ChatRepository(link, backgroundScope, InMemoryChatSeenStore(), nowMillis = { testScheduler.currentTime })
        val seenStates = CopyOnWriteArrayList<ConversationState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.conversation(A).collect { seenStates += it } }
        repository.open(A)
        repository.sink.accept(ChatInput.LinkUp(1, 15, aiAvailable = true))
        runCurrent()
        val list = envelope(ChatRig.listPayloadOf("chatsAndSessions", listOf(A to MAC_TIME, B to MAC_TIME - 1, C to MAC_TIME - 2)), 1)
        repository.sink.accept(ChatInput.Payload(1, 1, "chatsAndSessions", list))
        runCurrent()
        assertEquals("nothing more is planned on a gone link", 1, attempts)
        assertTrue("no Failed flash: $seenStates", seenStates.none { it is ConversationState.Failed })
        repository.sink.accept(ChatInput.LinkDown(1))
        runCurrent()
        assertEquals(ConversationState.Loading(NotSendableReason.NOT_CONNECTED), repository.conversation(A).value)
    }

    @Test
    fun slowOversizedListLatchesByTheThirdEnd() = runTest {
        val rig = ChatRig(this, timeouts = ChatTimeouts(list = 2.seconds))
        rig.chats = listOf(A to MAC_TIME)
        rig.listHold = 5.seconds
        rig.listFrame = { id -> envelope(padded(rig.listPayload("chatsAndSessions"), "chatsAndSessions", NOISE_CAP + 1024), id) }
        // A's subscribe is always out when the list's frame lands, so the list never ends a link alone.
        rig.neverAnswer += A
        rig.repository.open(A)
        rig.connect()
        rig.awaitConnected(4)
        settle()
        advanceTimeBy(10.minutes)
        runCurrent()
        // Links 1 to 3 each end on the list's frame with A out; the third latches the list.
        assertEquals("bounded", 4, rig.connectionCount)
        assertEquals(3, rig.listRequests.get())
        assertEquals(ChatListState.Failed(retryable = true), rig.repository.list.value)
        assertEquals("A is only ever a suspect", emptySet<String>(), rig.repository.tooLargeChats())
    }

    // Whole-phase review fixes

    /**
     * The reported race: a list request queued on the Mac, AI turned off before its reply, the
     * empty reply ahead of `aiAvailabilityChanged(false)`. Nothing is forgotten, the open chat is
     * not deleted, and AI back on shows every chat as it was.
     */
    @Test
    fun emptyListRacingAiOffKeepsMarksLatchesAndTranscripts() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1, C to MAC_TIME - 2)
        rig.connect()
        rig.repository.open(C)
        settle()
        rig.repository.close(C)
        rig.repository.open(A)
        rig.repository.setVisible(A)
        settle()
        rig.chatMac.streamReply(B, appends = 1)
        settle()
        assertEquals(AgentState.DONE, rig.row(B).state)
        assertEquals("A open, C cached", 2, rig.repository.heldTranscripts)

        // Locked and unlocked: the next link's list request waits on the Mac's main actor.
        rig.listHold = 1.seconds
        rig.reconnect()
        runCurrent()
        rig.chatMac.setAiAvailable(false)
        advanceTimeBy(1.seconds)
        runCurrent()
        val during = rig.shown(A)
        assertFalse("not latched deleted on a suspect list", during.deleted)
        assertEquals(listOf(A, B, C), rig.repository.rows.value.map { it.entry.chat.id })
        rig.chatMac.announceAi()
        settle()
        assertEquals(ChatListState.AiUnavailable, rig.repository.list.value)
        for (chatID in listOf(A, B, C)) assertNotNull(chatID, rig.seen.seen(chatID))
        assertEquals("the Done latch survives", AgentState.DONE, rig.row(B).state)
        assertEquals("transcripts kept", 2, rig.repository.heldTranscripts)
        assertFalse(rig.shown(A).deleted)

        rig.listHold = null
        rig.chatMac.flipAi(true)
        settle()
        assertEquals(listOf(A, B, C), rig.listedIDs())
        assertFalse("A is on screen", rig.row(A).unread)
        assertFalse("C was seen before AI went off", rig.row(C).unread)
        assertTrue("B's unseen turn is still news", rig.row(B).unread)
        assertTrue(rig.shown(A).sendable)
    }

    @Test
    fun secondEmptyListConfirmsTheFirst() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(B)
        settle()
        rig.repository.close(B)
        settle()
        rig.chats = emptyList()
        rig.pushList()
        settle()
        assertEquals("held: an empty list while chats are listed is suspect", listOf(A, B), rig.listedIDs())
        rig.pushList()
        settle()
        assertEquals(ChatListState.Loaded(emptyList(), refreshing = false), rig.repository.list.value)
        assertEquals("two lists without them", null, rig.seen.seen(A))
        assertNull(rig.seen.seen(B))
        assertEquals(0, rig.repository.heldTranscripts)
    }

    @Test
    fun heldEmptyListIsConfirmedByAFetch() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        rig.chats = emptyList()
        rig.pushList()
        runCurrent()
        assertEquals(listOf(A), rig.listedIDs())
        assertEquals(1, rig.listRequests.get())
        advanceTimeBy(ChatTimeouts().emptyListConfirm)
        settle()
        assertEquals("fetched to confirm it", 2, rig.listRequests.get())
        assertEquals(ChatListState.Loaded(emptyList(), refreshing = false), rig.repository.list.value)
    }

    @Test
    fun chatLeavingOnceKeepsItsMarksAndDropsOnlyItsTranscript() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(B)
        settle()
        rig.repository.close(B)
        rig.chatMac.streamReply(B, appends = 1)
        settle()
        assertEquals(AgentState.DONE, rig.row(B).state)
        assertEquals(1, rig.repository.heldTranscripts)

        rig.chats = listOf(A to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals("its transcript goes at once", 0, rig.repository.heldTranscripts)
        assertNotNull("one list without it keeps its mark", rig.seen.seen(B))
        assertEquals(AgentState.DONE, rig.repository.activity.value[B])

        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.pushList()
        settle()
        assertTrue("back: still its unseen turn", rig.row(B).unread)
        assertEquals(AgentState.DONE, rig.row(B).state)

        rig.chats = listOf(A to MAC_TIME)
        rig.pushList()
        rig.pushList()
        settle()
        assertNull("two lists in a row without it", rig.seen.seen(B))
        assertNull(rig.repository.activity.value[B])
    }

    @Test
    fun reconnectShowsConnectingNotFailedForAChatWithoutTranscript() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.neverAnswer += A
        rig.connect()
        settle()
        rig.repository.open(A)
        settle()
        assertEquals(ConversationState.Loading(), rig.repository.conversation(A).value)

        // Locked: the link goes, and the subscribe out fails with "the connection closed".
        rig.mac.stop()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(ConversationState.Loading(NotSendableReason.NOT_CONNECTED), rig.repository.conversation(A).value)

        // Unlocked: connecting, then loaded.
        rig.neverAnswer -= A
        rig.mac.start()
        rig.awaitConnected(2)
        settle()
        assertTrue(rig.repository.conversation(A).value is ConversationState.Shown)
    }

    /**
     * The order a lock produces: the session fails the subscribe still out ("the connection
     * closed") before its LinkDown is consumed. The chat shows connecting through the reconnect,
     * not "Couldn't load".
     */
    @Test
    fun subscribeFailedByTheLinkClosingIsNoLoadFailure() = runTest {
        val closed = CompletableDeferred<Unit>()
        var nextID = 1L
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(message: PhoneMessage, onLink: Long?, timeout: Duration?, onSent: (requestID: Long) -> Unit): MacMessage {
                onSent(nextID++)
                if (message is PhoneMessage.Subscribe && onLink == 1L) {
                    closed.await()
                    throw CompanionRequestException("the connection closed")
                }
                awaitCancellation()
            }
        }
        val repository = ChatRepository(
            link, backgroundScope, InMemoryChatSeenStore(), nowMillis = { testScheduler.currentTime }, snapshotUncollected = true,
        )
        repository.open(A)
        repository.sink.accept(ChatInput.LinkUp(1, 15, aiAvailable = true))
        runCurrent()
        closed.complete(Unit)
        runCurrent()
        repository.sink.accept(ChatInput.LinkDown(1))
        runCurrent()
        assertEquals(ConversationState.Loading(NotSendableReason.NOT_CONNECTED), repository.conversation(A).value)
        repository.sink.accept(ChatInput.LinkUp(2, 15, aiAvailable = true))
        runCurrent()
        assertEquals("loading on the new link", ConversationState.Loading(), repository.conversation(A).value)
    }

    @Test
    fun ownersMacTypedMessageReadsSeenAfterAReconnect() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        assertFalse(rig.row(A).unread)
        // Typed on the Mac while the phone was locked: the list moves, the newest message is the owner's.
        val typed = JsonObject(
            mapOf(
                "author" to JsonPrimitive("user"),
                "chatID" to JsonPrimitive(A),
                "content" to JsonObject(mapOf("plainText" to JsonObject(mapOf("_0" to JsonPrimitive("on the Mac"))))),
                "sentDate" to JsonPrimitive(1_795_000_600_000L),
                "uniqueID" to JsonPrimitive(ECHO_ID),
            ),
        )
        val body = FakeChatMac.history(A).getValue("history").jsonObject
        val history = JsonObject(mapOf("history" to JsonObject(body + ("messages" to JsonArray(body.getValue("messages").jsonArray + typed)))))
        rig.historyFrames[A] = { id -> envelope(history, id) }
        rig.chats = listOf(A to MAC_TIME + 100)
        rig.reconnect()
        settle()
        assertFalse("the owner's own words are not news", rig.row(A).unread)
    }

    @Test
    fun rowsAreNotRebuiltByDeltasThatChangeNoState() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.connect()
        rig.repository.open(A)
        settle()
        rig.chatMac.streamReply(A, appends = 0)
        settle()
        val rows = rig.repository.rows.value
        val snapshot = rig.repository.listSnapshot.value
        repeat(20) { rig.mac.sendMessage(rig.chatMac.append(A, it), null) }
        settle()
        assertTrue(rig.shown(A).messages.size > sampleHistory.size)
        assertSame("no list or state change: the same rows", rows, rig.repository.rows.value)
        assertSame(snapshot, rig.repository.listSnapshot.value)
    }

    @Test
    fun snapshotIsComparedWithoutIconBytes() {
        val icon = ByteArray(64) { it.toByte() }
        fun entry() = ChatListEntry(ChatSummary(A, "t", 1.0, 2.0, false, null, null, emptyList(), icon), null, false)
        val first = ChatListSnapshot(ChatListState.Loaded(listOf(entry()), false), emptyList(), aiAvailable = true)
        val second = ChatListSnapshot(ChatListState.Loaded(listOf(entry()), false), emptyList(), aiAvailable = true)
        assertNotEquals("different list values are different snapshots, whatever their bytes", first, second)
        assertEquals(first, ChatListSnapshot(first.list, first.rows, aiAvailable = true))
    }

    @Test
    fun uncollectedConversationsAreNotSnapshotted() = runTest {
        val rig = ChatRig(this, snapshotUncollected = false)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        val flow = rig.repository.conversation(A)
        assertEquals(ConversationState.Loading(), flow.value)
        settle()
        assertEquals("nobody collects it: not snapshotted", ConversationState.Loading(), flow.value)
        val seen = CopyOnWriteArrayList<ConversationState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        runCurrent()
        assertTrue("a collector gets it at once: ${flow.value}", flow.value is ConversationState.Shown)
        collector.cancel()
        runCurrent()
        val before = (flow.value as ConversationState.Shown).version
        rig.chatMac.streamReply(A, appends = 3)
        settle()
        assertEquals("not snapshotted while nobody collects", before, (flow.value as ConversationState.Shown).version)
    }

    @Test
    fun imageAllowanceIsSharedByEveryTranscript() = runTest {
        val image = 3 * 1024
        val budgets = ChatBudgets(attachments = AttachmentBudgets(maxImageBytes = image, maxTranscriptImageBytes = 2L * image), sharedImageBytes = 3L * image)
        val rig = ChatRig(this, budgets = budgets)
        val ids = (1..3).map(::chatId)
        rig.chats = ids.map { it to MAC_TIME }
        for (id in ids) rig.historyFrames[id] = { requestID -> envelope(historyWithImages(id, 2, image), requestID) }
        rig.connect()
        for (id in ids) {
            rig.repository.open(id)
            settle()
        }
        assertEquals("each transcript would keep two; the process keeps three", 3L * image, rig.repository.heldImageBytes)
        rig.repository.close(ids[0])
        rig.repository.close(ids[1])
        rig.repository.close(ids[2])
        settle()
        rig.chats = listOf(ids[2] to MAC_TIME)
        rig.pushList()
        rig.pushList()
        settle()
        assertEquals("dropped transcripts give their bytes back", 0L, rig.repository.heldImageBytes)
    }

    @Test
    fun deadConsumerShowsFailedInsteadOfLoading() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(message: PhoneMessage, onLink: Long?, timeout: Duration?, onSent: (requestID: Long) -> Unit): MacMessage =
                awaitCancellation()
        }
        val repository = ChatRepository(link, scope, InMemoryChatSeenStore(), nowMillis = { testScheduler.currentTime })
        repository.open(A)
        runCurrent()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        assertEquals(ConversationState.Failed(retryable = false, NotSendableReason.LOAD_FAILED), repository.conversation(A).value)
        assertEquals(ConversationState.Failed(retryable = false, NotSendableReason.LOAD_FAILED), repository.conversation(B).value)
        assertEquals(ChatListState.Failed(retryable = false), repository.list.value)
        assertEquals(ChatListState.Failed(retryable = false), repository.listSnapshot.value.list)
    }

    /** Counts single marks and batches. */
    private class CountingSeenStore : ChatSeenStore {
        private val inner = InMemoryChatSeenStore()
        @Volatile var singles = 0
        val batches = CopyOnWriteArrayList<Int>()

        override fun seen(chatID: String): Double? = inner.seen(chatID)

        override fun markSeen(chatID: String, lastModifiedMillis: Double) {
            singles++
            inner.markSeen(chatID, lastModifiedMillis)
        }

        override fun markSeenAll(marks: Map<String, Double>) {
            batches += marks.size
            inner.markSeenAll(marks)
        }

        override fun forget(chatID: String) = inner.forget(chatID)

        override fun clear() = inner.clear()
    }

    // Content

    @Test
    fun statesShowNoContent() {
        val message = ChatMessage(A, Participant.AGENT, MessageBody.Markdown("SENTINEL-text"), 1.0, ECHO_ID)
        val shown = ConversationState.Shown(listOf(message), version = 1, stale = false, deleted = false, sendable = true)
        assertFalse(shown.toString(), "SENTINEL" in shown.toString())
        val summary = ChatSummary(A, "SENTINEL-title", 1.0, 2.0, false, null, null, emptyList(), null)
        val loaded = ChatListState.Loaded(listOf(ChatListEntry(summary, "SENTINEL-snippet", false)), refreshing = false)
        assertFalse(loaded.toString(), "SENTINEL" in loaded.toString())
        val row = ChatRow(ChatListEntry(summary, "SENTINEL-snippet", false), AgentState.IDLE, unread = false)
        assertFalse(row.toString(), "SENTINEL" in row.toString())
    }

    private companion object {
        val A = chatId(101)
        val B = chatId(102)
        val C = chatId(103)
        const val CREATED = "2F3E4D5C-6B7A-4980-A1B2-C3D4E5F60718"
        const val DELIVERIES = 1_000
        const val OPENING_ID = "11111111-2222-4333-8444-000000000501"
        const val ECHO_ID = "11111111-2222-4333-8444-000000000500"
        const val APPENDED = " in **zsh** now."
        val FakeMacRevision: Int = dev.bugobliterator.itermbuddy.fakemac.FakeMac.MAC_REVISION

        /** The sample history's visible messages. */
        val sampleHistory: List<ChatMessage> =
            (decodeEnvelope(envelope(FakeChatMac.history(A), null)).second as MacMessage.History).messages.filterNot { it.hidden }

        /** A history for [chatID] of [count] agent messages, each with one `image/png` attachment of [size] bytes. */
        fun historyWithImages(chatID: String, count: Int, size: Int): JsonObject {
            val content = java.util.Base64.getEncoder().encodeToString(ByteArray(size) { 7 })
            val messages = (1..count).map { index ->
                val file = mapOf("name" to JsonPrimitive("shot$index.png"), "mimeType" to JsonPrimitive("image/png"), "content" to JsonPrimitive(content))
                val attachment = mapOf(
                    "inline" to JsonPrimitive(true),
                    "id" to JsonPrimitive("att$index"),
                    "type" to JsonObject(mapOf("file" to JsonObject(mapOf("_0" to JsonObject(file))))),
                )
                val part = JsonObject(mapOf("attachment" to JsonObject(mapOf("_0" to JsonObject(attachment)))))
                JsonObject(
                    mapOf(
                        "author" to JsonPrimitive("agent"),
                        "chatID" to JsonPrimitive(chatID),
                        "content" to JsonObject(mapOf("multipart" to JsonObject(mapOf("_0" to JsonArray(listOf(part)))))),
                        "sentDate" to JsonPrimitive(1_795_000_500_000L + index),
                        "uniqueID" to JsonPrimitive("11111111-2222-4333-8444-%012d".format(700_000 + index)),
                    ),
                )
            }
            val body = FakeChatMac.history(chatID).getValue("history").jsonObject
            return JsonObject(mapOf("history" to JsonObject(body + ("messages" to JsonArray(messages)))))
        }

        /** The sample history plus the streamed reply, as the Mac holds it after [appends] appends. */
        fun historyWithReply(chatID: String, appends: Int): JsonObject {
            val history = FakeChatMac.history(chatID)
            val body = history.getValue("history").jsonObject
            val reply = JsonObject(
                mapOf(
                    "author" to JsonPrimitive("agent"),
                    "chatID" to JsonPrimitive(chatID),
                    "content" to JsonObject(mapOf("markdown" to JsonObject(mapOf("_0" to JsonPrimitive("Running the tests" + APPENDED.repeat(appends)))))),
                    "sentDate" to JsonPrimitive(1_795_000_501_000L),
                    "uniqueID" to JsonPrimitive(OPENING_ID),
                ),
            )
            val messages = JsonArray(body.getValue("messages").jsonArray + reply)
            return JsonObject(mapOf("history" to JsonObject(body + ("messages" to messages))))
        }
    }
}
