package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The chat list as the Chats tab shows it. */
sealed interface ChatListState {
    /** No list yet. */
    data object Loading : ChatListState

    /** The Mac's list, in its order (most recent first); [refreshing] while a refetch is out. */
    data class Loaded(val entries: List<ChatListEntry>, val refreshing: Boolean) : ChatListState {
        override fun toString(): String = "Loaded(entries=${entries.size}, refreshing=$refreshing)"
    }

    /** The list could not be fetched or read; [retryable] offers Try again (pull to refresh). */
    data class Failed(val retryable: Boolean) : ChatListState

    /** AI is turned off on the Mac, which refuses every chat request (`HB:2276-2330`). */
    data object AiUnavailable : ChatListState
}

/** One conversation as the conversation screen shows it. */
sealed interface ConversationState {
    data object Loading : ConversationState

    /**
     * The transcript: [messages] is a read-only snapshot (the transcript itself stays on the chat
     * consumer), [version] changes with every snapshot. [stale]: served from the cache while a
     * refetch is out, after it failed, or after an append was lost (iOS's soft banner,
     * `AM:2934-3010`). [deleted]: missing from the Mac's list (`AM:2877-2900`), shown with
     * [ChatRepository.DELETED_CHAT_NOTICE]. [sendable]: connected, AI available, not deleted, and
     * a history for this chat received on the current link.
     */
    data class Shown(
        val messages: List<ChatMessage>,
        val version: Long,
        val stale: Boolean,
        val deleted: Boolean,
        val sendable: Boolean,
    ) : ConversationState {
        override fun toString(): String =
            "Shown(messages=${messages.size}, version=$version, stale=$stale, deleted=$deleted, sendable=$sendable)"
    }

    /** The history could not be loaded; [retryable] offers Try again (false while AI is off). */
    data class Failed(val retryable: Boolean) : ConversationState

    /** The history is over the raw chat cap, or ended a link by itself: not loaded (owner decision). */
    data object TooLarge : ConversationState
}

/** A chat-list row: the Mac's entry, the agent's state and whether it moved since the owner looked. */
data class ChatRow(val entry: ChatListEntry, val state: AgentState, val unread: Boolean)

/**
 * The repository's bounds, all injectable: request bounds as iOS's (`AM:2223`, `AM:2939`), the
 * send echo and delete waits used by later actions, how often an open transcript's snapshot may
 * be republished while it streams ([snapshotInterval]), and the activity tracker's timings
 * ([ChatActivityTracker]).
 */
data class ChatTimeouts(
    val list: Duration = 15.seconds,
    val subscribe: Duration = 15.seconds,
    val echo: Duration = 10.seconds,
    val deleteListWait: Duration = 3.seconds,
    val snapshotInterval: Duration = 100.milliseconds,
    val parkDebounce: Duration = 1_500.milliseconds,
    val snapshotSettle: Duration = 1.seconds,
)

