package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.chat.SubscribeOutcome.ANSWERED
import dev.bugobliterator.itermbuddy.core.chat.SubscribeOutcome.ERROR
import dev.bugobliterator.itermbuddy.core.chat.SubscribeOutcome.TIMEOUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chats a link subscribes and unsubscribes, as the chat repository asks each time something changes. */
class ChatSubscriptionsTest {
    private val oversized = OversizedChats()
    private var subscriptions = ChatSubscriptions(oversized)

    /** Tickets of the requests out, by chat, as the repository would hold them. */
    private val tickets = LinkedHashMap<String, SubscribeTicket>()

    private fun entry(id: String) = ChatListEntry(ChatSummary(id, "title", 1.0, 2.0, false, null, null, emptyList(), null), null, null)

    private fun listed(vararg ids: String, open: List<String> = emptyList()) =
        subscriptions.wanted(open = open, entries = ids.map(::entry))

    /** Plans and takes the plan, as the repository does; returns the subscribes started. */
    private fun planAndStart(wanted: List<String>, concurrency: Int = 2, aiAvailable: Boolean = true): List<String> {
        val plan = subscriptions.plan(wanted, concurrency, aiAvailable)
        subscriptions.take(plan).forEach { tickets[it.chatID] = it }
        return plan.subscribe
    }

    /** [chatID]'s history lands, then its request is answered. */
    private fun answer(chatID: String, tooLarge: Boolean = false) {
        subscriptions.subscribed(chatID, tooLarge)
        settle(chatID, ANSWERED)
    }

    private fun settle(chatID: String, outcome: SubscribeOutcome) = subscriptions.settled(tickets.remove(chatID)!!, outcome)

    /** Subscribes [wanted] until nothing more is planned, every history landing at once. */
    private fun subscribeAll(wanted: List<String>) {
        while (true) {
            val started = planAndStart(wanted)
            if (started.isEmpty()) return
            started.forEach(::answer)
        }
    }

    private fun nextLink(messageTooLarge: Boolean) {
        subscriptions.linkEnded(messageTooLarge)
        subscriptions = ChatSubscriptions(oversized)
        tickets.clear()
    }

    @Test
    fun openChatsComeFirst() {
        val wanted = listed(CHAT_A, CHAT_B, CHAT_C, CHAT_D, open = listOf(CHAT_C, CHAT_B))
        assertEquals(listOf(CHAT_C, CHAT_B, CHAT_A, CHAT_D), wanted)
        assertEquals(listOf(CHAT_C, CHAT_B), subscriptions.plan(wanted, concurrency = 2, aiAvailable = true).subscribe)
    }

    @Test
    fun everyListedChatIsWanted() {
        val ids = (0 until 300).map { "CHAT-%04d".format(it) }
        val wanted = listed(*ids.toTypedArray())
        assertEquals("the Mac's order, every chat", ids, wanted)

        subscribeAll(wanted)
        assertEquals(ids.toSet(), subscriptions.subscribedChats)
    }

    @Test
    fun openChatMissingFromTheListIsStillWanted() {
        assertEquals(listOf(CHAT_D, CHAT_A), listed(CHAT_A, open = listOf(CHAT_D, CHAT_D)))
    }

    @Test
    fun tooLargeChatsAreExcluded() {
        planAndStart(listOf(CHAT_B, CHAT_C))
        answer(CHAT_B, tooLarge = true)
        answer(CHAT_C, tooLarge = true)
        nextLink(messageTooLarge = false)

        val wanted = listed(CHAT_A, CHAT_B, CHAT_C, open = listOf(CHAT_B))
        assertEquals(listOf(CHAT_A), wanted)
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
    }

    @Test
    fun tooLargeChatAlreadySubscribedOnThisLinkIsKept() {
        // Its history was over the cap, but the Mac still installed the subscription, which keeps
        // feeding the chat's live state: it is neither resubscribed nor cut while it is listed.
        planAndStart(listed(CHAT_A))
        answer(CHAT_A, tooLarge = true)
        assertEquals(setOf(CHAT_A), oversized.tooLarge)
        val wanted = listed(CHAT_A, CHAT_B)
        assertEquals(listOf(CHAT_A, CHAT_B), wanted)
        assertEquals(SubscriptionPlan(subscribe = listOf(CHAT_B), unsubscribe = emptyList()), subscriptions.plan(wanted, 2, true))

        assertEquals(listOf(CHAT_A), subscriptions.plan(listed(CHAT_B), 2, true).unsubscribe)
    }

