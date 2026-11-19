package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.MAC_TIME
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.SETTLE
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.chatId
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.envelope
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.ui.conversation.ChatDrafts
import dev.bugobliterator.itermbuddy.ui.conversation.DraftSend
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationPresenter
import dev.bugobliterator.itermbuddy.ui.conversation.ComposerSendStep
import dev.bugobliterator.itermbuddy.ui.conversation.composerSendStep
import dev.bugobliterator.itermbuddy.ui.conversation.send
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sending from the composer: the publish iOS sends (`AM:3087-3135`), its optimistic echo and how
 * the echo is acknowledged or found not delivered, on a real client, the fake relay and Mac, in
 * virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendTest {
    private suspend fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun ChatRig.shown(chatID: String = A): ConversationState.Shown {
        val state = repository.conversation(chatID).value
        assertTrue("$chatID: $state", state is ConversationState.Shown)
        return state as ConversationState.Shown
    }

    private fun draft(text: String): MentionDraft = MentionDraft.EMPTY.applyEdit(0, 0, text)

    /** A rig connected with chat [A] listed, open and loaded. */
    private suspend fun TestScope.openRig(rig: ChatRig = ChatRig(this)): ChatRig {
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        rig.repository.open(A)
        settle()
        assertTrue(rig.shown().sendable)
        return rig
    }

    private fun ConversationState.Shown.idOf(text: String): String =
        messages.single { (it.body as? MessageBody.PlainText)?.text == text }.uniqueID

    @Test
    fun publishMatchesSampleShape() = runTest {
        val sample = Json.parseToJsonElement(Fixtures.read("messages/agent-chat/publish-plain-text.json")).jsonObject
        val expected = sample.getValue("json").jsonObject
        val body = expected.getValue("payload").jsonObject.getValue("publish").jsonObject
        val message = body.getValue("message").jsonObject
        val rig = ChatRig(
            this,
            newUuid = { UUID.fromString(message.getValue("uniqueID").jsonPrimitive.content) },
            wallClockMillis = { message.getValue("sentDate").jsonPrimitive.content.toLong() },
        )
        rig.chats = listOf(SAMPLE to MAC_TIME)
        rig.connect()
        rig.repository.open(SAMPLE)
        settle()
        // Typed with a picked mention and stray whitespace around it, as the composer leaves it.
        val typed = MentionDraft.EMPTY.applyEdit(0, 0, " \n Ask ").insertMention(7, "ptys_3HX6W0ZD5N2BT", "Code Review").let {
            it.applyEdit(it.text.length, it.text.length, "to review src/widget.c\nthen ship it 🚀 — naïve café\n\n ")
        }
        assertEquals(SendOutcome.Sent, rig.repository.send(SAMPLE, typed))
        runCurrent()
        val sent = rig.mac.receivedEnvelopes().single { it.getValue("payload").jsonObject.containsKey("publish") }
        assertEquals("the sample, request ID and all", expected, sent)
        assertNotNull("sent on the link the history came on", rig.publishLinks.single())

        // A lowercase UUID source still sends an uppercase uniqueID.
        val lower = ChatRig(this, newUuid = { UUID.fromString("6a1d3f5b-2c4e-4a8b-9d0f-1e2a3b4c5d6e") })
        openRig(lower)
        assertEquals(SendOutcome.Sent, lower.repository.send(A, draft("hello")))
        runCurrent()
        val published = lower.publishes().single().getValue("message").jsonObject
        assertEquals("6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E", published.getValue("uniqueID").jsonPrimitive.content)
        assertEquals(setOf("chatID", "author", "content", "sentDate", "uniqueID"), published.keys)
        assertFalse("whole milliseconds, an integer literal", published.getValue("sentDate").jsonPrimitive.content.contains('.'))
    }

    @Test
    fun emptyAfterTrimIsNotSent() = runTest {
        val rig = openRig()
        val before = rig.shown()
        assertEquals(SendOutcome.Empty, rig.repository.send(A, draft(" \n\t  ")))
        assertEquals(SendOutcome.Empty, rig.repository.send(A, MentionDraft.EMPTY))
        settle()
        assertTrue(rig.publishes().isEmpty())
        assertEquals(before.messages.size, rig.shown().messages.size)
    }

    @Test
    fun echoIsReplacedByDelivery() = runTest {
        val rig = openRig()
        val count = rig.shown().messages.size
        assertEquals(SendOutcome.Sent, rig.repository.send(A, draft("  Ship it  ")))
        runCurrent()
        val pending = rig.shown()
        val id = pending.idOf("Ship it")
        assertEquals("the optimistic echo shows at once", count + 1, pending.messages.size)
        assertEquals(EchoState.SENDING, pending.echoes[id])
        assertEquals(Participant.USER, pending.messages.last().author)

        rig.echo(rig.publishes().single())
        settle()
        val delivered = rig.shown()
        assertEquals("upserted, not appended again", count + 1, delivered.messages.size)
        assertEquals(EchoState.DELIVERED, delivered.echoes[id])
        // The timeout passing later changes nothing.
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(EchoState.DELIVERED, rig.shown().echoes[id])
    }

    @Test
    fun failedSendWithdrawsTheEchoAndKeepsDraft() = runTest {
        val rig = openRig()
        val count = rig.shown().messages.size
        rig.failPublishes = true
        val drafts = ChatDrafts()
        drafts.set(A, draft("Deploy now"))
        val outcome = drafts.send(A) { rig.repository.send(A, it) }
        assertEquals(SendOutcome.NotSendable(NotSendableReason.SEND_FAILED), outcome)
        assertEquals("the draft is kept", "Deploy now", drafts.get(A).serialised())
        // Nothing reached the Mac: the draft is the only copy (owner decision), no bubble.
        val shown = rig.shown()
        assertEquals(count, shown.messages.size)
        assertTrue(shown.messages.none { (it.body as? MessageBody.PlainText)?.text == "Deploy now" })
        assertTrue(shown.echoes.isEmpty())
        settle()
        assertEquals(count, rig.shown().messages.size)

        // Its own-send mark went with it: the agent's next bump is news.
        rig.sendSample("delivery-agent-opening.json", A)
        rig.chats = listOf(A to MAC_TIME + 500)
        rig.pushList()
        settle()
        assertTrue("the failed send did not swallow the agent's bump", rig.repository.rows.value.single().unread)

        // Sent: the draft clears.
        rig.failPublishes = false
        assertEquals(SendOutcome.Sent, drafts.send(A) { rig.repository.send(A, it) })
        assertTrue(drafts.get(A).isBlank)
        // Not sent because the draft was empty: nothing changes.
        assertEquals(SendOutcome.Empty, drafts.send(A) { rig.repository.send(A, it) })
    }

    @Test
    fun failedSendsLeaveNoStaleMark() = runTest {
        val rig = openRig()
        val gate = CompletableDeferred<Unit>()
        rig.publishGate = gate
        // Two sends wait on the link; the earlier one's mark sits under the later one's.
        val first = async { rig.repository.send(A, draft("first")) }
        runCurrent()
        val second = async { rig.repository.send(A, draft("second")) }
        runCurrent()
        rig.failPublishes = true
        // They fail in order: the earlier one (not on top) first, then the later one.
        gate.complete(Unit)
        runCurrent()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.SEND_FAILED), first.await())
        assertEquals(SendOutcome.NotSendable(NotSendableReason.SEND_FAILED), second.await())
        settle()
        assertTrue(rig.shown().echoes.isEmpty())
        // No mark of either is left to swallow the agent's bump.
        rig.sendSample("delivery-agent-opening.json", A)
        rig.chats = listOf(A to MAC_TIME + 500)
        rig.pushList()
        settle()
        assertTrue(rig.repository.rows.value.single().unread)
    }

    @Test
    fun ownSendSetsTheSeenMark() = runTest {
        val rig = openRig()
        assertEquals(SendOutcome.Sent, rig.repository.send(A, draft("mine")))
        settle()
        // The list's bump for the send is the owner's own, not news.
        rig.chats = listOf(A to MAC_TIME + 500)
        rig.pushList()
        settle()
        assertFalse(rig.repository.rows.value.single().unread)
        rig.sendSample("delivery-agent-opening.json", A)
        rig.chats = listOf(A to MAC_TIME + 900)
        rig.pushList()
        settle()
        assertTrue("a later change is news", rig.repository.rows.value.single().unread)
    }

    @Test
    fun consumerKeepsDrainingWhileAPublishWaits() = runTest {
        val rig = openRig()
        val gate = CompletableDeferred<Unit>()
        rig.publishGate = gate
        val sending = async { rig.repository.send(A, draft("behind the lock")) }
        runCurrent()
        assertFalse("the write waits", sending.isCompleted)
        // Meanwhile the Mac's messages are still consumed and published.
        rig.sendSample("delivery-agent-opening.json", A)
        settle()
        assertTrue(rig.shown().messages.any { it.uniqueID == OPENING_ID })
        assertEquals(EchoState.SENDING, rig.shown().echoes[rig.shown().idOf("behind the lock")])
        gate.complete(Unit)
        runCurrent()
        assertEquals(SendOutcome.Sent, sending.await())
    }

    @Test
    fun sendAnswersWhenTheConsumerStops() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(message: PhoneMessage, onLink: Long?, timeout: Duration?, onSent: (Long) -> Unit): MacMessage =
                error("no requests")
        }
        val repository = ChatRepository(link, scope, InMemoryChatSeenStore(), nowMillis = { testScheduler.currentTime })
        // Posted while the consumer is alive, dropped when it stops before running it.
        val sending = async(start = CoroutineStart.UNDISPATCHED) { repository.send(A, draft("hello")) }
        job.cancel()
        runCurrent()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED), sending.await())

        // Handed to a consumer suspended in receive, which is cancelled before it resumes.
        val waitingJob = Job()
        val waiting = ChatRepository(
            link,
            CoroutineScope(StandardTestDispatcher(testScheduler) + waitingJob),
            InMemoryChatSeenStore(),
            nowMillis = { testScheduler.currentTime },
        )
        runCurrent()
        val handedOver = async(start = CoroutineStart.UNDISPATCHED) { waiting.send(A, draft("hello")) }
        waitingJob.cancel()
        runCurrent()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED), handedOver.await())
        // And after it stopped.
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED), repository.send(A, draft("again")))
    }

    @Test
    fun historyOnThePublishLinkKeepsItSending() = runTest {
        val rig = openRig()
        rig.repository.send(A, draft("Still on its way"))
        runCurrent()
        val id = rig.shown().idOf("Still on its way")
        // A resubscribe on the same link, before the echo is due: its history may predate the publish.
        rig.repository.retry(A)
        settle()
        val shown = rig.shown()
        assertEquals(2, rig.subscribesFor(A))
        assertEquals("put back at the end", id, shown.messages.last().uniqueID)
        assertEquals(EchoState.SENDING, shown.echoes[id])
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[id])
        // Once overdue, a history on the same link finds it not delivered at once.
        rig.repository.send(A, draft("Overdue"))
        advanceTimeBy(11.seconds)
        runCurrent()
        rig.repository.retry(A)
        settle()
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[rig.shown().idOf("Overdue")])
    }

    @Test
    fun deliveryAfterDiscardBringsItBack() = runTest {
        val rig = openRig()
        rig.repository.send(A, draft("Discarded"))
        advanceTimeBy(15.seconds)
        runCurrent()
        val id = rig.shown().idOf("Discarded")
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[id])
        rig.repository.discardUnsent(A, id)
        settle()
        assertTrue(rig.shown().messages.none { it.uniqueID == id })
        assertNull(rig.shown().echoes[id])
        // The Mac had it after all: it reappears, delivered, at the end.
        rig.echo(rig.publishes().single())
        settle()
        assertEquals(id, rig.shown().messages.last().uniqueID)
        assertEquals(EchoState.DELIVERED, rig.shown().echoes[id])
    }

    @Test
    fun composerReasonForEveryState() = runTest {
        assertEquals(NotSendableReason.TOO_LARGE, ConversationState.TooLarge.whyNotSendable)
        assertEquals(NotSendableReason.LOAD_FAILED, ConversationState.Failed(retryable = true).whyNotSendable)
        assertEquals(NotSendableReason.AI_UNAVAILABLE, ConversationState.Failed(retryable = false).whyNotSendable)
        assertEquals(
            NotSendableReason.TOO_LARGE,
            ConversationPresenter().present(A, ConversationState.TooLarge, null, null, 12, null, null, emptyMap()).whyNotSendable,
        )
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.repository.open(B)
        settle()
        assertEquals("not connected", ConversationState.Loading(NotSendableReason.NOT_CONNECTED), rig.repository.conversation(B).value)
        rig.neverAnswer += B
        rig.connect()
        settle()
        assertEquals("not listed: deleted", ConversationState.Loading(NotSendableReason.DELETED), rig.repository.conversation(B).value)
        rig.repository.open(A)
        settle()
        rig.chatMac.flipAi(false)
        settle()
        assertEquals(NotSendableReason.AI_UNAVAILABLE, rig.repository.conversation(A).value.whyNotSendable)
    }

    @Test
    fun echoTimeoutMarksNotDelivered() = runTest {
        val rig = openRig(ChatRig(this, timeouts = ChatTimeouts(echo = 10.seconds)))
        rig.repository.send(A, draft("Anyone there?"))
        val id = rig.shown().idOf("Anyone there?")
        advanceTimeBy(9_900.milliseconds)
        runCurrent()
        assertEquals(EchoState.SENDING, rig.shown().echoes[id])
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals("the Mac swallowed it (HB:1822)", EchoState.NOT_DELIVERED, rig.shown().echoes[id])
    }

    @Test
    fun retryMintsANewUniqueId() = runTest {
        val rig = openRig()
        rig.repository.send(A, draft("Try this"))
        settle()
        advanceTimeBy(15.seconds)
        runCurrent()
        val original = rig.shown().idOf("Try this")
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[original])

        assertEquals(SendOutcome.Sent, rig.repository.retrySend(A, original))
        runCurrent()
        val published = rig.publishes().map { it.getValue("message").jsonObject.getValue("uniqueID").jsonPrimitive.content }
        assertEquals(2, published.size)
        assertEquals(original, published.first())
        assertNotEquals("the Mac appends without deduping (CB:146-160)", original, published.last())
        val shown = rig.shown()
        assertEquals("the retry replaces the bubble", published.last(), shown.idOf("Try this"))
        assertEquals(EchoState.SENDING, shown.echoes[published.last()])
        assertNull(shown.echoes[original])
        // A retry whose write fails puts the original back, not delivered.
        rig.failPublishes = true
        advanceTimeBy(15.seconds)
        runCurrent()
        val copy = published.last()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.SEND_FAILED), rig.repository.retrySend(A, copy))
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[copy])
        assertEquals(copy, rig.shown().idOf("Try this"))
        rig.failPublishes = false
        // Only a message not delivered can be retried.
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_RETRYABLE), rig.repository.retrySend(A, original))
        assertEquals(2, rig.publishes().size)

        // Delete drops a bubble that was not delivered, and only such a bubble.
        rig.repository.discardUnsent(A, published.last())
        settle()
        assertTrue(rig.shown().messages.none { it.uniqueID == published.last() })
    }

    @Test
    fun lateEchoMarksDelivered() = runTest {
        val rig = openRig()
        rig.repository.send(A, draft("Late one"))
        advanceTimeBy(15.seconds)
        runCurrent()
        val original = rig.shown().idOf("Late one")
        assertEquals(EchoState.NOT_DELIVERED, rig.shown().echoes[original])
        rig.echo(rig.publishes().single())
        settle()
        assertEquals(EchoState.DELIVERED, rig.shown().echoes[original])

        // Retried, then the original's echo comes after all: it upserts and shows as delivered.
        rig.repository.send(A, draft("Twice"))
        advanceTimeBy(15.seconds)
        runCurrent()
        val first = rig.shown().idOf("Twice")
        rig.repository.retrySend(A, first)
        runCurrent()
        rig.echo(rig.publishes()[1])
        settle()
        val shown = rig.shown()
        assertEquals(EchoState.DELIVERED, shown.echoes[first])
        assertEquals(2, shown.messages.count { (it.body as? MessageBody.PlainText)?.text == "Twice" })
    }

    @Test
    fun pendingEchoSurvivesReconnect() = runTest {
        val rig = openRig()
        rig.repository.send(A, draft("Lost in the drop"))
        runCurrent()
        val lost = rig.shown().idOf("Lost in the drop")
        rig.repository.send(A, draft("Made it"))
        runCurrent()
        val made = rig.publishes().last()
        val madeID = made.getValue("message").jsonObject.getValue("uniqueID").jsonPrimitive.content
        // The Mac appended the second before the link dropped: its next history holds it.
        rig.historyFrames[A] = { id -> envelope(historyWith(A, made.getValue("message").jsonObject), id) }
        rig.reconnect()
        settle()
        val shown = rig.shown()
        assertEquals("kept at the end of the new history", lost, shown.messages.last().uniqueID)
        assertEquals(EchoState.NOT_DELIVERED, shown.echoes[lost])
        assertEquals(EchoState.DELIVERED, shown.echoes[madeID])
        assertEquals(1, shown.messages.count { it.uniqueID == madeID })
    }

    @Test
    fun sendNeedsCurrentLink() = runTest {
        val rig = openRig()
        rig.mac.stop()
        settle()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED), rig.repository.send(A, draft("hello")))
        assertFalse(rig.shown().sendable)
        assertEquals(NotSendableReason.NOT_CONNECTED, rig.shown().whyNotSendable)
        assertTrue(rig.publishLinks.isEmpty())
    }

    @Test
    fun sendRefusedWhenNotSubscribedOnThisLink() = runTest {
        val rig = openRig()
        rig.holdNext[A] = 5.seconds
        rig.reconnect()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(NotSendableReason.NOT_LOADED, rig.shown().whyNotSendable)
        assertEquals(SendOutcome.NotSendable(NotSendableReason.NOT_LOADED), rig.repository.send(A, draft("too soon")))
        assertTrue(rig.publishes().isEmpty())
        advanceTimeBy(10.seconds)
        runCurrent()
        assertNull(rig.shown().whyNotSendable)
        assertEquals(SendOutcome.Sent, rig.repository.send(A, draft("now")))
        runCurrent()
        assertEquals(1, rig.publishes().size)

        // A chat deleted on the Mac takes nothing.
        rig.chats = listOf(B to MAC_TIME)
        rig.pushList()
        settle()
        assertEquals(SendOutcome.NotSendable(NotSendableReason.DELETED), rig.repository.send(A, draft("gone")))
    }

    @Test
    fun pendingRequestWarnsBeforeSend() = runTest {
        val rig = openRig()
        rig.sendSample("turn-lifecycle-started.json", A)
        rig.sendSample("typing-status-on.json", A)
        rig.sendSample("delivery-remote-command-classic.json", A)
        rig.sendSample("typing-status-off.json", A)
        settle()
        val activity = rig.repository.activity.value[A]
        assertEquals(AgentState.WAITING, activity)
        val messages = rig.shown().messages
        assertTrue(warnsOfPendingRequest(activity, messages))
        val typed = draft("Actually, do something else")
        assertEquals(ComposerSendStep.CONFIRM, composerSendStep(typed, enabled = true, warns = true, confirmed = false))
        assertEquals(ComposerSendStep.SEND, composerSendStep(typed, enabled = true, warns = true, confirmed = true))
        assertEquals(ComposerSendStep.SEND, composerSendStep(typed, enabled = true, warns = false, confirmed = false))
        assertEquals(ComposerSendStep.NOTHING, composerSendStep(MentionDraft.EMPTY, enabled = true, warns = true, confirmed = false))
        assertEquals(ComposerSendStep.NOTHING, composerSendStep(typed, enabled = false, warns = false, confirmed = false))

        // Sent anyway: the owner's message is now the newest, and the warning goes.
        assertEquals(SendOutcome.Sent, rig.repository.send(A, typed))
        runCurrent()
        assertFalse(warnsOfPendingRequest(rig.repository.activity.value[A], rig.shown().messages))
    }

    @Test
    fun warningOnlyForAbandonableRequests() {
        fun agent(body: MessageBody, index: Int) = ChatMessage(A, Participant.AGENT, body, MAC_TIME + index, "11111111-2222-4333-8444-%012d".format(index))
        val classic = agent(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("make deploy"), safe = false), 1)
        val enable = agent(MessageBody.ClientNotice(ClientAction.EnableOrchestrationRequest("R1")), 2)
        val select = agent(MessageBody.SelectSessionRequest(terminal = true), 3)
        val permission = agent(MessageBody.ClientNotice(ClientAction.WorkgroupPermissionRequest("R2", "wg-1", "Team", "Run tests")), 4)
        val executing = agent(MessageBody.ClientNotice(ClientAction.ExecutingCommand(CommandContent.ExecuteCommand("make deploy"))), 5)
        val hidden = agent(MessageBody.Hidden("renameChat"), 6)
        assertTrue(warnsOfPendingRequest(AgentState.WAITING, listOf(classic)))
        assertTrue(warnsOfPendingRequest(AgentState.WAITING, listOf(enable)))
        assertTrue("hidden messages are not the newest visible", warnsOfPendingRequest(AgentState.WAITING, listOf(classic, hidden)))
        assertFalse("not abandoned by typing", warnsOfPendingRequest(AgentState.WAITING, listOf(select)))
        assertFalse("not abandoned by typing", warnsOfPendingRequest(AgentState.WAITING, listOf(permission)))
        assertFalse("the approved command already runs", warnsOfPendingRequest(AgentState.WAITING, listOf(classic, executing)))
        for (state in AgentState.entries - AgentState.WAITING) assertFalse(state.name, warnsOfPendingRequest(state, listOf(classic)))
        assertFalse(warnsOfPendingRequest(null, listOf(classic)))
        assertFalse(warnsOfPendingRequest(AgentState.WAITING, emptyList()))
    }

    @Test
    fun noSendWhileAiUnavailable() = runTest {
        val rig = openRig()
        rig.chatMac.flipAi(false)
        settle()
        assertEquals(NotSendableReason.AI_UNAVAILABLE, (rig.repository.conversation(A).value as? ConversationState.Shown)?.whyNotSendable)
        assertEquals(SendOutcome.NotSendable(NotSendableReason.AI_UNAVAILABLE), rig.repository.send(A, draft("hello")))
        settle()
        assertTrue(rig.publishes().isEmpty())
    }

    @Test
    fun draftsClearOnUnpair() = runTest {
        val rig = SessionsRig(this)
        rig.connect()
        runCurrent()
        val typed = draft("half a thought").insertMention(0, "ptys_3HX6W0ZD5N2BT", "Code Review")
        rig.graph.chatDrafts.set(A, typed)
        assertEquals(typed, rig.graph.chatDrafts.get(A))
        rig.client.unpair()
        runCurrent()
        assertTrue(rig.graph.chatDrafts.get(A).isBlank)
        assertEquals("ChatDrafts(chats=0)", rig.graph.chatDrafts.toString())
    }

    /**
     * A rotation right after Send: the composer's scope is cancelled at once, the send still runs
     * in the app's scope and clears the draft once, and the composer that comes back cannot send
     * it again while it is out, nor after (the draft is empty).
     */
    @Test
    fun sendOutlivesTheComposerAndCannotBeDoubled() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        advanceTimeBy(5.seconds)
        runCurrent()
        rig.navigator.openConversation(SAMPLE)
        advanceTimeBy(5.seconds)
        runCurrent()
        val drafts = rig.graph.chatDrafts
        drafts.set(SAMPLE, draft("Ship it"))
        val composer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            assertTrue(rig.graph.chatsHome.sendDraft(SAMPLE))
            awaitCancellation()
        }
        composer.cancel()
        assertTrue(drafts.sendState(SAMPLE).value.sending)
        assertFalse("the composer after the rotation offers no second send", rig.graph.chatsHome.sendDraft(SAMPLE))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue("cleared from the outcome", drafts.get(SAMPLE).isBlank)
        assertEquals(DraftSend(sending = false, finished = 1, outcome = SendOutcome.Sent), drafts.sendState(SAMPLE).value)
        assertEquals(1, rig.chatMac.sent("publish").size)

        assertTrue(rig.graph.chatsHome.sendDraft(SAMPLE))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(DraftSend(sending = false, finished = 2, outcome = SendOutcome.Empty), drafts.sendState(SAMPLE).value)
        assertEquals("published once", 1, rig.chatMac.sent("publish").size)
    }

    @Test
    fun outcomesShowNoContent() {
        val secret = "Sentinel deploy shell"
        val message = UserTextMessage(A, secret, 1, "11111111-2222-4333-8444-000000000001")
        for (text in listOf(SendOutcome.NotSendable(NotSendableReason.SEND_FAILED).toString(), message.toString())) {
            assertFalse(text, text.contains(secret))
        }
    }

    /** The sample history for [chatID] with [message] (a published message) appended. */
    private fun historyWith(chatID: String, message: JsonObject): JsonObject {
        val history = FakeChatMac.history(chatID)
        val body = history.getValue("history").jsonObject
        val messages = JsonArray(body.getValue("messages").jsonArray + message)
        return JsonObject(mapOf("history" to JsonObject(body + ("messages" to messages))))
    }

    private companion object {
        val A = chatId(201)
        val B = chatId(202)
        const val SAMPLE = FakeChatMac.SAMPLE_CHAT
        const val OPENING_ID = "11111111-2222-4333-8444-000000000501"
    }
}
