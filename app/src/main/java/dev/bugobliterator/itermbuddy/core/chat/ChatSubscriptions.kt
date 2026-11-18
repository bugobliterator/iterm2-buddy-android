package dev.bugobliterator.itermbuddy.core.chat

/**
 * What one [ChatSubscriptions.plan] asks for: the subscribes to start now, in order, and the
 * unsubscribes to send. Chat IDs only.
 */
data class SubscriptionPlan(val subscribe: List<String>, val unsubscribe: List<String>)

/**
 * Which chats to subscribe and unsubscribe on one link. The chat repository makes one per link
 * (subscriptions live and die with the Mac's bridge, `AM:2427-2438`), hands it the process's
 * [suspects], and calls [plan] whenever the list, the navigation, the link or AI availability
 * changes. Pure bookkeeping: it sends nothing, holds chat IDs only, and is single-threaded (the
 * chat consumer's).
 *
 * The repository reports, for each chat:
 * - [started] for each subscribe it sends from a plan, at once (planning again before that plans
 *   the same chats again);
 * - [subscribed] when a `History`, or a history `PayloadTooLarge`, for it lands from the sink on
 *   this link: subscribed state is derived from the sink, never from the request's outcome;
 * - [settled] when its subscribe request ends, however it ended (history taken, timeout, error);
 * - [unsubscribed] for each unsubscribe it sends from a plan.
 *
 * Rules:
 * - A chat subscribed or subscribing on this link is never planned again on it: the Mac installs
 *   a subscription before it sends the history (`HB:1411-1427`), so a subscribe that timed out on
 *   the phone is still installed and its history still lands. Only [tryAgain] (the owner's Try
 *   again) and [aiDropped] plan it once more.
 * - At most `concurrency` subscribe requests are out at once, counted from [started] until
 *   [subscribed] or [settled], whichever comes first; and one chat never has two out.
 * - A suspect (a chat in flight when a link ended on an oversized frame) is planned only alone,
 *   with nothing else out, after every other wanted chat has been tried; nothing else is planned
 *   while one is out.
 * - An unsubscribe is planned only for a chat subscribed on this link and no longer wanted, i.e.
 *   neither open nor listed ([wanted]). The Mac's subscription is not reference-counted
 *   (`HB:489-493`), so an unsubscribe for a wanted chat would cut it.
 */
class ChatSubscriptions {
    /** Started or subscribed on this link (since the last [aiDropped]); never planned again. */
    private val attempted = LinkedHashSet<String>()

    /** A `History` (or a history `PayloadTooLarge`) landed on this link. */
    private val subscribed = LinkedHashSet<String>()

    /** Subscribe requests out: started, neither subscribed nor settled yet. */
    private val inFlight = LinkedHashSet<String>()

    /**
     * Requests that were in flight when the Mac dropped every subscription ([aiDropped]): the Mac
     * refuses them, but each holds its slot, and its chat is not planned, until it [settled].
     */
    private val dropped = LinkedHashSet<String>()

    private val suspected = HashSet<String>()

    /** Chats subscribed on this link, in the order their histories landed. */
    val subscribedChats: Set<String> get() = subscribed.toSet()

    /**
     * Every subscribe request still out, in the order started. When a link ends on an oversized
     * frame, these are the suspects for the next link; when there is only one, it is the culprit.
     */
    val outstanding: Set<String> get() = LinkedHashSet<String>(inFlight).apply { addAll(dropped) }

    /**
     * The chats this link should have, in order (owner decision: every chat is watched): the
     * [open] conversations first, then **every** listed chat in the Mac's order, without
     * duplicates, minus [tooLarge] ones. A too-large chat already subscribed on this link stays
     * wanted: its history was over the cap but the Mac installed the subscription, which still
     * feeds its live state (the activity tracker treats that `PayloadTooLarge` as a history), so
     * it is not cut while it is open or listed. It is never planned again either way.
     */
    fun wanted(open: List<String>, entries: List<ChatListEntry>, tooLarge: Set<String>): List<String> {
        val wanted = LinkedHashSet<String>()
        for (chatID in open) wanted += chatID
        for (entry in entries) wanted += entry.chat.id
        return wanted.filter { it !in tooLarge || it in subscribed }
    }

    /**
     * The minimal diff from this link's state to [wanted]: the wanted chats not yet tried on this
     * link, in [wanted]'s order, as far as `concurrency` allows (suspects alone and last); and the
     * subscribed chats no longer wanted. Changes nothing.
     */
    fun plan(wanted: List<String>, concurrency: Int): SubscriptionPlan {
        require(concurrency >= 1) { "concurrency must be at least 1" }
        val wantedSet = wanted.toHashSet()
        val unsubscribe = subscribed.filter { it !in wantedSet }
        return SubscriptionPlan(planSubscribes(wanted, concurrency), unsubscribe)
    }

    private fun planSubscribes(wanted: List<String>, concurrency: Int): List<String> {
        val out = inFlight.size + dropped.size
        if (out >= concurrency || inFlight.any { it in suspected } || dropped.any { it in suspected }) return emptyList()
        val candidates = wanted.filter { it !in attempted && it !in inFlight && it !in dropped }.distinct()
        val ordinary = candidates.filter { it !in suspected }
        if (ordinary.isNotEmpty()) return ordinary.take(concurrency - out)
        if (out > 0) return emptyList()
        return candidates.take(1)
    }

    /** A subscribe for [chatID] was sent from a plan on this link. */
    fun started(chatID: String) {
        attempted += chatID
        inFlight += chatID
    }

    /** A `History` or a history `PayloadTooLarge` for [chatID] landed on this link. */
    fun subscribed(chatID: String) {
        attempted += chatID
        subscribed += chatID
        inFlight -= chatID
    }

    /** The subscribe request for [chatID] ended, however; frees its slot. Idempotent. */
    fun settled(chatID: String) {
        if (!inFlight.remove(chatID)) dropped -= chatID
    }

    /** An unsubscribe for [chatID] was sent from a plan: the chat is planned again if wanted again. */
    fun unsubscribed(chatID: String) {
        subscribed -= chatID
        attempted -= chatID
    }

    /**
     * The owner's Try again: [chatID] is planned once more on this link, after any request for it
     * still out has [settled].
     */
    fun tryAgain(chatID: String) {
        attempted -= chatID
        subscribed -= chatID
    }

    /**
     * The process's suspects (see [ChatSubscriptions]), replacing any given before. The repository
     * removes a suspect once its history lands and marks the only one left out too large.
     */
    fun suspects(ids: Set<String>) {
        suspected.clear()
        suspected += ids
    }

    /**
     * AI was turned off: the Mac dropped every subscription and does not restore them when AI
     * comes back (`HB:2250-2269`, `AM:4470-4486`), so every chat is planned again. Requests still
     * out keep their slots until they settle (the Mac refuses them while AI is off).
     */
    fun aiDropped() {
        subscribed.clear()
        attempted.clear()
        dropped += inFlight
        inFlight.clear()
    }
}
