package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.chat.AgentState.DONE
import dev.bugobliterator.itermbuddy.core.chat.AgentState.IDLE
import dev.bugobliterator.itermbuddy.core.chat.AgentState.UNKNOWN
import dev.bugobliterator.itermbuddy.core.chat.AgentState.WAITING
import dev.bugobliterator.itermbuddy.core.chat.AgentState.WORKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-chat activity state machine. Time is a fake clock advanced by hand; both intervals are
 * injected with values that differ from the defaults.
 */
class ChatActivityTest {
    private var now = 50_000L
    private val tracker = ChatActivityTracker({ now }, parkDebounceMillis = DEBOUNCE, snapshotSettleMillis = SETTLE)

    /** Every state [CHAT] showed after each step, so a transient flash cannot hide. */
    private val trail = mutableListOf<AgentState>()

    private var nextMessage = 0

    private fun state(chatID: String = CHAT): AgentState = tracker.states.value[chatID] ?: UNKNOWN

    private fun record(): AgentState = state().also { trail += it }

    private fun advance(millis: Long, stepMillis: Long = 50) {
        var left = millis
        while (left > 0) {
            val step = minOf(stepMillis, left)
            now += step
            left -= step
            tracker.tick()
            record()
        }
    }

    private fun message(body: MessageBody, author: Participant = Participant.AGENT, chatID: String = CHAT) =
        ChatMessage(chatID, author, body, now.toDouble(), "33333333-4444-4555-8666-%012d".format(nextMessage++))

    private fun started(chatID: String = CHAT) = tracker.onTurn(chatID, TurnEvent.STARTED, visible = false)
    private fun ended(chatID: String = CHAT, visible: Boolean = false) = tracker.onTurn(chatID, TurnEvent.ENDED, visible)
    private fun typing(isTyping: Boolean, chatID: String = CHAT) = tracker.onTyping(chatID, Participant.AGENT, isTyping)
    private fun deliver(body: MessageBody, author: Participant = Participant.AGENT, chatID: String = CHAT) =
        tracker.onDelivery(chatID, message(body, author, chatID))
    private fun history(newest: MessageBody? = null, chatID: String = CHAT, visible: Boolean = false) =
        tracker.onHistory(chatID, newest?.let { message(it, chatID = chatID) }, visible)

    private fun working(chatID: String = CHAT) {
        started(chatID)
        typing(true, chatID)
    }

    private fun done(chatID: String = CHAT) {
        working(chatID)
        typing(false, chatID)
        ended(chatID)
    }

    @Test
    fun turnStartedThenTypingIsWorking() {
        started()
        typing(true)
        assertEquals(WORKING, record())
        advance(DEBOUNCE * 10)
        assertEquals(WORKING, state())
    }

    @Test
    fun turnEndedWhileHiddenIsDoneUntilRead() {
        working()
        typing(false)
        ended()
        assertEquals(DONE, state())
        advance(DEBOUNCE * 10)
        assertEquals(DONE, state())
        tracker.markRead(CHAT)
        assertEquals(IDLE, state())
    }

    @Test
    fun turnEndedWhileVisibleIsIdle() {
        working()
        typing(false)
        ended(visible = true)
        assertEquals(IDLE, state())
    }

    @Test
    fun turnEndedWhileVisibleClearsAnEarlierDone() {
        done()
        working()
        ended(visible = true)
        assertEquals(IDLE, state())
    }

    @Test
    fun parkShowsWaitingAfterDebounce() {
        working()
        typing(false)
        assertEquals(now + DEBOUNCE, tracker.nextDeadlineMillis())
        advance(DEBOUNCE - 1, stepMillis = 1)
        assertEquals(WORKING, state())
        advance(1)
        assertEquals(WAITING, state())
        assertNull(tracker.nextDeadlineMillis())
        typing(true)
        assertEquals(WORKING, state())
    }

    @Test
    fun endOfTurnTypingEdgeDoesNotFlashWaiting() {
        working()
        typing(false)
        record()
        advance(DEBOUNCE - 1)
        ended()
        record()
        advance(DEBOUNCE * 3)
        assertFalse(trail.toString(), WAITING in trail)
        assertEquals(DONE, state())
    }