    @Test
    fun tryAgainLiftsTooLarge() {
        planAndStart(listed(CHAT_A))
        answer(CHAT_A, tooLarge = true)
        nextLink(messageTooLarge = false)
        assertEquals(emptyList<String>(), planAndStart(listed(CHAT_A)))

        subscriptions.tryAgain(CHAT_A)
        assertEquals(emptySet<String>(), oversized.tooLarge)
        assertEquals(listOf(CHAT_A), planAndStart(listed(CHAT_A)))
    }

    @Test
    fun subscribedOnThisLinkIsNeverPlannedAgain() {
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C)
        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(wanted))
        // In flight: not planned again, whatever changes.
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_B, CHAT_A)))

        answer(CHAT_A)
        // The subscribe for B timed out on the phone; the Mac installed it anyway and its history
        // may still land, so it is not planned again on this link either.
        settle(CHAT_B, TIMEOUT)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
        answer(CHAT_C)
        assertEquals(emptyList<String>(), planAndStart(wanted))

        // The late history is applied when it lands.
        subscriptions.subscribed(CHAT_B)
        assertEquals(setOf(CHAT_A, CHAT_B, CHAT_C), subscriptions.subscribedChats)
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(wanted, 2, true))
    }

    @Test
    fun timedOutSubscribeStaysUnansweredAndIsNamedWhenTheLinkEndsOversized() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        answer(CHAT_A)
        settle(CHAT_B, TIMEOUT)
        assertEquals(setOf(CHAT_B), subscriptions.outstanding)

        nextLink(messageTooLarge = true)
        assertEquals("the only unanswered chat is the culprit", setOf(CHAT_B), oversized.tooLarge)
    }

    @Test
    fun errorReplyAnswersTheChat() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        settle(CHAT_A, ERROR)
        settle(CHAT_B, TIMEOUT)
        assertEquals(setOf(CHAT_B), subscriptions.outstanding)
        assertEquals("refused with AI on: only Try again retries it", emptyList<String>(), planAndStart(listOf(CHAT_A)))
    }

    @Test
    fun staleSettleNeverFreesANewerRequestsSlot() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        val oldA = tickets.getValue(CHAT_A)
        subscriptions.settled(oldA, TIMEOUT)
        tickets.remove(CHAT_A)
        subscriptions.tryAgain(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C)))

        // The old request's outcome arrives again (a duplicate report): it frees nothing.
        subscriptions.settled(oldA, ANSWERED)
        subscriptions.settled(oldA, ERROR)
        assertEquals("both slots still taken", emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C)))
        assertEquals(setOf(CHAT_A, CHAT_B), subscriptions.outstanding)
    }

    @Test
    fun tryAgainPlansTheChatOnceMore() {
        planAndStart(listOf(CHAT_A))
        settle(CHAT_A, TIMEOUT)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A)))

        subscriptions.tryAgain(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A)))
    }

    @Test
    fun tryAgainWaitsForTheRequestStillOut() {
        planAndStart(listOf(CHAT_A))
        subscriptions.tryAgain(CHAT_A)
        assertEquals("one request per chat at a time", emptyList<String>(), planAndStart(listOf(CHAT_A)))
        settle(CHAT_A, TIMEOUT)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A)))
    }

    @Test
    fun concurrencyIsBounded() {
        val wanted = (0 until 10).map { "CHAT-%04d".format(it) }
        var peak = 0
        val done = mutableListOf<String>()
        while (done.size < wanted.size) {
            planAndStart(wanted, concurrency = 3)
            peak = maxOf(peak, tickets.size)
            assertTrue("something is out", tickets.isNotEmpty())
            val oldest = tickets.keys.first()
            answer(oldest)
            done += oldest
        }
        assertEquals(3, peak)
        assertEquals(wanted, done)
        assertThrows(IllegalArgumentException::class.java) { subscriptions.plan(wanted, concurrency = 0, aiAvailable = true) }
    }

    @Test
    fun timeoutFreesTheSlot() {
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D)
        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(wanted))
        settle(CHAT_A, TIMEOUT)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
        assertEquals(setOf(CHAT_A, CHAT_B, CHAT_C), subscriptions.outstanding)
    }

    @Test
    fun severalUnansweredBecomeSuspects() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        assertEquals(setOf(CHAT_A, CHAT_B), oversized.suspects)
        assertEquals(emptySet<String>(), oversized.tooLarge)

        // A link that ends on something else names nothing.
        planAndStart(listOf(CHAT_C))
        nextLink(messageTooLarge = false)
        assertEquals(setOf(CHAT_A, CHAT_B), oversized.suspects)
    }

    @Test
    fun suspectsGoFirstAndAlone() {
        planAndStart(listOf(CHAT_A, CHAT_C))
        nextLink(messageTooLarge = true)
        val wanted = listOf(CHAT_B, CHAT_A, CHAT_D, CHAT_C)

        assertEquals("one suspect, before anything else", listOf(CHAT_A), planAndStart(wanted))
        assertEquals("nothing beside it", emptyList<String>(), planAndStart(wanted))
        // A timeout does not release it: it may be the frame still arriving.
        settle(CHAT_A, TIMEOUT)
        assertEquals(emptyList<String>(), planAndStart(wanted))
        // Its late history lands, with a request ID the caller could not map.
        subscriptions.subscribed(CHAT_A, consumeLateTicket = true)
        assertEquals(setOf(CHAT_C), oversized.suspects)

        assertEquals("the next suspect, alone", listOf(CHAT_C), planAndStart(wanted))
        assertEquals(emptyList<String>(), planAndStart(wanted))
        answer(CHAT_C)

        assertEquals(listOf(CHAT_B, CHAT_D), planAndStart(wanted))
        assertEquals(emptySet<String>(), oversized.suspects)
    }

    @Test
    fun suspectThatEndsALinkAloneIsTooLarge() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C)))
        nextLink(messageTooLarge = true)
        assertEquals(setOf(CHAT_A), oversized.tooLarge)
        assertEquals(setOf(CHAT_B), oversized.suspects)

        assertEquals(listOf(CHAT_B), planAndStart(subscriptions.wanted(emptyList(), listOf(CHAT_A, CHAT_B, CHAT_C).map(::entry))))
    }

    @Test
    fun suspectWaitsForAChatAlreadyOut() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        // A first list without the suspects lets C go; a later list names suspect A.
        assertEquals(listOf(CHAT_C), planAndStart(listed(CHAT_C)))
        assertEquals(emptyList<String>(), planAndStart(listed(CHAT_C, CHAT_A, CHAT_D)))
        answer(CHAT_C)
        assertEquals(listOf(CHAT_A), planAndStart(listed(CHAT_C, CHAT_A, CHAT_D)))
    }

    @Test
    fun beforeTheListOnlyOpenSuspectsArePlanned() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        val beforeList = subscriptions.wanted(open = listOf(CHAT_C, CHAT_B), entries = null)
        assertEquals("the open suspect, alone; not the open ordinary chat", listOf(CHAT_B), planAndStart(beforeList))
        answer(CHAT_B)
        assertEquals("still no list: C waits for it", emptyList<String>(), planAndStart(subscriptions.wanted(listOf(CHAT_C), null)))

        // The list names suspect A, which goes before C.
        assertEquals(listOf(CHAT_A), planAndStart(listed(CHAT_A, CHAT_D, open = listOf(CHAT_C))))
        answer(CHAT_A)
        assertEquals(listOf(CHAT_C, CHAT_D), planAndStart(listed(CHAT_A, CHAT_D, open = listOf(CHAT_C))))
    }

    @Test
    fun withoutSuspectsOpenChatsGoBeforeTheList() {
        assertEquals(listOf(CHAT_C), planAndStart(subscriptions.wanted(listOf(CHAT_C), null)))
    }

    @Test
    fun lateUndecodableAfterTimeoutAnswersTheChat() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        val ticketA = tickets.getValue(CHAT_A)
        settle(CHAT_A, TIMEOUT)
        assertEquals(setOf(CHAT_A, CHAT_B), subscriptions.outstanding)
        // The history frame arrives after the timeout but cannot be decoded: no History lands,
        // yet the whole frame came, so A is answered.
        subscriptions.settled(ticketA, ANSWERED)
        assertEquals(setOf(CHAT_B), subscriptions.outstanding)
        assertEquals("the slot was freed once, by the timeout", listOf(CHAT_C), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D)))
        subscriptions.settled(ticketA, ANSWERED)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D)))

        nextLink(messageTooLarge = true)
        assertEquals("A is not blamed", setOf(CHAT_B, CHAT_C), oversized.suspects)
    }

    @Test
    fun lateErrorAfterTimeoutAnswersTheChat() {
        planAndStart(listOf(CHAT_A))
        val ticketA = tickets.getValue(CHAT_A)
        settle(CHAT_A, TIMEOUT)
        subscriptions.settled(ticketA, TIMEOUT)
        assertEquals(setOf(CHAT_A), subscriptions.outstanding)
        subscriptions.settled(ticketA, ERROR)
        assertEquals(emptySet<String>(), subscriptions.outstanding)
    }

    @Test
    fun lateAnswerFromAnOlderRequestLeavesANewerOneUnanswered() {
        planAndStart(listOf(CHAT_A))
        val oldA = tickets.getValue(CHAT_A)
        settle(CHAT_A, TIMEOUT)
        subscriptions.tryAgain(CHAT_A)
        planAndStart(listOf(CHAT_A))
        subscriptions.settled(oldA, ANSWERED)
        assertEquals(setOf(CHAT_A), subscriptions.outstanding)
    }

    @Test
    fun lateAnswerUnblocksASuspect() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A, CHAT_B)))
        val ticketA = tickets.getValue(CHAT_A)
        settle(CHAT_A, TIMEOUT)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B)))
        subscriptions.settled(ticketA, ANSWERED)
        assertEquals(setOf(CHAT_B), oversized.suspects)
        assertEquals(listOf(CHAT_B), planAndStart(listOf(CHAT_A, CHAT_B)))
    }

    @Test
    fun errorOnASuspectAnswersItButKeepsItSuspect() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        nextLink(messageTooLarge = true)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C)))
        settle(CHAT_A, ERROR)
        assertEquals(emptySet<String>(), subscriptions.outstanding)
        assertEquals(setOf(CHAT_A, CHAT_B), oversized.suspects)
        assertEquals("the next suspect, alone", listOf(CHAT_B), planAndStart(listOf(CHAT_A, CHAT_B, CHAT_C)))
    }

    @Test
    fun resubscribePlansAStaleChatOnceWithoutLiftingTooLarge() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        answer(CHAT_A)
        answer(CHAT_B, tooLarge = true)

        assertTrue(subscriptions.resubscribe(CHAT_A))
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A, CHAT_B)))
        answer(CHAT_A)
        assertEquals("once per link", false, subscriptions.resubscribe(CHAT_A))
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B)))

        assertEquals("too large: skipped", false, subscriptions.resubscribe(CHAT_B))
        assertEquals(setOf(CHAT_B), oversized.tooLarge)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B)))
    }

    @Test
    fun noSubscribesWhileAiIsOff() {
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A), aiAvailable = false))
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A), aiAvailable = true))
    }

    @Test
    fun refusedWhileAiWasOffIsRetriedWhenItIsBack() {
        planAndStart(listOf(CHAT_A, CHAT_B))
        answer(CHAT_A)
        subscriptions.aiDropped()
        // B reached the Mac after AI went off: refused.
        settle(CHAT_B, ERROR)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B), aiAvailable = false))

        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(listOf(CHAT_A, CHAT_B), aiAvailable = true))
    }

    @Test
    fun refusedAfterAPlanWithAiOffIsRetriedWithoutADrop() {
        // The link came up with AI off in the hello: no drop is reported, only plans with AI off.
        planAndStart(listOf(CHAT_A))
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B), aiAvailable = false))
        settle(CHAT_A, ERROR)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B), aiAvailable = false))
        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(listOf(CHAT_A, CHAT_B), aiAvailable = true))
    }

    @Test
    fun refusalReportedBeforeTheDropIsRetriedToo() {
        planAndStart(listOf(CHAT_A))
        settle(CHAT_A, ERROR)
        subscriptions.aiDropped()
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A), aiAvailable = true))
    }

    @Test
    fun aiDroppedAllowsResubscribe() {
        val wanted = listed(CHAT_A, CHAT_B, CHAT_C)
        subscribeAll(listOf(CHAT_A, CHAT_B))
        assertEquals(listOf(CHAT_C), planAndStart(wanted))

        subscriptions.aiDropped()
        assertEquals(emptySet<String>(), subscriptions.subscribedChats)
        assertEquals("nothing left to cut", emptyList<String>(), subscriptions.plan(listed(), 2, false).unsubscribe)

        // C's request is still out (the Mac refuses it): it holds its slot and is not doubled.
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        assertEquals(setOf(CHAT_C, CHAT_A), subscriptions.outstanding)
        settle(CHAT_C, ERROR)
        assertEquals(listOf(CHAT_B), planAndStart(wanted))
        answer(CHAT_A)
        answer(CHAT_B)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
    }

    @Test
    fun planIsAMinimalDiff() {
        subscribeAll(listed(CHAT_A, CHAT_B, CHAT_C))
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(listed(CHAT_C, CHAT_A, CHAT_B), 2, true))

        val next = listed(CHAT_A, CHAT_C, CHAT_D)
        val plan = subscriptions.plan(next, 2, true)
        assertEquals(SubscriptionPlan(subscribe = listOf(CHAT_D), unsubscribe = listOf(CHAT_B)), plan)
        assertEquals("planning alone changes nothing", plan, subscriptions.plan(next, 2, true))

        subscriptions.take(plan)
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(next, 2, true))
        assertEquals("listed again: subscribed again", listOf(CHAT_B), subscriptions.plan(listed(CHAT_A, CHAT_B), 2, true).subscribe)
    }

    @Test
    fun openChatNeverListedIsNotCut() {
        planAndStart(listed(open = listOf(CHAT_A)))
        answer(CHAT_A)
        // The owner leaves before the list arrives: A is not wanted, but no list has named it.
        assertEquals(emptyList<String>(), subscriptions.plan(listed(), 2, true).unsubscribe)
        // A list without it does not count either: it never left a list.
        assertEquals(emptyList<String>(), subscriptions.plan(listed(CHAT_B), 2, true).unsubscribe)

        listed(CHAT_A, CHAT_B)
        assertEquals("it left a list", listOf(CHAT_A), subscriptions.plan(listed(CHAT_B), 2, true).unsubscribe)
    }

    @Test
    fun unsubscribeWaitsForTheHistoryOfAChatStillOut() {
        planAndStart(listed(CHAT_A))
        assertEquals("not subscribed yet: nothing to cut", emptyList<String>(), subscriptions.plan(listed(), 2, true).unsubscribe)
        subscriptions.subscribed(CHAT_A)
        assertEquals(listOf(CHAT_A), subscriptions.plan(listed(), 2, true).unsubscribe)
    }

    @Test
    fun aHistoryDoesNotAnswerWhileANewerRequestIsOut() {
        val wanted = listed(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        val older = tickets.remove(CHAT_A)!!
        subscriptions.settled(older, TIMEOUT)
        subscriptions.tryAgain(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        // The older request's late history lands while the Try again is still out.
        subscriptions.settled(older, ANSWERED)
        subscriptions.subscribed(CHAT_A)
        assertEquals("the newer request can still end the link", setOf(CHAT_A), subscriptions.outstanding)
        settle(CHAT_A, ANSWERED)
        assertEquals(emptySet<String>(), subscriptions.outstanding)
    }

    @Test
    fun twoTimedOutTicketsAreAnsweredOnlyByTheirOwnReplies() {
        val wanted = listed(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        val older = tickets.remove(CHAT_A)!!
        subscriptions.settled(older, TIMEOUT)
        subscriptions.tryAgain(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        val newer = tickets.remove(CHAT_A)!!
        subscriptions.settled(newer, TIMEOUT)

        // A mapped history: the caller settles its own ticket; this consumes none.
        subscriptions.settled(older, ANSWERED)
        subscriptions.subscribed(CHAT_A)
        assertEquals("the newer request can still end the link", setOf(CHAT_A), subscriptions.outstanding)
        subscriptions.settled(newer, ANSWERED)
        assertEquals(emptySet<String>(), subscriptions.outstanding)
    }

    @Test
    fun anUnmappedHistoryConsumesOnlyTheOldestTimedOutTicket() {
        val wanted = listed(CHAT_A)
        planAndStart(wanted)
        subscriptions.settled(tickets.remove(CHAT_A)!!, TIMEOUT)
        subscriptions.tryAgain(CHAT_A)
        planAndStart(wanted)
        subscriptions.settled(tickets.remove(CHAT_A)!!, TIMEOUT)
        subscriptions.subscribed(CHAT_A, consumeLateTicket = true)
        assertEquals(setOf(CHAT_A), subscriptions.outstanding)
        subscriptions.subscribed(CHAT_A, consumeLateTicket = true)
        assertEquals(emptySet<String>(), subscriptions.outstanding)
    }

    @Test
    fun aSingleUnansweredChatWithTheListOutIsOnlyASuspect() {
        planAndStart(listOf(CHAT_A))
        subscriptions.linkEnded(messageTooLarge = true, listOut = true)
        assertEquals(emptySet<String>(), oversized.tooLarge)
        assertEquals(setOf(CHAT_A), oversized.suspects)
    }

    private companion object {
        const val CHAT_A = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
        const val CHAT_B = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
        const val CHAT_C = "0C1D2E3F-4A5B-4C6D-8E7F-9A0B1C2D3E4F"
        const val CHAT_D = "F0E1D2C3-B4A5-4968-8776-655443322110"
    }
}
