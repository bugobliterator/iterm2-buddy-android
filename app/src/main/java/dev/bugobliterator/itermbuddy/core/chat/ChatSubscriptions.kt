package dev.bugobliterator.itermbuddy.core.chat

/**
 * What one [ChatSubscriptions.plan] asks for: the subscribes to start now, in order, and the
 * unsubscribes to send. Chat IDs only.
 */
data class SubscriptionPlan(val subscribe: List<String>, val unsubscribe: List<String>)

/** How a subscribe request ended, as [ChatSubscriptions.settled] is told. */
enum class SubscribeOutcome {
    /** Its reply frame arrived whole (a history taken by the sink, decodable or not, or a `PayloadTooLarge`). */
    ANSWERED,

    /**
     * The Mac answered with an error (an `aiUnavailable` refusal, say). It answers the chat but
     * does not clear a suspect: the error says nothing about the size of its history.
     */
    ERROR,

    /**
     * The phone stopped waiting. The Mac installed the subscription before it began the history
     * (`HB:1411-1427`), so that history may still be arriving: the chat stays unanswered until
     * the same ticket is settled again with [ANSWERED] or [ERROR] (a late reply).
     */
    TIMEOUT,
}

/**
 * One subscribe request, from [ChatSubscriptions.started] (or [ChatSubscriptions.take]) to
 * [ChatSubscriptions.settled]. Compared by identity: a ticket from an older request never settles
 * a newer one.
 */
class SubscribeTicket internal constructor(val chatID: String) {
    override fun toString(): String = "SubscribeTicket($chatID)"
}

/**
 * The process's oversized chats, kept by the chat repository across links (owner decision: a chat
 * found too large is not resubscribed for the rest of the process).
 *
 * - [tooLarge]: a history over the chat cap landed for it (a history `PayloadTooLarge`), or it was
 *   the only subscribe unanswered when a link ended on a frame over the Noise cap.
 * - [suspects]: two or more subscribes were unanswered when a link ended on such a frame. On later
 *   links each suspect is subscribed first and alone ([ChatSubscriptions]), until it is answered
 *   (cleared) or ends a link by itself (too large).
 *
 * [tryAgain] (the owner's Try again) clears both for a chat. Single-threaded (the chat consumer's).
 */
class OversizedChats {
    private val tooLargeChats = LinkedHashSet<String>()
    private val suspectChats = LinkedHashSet<String>()

    val tooLarge: Set<String> get() = tooLargeChats.toSet()
    val suspects: Set<String> get() = suspectChats.toSet()

    internal fun isSuspect(chatID: String): Boolean = chatID in suspectChats

    /** A history over the chat cap landed for [chatID]. */
    internal fun foundTooLarge(chatID: String) {
        suspectChats -= chatID
        tooLargeChats += chatID
    }

    /** [chatID]'s subscribe was answered: it did not end a link. */
    internal fun answered(chatID: String) {
        suspectChats -= chatID
    }

    /**
     * A link ended on a frame over the Noise cap with [unanswered] subscribes out: one is the
     * culprit; several become suspects. None names nothing (the frame was something else). With
     * [listOut] (the chat list request was unanswered too) the list may be the culprit, so even a
     * single unanswered chat only becomes a suspect.
     */
    internal fun linkEndedOversized(unanswered: Set<String>, listOut: Boolean = false) {
        when {
            unanswered.isEmpty() -> Unit
            unanswered.size == 1 && !listOut -> foundTooLarge(unanswered.single())
            else -> suspectChats += unanswered
        }
    }

    /** The owner's Try again: [chatID] is subscribed again on the next plan. */
    fun tryAgain(chatID: String) {
        tooLargeChats -= chatID
        suspectChats -= chatID
    }

    /** Unpair: forgets everything. */
    fun clear() {
        tooLargeChats.clear()
        suspectChats.clear()
    }
}