/**
 * The app's chat state: the list, every chat's subscription on the current link, open
 * transcripts, agent activity, unread marks and AI availability, fed by the client's chat sink.
 *
 * **One consumer.** [sink] is a [ChatInbox]: its accept only enqueues, so the receive loop never
 * waits on chat work. One consumer owns all state below. It never awaits the Mac: each request
 * (list, subscribe) runs in its own coroutine on [scope], started undispatched so envelopes leave
 * in the order the consumer asked, and posts its outcome back through the inbox. Public calls post
 * too; the flows are published after the consumer has drained what the inbox holds (or once per
 * [ChatTimeouts.snapshotInterval] under a constant stream), and an open transcript's snapshot at
 * most once per [ChatTimeouts.snapshotInterval] while it streams.
 *
 * **Links.** On [ChatInput.LinkUp]: revision and AI are recorded; with AI on the list is fetched
 * (bounded by [ChatTimeouts.list]) and [ChatSubscriptions] plans the subscribes (every listed chat
 * is watched, plus the open ones; `subscribeConcurrency` out at once; iOS subscribes one at a time,
 * `AM:2401-2438`); with AI off nothing is requested. On [ChatInput.LinkDown]: a fresh
 * [ChatSubscriptions], [ChatActivityTracker.onLinkDown], and open transcripts are kept, stale.
 * Inputs from any link but the current one are dropped.
 *
 * **Subscribed state comes from the sink**, never from a request's outcome: a `History` or a
 * history [ChatInput.PayloadTooLarge] on link N means subscribed on N whenever it lands, even after
 * the request timed out (the Mac installs the subscription first, `HB:1411-1427`). Each subscribe's
 * request ID maps to its [SubscribeTicket] for the life of the link, so a reply that lands late
 * still answers it. The request's outcome only drives the banner and settles the ticket.
 *
 * **Histories and deliveries.** A history replaces the chat's transcript when the chat is open or
 * cached and is dropped otherwise (watched chats cost no transcript memory); it seeds the activity
 * tracker. A delivery merges only into a transcript built from a history on the current link
 * (`AM:4296-4313`), and always feeds the tracker. A lost append (a delivery over the raw cap, or an
 * undecodable delta) marks the transcript stale and resubscribes the chat once per link.
 *
 * **Too large.** A history over the raw chat cap makes its chat too large for the process (its
 * subscription still feeds its live state); a link ended by a frame over the Noise cap names the
 * one unanswered subscribe as too large, or makes the unanswered ones suspects, retried first and
 * alone ([OversizedChats], [ChatSubscriptions]). Only [retry] subscribes a too-large chat again.
 *
 * **Unread.** A chat is unread when the Mac's `lastModified` is past the owner's mark
 * ([ChatSeenStore], Mac values only, never phone time), or its turn ended unseen (Done). A chat's
 * first appearance sets its mark. Marked seen: on opening; at each list value while it is visible;
 * at the first list value past an own send ([noteOwnSend]); and at a list value that moved while
 * the chat's newest visible delivery on this link is the owner's (typed on the Mac, `CLM:122-129`).
 *
 * **Memory.** Transcripts are kept for open conversations and the `closedTranscripts` most
 * recently closed ([ChatBudgets]); attachment bytes under [ChatBudgets.attachments] per transcript.
 *
 * [clear] (unpair) drops everything, ignores every input from links up to then, and clears the
 * [OversizedChats] and [seen] on the consumer, so no mark lands after the wipe.
 *
 * @param nowMillis a monotonic clock for timers and rate limits only; never compared with the Mac's times.
 * @param log diagnostics: chat IDs, counts, sizes, cases and exception classes only, never content.
 */
