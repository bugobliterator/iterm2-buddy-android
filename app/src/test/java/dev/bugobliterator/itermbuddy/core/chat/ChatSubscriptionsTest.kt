package dev.bugobliterator.itermbuddy.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chats a link subscribes and unsubscribes, as the chat repository asks each time something changes. */
class ChatSubscriptionsTest {
    private val subscriptions = ChatSubscriptions()

    private fun entry(id: String) = ChatListEntry(ChatSummary(id, "title", 1.0, 2.0, false, null, null, emptyList(), null), null, null)

    private fun entries(vararg ids: String) = ids.map(::entry)

    /** Plans, then starts every planned subscribe, as the repository does; returns what was started. */
    private fun planAndStart(wanted: List<String>, concurrency: Int = 2): List<String> {
        val plan = subscriptions.plan(wanted, concurrency)
        plan.subscribe.forEach(subscriptions::started)
        return plan.subscribe
    }

    /** Subscribes [wanted] until nothing more is planned, every history landing at once. */
    private fun subscribeAll(wanted: List<String>) {
        while (true) {
            val started = planAndStart(wanted)
            if (started.isEmpty()) return
            started.forEach(subscriptions::subscribed)
        }
    }

    @Test
    fun openChatsComeFirst() {
        val wanted = subscriptions.wanted(open = listOf(CHAT_C, CHAT_B), entries = entries(CHAT_A, CHAT_B, CHAT_C, CHAT_D), tooLarge = emptySet())
        assertEquals(listOf(CHAT_C, CHAT_B, CHAT_A, CHAT_D), wanted)
        assertEquals(listOf(CHAT_C, CHAT_B), subscriptions.plan(wanted, concurrency = 2).subscribe)
    }

    @Test
    fun everyListedChatIsWanted() {
        val ids = (0 until 300).map { "CHAT-%04d".format(it) }
        val wanted = subscriptions.wanted(open = emptyList(), entries = ids.map(::entry), tooLarge = emptySet())
        assertEquals("the Mac's order, every chat", ids, wanted)

        subscribeAll(wanted)
        assertEquals(ids.toSet(), subscriptions.subscribedChats)
    }

    @Test
    fun openChatMissingFromTheListIsStillWanted() {
        val wanted = subscriptions.wanted(open = listOf(CHAT_D, CHAT_D), entries = entries(CHAT_A), tooLarge = emptySet())
        assertEquals(listOf(CHAT_D, CHAT_A), wanted)
    }

    @Test
    fun tooLargeChatsAreExcluded() {
        val wanted = subscriptions.wanted(open = listOf(CHAT_B), entries = entries(CHAT_A, CHAT_B, CHAT_C), tooLarge = setOf(CHAT_B, CHAT_C))
        assertEquals(listOf(CHAT_A), wanted)
        assertEquals(listOf(CHAT_A), subscriptions.plan(wanted, concurrency = 2).subscribe)
    }

    @Test
    fun tooLargeChatAlreadySubscribedOnThisLinkIsKept() {
        // Its history was over the cap, but the Mac still installed the subscription, which keeps
        // feeding the chat's live state: it is neither resubscribed nor cut while it is listed.
        subscriptions.started(CHAT_A)
        subscriptions.subscribed(CHAT_A)
        val listed = subscriptions.wanted(open = emptyList(), entries = entries(CHAT_A, CHAT_B), tooLarge = setOf(CHAT_A))
        assertEquals(listOf(CHAT_A, CHAT_B), listed)
        assertEquals(SubscriptionPlan(subscribe = listOf(CHAT_B), unsubscribe = emptyList()), subscriptions.plan(listed, 2))

        val gone = subscriptions.wanted(open = emptyList(), entries = entries(CHAT_B), tooLarge = setOf(CHAT_A))
        assertEquals(listOf(CHAT_A), subscriptions.plan(gone, 2).unsubscribe)
    }