/**
 * Which chats to subscribe and unsubscribe on one link. The chat repository makes one per link
 * (subscriptions live and die with the Mac's bridge, `AM:2427-2438`) over the process's
 * [oversized] chats, and calls [plan] then [take] whenever the list, the navigation, the link or
 * AI availability changes. Bookkeeping only: it sends nothing, holds chat IDs only, and is
 * single-threaded (the chat consumer's).
 *
 * The repository reports, for each chat:
 * - [take] (or [started] and [unsubscribed]) for what it sends from a plan, in the same step;
 * - [subscribed] when a `History`, or a history `PayloadTooLarge` (`tooLarge = true`), for it
 *   lands from the sink on this link: subscribed state comes from the sink, never from the
 *   request's outcome;
 * - [settled] with the request's [SubscribeTicket] and [SubscribeOutcome] when it ends, and again
 *   with [SubscribeOutcome.ANSWERED] or [SubscribeOutcome.ERROR] when a reply lands after a
 *   timeout (the repository keeps a timed-out request's ticket by request ID until the link ends);
 * - [linkEnded] when the link goes down.
 *
 * Rules:
 * - A chat subscribed or subscribing on this link is never planned again on it: the Mac installs
 *   a subscription before it sends the history (`HB:1411-1427`), so a subscribe that timed out on
 *   the phone is still installed and its history still lands. Only [tryAgain] (the owner's Try
 *   again), [resubscribe] (a stale transcript), [aiDropped] and a refusal while AI was off plan it
 *   once more.
 * - At most `concurrency` subscribe requests are out at once, counted from [started] until
 *   [settled]; one chat never has two out.
 * - A chat is **unanswered** from [started] until its history lands or its request is answered
 *   or refused, also late; a timeout does not answer it. [outstanding] is the unanswered set, and is what
 *   names the culprit when the link ends on a frame over the Noise cap, which can be long after
 *   the phone's 15 s subscribe timeout (32 MiB has to arrive first).
 * - Owner decision: suspects go **first and alone**. Right after the link comes up each wanted
 *   suspect is planned on its own, with nothing else out, before any other chat; nothing at all
 *   is planned while a suspect is unanswered. While there are suspects and no list has been given
 *   on this link, only suspects are planned, so open ordinary chats cannot overtake a suspect the
 *   list is about to name.
 * - Nothing is subscribed while AI is off (the Mac refuses it, `HB:2276-2330`).
 * - An unsubscribe is planned only for a chat subscribed on this link that was in a list given
 *   to [wanted] on this link and has left it (and is not open). A chat that is only open, never
 *   listed yet, is never cut: the list may not have arrived. The Mac's subscription is not
 *   reference-counted (`HB:489-493`), so an unsubscribe for a wanted chat would cut it.
 */
class ChatSubscriptions(private val oversized: OversizedChats) {
    /** Started or subscribed on this link (since the last [aiDropped]); never planned again. */
    private val attempted = LinkedHashSet<String>()

    /** A `History` (or a history `PayloadTooLarge`) landed on this link. */
    private val subscribed = LinkedHashSet<String>()

    /** Requests out (started, not settled), in start order; their chats are not planned. */
    private val requests = LinkedHashMap<SubscribeTicket, String>()

    /** Requests that timed out: their slots are free, but a late reply still answers the chat. */
    private val late = LinkedHashMap<SubscribeTicket, String>()

    /** Chats [resubscribe]d on this link: once each. */
    private val resubscribed = HashSet<String>()

    /** Started on this link, with no history, answer or refusal yet. */
    private val unanswered = LinkedHashSet<String>()

    /** Chats seen in a list given to [wanted] on this link. */
    private val everListed = HashSet<String>()

    /** A list (even an empty one) was given to [wanted] on this link. */
    private var listGiven = false

    /** False after [aiDropped] or a plan with AI off, until a plan with AI on. */
    private var aiOn = true

    /** Chats refused while AI was off: planned again when it is back. */
    private val refusedWhileOff = HashSet<String>()

