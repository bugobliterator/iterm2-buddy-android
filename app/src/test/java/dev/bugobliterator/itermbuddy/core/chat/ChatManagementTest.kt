package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.MAC_TIME
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.SETTLE
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.chatId
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.requestIDOf
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.ui.chats.NewChatCreator
import dev.bugobliterator.itermbuddy.ui.chats.NewChatStatus
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationPresenter
import dev.bugobliterator.itermbuddy.ui.conversation.TranscriptItem
import dev.bugobliterator.itermbuddy.ui.conversation.offersDeleteFromHere
import dev.bugobliterator.itermbuddy.ui.conversation.transcriptItems
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creating, deleting, muting and truncating chats (`AM:2582-2765`), on a real client, the fake
 * relay, `FakeMac` and `FakeChatMac`, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatManagementTest {
    private suspend fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun ChatRig.listedIDs(): List<String> = (repository.list.value as ChatListState.Loaded).entries.map { it.chat.id }

    private fun ChatRig.entry(chatID: String): ChatListEntry =
        (repository.list.value as ChatListState.Loaded).entries.single { it.chat.id == chatID }

    private fun ChatRig.shown(chatID: String): ConversationState.Shown {
        val state = repository.conversation(chatID).value
        assertTrue("$chatID: $state", state is ConversationState.Shown)
        return state as ConversationState.Shown
    }

    /** The phone→Mac sample [name]'s whole envelope. */
    private fun sample(name: String): JsonObject =
        Json.parseToJsonElement(Fixtures.read("messages/agent-chat/$name.json")).jsonObject.getValue("json").jsonObject

    private fun JsonObject.withoutRequestID(): JsonObject = JsonObject(this - "requestID")

    /** The envelopes the Mac received with payload [case], in order. */
    private fun ChatRig.received(case: String): List<JsonObject> =
        mac.receivedEnvelopes().filter { it.getValue("payload").jsonObject.containsKey(case) }

    /** A rig connected with [chats] listed (each at [MAC_TIME]), at the Mac's hello [revision]. */
    private suspend fun TestScope.connected(vararg chats: String, revision: Int? = null): ChatRig {
        val rig = ChatRig(this)
        revision?.let { rig.mac.revision = it }
        rig.chats = chats.mapIndexed { index, id -> id to MAC_TIME - index }
        rig.connect()
        settle()
        return rig
    }

    // Create

    @Test
    fun createOrchestratorAndSessionMatchSamples() = runTest {
        val rig = connected(A)
        assertEquals(CreateOutcome.Created(CREATED), rig.repository.create(NewChatMode.Orchestrator))
        assertEquals(CreateOutcome.Created(CREATED), rig.repository.create(NewChatMode.Session(SESSION_GUID)))
        val sent = rig.received("createChat")
        assertEquals(2, sent.size)
        // The payloads are the samples'; the request IDs are this client's own (the samples' are 10 and 11).
        assertEquals(sample("create-chat-orchestrator").withoutRequestID(), sent[0].withoutRequestID())
        assertEquals(sample("create-chat-session").withoutRequestID(), sent[1].withoutRequestID())
        assertNotNull("a request", requestIDOf(sent[0]))
        assertNotNull("a request", requestIDOf(sent[1]))
    }

    @Test
    fun createdChatIsInsertedOnceAndOpened() = runTest {
        val rig = connected(A)
        val opened = CopyOnWriteArrayList<String>()
        val creator = NewChatCreator(backgroundScope, rig.repository::create) { chatID ->
            opened += chatID
            rig.repository.open(chatID)
        }
        creator.create(NewChatMode.Orchestrator)
        // A second tap while the first is out creates nothing more.
        creator.create(NewChatMode.Orchestrator)
        settle()
        assertEquals(1, rig.received("createChat").size)
        assertEquals(listOf(CREATED), opened.toList())
        assertEquals(NewChatStatus.Idle, creator.status.value)
        assertEquals("inserted at the top, once", listOf(CREATED, A), rig.listedIDs())
        // Opened and loaded: watched once it was listed, then subscribed once more for its history
        // on opening (a watched chat keeps no transcript, and only a subscribe sends one, HB:1417).
        assertTrue(rig.shown(CREATED).sendable)
        assertEquals(2, rig.subscribesFor(CREATED))

        // The Mac's own push listing it changes nothing.
        rig.chats = listOf(CREATED to MAC_TIME + 10, A to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals(listOf(CREATED, A), rig.listedIDs())
    }

    @Test
    fun createFailuresSayWhetherToRetry() = runTest {
        val rig = connected(A)
        // A Mac that refuses: retryable, nothing opened.
        rig.mac.onRequestScripted("createChat") { envelope ->
            rig.mac.sendMessage(FakeChatMac.samplePayload("error-unknown-session.json"), requestIDOf(envelope))
        }
        val opened = CopyOnWriteArrayList<String>()
        val creator = NewChatCreator(backgroundScope, rig.repository::create) { opened += it }
        creator.create(NewChatMode.Session(SESSION_GUID))
        settle()
        assertEquals(NewChatStatus.Failed(retryable = true), creator.status.value)
        assertTrue(opened.isEmpty())
        assertEquals(listOf(A), rig.listedIDs())

        // AI off: not even asked.
        rig.chatMac.flipAi(false)
        settle()
        val asked = rig.received("createChat").size
        assertEquals(CreateOutcome.Failed(retryable = false), rig.repository.create(NewChatMode.Orchestrator))
        assertEquals(asked, rig.received("createChat").size)

        // Offline: retryable.
        rig.mac.stop()
        settle()
        assertEquals(CreateOutcome.Failed(retryable = true), rig.repository.create(NewChatMode.Orchestrator))
    }

    // Delete

    @Test
    fun deleteIsOptimisticAndRefetchesWithoutPush() = runTest {
        val rig = connected(SESSION_CHAT, B)
        assertEquals(AgentState.IDLE, rig.repository.activity.value[SESSION_CHAT])
        rig.repository.open(SESSION_CHAT)
        settle()
        rig.repository.close(SESSION_CHAT)
        settle()
        assertEquals(1, rig.repository.heldTranscripts)
        val lists = rig.listRequests.get()

        // The Mac deletes it but pushes nothing (as while AI is off on it, HB:2289-2293).
        rig.chats = listOf(B to MAC_TIME)
        rig.repository.delete(SESSION_CHAT)
        runCurrent()
        assertEquals("removed at once", listOf(B), rig.listedIDs())
        assertNull("its activity goes", rig.repository.activity.value[SESSION_CHAT])
        assertEquals("its transcript goes", 0, rig.repository.heldTranscripts)
        assertEquals(sample("delete-chat"), rig.received("deleteChat").single())

        advanceTimeBy(DELETE_LIST_WAIT - 1.milliseconds)
        runCurrent()
        assertEquals("not yet", lists, rig.listRequests.get())
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals("refetched once the wait is over", lists + 1, rig.listRequests.get())
        settle()
        assertEquals(listOf(B), rig.listedIDs())
        assertEquals(lists + 1, rig.listRequests.get())
    }

    @Test
    fun refusedDeleteRefetchesAtOnce() = runTest {
        val rig = connected(SESSION_CHAT, B)
        val lists = rig.listRequests.get()
        rig.repository.delete(SESSION_CHAT)
        runCurrent()
        assertEquals(listOf(B), rig.listedIDs())
        // The Mac refused: an unsolicited internalError (HB:458-470), and the chat is still there.
        rig.chatMac.refuseDelete()
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals("refetched before the wait is over", lists + 1, rig.listRequests.get())
        assertEquals("the row is back", listOf(SESSION_CHAT, B), rig.listedIDs())
        // And not once more when the wait would have been over.
        settle()
        assertEquals(lists + 1, rig.listRequests.get())
        assertFalse("the Mac's text is never logged", rig.logs.any { "not available" in it })
    }

    @Test
    fun deleteThenPushDoesNotRefetch() = runTest {
        val rig = connected(SESSION_CHAT, B)
        val lists = rig.listRequests.get()
        rig.repository.delete(SESSION_CHAT)
        runCurrent()
        rig.chats = listOf(B to MAC_TIME)
        rig.pushList()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf(B), rig.listedIDs())
        assertEquals("the push confirmed it", lists, rig.listRequests.get())
        // An unrelated error later is not a refused delete.
        rig.chatMac.refuseDelete()
        settle()
        assertEquals(lists, rig.listRequests.get())
    }

    // Mute

    @Test
    fun muteIsOptimisticAndSent() = runTest {
        val rig = connected(SESSION_CHAT, B)
        assertEquals("the sample entry is muted", true, rig.entry(SESSION_CHAT).muted)
        rig.repository.setMuted(SESSION_CHAT, false)
        runCurrent()
        assertEquals(false, rig.entry(SESSION_CHAT).muted)
        assertEquals(false, rig.repository.rows.value.first { it.entry.chat.id == SESSION_CHAT }.entry.muted)
        rig.repository.setMuted(SESSION_CHAT, true)
        runCurrent()
        assertEquals(true, rig.entry(SESSION_CHAT).muted)
        assertEquals(listOf(sample("set-chat-muted-off"), sample("set-chat-muted-on")), rig.received("setChatMuted"))
        // The Mac does not push; the flag stands until the next list (which carries the Mac's value).
        settle()
        assertEquals(true, rig.entry(SESSION_CHAT).muted)
        assertEquals(1, rig.listRequests.get())
    }

    // Delete From Here

    @Test
    fun deleteFromSendsTheSuffix() = runTest {
        val rig = connected(SAMPLE)
        rig.repository.open(SAMPLE)
        settle()
        val before = rig.shown(SAMPLE).messages.map { it.uniqueID }
        assertTrue(rig.repository.deleteFrom(SAMPLE, OWN_COMMAND.lowercase()))
        runCurrent()
        assertEquals(sample("delete-messages"), rig.received("deleteMessages").single())
        assertEquals("truncated at once", before.takeWhile { it != OWN_COMMAND }, rig.shown(SAMPLE).messages.map { it.uniqueID })
        // A message no longer there truncates nothing more.
        assertFalse(rig.repository.deleteFrom(SAMPLE, OWN_COMMAND))
        runCurrent()
        assertEquals(1, rig.received("deleteMessages").size)
    }

    @Test
    fun deleteFromHiddenBelowRevisionTwelve() = runTest {
        val rig = connected(SAMPLE, revision = 11)
        rig.repository.open(SAMPLE)
        settle()
        val before = rig.shown(SAMPLE).messages.size
        assertFalse(content(rig, 11).deletesFromHere)
        assertFalse(rig.repository.deleteFrom(SAMPLE, OWN_COMMAND))
        settle()
        assertTrue(rig.received("deleteMessages").isEmpty())
        assertEquals(before, rig.shown(SAMPLE).messages.size)
        assertTrue(content(rig, 12).deletesFromHere)
    }

    @Test
    fun deleteFromOnlyOnOwnMessages() = runTest {
        val rig = connected(SAMPLE)
        rig.repository.open(SAMPLE)
        settle()
        val shown = rig.shown(SAMPLE)
        assertFalse("the agent's reply", rig.repository.deleteFrom(SAMPLE, AGENT_REPLY))
        settle()
        assertTrue(rig.received("deleteMessages").isEmpty())
        assertEquals(shown.messages.size, rig.shown(SAMPLE).messages.size)

        // Offered on the owner's own bubbles only: not the agent's, notices or request cards.
        val items = transcriptItems(shown.messages, typing = false)
        val offered = items.filter(::offersDeleteFromHere).map { it.key }
        val own = items.filter { it is TranscriptItem.Bubble && it.fromUser }.map { it.key }
        assertTrue(offered.isNotEmpty())
        assertEquals(own, offered)
        assertTrue(OWN_COMMAND in offered)
        assertFalse(AGENT_REPLY in offered)
        // Nor on a send of this phone's not yet delivered: the Mac does not hold it.
        val echo = ChatMessage(SAMPLE, Participant.USER, MessageBody.PlainText("hi"), 1.0, ECHO_ID)
        val pending = transcriptItems(listOf(echo), typing = false, echoes = mapOf(ECHO_ID to EchoState.SENDING))
        assertFalse(offersDeleteFromHere(pending.single()))
        val delivered = transcriptItems(listOf(echo), typing = false, echoes = mapOf(ECHO_ID to EchoState.DELIVERED))
        assertTrue(offersDeleteFromHere(delivered.single()))
    }

    @Test
    fun messagesRemovedEchoIsIdempotent() = runTest {
        val rig = connected(SAMPLE)
        rig.repository.open(SAMPLE)
        settle()
        assertTrue(rig.repository.deleteFrom(SAMPLE, OWN_COMMAND))
        settle()
        val truncated = rig.shown(SAMPLE).messages.map { it.uniqueID }
        // The echo names the suffix plus a hidden row the phone never held, twice.
        rig.sendSample("messages-removed.json", SAMPLE)
        rig.sendSample("messages-removed.json", SAMPLE)
        settle()
        assertEquals(truncated, rig.shown(SAMPLE).messages.map { it.uniqueID })
        // A history after it is the Mac's word.
        rig.reconnect()
        settle()
        assertTrue(rig.shown(SAMPLE).messages.any { it.uniqueID == OWN_COMMAND })
    }

    @Test
    fun outcomesCarryNoContent() {
        assertEquals("Created(chatID=$CREATED)", CreateOutcome.Created(CREATED).toString())
        assertEquals("Failed(retryable=true)", CreateOutcome.Failed(retryable = true).toString())
    }

    /** The conversation's content for [SAMPLE] as shown at Mac [revision]. */
    private fun content(rig: ChatRig, revision: Int): ConversationContent {
        val entry = (rig.repository.list.value as ChatListState.Loaded).entries.single { it.chat.id == SAMPLE }
        return ConversationPresenter().present(SAMPLE, rig.shown(SAMPLE), entry, AgentState.IDLE, revision, null, null, emptyMap())
    }

    private companion object {
        val A = chatId(301)
        val B = chatId(302)
        const val SAMPLE = FakeChatMac.SAMPLE_CHAT

        /** The chat the delete and mute samples name: the sample list's session-bound chat. */
        const val SESSION_CHAT = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
        const val SESSION_GUID = "ptys_7K2M9Q4R8T1VH"

        /** The `id` of the chat `chat-created.json` names. */
        const val CREATED = "2F3E4D5C-6B7A-4980-A1B2-C3D4E5F60718"

        /** The owner's terminal command in the sample history, and the agent's reply after it. */
        const val OWN_COMMAND = "11111111-2222-4333-8444-000000000300"
        const val AGENT_REPLY = "11111111-2222-4333-8444-000000000400"
        const val ECHO_ID = "11111111-2222-4333-8444-000000000500"
        val DELETE_LIST_WAIT = 3.seconds
    }
}