    @Test
    fun subscribedOnThisLinkIsNeverPlannedAgain() {
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C)
        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(wanted))
        // In flight: not planned again, whatever changes.
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_B, CHAT_A)))

        subscriptions.subscribed(CHAT_A)
        subscriptions.settled(CHAT_A)
        // The subscribe for B timed out on the phone; the Mac installed it anyway and its history
        // may still land, so it is not planned again on this link either.
        subscriptions.settled(CHAT_B)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
        subscriptions.subscribed(CHAT_C)
        assertEquals(emptyList<String>(), planAndStart(wanted))

        // The late history is applied when it lands.
        subscriptions.subscribed(CHAT_B)
        assertEquals(setOf(CHAT_A, CHAT_B, CHAT_C), subscriptions.subscribedChats)
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(wanted, 2))
    }

    @Test
    fun tryAgainPlansTheChatOnceMore() {
        planAndStart(listOf(CHAT_A))
        subscriptions.settled(CHAT_A)
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A)))

        subscriptions.tryAgain(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A)))
    }

    @Test
    fun tryAgainWaitsForTheRequestStillOut() {
        planAndStart(listOf(CHAT_A))
        subscriptions.tryAgain(CHAT_A)
        assertEquals("one request per chat at a time", emptyList<String>(), planAndStart(listOf(CHAT_A)))
        subscriptions.settled(CHAT_A)
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A)))
    }

    @Test
    fun concurrencyIsBounded() {
        val wanted = (0 until 10).map { "CHAT-%04d".format(it) }
        var outstanding = 0
        var peak = 0
        val done = mutableListOf<String>()
        while (done.size < wanted.size) {
            val started = planAndStart(wanted, concurrency = 3)
            outstanding += started.size
            peak = maxOf(peak, outstanding)
            assertTrue("something is out or planned", outstanding > 0)
            // Answer the oldest one: its history lands, then its request settles.
            val oldest = subscriptions.outstanding.first()
            subscriptions.subscribed(oldest)
            subscriptions.settled(oldest)
            outstanding--
            done += oldest
        }
        assertEquals(3, peak)
        assertEquals(wanted, done)
        assertThrows(IllegalArgumentException::class.java) { subscriptions.plan(wanted, concurrency = 0) }
    }

    @Test
    fun settledAndSubscribedEachFreeTheSlotOnce() {
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D)
        assertEquals(listOf(CHAT_A, CHAT_B), planAndStart(wanted))
        subscriptions.subscribed(CHAT_A)
        subscriptions.settled(CHAT_A)
        subscriptions.settled(CHAT_A)
        subscriptions.subscribed(CHAT_A)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
        assertEquals(setOf(CHAT_B, CHAT_C), subscriptions.outstanding)
    }

    @Test
    fun suspectsGoAloneAndLast() {
        subscriptions.suspects(setOf(CHAT_A, CHAT_C))
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D)

        assertEquals("others first", listOf(CHAT_B, CHAT_D), planAndStart(wanted))
        subscriptions.subscribed(CHAT_B)
        assertEquals("not beside another subscribe", emptyList<String>(), planAndStart(wanted))
        subscriptions.subscribed(CHAT_D)

        assertEquals("one suspect, alone", listOf(CHAT_A), planAndStart(wanted))
        assertEquals(emptyList<String>(), planAndStart(wanted))
        assertEquals(setOf(CHAT_A), subscriptions.outstanding)
        subscriptions.subscribed(CHAT_A)

        assertEquals(listOf(CHAT_C), planAndStart(wanted))
        subscriptions.subscribed(CHAT_C)
        assertEquals(setOf(CHAT_A, CHAT_B, CHAT_C, CHAT_D), subscriptions.subscribedChats)
    }

    @Test
    fun aNewChatWaitsWhileASuspectIsOut() {
        subscriptions.suspects(setOf(CHAT_A))
        assertEquals(listOf(CHAT_A), planAndStart(listOf(CHAT_A)))
        assertEquals(emptyList<String>(), planAndStart(listOf(CHAT_A, CHAT_B)))
        subscriptions.settled(CHAT_A)
        assertEquals(listOf(CHAT_B), planAndStart(listOf(CHAT_A, CHAT_B)))
    }

    @Test
    fun aiDroppedAllowsResubscribe() {
        val wanted = listOf(CHAT_A, CHAT_B, CHAT_C)
        subscribeAll(listOf(CHAT_A, CHAT_B))
        assertEquals(listOf(CHAT_C), planAndStart(wanted))

        subscriptions.aiDropped()
        assertEquals(emptySet<String>(), subscriptions.subscribedChats)
        assertEquals("nothing left to cut", emptyList<String>(), subscriptions.plan(emptyList(), 2).unsubscribe)

        // C's request is still out (the Mac refuses it): it holds its slot and is not doubled.
        assertEquals(listOf(CHAT_A), planAndStart(wanted))
        assertEquals(setOf(CHAT_C, CHAT_A), subscriptions.outstanding)
        subscriptions.settled(CHAT_C)
        assertEquals(listOf(CHAT_B), planAndStart(wanted))
        subscriptions.subscribed(CHAT_A)
        subscriptions.subscribed(CHAT_B)
        assertEquals(listOf(CHAT_C), planAndStart(wanted))
    }

    @Test
    fun planIsAMinimalDiff() {
        subscribeAll(listOf(CHAT_A, CHAT_B, CHAT_C))
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(listOf(CHAT_C, CHAT_A, CHAT_B), 2))

        val plan = subscriptions.plan(listOf(CHAT_A, CHAT_C, CHAT_D), 2)
        assertEquals(SubscriptionPlan(subscribe = listOf(CHAT_D), unsubscribe = listOf(CHAT_B)), plan)
        assertEquals("planning alone changes nothing", plan, subscriptions.plan(listOf(CHAT_A, CHAT_C, CHAT_D), 2))

        subscriptions.unsubscribed(CHAT_B)
        subscriptions.started(CHAT_D)
        assertEquals(SubscriptionPlan(emptyList(), emptyList()), subscriptions.plan(listOf(CHAT_A, CHAT_C, CHAT_D), 2))
        assertEquals("listed again: subscribed again", listOf(CHAT_B), subscriptions.plan(listOf(CHAT_A, CHAT_B), 2).subscribe)
    }

    @Test
    fun unsubscribeWaitsForTheHistoryOfAChatStillOut() {
        planAndStart(listOf(CHAT_A))
        assertEquals("not subscribed yet: nothing to cut", emptyList<String>(), subscriptions.plan(emptyList(), 2).unsubscribe)
        subscriptions.subscribed(CHAT_A)
        assertEquals(listOf(CHAT_A), subscriptions.plan(emptyList(), 2).unsubscribe)
    }

    private companion object {
        const val CHAT_A = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
        const val CHAT_B = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
        const val CHAT_C = "0C1D2E3F-4A5B-4C6D-8E7F-9A0B1C2D3E4F"
        const val CHAT_D = "F0E1D2C3-B4A5-4968-8776-655443322110"
    }
}