    /** Chats subscribed on this link, in the order their histories landed. */
    val subscribedChats: Set<String> get() = subscribed.toSet()

    /** The unanswered chats, in start order (see [ChatSubscriptions]). */
    val outstanding: Set<String> get() = unanswered.toSet()

    /**
     * The chats this link should have, in order (owner decision: every chat is watched): the
     * [open] conversations first, then **every** chat in [entries] (an authoritative list from the
     * Mac; null while this link has had none) in the Mac's order, without duplicates, minus too-large ones. A too-large chat already
     * subscribed on this link stays wanted: its history was over the cap but the Mac installed the
     * subscription, which still feeds its live state (the activity tracker treats that
     * `PayloadTooLarge` as a history), so it is not cut while it is open or listed. It is never
     * planned again either way.
     */
    fun wanted(open: List<String>, entries: List<ChatListEntry>?): List<String> {
        val wanted = LinkedHashSet<String>()
        for (chatID in open) wanted += chatID
        if (entries != null) listGiven = true
        for (entry in entries.orEmpty()) {
            wanted += entry.chat.id
            everListed += entry.chat.id
        }
        val tooLarge = oversized.tooLarge
        return wanted.filter { it !in tooLarge || it in subscribed }
    }

    /**
     * The minimal diff from this link's state to [wanted]: the wanted chats not yet tried on this
     * link, in [wanted]'s order, as far as `concurrency` and the suspect rule allow, and none while
     * AI is off; and the subscribed chats that left the list. Changes nothing but the AI state:
     * the first plan with [aiAvailable] after AI was off makes the chats refused meanwhile wanted
     * again.
     */
    fun plan(wanted: List<String>, concurrency: Int, aiAvailable: Boolean): SubscriptionPlan {
        require(concurrency >= 1) { "concurrency must be at least 1" }
        if (aiAvailable && !aiOn) {
            attempted -= refusedWhileOff
            refusedWhileOff.clear()
        }
        aiOn = aiAvailable
        val wantedSet = wanted.toHashSet()
        val unsubscribe = subscribed.filter { it !in wantedSet && it in everListed }
        val subscribe = if (aiAvailable) planSubscribes(wanted, concurrency) else emptyList()
        return SubscriptionPlan(subscribe, unsubscribe)
    }

    private fun planSubscribes(wanted: List<String>, concurrency: Int): List<String> {
        if (unanswered.any(oversized::isSuspect)) return emptyList()
        val busy = requests.values.toHashSet()
        val candidates = wanted.filter { it !in attempted && it !in busy }.distinct()
            .filter { listGiven || oversized.suspects.isEmpty() || oversized.isSuspect(it) }
        val suspect = candidates.firstOrNull(oversized::isSuspect)
        if (suspect != null) return if (requests.isEmpty() && unanswered.isEmpty()) listOf(suspect) else emptyList()
        val free = concurrency - requests.size
        return if (free > 0) candidates.take(free) else emptyList()
    }

    /**
     * Records [plan] as sent: [started] for each subscribe, [unsubscribed] for each unsubscribe.
     * @return the subscribe tickets, in [SubscriptionPlan.subscribe]'s order.
     */
    fun take(plan: SubscriptionPlan): List<SubscribeTicket> {
        plan.unsubscribe.forEach(::unsubscribed)
        return plan.subscribe.map(::started)
    }

    /** A subscribe for [chatID] was sent on this link. Prefer [take]. */
    fun started(chatID: String): SubscribeTicket {
        val ticket = SubscribeTicket(chatID)
        attempted += chatID
        unanswered += chatID
        requests[ticket] = chatID
        return ticket
    }