class ChatRepository(
    private val link: StreamLink,
    private val scope: CoroutineScope,
    private val seen: ChatSeenStore,
    private val timeouts: ChatTimeouts = ChatTimeouts(),
    private val budgets: ChatBudgets = ChatBudgets(),
    private val subscribeConcurrency: Int = 2,
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit = {},
) {
    init {
        require(subscribeConcurrency >= 1) { "subscribeConcurrency must be at least 1" }
    }

    private val listFlow = MutableStateFlow<ChatListState>(ChatListState.Loading)

    /** The chat list. */
    val list: StateFlow<ChatListState> = listFlow.asStateFlow()

    private val rowsFlow = MutableStateFlow<List<ChatRow>>(emptyList())

    /** The list's rows with each chat's state and unread mark, in the Mac's order. */
    val rows: StateFlow<List<ChatRow>> = rowsFlow.asStateFlow()

    private val aiFlow = MutableStateFlow(true)

    /** Whether AI is on on the Mac: from the hello (absent means true), then `aiAvailabilityChanged`. */
    val aiAvailable: StateFlow<Boolean> = aiFlow.asStateFlow()

    private val revisionFlow = MutableStateFlow<Int?>(null)

    /** The Mac's hello revision on the latest link; null before the first and after [clear]. */
    val macRevision: StateFlow<Int?> = revisionFlow.asStateFlow()

    private val tracker = ChatActivityTracker(nowMillis, timeouts.parkDebounce.inWholeMilliseconds, timeouts.snapshotSettle.inWholeMilliseconds)

    /** Each chat's agent state; an absent chat is [AgentState.UNKNOWN]. */
    val activity: StateFlow<Map<String, AgentState>> = tracker.states

    // Everything below is the consumer's.

    /** One conversation the owner has open, or closed recently enough to keep. */
    private class Conversation {
        var transcript: Transcript? = null

        /** The link the transcript's history arrived on. */
        var historyLink: Long? = null

        /** An append was lost since the history (a resubscribe is out, or was refused). */
        var lostAppend = false

        /** The transcript the last snapshot was taken from, its version and when. */
        var snapshotOf: Transcript? = null
        var snapshotVersion = -1L
        var snapshotAtMillis = Long.MIN_VALUE
        var snapshot: List<ChatMessage> = emptyList()

        /** Counts the snapshots taken, for [ConversationState.Shown.version]. */
        var snapshots = 0L
    }

    private val oversized = OversizedChats()
    private var subscriptions = ChatSubscriptions(oversized)
    private var currentLink: Long? = null

    /** The newest link any input named; a [ChatInput.LinkUp] at or below it is stale. */
    private var newestLink = 0L

    /** Inputs from links up to this one are dropped ([clear]). */
    private var clearedThrough = 0L
    private var ai = true
    private var entries: List<ChatListEntry>? = null

    /** A whole list arrived on the current link (subscriptions plan with it only then). */
    private var listOnThisLink = false
    private var listFailed = false
    private var listInFlight = false

    /** Bumped by every list applied, so a failed request does not hide a list that landed meanwhile. */
    private var listGeneration = 0L
    private val listRequests = HashSet<Long>()
    private val subscribeTickets = HashMap<Long, SubscribeTicket>()

    /** Chats whose subscribe on this link failed (an error or an undecodable reply) or timed out, with no history yet. */
    private val failedThisLink = HashSet<String>()

    /** Open conversations: chat ID to how many times it is open, in opening order. */
    private val open = LinkedHashMap<String, Int>()

    /** Closed conversations whose transcripts are kept, least recently closed first. */
    private val closed = LinkedHashSet<String>()
    private val conversations = HashMap<String, Conversation>()
    private var visibleChat: String? = null
    private var foreground = true

    /** The author of each chat's newest visible delivery on this link. */
    private val newestDeliveryAuthor = HashMap<String, Participant>()

    /** A chat's `lastModified` just before an own send, until a list value passes it. */
    private val sendMarks = HashMap<String, Double>()

    /** Each listed chat's `lastModified` in the previous list. */
    private val previousModified = HashMap<String, Double>()
    private var planNeeded = false
    private var publishedAtMillis = Long.MIN_VALUE
    private var timer: Job? = null
    private var timerAtMillis = Long.MAX_VALUE
    private var cost = LinkCost(0, 0)
    private var lastWanted: List<String> = emptyList()

    private class LinkCost(val linkId: Long, val upAtMillis: Long) {
        var histories = 0
        var bytes = 0L
        var subscribes = 0
        var allWantedLogged = false
    }

    /** The conversation flows the UI asked for, by chat ID; published by the consumer. */
    private val conversationFlows = ConcurrentHashMap<String, MutableStateFlow<ConversationState>>()

    /** For tests: transcripts held, as of the last publish. */
    @Volatile
    internal var heldTranscripts: Int = 0
        private set

    @Volatile
    private var tooLargeSnapshot: Set<String> = emptySet()

    /** For tests: the process's too-large chats, as of the last publish. */
    internal fun tooLargeChats(): Set<String> = tooLargeSnapshot

    /** The client's chat sink. Declared last: its consumer reads the state above. */
    private val inbox: ChatInbox = ChatInbox(scope, budgets, log) { input ->
        handle(input)
        // The input being consumed still holds its slot.
        afterStep(drained = inbox.queuedItems <= 1)
    }

    /** Where the client delivers chat inputs: accept only enqueues. */
    val sink: ChatSink get() = inbox

    /** [chatID]'s conversation. Loading until it is [open]ed and its history lands. Thread-safe. */
    fun conversation(chatID: String): StateFlow<ConversationState> =
        conversationFlows.computeIfAbsent(chatID) { MutableStateFlow(ConversationState.Loading) }

    /**
     * The owner opened [chatID] (a navigator push): it is kept and subscribed while open, and seen.
     * Opening a chat watched without a transcript subscribes it again for its history.
     */
    fun open(chatID: String) {
        // The flow exists from now on, so a screen reading it at once gets the published state.
        conversation(chatID)
        post { openNow(chatID) }
    }

    /** The owner left [chatID] (a navigator pop). A listed chat stays subscribed; its transcript is cached. */
    fun close(chatID: String) = post { closeNow(chatID) }

    /** The conversation on screen, or null. Visible only while [setForeground] says the app is. */
    fun setVisible(chatID: String?) = post {
        visibleChat = chatID
        if (chatID != null && foreground) ownerSaw(chatID)
    }

    /** Whether the app is in the foreground (false while locked or backgrounded): no chat is visible otherwise. */
    fun setForeground(foreground: Boolean) = post {
        this.foreground = foreground
        val chatID = visibleChat
        if (foreground && chatID != null) ownerSaw(chatID)
    }

    /** Try again for [chatID]: subscribes it once more on this link, too large or failed included. */
    fun retry(chatID: String) = post {
        failedThisLink -= chatID
        conversations[chatID]?.lostAppend = false
        // Offline, the next link plans it: only the too-large mark has to go.
        if (currentLink == null) oversized.tryAgain(chatID) else subscriptions.tryAgain(chatID)
        planNeeded = true
    }

    /** Refetches the list (pull to refresh, or Try again after a failed list). */
    fun refresh() = post { if (ai) requestList() }

    /**
     * Unpair: drops everything, ignores every input from the links seen so far, and clears the
     * oversized chats and the seen marks on the consumer, so none lands after the wipe.
     */
    fun clear() = post { clearNow() }

    /**
     * An own message was published to [chatID] (the send action calls this): the first list value
     * past the chat's current `lastModified` is the owner's own bump, not news.
     */
    internal fun noteOwnSend(chatID: String) = post {
        entries?.firstOrNull { it.chat.id == chatID }?.let { sendMarks[chatID] = it.chat.lastModifiedMillis }
    }

    private fun post(block: () -> Unit) {
        inbox.post {
            block()
            afterStep(drained = inbox.queuedItems == 0)
        }
    }

    // Inputs

    private fun handle(input: ChatInput) {
        when (input) {
            is ChatInput.LinkUp -> linkUp(input)
            is ChatInput.LinkDown -> linkDown(input)
            is ChatInput.Message -> if (isCurrent(input.linkId)) message(input.message, input.requestID, input.linkId, input.frameBytes)
            is ChatInput.Payload -> if (isCurrent(input.linkId)) payload(input)
            is ChatInput.PayloadTooLarge -> if (isCurrent(input.linkId)) tooLarge(input)
        }
    }

    private fun isCurrent(linkId: Long): Boolean = linkId == currentLink && linkId > clearedThrough

    private fun linkUp(up: ChatInput.LinkUp) {
        if (up.linkId <= clearedThrough || up.linkId <= newestLink) {
            log("chat link up ${up.linkId} ignored: not newer than ${maxOf(newestLink, clearedThrough)}")
            return
        }
        if (currentLink != null) endLink(messageTooLarge = false)
        newestLink = up.linkId
        currentLink = up.linkId
        revisionFlow.value = up.macRevision
        ai = up.aiAvailable
        subscriptions = ChatSubscriptions(oversized)
        listOnThisLink = false
        listFailed = false
        listInFlight = false
        listRequests.clear()
        subscribeTickets.clear()
        failedThisLink.clear()
        newestDeliveryAuthor.clear()
        cost = LinkCost(up.linkId, nowMillis())
        log("chat link up ${up.linkId}: revision ${up.macRevision}, ai ${up.aiAvailable}")
        if (ai) requestList()
        planNeeded = true
    }

    private fun linkDown(down: ChatInput.LinkDown) {
        newestLink = maxOf(newestLink, down.linkId)
        if (down.linkId != currentLink) return
        endLink(down.messageTooLarge)
    }

    private fun endLink(messageTooLarge: Boolean) {
        val linkId = currentLink ?: return
        val before = oversized.tooLarge
        subscriptions.linkEnded(messageTooLarge)
        val named = oversized.tooLarge - before
        if (messageTooLarge) {
            log("chat link $linkId ended on a frame over the Noise cap: too large ${named.joinToString()}; suspects ${oversized.suspects.size}")
        }
        subscriptions = ChatSubscriptions(oversized)
        tracker.onLinkDown()
        currentLink = null
        listInFlight = false
        log("chat link $linkId down: ${cost.subscribes} subscribes, ${cost.histories} histories, ${cost.bytes} bytes")
    }

    private fun payload(input: ChatInput.Payload) {
        when (val decoded = input.decode(budgets.attachments)) {
            is PayloadDecoding.Decoded -> message(decoded.message, input.requestID, input.linkId, input.size)
            is PayloadDecoding.Undecodable -> undecodable(decoded, input.linkId)
        }
    }

    private fun message(message: MacMessage, requestID: Long?, linkId: Long, frameBytes: Int) {
        when (message) {
            is MacMessage.History -> history(message, requestID, linkId, frameBytes)
            is MacMessage.Delivery -> delivery(message, linkId)
            is MacMessage.TypingStatus -> tracker.onTyping(message.chatID, message.participant, message.isTyping)
            is MacMessage.TurnLifecycle -> tracker.onTurn(message.chatID, message.event, isVisible(message.chatID))
            is MacMessage.MessagesRemoved -> {
                // AM:4336-4345: from the open transcript and the cache alike; unknown IDs are ignored.
                conversations[message.chatID]?.transcript?.removeAll(message.messageIDs.toSet())
            }
            is MacMessage.ChatsAndSessions -> {
                if (requestID != null) listRequests -= requestID
                applyList(message.chats)
            }
            is MacMessage.ChatListChanged -> applyList(message.chats)
            is MacMessage.ChatCreated -> chatCreated(message.entry)
            is MacMessage.AiAvailabilityChanged -> if (message.available) aiOn() else aiOff()
            is MacMessage.Error -> log("chat error from the Mac: ${message.code}")
            else -> log("chat input not handled: ${message.javaClass.simpleName}")
        }
    }

    private fun history(history: MacMessage.History, requestID: Long?, linkId: Long, frameBytes: Int) {
        val chatID = history.chatID
        requestID?.let(subscribeTickets::get)?.let { subscriptions.settled(it, SubscribeOutcome.ANSWERED) }
        subscriptions.subscribed(chatID)
        failedThisLink -= chatID
        cost.histories++
        cost.bytes += frameBytes
        tracker.onHistory(chatID, history.messages.lastOrNull { !it.hidden }, isVisible(chatID))
        conversations[chatID]?.let { conversation ->
            conversation.transcript = Transcript(history.messages, budgets.attachments)
            conversation.historyLink = linkId
            conversation.lostAppend = false
        }
        planNeeded = true
        noteAllWantedLanded()
    }

    private fun delivery(delivery: MacMessage.Delivery, linkId: Long) {
        val chatID = delivery.chatID
        val message = delivery.message
        if (!message.hidden) newestDeliveryAuthor[chatID] = message.author
        tracker.onDelivery(chatID, message)
        val conversation = conversations[chatID] ?: return
        val transcript = conversation.transcript ?: return
        if (conversation.historyLink != linkId) return
        val result = transcript.applyDelivery(message, chatID)
        if (result == MergeResult.Ignored(IgnoreReason.UNREADABLE_DELTA)) lostAppend(chatID)
    }

    private fun lostAppend(chatID: String) {
        conversations[chatID]?.lostAppend = true
        if (subscriptions.resubscribe(chatID)) {
            log("chat $chatID lost an append: subscribing again")
            planNeeded = true
        }
    }

    private fun tooLarge(input: ChatInput.PayloadTooLarge) {
        when (input.case) {
            "history" -> {
                val ticket = input.requestID?.let(subscribeTickets::get)
                ticket?.let { subscriptions.settled(it, SubscribeOutcome.ANSWERED) }
                val chatID = input.chatID ?: ticket?.chatID
                if (chatID == null) {
                    log("a history of ${input.sizeBytes} bytes over the chat cap names no chat")
                    return
                }
                log("chat $chatID history of ${input.sizeBytes} bytes is over the chat cap: too large")
                subscriptions.subscribed(chatID, tooLarge = true)
                failedThisLink -= chatID
                cost.histories++
                cost.bytes += input.sizeBytes
                tracker.onHistory(chatID, null, isVisible(chatID))
                conversations[chatID]?.let {
                    it.transcript = null
                    it.historyLink = null
                }
                planNeeded = true
                noteAllWantedLanded()
            }
            "delivery" -> {
                val chatID = input.chatID ?: return log("a delivery of ${input.sizeBytes} bytes over the chat cap names no chat")
                log("chat $chatID delivery of ${input.sizeBytes} bytes over the chat cap was dropped")
                lostAppend(chatID)
            }
            else -> {
                log("a ${input.case} of ${input.sizeBytes} bytes is over the chat cap")
                input.requestID?.let { listRequests -= it }
                listFailed = true
            }
        }
    }

    private fun undecodable(failure: PayloadDecoding.Undecodable, linkId: Long) {
        log("a chat ${failure.case} did not decode: ${failure.reason}")
        val requestID = failure.requestID
        val ticket = requestID?.let(subscribeTickets::get)
        when {
            ticket != null -> {
                // The reply arrived whole: it answers the subscribe, but the chat has no history.
                subscriptions.settled(ticket, SubscribeOutcome.ANSWERED)
                val conversation = conversations[ticket.chatID]
                if (conversation?.historyLink != linkId) failedThisLink += ticket.chatID
                planNeeded = true
            }
            failure.case == "chatsAndSessions" || failure.case == "chatListChanged" -> {
                requestID?.let { listRequests -= it }
                listFailed = true
            }
        }
    }

    // The list

    private fun applyList(newEntries: List<ChatListEntry>) {
        val previous = entries
        entries = newEntries
        listOnThisLink = true
        listFailed = false
        listGeneration++
        val listed = newEntries.mapTo(HashSet()) { it.chat.id }
        previous?.forEach { if (it.chat.id !in listed) forgetChat(it.chat.id) }
        for (entry in newEntries) {
            val chatID = entry.chat.id
            val modified = entry.chat.lastModifiedMillis
            val mark = seen.seen(chatID)
            val moved = previousModified[chatID]?.let { modified > it } == true
            val pastSend = sendMarks[chatID]?.let { modified > it } == true
            when {
                mark == null -> seen.markSeen(chatID, modified)
                isVisible(chatID) -> seen.markSeen(chatID, modified)
                pastSend -> seen.markSeen(chatID, modified)
                moved && newestDeliveryAuthor[chatID] == Participant.USER -> seen.markSeen(chatID, modified)
            }
            if (pastSend) sendMarks -= chatID
            previousModified[chatID] = modified
        }
        planNeeded = true
    }

    /** A chat that left the list: its cached transcript, activity and marks go (`AM:2242-2254`). */
    private fun forgetChat(chatID: String) {
        tracker.forget(chatID)
        seen.forget(chatID)
        sendMarks -= chatID
        previousModified -= chatID
        newestDeliveryAuthor -= chatID
        if (chatID !in open) {
            closed -= chatID
            conversations -= chatID
            conversationFlows.remove(chatID)?.value = ConversationState.Loading
        }
    }

    /** `chatCreated`: inserted at the top only if missing (`AM:2587-2591`); with no list yet the next list brings it. */
    private fun chatCreated(entry: ChatListEntry) {
        val current = entries ?: return
        if (current.any { it.chat.id == entry.chat.id }) return
        entries = listOf(entry) + current
        if (seen.seen(entry.chat.id) == null) seen.markSeen(entry.chat.id, entry.chat.lastModifiedMillis)
        previousModified[entry.chat.id] = entry.chat.lastModifiedMillis
        planNeeded = true
    }

    private fun requestList() {
        val linkId = currentLink ?: return
        if (listInFlight) return
        listInFlight = true
        listFailed = false
        val generation = listGeneration
        launchRequest(
            PhoneMessage.ListChatsAndSessions,
            linkId,
            timeouts.list,
            onSent = { id -> listRequests += id },
        ) { outcome ->
            listInFlight = false
            when (outcome) {
                is Outcome.Replied -> when (val reply = outcome.reply) {
                    is MacMessage.ChatPayloadTaken -> Unit
                    is MacMessage.Error -> if (reply.isAiUnavailable) {
                        aiOff()
                    } else {
                        log("the chat list request was refused: ${reply.code}")
                        listFailed = true
                    }
                    else -> log("unexpected reply to the chat list request: ${reply.javaClass.simpleName}")
                }
                is Outcome.Failed -> {
                    log("the chat list request failed: ${outcome.reason}")
                    if (listGeneration == generation) listFailed = true
                }
            }
        }
    }

    // Subscriptions

    private fun plan() {
        val linkId = currentLink ?: return
        val wanted = subscriptions.wanted(open.keys.toList(), entries.takeIf { listOnThisLink })
        lastWanted = wanted
        val plan = subscriptions.plan(wanted, subscribeConcurrency, ai)
        if (plan.subscribe.isEmpty() && plan.unsubscribe.isEmpty()) return
        val tickets = subscriptions.take(plan)
        for (chatID in plan.unsubscribe) {
            tracker.onUnsubscribed(chatID)
            launchSend(PhoneMessage.Unsubscribe(chatID), linkId)
        }
        for (ticket in tickets) subscribe(ticket, linkId)
    }

    private fun subscribe(ticket: SubscribeTicket, linkId: Long) {
        cost.subscribes++
        launchRequest(
            PhoneMessage.Subscribe(ticket.chatID),
            linkId,
            timeouts.subscribe,
            onSent = { id -> subscribeTickets[id] = ticket },
        ) { outcome -> subscribeOutcome(ticket, linkId, outcome) }
    }

    private fun subscribeOutcome(ticket: SubscribeTicket, linkId: Long, outcome: Outcome) {
        val chatID = ticket.chatID
        planNeeded = true
        when (outcome) {
            // The reply went through the sink, ahead of this outcome: already settled there.
            is Outcome.Replied -> when (val reply = outcome.reply) {
                is MacMessage.ChatPayloadTaken -> subscriptions.settled(ticket, SubscribeOutcome.ANSWERED)
                is MacMessage.Error -> {
                    if (reply.isAiUnavailable) aiOff() else log("chat $chatID subscribe refused: ${reply.code}")
                    subscriptions.settled(ticket, SubscribeOutcome.ERROR)
                    if (ai && conversations[chatID]?.historyLink != linkId) failedThisLink += chatID
                }
                else -> {
                    log("unexpected reply to chat $chatID subscribe: ${reply.javaClass.simpleName}")
                    subscriptions.settled(ticket, SubscribeOutcome.ANSWERED)
                }
            }
            is Outcome.Failed -> {
                // Still installed on the Mac: its history may land later and answer the ticket.
                log("chat $chatID subscribe failed: ${outcome.reason}")
                subscriptions.settled(ticket, SubscribeOutcome.TIMEOUT)
                if (chatID !in subscriptions.subscribedChats) failedThisLink += chatID
            }
        }
    }

    private fun noteAllWantedLanded() {
        if (cost.allWantedLogged || lastWanted.isEmpty() || !listOnThisLink) return
        val landed = subscriptions.subscribedChats
        if (lastWanted.all { it in landed }) {
            cost.allWantedLogged = true
            log(
                "chat link ${cost.linkId}: ${lastWanted.size} wanted chats subscribed after " +
                    "${nowMillis() - cost.upAtMillis} ms (${cost.histories} histories, ${cost.bytes} bytes)",
            )
        }
    }

    // AI

    private fun aiOff() {
        if (!ai) return
        log("chat AI is off on the Mac")
        ai = false
        for (chatID in subscriptions.subscribedChats) tracker.onUnsubscribed(chatID)
        subscriptions.aiDropped()
        failedThisLink.clear()
        planNeeded = true
    }

    private fun aiOn() {
        if (ai) return
        log("chat AI is on on the Mac")
        ai = true
        // AM:4470-4486: the list was empty and every subscription was dropped; neither comes back by itself.
        requestList()
        planNeeded = true
    }

    // Navigation

    private fun openNow(chatID: String) {
        open[chatID] = (open[chatID] ?: 0) + 1
        closed -= chatID
        val conversation = conversations.getOrPut(chatID, ::Conversation)
        ownerSaw(chatID)
        // A watched chat's history was dropped: only a new subscribe brings it back (HB:1417).
        if (conversation.transcript == null && currentLink != null && chatID in subscriptions.subscribedChats &&
            chatID !in oversized.tooLarge
        ) {
            subscriptions.tryAgain(chatID)
        }
        planNeeded = true
    }

    private fun closeNow(chatID: String) {
        val count = open[chatID] ?: return
        if (count > 1) {
            open[chatID] = count - 1
            return
        }
        open -= chatID
        val conversation = conversations[chatID]
        // A deleted chat is readable only until the owner leaves it (AM:441-442).
        if (conversation?.transcript != null && !isDeleted(chatID)) {
            closed += chatID
            while (closed.size > budgets.closedTranscripts) {
                val evicted = closed.first()
                closed -= evicted
                conversations -= evicted
                conversationFlows.remove(evicted)?.value = ConversationState.Loading
            }
        } else {
            conversations -= chatID
            conversationFlows.remove(chatID)?.value = ConversationState.Loading
        }
        planNeeded = true
    }

    private fun isVisible(chatID: String): Boolean = foreground && visibleChat == chatID

    /** The owner sees [chatID] now: its mark moves to the Mac's latest value and Done ends. */
    private fun ownerSaw(chatID: String) {
        entries?.firstOrNull { it.chat.id == chatID }?.let { seen.markSeen(chatID, it.chat.lastModifiedMillis) }
        tracker.markRead(chatID)
    }

    // Unpair

    private fun clearNow() {
        clearedThrough = maxOf(newestLink, currentLink ?: 0)
        log("chat state cleared through link $clearedThrough")
        currentLink = null
        subscriptions = ChatSubscriptions(oversized)
        for (chatID in tracker.states.value.keys) tracker.forget(chatID)
        entries = null
        listOnThisLink = false
        listFailed = false
        listInFlight = false
        listRequests.clear()
        subscribeTickets.clear()
        failedThisLink.clear()
        open.clear()
        closed.clear()
        conversations.clear()
        conversationFlows.values.forEach { it.value = ConversationState.Loading }
        conversationFlows.clear()
        visibleChat = null
        newestDeliveryAuthor.clear()
        sendMarks.clear()
        previousModified.clear()
        lastWanted = emptyList()
        ai = true
        aiFlow.value = true
        revisionFlow.value = null
        timer?.cancel()
        timer = null
        timerAtMillis = Long.MAX_VALUE
        oversized.clear()
        seen.clear()
        planNeeded = false
        publish(nowMillis())
    }

    // Requests

    private sealed interface Outcome {
        class Replied(val reply: MacMessage) : Outcome

        class Failed(val reason: String) : Outcome
    }

    /**
     * Sends [message] as a request on [linkId] in its own coroutine, started undispatched so the
     * envelope is queued for the socket in the consumer's order, and posts [done] with the outcome.
     * [onSent] runs on the consumer, before any reply can be consumed.
     */
    private fun launchRequest(
        message: PhoneMessage,
        linkId: Long,
        timeout: Duration,
        onSent: (Long) -> Unit,
        done: (Outcome) -> Unit,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome = try {
                Outcome.Replied(
                    link.request(message, onLink = linkId, timeout = timeout) { id ->
                        // Posted before the envelope is written, so ahead of its reply in the inbox.
                        inbox.post { if (currentLink == linkId) onSent(id) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName)
            }
            post { if (currentLink == linkId) done(outcome) }
        }
    }

    /** Sends [message] with no request ID on [linkId], in the consumer's order; a failure is logged. */
    private fun launchSend(message: PhoneMessage, linkId: Long) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.send(message, onLink = linkId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("a chat ${message.javaClass.simpleName} was not sent: ${e.javaClass.simpleName}")
            }
        }
    }

    // Publishing

    private fun afterStep(drained: Boolean) {
        if (planNeeded) {
            planNeeded = false
            plan()
        }
        val now = nowMillis()
        val interval = timeouts.snapshotInterval.inWholeMilliseconds
        if (drained || now - publishedAtMillis >= interval) publish(now) else scheduleAt(publishedAtMillis + interval)
        tracker.nextDeadlineMillis()?.let(::scheduleAt)
    }

    private fun publish(now: Long) {
        publishedAtMillis = now
        aiFlow.value = ai
        listFlow.value = when {
            currentLink != null && !ai -> ChatListState.AiUnavailable
            listFailed -> ChatListState.Failed(retryable = true)
            else -> entries?.let { ChatListState.Loaded(it, refreshing = listInFlight) } ?: ChatListState.Loading
        }
        val states = tracker.states.value
        rowsFlow.value = entries.orEmpty().map { entry ->
            val chatID = entry.chat.id
            val state = states[chatID] ?: AgentState.UNKNOWN
            val mark = seen.seen(chatID)
            ChatRow(entry, state, unread = state == AgentState.DONE || (mark != null && entry.chat.lastModifiedMillis > mark))
        }
        for ((chatID, flow) in conversationFlows) {
            val next = conversationState(chatID, now)
            if (next != flow.value) flow.value = next
        }
        heldTranscripts = conversations.values.count { it.transcript != null }
        tooLargeSnapshot = oversized.tooLarge
    }

    private fun conversationState(chatID: String, now: Long): ConversationState {
        if (chatID in oversized.tooLarge) return ConversationState.TooLarge
        val conversation = conversations[chatID]
        val transcript = conversation?.transcript
        if (conversation != null && transcript != null) {
            if (conversation.snapshotOf !== transcript || conversation.snapshotVersion != transcript.version) {
                val interval = timeouts.snapshotInterval.inWholeMilliseconds
                // A new history shows at once; a streaming transcript at most once per interval.
                if (conversation.snapshotOf !== transcript || now - conversation.snapshotAtMillis >= interval) {
                    conversation.snapshot = transcript.messages()
                    conversation.snapshotOf = transcript
                    conversation.snapshotVersion = transcript.version
                    conversation.snapshotAtMillis = now
                    conversation.snapshots++
                } else {
                    scheduleAt(conversation.snapshotAtMillis + interval)
                }
            }
            val linkId = currentLink
            val current = linkId != null && conversation.historyLink == linkId
            val deleted = isDeleted(chatID)
            return ConversationState.Shown(
                messages = conversation.snapshot,
                version = conversation.snapshots,
                stale = !current || conversation.lostAppend,
                deleted = deleted,
                sendable = current && ai && !deleted,
            )
        }
        return when {
            currentLink != null && !ai -> ConversationState.Failed(retryable = false)
            chatID in failedThisLink -> ConversationState.Failed(retryable = true)
            else -> ConversationState.Loading
        }
    }

    /** Deleted only on an authoritative list's word (`AM:2877-2900`): an empty history never means it. */
    private fun isDeleted(chatID: String): Boolean = entries?.none { it.chat.id == chatID } ?: false

    /** Has the consumer run [onTimer] at [atMillis] (or sooner, if already due sooner). */
    private fun scheduleAt(atMillis: Long) {
        if (timer?.isActive == true && timerAtMillis <= atMillis) return
        timer?.cancel()
        timerAtMillis = atMillis
        timer = scope.launch {
            delay((atMillis - nowMillis()).coerceAtLeast(0))
            post { onTimer() }
        }
    }

    private fun onTimer() {
        timerAtMillis = Long.MAX_VALUE
        tracker.tick()
        publish(nowMillis())
    }

    override fun toString(): String = "ChatRepository(link=$currentLink, chats=${entries?.size}, open=${open.size})"

    companion object {
        /** iOS's notice for an open chat found deleted (`AM:438-442`). */
        const val DELETED_CHAT_NOTICE =
            "This chat was deleted on your Mac. You can keep reading it until you leave, but nothing new can be sent."
    }
}

private val MacMessage.Error.isAiUnavailable: Boolean get() = code == "aiUnavailable"