    @Test
    fun startBeforeTypingDoesNotFlashWaiting() {
        started()
        assertEquals(WORKING, record())
        advance(DEBOUNCE - 1)
        typing(true)
        record()
        advance(DEBOUNCE * 3)
        assertFalse(trail.toString(), WAITING in trail)
        assertEquals(WORKING, state())
    }

    @Test
    fun interactiveRequestShowsWaitingAtOnce() {
        for ((index, request) in BLOCKING_REQUESTS.withIndex()) {
            val chatID = "chat-$index"
            working(chatID)
            deliver(request, chatID = chatID)
            assertEquals(request.toString(), WAITING, state(chatID))
            typing(false, chatID)
            assertEquals(request.toString(), WAITING, state(chatID))
        }
    }

    @Test
    fun requestWithTypingOnIsWaiting() {
        working()
        deliver(WORKGROUP_PERMISSION)
        assertEquals(WAITING, record())
        advance(DEBOUNCE * 10)
        assertEquals(WAITING, state())
        assertFalse(WORKING in trail)
    }

    @Test
    fun resumeAfterRequestClearsWaiting() {
        // A newer visible agent delivery: the orchestration tool call went on, typing true throughout.
        working("by-delivery")
        deliver(WORKGROUP_PERMISSION, chatID = "by-delivery")
        assertEquals(WAITING, state("by-delivery"))
        deliver(MessageBody.Markdown("next"), chatID = "by-delivery")
        assertEquals(WORKING, state("by-delivery"))

        // A typing-true edge after the park cleared typing (ChatAgent's resume, CA:259-263).
        working("by-typing")
        deliver(CLASSIC, chatID = "by-typing")
        typing(false, "by-typing")
        assertEquals(WAITING, state("by-typing"))
        typing(true, "by-typing")
        assertEquals(WORKING, state("by-typing"))

        // The turn ended.
        working("by-end")
        deliver(SELECT_SESSION, chatID = "by-end")
        typing(false, "by-end")
        ended("by-end", visible = true)
        assertEquals(IDLE, state("by-end"))

        // A history whose newest visible message is not a request; the snapshot re-seeds the turn.
        working("by-history")
        deliver(ENABLE_ORCHESTRATION, chatID = "by-history")
        typing(false, "by-history")
        history(MessageBody.Markdown("answered"), chatID = "by-history")
        typing(true, "by-history")
        started("by-history")
        assertEquals(WORKING, state("by-history"))
    }

    @Test
    fun repeatedTypingTrueDoesNotClearATypingOnPark() {
        working()
        deliver(WORKGROUP_PERMISSION)
        typing(true)
        assertEquals(WAITING, state())
    }

    @Test
    fun externalCommandDoesNotLatch() {
        working()
        deliver(MessageBody.ExternalCommand("send_text", "Send text", safe = null))
        assertEquals(WORKING, record())
        for (card in NON_BLOCKING_CARDS) {
            deliver(card)
            assertEquals(card.toString(), WORKING, record())
        }
        advance(DEBOUNCE * 3)
        assertFalse(WAITING in trail)
    }

    @Test
    fun userMessageDuringParkKeepsWaiting() {
        // A select-session or workgroup request is not abandoned by a typed message (CS:229-237).
        working()
        deliver(SELECT_SESSION)
        typing(false)
        deliver(MessageBody.PlainText("use the left one"), author = Participant.USER)
        assertEquals(WAITING, state())
    }

    @Test
    fun approvedCommandRunningIsWorking() {
        working()
        deliver(CLASSIC)
        typing(false)
        assertEquals(WAITING, record())
        // The owner approved on the Mac: a notice, then executingCommand, typing still false (ChatClient:235-270).
        deliver(MessageBody.ClientNotice(ClientAction.Notice("Running make…")))
        deliver(MessageBody.ClientNotice(ClientAction.ExecutingCommand(COMMAND)))
        assertEquals(WORKING, record())
        advance(DEBOUNCE * 10)
        assertEquals(WORKING, state())
        // The result is handled and typing resumes; a later quiet spell parks as usual.
        typing(true)
        typing(false)
        advance(DEBOUNCE)
        assertEquals(WAITING, state())
    }

    @Test
    fun approvedCommandRunningIsWorkingAfterReconnect() {
        history(MessageBody.ClientNotice(ClientAction.ExecutingCommand(COMMAND)))
        started()
        advance(DEBOUNCE * 10)
        assertEquals(WORKING, state())
    }