    /**
     * A `History` for [chatID] landed on this link, or a history `PayloadTooLarge` ([tooLarge]),
     * which also makes it too large for the process. The caller settles the reply's own ticket
     * first when it can map the reply's request ID; when it cannot ([consumeLateTicket]), the
     * history answers the chat's oldest timed-out request. The chat stays unanswered while a
     * request for it is still in flight (a Try again), or another timed-out one remains: this
     * history may be the older request's, and the other's reply can still end a link.
     */
    fun subscribed(chatID: String, tooLarge: Boolean = false, consumeLateTicket: Boolean = false) {
        attempted += chatID
        subscribed += chatID
        if (consumeLateTicket && requests.values.none { it == chatID }) {
            late.entries.firstOrNull { it.value == chatID }?.let { late.remove(it.key) }
        }
        answered(chatID)
        if (tooLarge) oversized.foundTooLarge(chatID) else oversized.answered(chatID)
    }

    /**
     * The request [ticket] ended with [outcome]: frees its slot. [SubscribeOutcome.TIMEOUT]
     * leaves the chat unanswered; a later [SubscribeOutcome.ANSWERED] or [SubscribeOutcome.ERROR]
     * for the same ticket (a late reply) then answers it. A ticket already answered, timed out
     * twice, or from another link, is ignored.
     */
    fun settled(ticket: SubscribeTicket, outcome: SubscribeOutcome) {
        // A late reply's ticket is in [late]: its slot is already free.
        val chatID = requests.remove(ticket) ?: late.remove(ticket) ?: return
        when (outcome) {
            SubscribeOutcome.TIMEOUT -> late[ticket] = chatID
            SubscribeOutcome.ANSWERED -> {
                answered(chatID)
                oversized.answered(chatID)
            }
            SubscribeOutcome.ERROR -> {
                answered(chatID)
                if (!aiOn) refusedWhileOff += chatID
            }
        }
    }

    private fun answered(chatID: String) {
        // A newer request for the chat may still be unanswered after a Try again.
        if (requests.values.none { it == chatID } && late.values.none { it == chatID }) unanswered -= chatID
    }

    /** An unsubscribe for [chatID] was sent: the chat is planned again if wanted again. Prefer [take]. */
    fun unsubscribed(chatID: String) {
        subscribed -= chatID
        attempted -= chatID
    }

    /**
     * The owner's Try again: [chatID] is no longer too large or a suspect, and is planned once more
     * on this link, after any request for it still out has settled. Accepted for a suspect still
     * out too: it is the owner's choice, and the chat stays unanswered until that request is.
     */
    fun tryAgain(chatID: String) {
        oversized.tryAgain(chatID)
        attempted -= chatID
        subscribed -= chatID
    }

    /**
     * A stale transcript (a delivery `PayloadTooLarge`, or a lost append, `UNREADABLE_DELTA`):
     * [chatID] is planned once more on this link, so its history replaces the transcript. Unlike
     * [tryAgain] it leaves [OversizedChats] alone, and it is skipped for a too-large chat and for
     * a chat already resubscribed on this link.
     *
     * @return whether the chat will be planned again.
     */
    fun resubscribe(chatID: String): Boolean {
        if (chatID in oversized.tooLarge || !resubscribed.add(chatID)) return false
        attempted -= chatID
        subscribed -= chatID
        return true
    }

    /**
     * AI was turned off: the Mac dropped every subscription and does not restore them when AI
     * comes back (`HB:2250-2269`, `AM:4470-4486`), so every chat is planned again once a plan says
     * AI is available. Requests still out keep their slots until they settle (the Mac refuses
     * them while AI is off) and stay unanswered until then.
     */
    fun aiDropped() {
        aiOn = false
        subscribed.clear()
        attempted.clear()
    }

    /**
     * The link ended; this instance is done. On a frame over the Noise cap ([messageTooLarge]),
     * the unanswered chats name the culprit or become suspects in [oversized]; with [listOut] (the
     * chat list request was unanswered too) they only become suspects, even one alone.
     */
    fun linkEnded(messageTooLarge: Boolean, listOut: Boolean = false) {
        if (messageTooLarge) oversized.linkEndedOversized(unanswered.toSet(), listOut)
    }
}