    @Test
    fun historyResetsTurnState() {
        working()
        history(visible = true)
        advance(SETTLE)
        assertEquals(IDLE, state())

        // Typing was reset too: a snapshot with only `started` is a quiet turn, parked after the debounce.
        history(chatID = "quiet")
        started("quiet")
        assertEquals(WORKING, state("quiet"))
        advance(DEBOUNCE)
        assertEquals(WAITING, state("quiet"))
    }

    @Test
    fun historySeedsTheLatch() {
        history(CLASSIC)
        started()
        assertEquals(WAITING, state())
    }

    @Test
    fun historySeededRequestNeedsATurn() {
        history(CLASSIC)
        advance(SETTLE)
        assertEquals(IDLE, state())
    }

    @Test
    fun typingOnParkSurvivesReconnect() {
        working()
        deliver(WORKGROUP_PERMISSION)
        tracker.onLinkDown()
        history(WORKGROUP_PERMISSION)
        typing(true)
        started()
        assertEquals(WAITING, state())
        advance(DEBOUNCE * 3)
        assertEquals(WAITING, state())
    }

    @Test
    fun clearedTypingParkResumesAfterReconnect() {
        history(CLASSIC)
        started()
        assertEquals(WAITING, state())
        typing(true)
        assertEquals(WORKING, state())
    }

    @Test
    fun snapshotAfterHistoryReseedsWorking() {
        history(MessageBody.Markdown("so far"))
        typing(true)
        started()
        assertEquals(WORKING, state())
        advance(DEBOUNCE * 3)
        assertEquals(WORKING, state())
    }

    @Test
    fun linkDownMakesStateUnknown() {
        working("working")
        working("waiting")
        deliver(CLASSIC, chatID = "waiting")
        history(chatID = "idle")
        advance(SETTLE)
        done("done")
        assertEquals(listOf(WORKING, WAITING, IDLE, DONE), listOf("working", "waiting", "idle", "done").map(::state))
        tracker.onLinkDown()
        assertEquals(listOf(UNKNOWN, UNKNOWN, UNKNOWN, DONE), listOf("working", "waiting", "idle", "done").map(::state))
        // Typing is not re-sent after a drop: a stray `ended` cannot come, and nothing turns it back on.
        advance(DEBOUNCE * 3)
        assertEquals(UNKNOWN, state("working"))
    }

    @Test
    fun doneSurvivesReconnect() {
        done()
        tracker.onLinkDown()
        assertEquals(DONE, state())
        history()
        advance(SETTLE)
        assertEquals(DONE, state())
        tracker.markRead(CHAT)
        assertEquals(IDLE, state())

        // A new turn in the snapshot replaces Done.
        done("renewed")
        tracker.onLinkDown()
        history(chatID = "renewed")
        typing(true, "renewed")
        started("renewed")
        assertEquals(WORKING, state("renewed"))
    }

    @Test
    fun turnEndedDuringDropShowsDone() {
        working()
        tracker.onLinkDown()
        history()
        advance(SETTLE)
        assertEquals(DONE, state())

        working("waiting")
        deliver(SELECT_SESSION, chatID = "waiting")
        tracker.onLinkDown()
        history(SELECT_SESSION, chatID = "waiting")
        advance(SETTLE)
        assertEquals(DONE, state("waiting"))

        working("open")
        tracker.onLinkDown()
        history(chatID = "open", visible = true)
        advance(SETTLE)
        assertEquals(IDLE, state("open"))
    }

    @Test
    fun turnEndedAcrossTwoDropsShowsDone() {
        working()
        tracker.onLinkDown()
        tracker.onLinkDown()
        history()
        advance(SETTLE)
        assertEquals(DONE, state())
    }

    @Test
    fun historyDoesNotFlashDoneBeforeSnapshot() {
        working()
        tracker.onLinkDown()
        history()
        assertEquals(UNKNOWN, record())
        advance(SETTLE - 1, stepMillis = 1)
        typing(true)
        record()
        started()
        assertEquals(WORKING, record())
        assertFalse(trail.toString(), DONE in trail)
    }

    @Test
    fun snapshotSettlesOnTheNextLiveInput() {
        working()
        tracker.onLinkDown()
        history()
        assertEquals(UNKNOWN, state())
        // The Mac sends history and snapshot in one block (HB:1411-1443): another chat's input ends it.
        history(chatID = "other")
        assertEquals(DONE, state())
    }

    @Test
    fun unsubscribedChatIsUnknownButKeepsDone() {
        working()
        done("done")
        tracker.onUnsubscribed(CHAT)
        tracker.onUnsubscribed("done")
        assertEquals(UNKNOWN, state())
        assertEquals(DONE, state("done"))
        history()
        advance(SETTLE)
        assertEquals(DONE, state())
    }

    @Test
    fun userTypingIsIgnored() {
        working()
        tracker.onTyping(CHAT, Participant.USER, false)
        advance(DEBOUNCE * 3)
        assertEquals(WORKING, state())
        tracker.onTyping("idle", Participant.USER, true)
        assertNull(tracker.states.value["idle"])
    }

    @Test
    fun futureTurnEventIsIgnored() {
        working()
        tracker.onTurn(CHAT, TurnEvent.UNKNOWN_FUTURE, visible = false)
        assertEquals(WORKING, state())
        done("done")
        tracker.onTurn("done", TurnEvent.UNKNOWN_FUTURE, visible = true)
        assertEquals(DONE, state("done"))
    }

    @Test
    fun hiddenDeliveryChangesNothing() {
        working()
        deliver(WORKGROUP_PERMISSION)
        deliver(MessageBody.Commit("33333333-4444-4555-8666-000000000000"))
        deliver(MessageBody.Hidden("remoteCommandResponse"))
        assertEquals(WAITING, state())
    }

    @Test
    fun nextDeadlineTracksDebounceAndSettle() {
        assertNull(tracker.nextDeadlineMillis())
        history(chatID = "pending")
        assertEquals(now + SETTLE, tracker.nextDeadlineMillis())
        advance(SETTLE)
        assertNull(tracker.nextDeadlineMillis())
        working()
        assertNull(tracker.nextDeadlineMillis())
        typing(false)
        assertEquals(now + DEBOUNCE, tracker.nextDeadlineMillis())
    }

    @Test
    fun forgetDropsTheChat() {
        done()
        tracker.forget(CHAT)
        assertNull(tracker.states.value[CHAT])
    }

    @Test
    fun chatsAreIndependent() {
        working("a")
        deliver(CLASSIC, chatID = "b")
        done("c")
        assertEquals(WORKING, state("a"))
        assertEquals(DONE, state("c"))
        assertEquals(UNKNOWN, state("b"))
    }

    @Test
    fun defaultParkDebounceIsOneAndAHalfSeconds() {
        var clock = 0L
        val defaults = ChatActivityTracker({ clock })
        defaults.onTurn(CHAT, TurnEvent.STARTED, visible = false)
        clock = 1_499
        defaults.tick()
        assertEquals(WORKING, defaults.states.value[CHAT])
        clock = 1_500
        defaults.tick()
        assertEquals(WAITING, defaults.states.value[CHAT])
    }

    private companion object {
        const val CHAT = "chat-under-test"
        const val DEBOUNCE = 1_200L
        const val SETTLE = 400L
        val COMMAND = CommandContent.ExecuteCommand("make test")
        val CLASSIC = MessageBody.ClassicCommand(COMMAND, safe = false)
        val SELECT_SESSION = MessageBody.SelectSessionRequest(terminal = true)
        val WORKGROUP_PERMISSION = MessageBody.ClientNotice(
            ClientAction.WorkgroupPermissionRequest("request", "workgroup", "Builders", "Control the build"),
        )
        val ENABLE_ORCHESTRATION = MessageBody.ClientNotice(ClientAction.EnableOrchestrationRequest("request"))
        val BLOCKING_REQUESTS = listOf(CLASSIC, SELECT_SESSION, WORKGROUP_PERMISSION, ENABLE_ORCHESTRATION)
        val NON_BLOCKING_CARDS = listOf(
            MessageBody.ClientNotice(ClientAction.OfferLink(terminal = true, guid = "guid", name = "shell")),
            MessageBody.ClientNotice(ClientAction.OrchestrationPermissionGranted("scope", "Builders")),
        )
    }
}
