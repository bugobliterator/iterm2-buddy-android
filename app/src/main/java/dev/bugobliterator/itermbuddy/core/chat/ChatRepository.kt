package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.RequestNotSentException
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * The Mac's list, in its order (most recent first); [refreshing] while a refetch is out;
     * [refreshFailed] when the latest refetch failed and these are the last entries read.
     */
    data class Loaded(val entries: List<ChatListEntry>, val refreshing: Boolean, val refreshFailed: Boolean = false) :
        ChatListState {
        override fun toString(): String = "Loaded(entries=${entries.size}, refreshing=$refreshing, refreshFailed=$refreshFailed)"
    }

    /** No list could be fetched or read; [retryable] offers Try again (pull to refresh). */
    data class Failed(val retryable: Boolean) : ChatListState

    /** AI is turned off on the Mac, which refuses every chat request (`HB:2276-2330`). */
    data object AiUnavailable : ChatListState
}

/** One conversation as the conversation screen shows it. */
sealed interface ConversationState {
    data object Loading : ConversationState

    /**
     * The transcript: [messages] is a read-only snapshot (the transcript itself stays on the chat
     * consumer); [version] is unique to that snapshot within the process. [stale]: served from the
     * cache while a refetch is out, after it failed, or after an append was lost (iOS's soft
     * banner, `AM:2934-3010`). [deleted]: missing from the Mac's list (`AM:2877-2900`), shown with
     * [ChatRepository.DELETED_CHAT_NOTICE]. [sendable]: connected, AI available, not deleted, and
     * a history for this chat received on the current link.
     *
     * Equality compares [version] and the flags only, never the messages: the version names the
     * snapshot, so a republish of the same snapshot is equal at no cost.
     */
    class Shown(
        val messages: List<ChatMessage>,
        val version: Long,
        val stale: Boolean,
        val deleted: Boolean,
        val sendable: Boolean,
    ) : ConversationState {
        override fun equals(other: Any?): Boolean =
            other is Shown && other.version == version && other.stale == stale && other.deleted == deleted &&
                other.sendable == sendable

        override fun hashCode(): Int = listOf(version, stale, deleted, sendable).hashCode()

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
 * still answers it. The request's outcome only drives the banner and settles the ticket; a
 * request that never reached the Mac ([RequestNotSentException]) settles as answered by an error,
 * so it is never blamed for a link that ends on an oversized frame.
 *
 * **AI off.** The Mac drops every subscription and lists no chats while AI is off (`HB:283-288`,
 * `HB:2250-2269`), so list values are not applied then: the entries, seen marks, Done latches and
 * transcripts are kept, read-only, until AI is back and the list is fetched again.
 *
 * **Histories and deliveries.** A history replaces the chat's transcript when the chat is open or
 * cached and is dropped otherwise (watched chats cost no transcript memory); it seeds the activity
 * tracker. A delivery merges only into a transcript built from a history on the current link
 * (`AM:4296-4313`), and always feeds the tracker. A lost append (a delivery over the raw cap, an
 * undecodable raw delivery, or an undecodable delta) marks a held transcript stale and resubscribes
 * its chat once per link.
 *
 * **Too large.** A history over the raw chat cap makes its chat too large for the process (its
 * subscription still feeds its live state); a link ended by a frame over the Noise cap names the
 * one unanswered subscribe as too large, or makes the unanswered ones suspects, retried first and
 * alone ([OversizedChats], [ChatSubscriptions]). Only [retry] subscribes a too-large chat again,
 * or the chat leaving the list and coming back. The list request is unanswered until its reply
 * lands through the sink (a timeout does not answer it). A link ended that way with only the list
 * unanswered marks the list too large for the process: it is not fetched again until [refresh].
 * With subscribes unanswered too, the list and those chats (even one alone) become suspects, and
 * the next link fetches the list first and alone.
 *
 * **Unread.** A chat is unread when the Mac's `lastModified` is past the owner's mark
 * ([ChatSeenStore], Mac values only, never phone time), or its turn ended unseen (Done). A chat's
 * first appearance sets its mark (one store write per list). Marked seen: on opening; at each list
 * value while it is visible; at the first list value past an own send ([noteOwnSend]); and at a
 * list value that moved while the chat's newest visible delivery on this link is the owner's
 * (typed on the Mac, `CLM:122-129`).
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

        /** The transcript the last snapshot was taken from, its version, and when (null: none yet). */
        var snapshotOf: Transcript? = null
        var snapshotOfVersion = -1L
        var snapshotAtMillis: Long? = null
        var snapshot: List<ChatMessage> = emptyList()

        /** [ConversationState.Shown.version] of [snapshot]. */
        var snapshotSerial = 0L
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

    /** [entries] by chat ID. */
    private val entryByID = HashMap<String, ChatListEntry>()

    /** A whole list arrived on the current link (subscriptions plan with it only then). */
    private var listOnThisLink = false

    /** The latest list request failed, or the latest list could not be read. */
    private var listFailed = false
    private var listInFlight = false

    /**
     * Process-wide: a link ended on a frame over the Noise cap while only the list request was
     * out, so the list is too large to read. Not fetched again until [refresh] or [clear].
     */
    private var listTooLarge = false

    /**
     * The list request on this link was sent and its reply has not landed through the sink (nor
     * did it fail with an error or never get sent). Unlike [listInFlight], a timeout does not
     * clear it: a frame over the Noise cap can take longer than [ChatTimeouts.list] to arrive.
     */
    private var listUnanswered = false

    /**
     * Process-wide: the list was unanswered, beside unanswered subscribes, when a link ended on a
     * frame over the Noise cap, so it may be the culprit. The next link requests it first and
     * alone (no subscribe is planned while it is unanswered); landing clears this, ending a link
     * alone latches [listTooLarge].
     */
    private var listSuspect = false

    /** Bumped by every list applied, so a failed request does not hide a list that landed meanwhile. */
    private var listGeneration = 0L
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

    /** Open chats that left the list: forgotten once closed, unless listed again. */
    private val leftWhileOpen = HashSet<String>()
    private var planNeeded = false
    private var publishedAtMillis: Long? = null
    private var timer: Job? = null
    private var timerAtMillis = Long.MAX_VALUE

    /** Names the scheduled timer; a timer's post that is no longer current changes no schedule. */
    private var timerSerial = 0L

    /** Process-wide counter for [ConversationState.Shown.version]. */
    private var snapshotSerials = 0L
    private var cost = LinkCost(0, 0)
    private var lastWanted: List<String> = emptyList()

    private class LinkCost(val linkId: Long, val upAtMillis: Long) {
        var histories = 0
        var bytes = 0L
        var subscribes = 0
        var allWantedLogged = false
    }

    /**
     * The conversation flows handed out, by chat ID. Never removed while the process is paired, so
     * a screen keeps the instance it was given across closes, reopens and evictions; [clear] resets
     * them to Loading. They grow with the chats the owner opens, a handful per session.
     */
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

    /**
     * [chatID]'s conversation, for a chat the owner [open]s: Loading until its history lands, and
     * Loading again once it is closed and its transcript no longer kept. The same instance for the
     * life of the pairing (see [conversationFlows]). Thread-safe.
     */
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
        visibilityChanged()
    }

    /** Whether the app is in the foreground (false while locked or backgrounded): no chat is visible otherwise. */
    fun setForeground(foreground: Boolean) = post {
        this.foreground = foreground
        visibilityChanged()
    }

    /** Try again for [chatID]: subscribes it once more on this link, too large or failed included. */
    fun retry(chatID: String) = post {
        failedThisLink -= chatID
        conversations[chatID]?.lostAppend = false
        // Offline, the next link plans it: only the too-large mark has to go.
        if (currentLink == null) oversized.tryAgain(chatID) else subscriptions.tryAgain(chatID)
        planNeeded = true
    }

    /** Refetches the list (pull to refresh, or Try again after a failed list); lifts a too-large list. */
    fun refresh() = post {
        listTooLarge = false
        if (ai) requestList()
    }

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
        entryByID[chatID]?.let { sendMarks[chatID] = it.chat.lastModifiedMillis }
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
        listInFlight = false
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
        val listOut = messageTooLarge && listUnanswered
        if (listOut && subscriptions.outstanding.isEmpty()) {
            // Only the list was out: it is what ended the link. Fetching it on every link would
            // end every link (and the live view with it).
            listTooLarge = true
            listSuspect = false
            listFailed = true
            log("chat link $linkId ended on a frame over the Noise cap with only the list out: the list is too large")
        } else if (listOut) {
            // The list or a subscribe: the list goes first and alone on the next link.
            listSuspect = true
            log("chat link $linkId ended on a frame over the Noise cap with the list out: the list is a suspect")
        }
        listUnanswered = false
        val before = oversized.tooLarge
        subscriptions.linkEnded(messageTooLarge, listOut)
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
            is PayloadDecoding.Undecodable -> undecodable(decoded, input.chatID, input.linkId)
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
                listAnswered()
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
        val ticket = requestID?.let(subscribeTickets::get)
        ticket?.let { subscriptions.settled(it, SubscribeOutcome.ANSWERED) }
        // A history in flight when AI went off: the Mac has dropped that subscription again
        // (HB:2250-2269), so it is not subscribed, and is planned again when AI is back.
        if (ai) subscriptions.subscribed(chatID, consumeLateTicket = ticket == null)
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

    /**
     * An append for [chatID] was lost. Only a held transcript is hurt by it (a watched chat keeps
     * none): it is marked stale and the chat subscribed once more on this link for a fresh history.
     */
    private fun lostAppend(chatID: String) {
        val conversation = conversations[chatID]
        if (conversation?.transcript == null) {
            log("chat $chatID lost an append; no transcript is held")
            return
        }
        conversation.lostAppend = true
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
                if (ai) {
                    subscriptions.subscribed(chatID, tooLarge = true, consumeLateTicket = ticket == null)
                } else {
                    oversized.foundTooLarge(chatID)
                }
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
                if (input.case == LIST_REPLY_CASE) listAnswered()
                listFailed = true
            }
        }
    }

    private fun undecodable(failure: PayloadDecoding.Undecodable, chatID: String?, linkId: Long) {
        log("a chat ${failure.case} did not decode: ${failure.reason}")
        val ticket = failure.requestID?.let(subscribeTickets::get)
        when {
            ticket != null -> {
                // The reply arrived whole: it answers the subscribe, but the chat has no history.
                subscriptions.settled(ticket, SubscribeOutcome.ANSWERED)
                val conversation = conversations[ticket.chatID]
                if (conversation?.historyLink != linkId) failedThisLink += ticket.chatID
                planNeeded = true
            }
            failure.case == "delivery" -> if (chatID != null) lostAppend(chatID)
            failure.case == LIST_REPLY_CASE || failure.case == "chatListChanged" -> {
                if (failure.case == LIST_REPLY_CASE) listAnswered()
                listFailed = true
            }
        }
    }

    // The list

    private fun applyList(newEntries: List<ChatListEntry>) {
        if (!ai) {
            // HB:283-288: with AI off the Mac lists no chats. That says nothing about which exist:
            // keep the list, marks, latches and transcripts until AI is back.
            log("a chat list of ${newEntries.size} while AI is off: not applied")
            return
        }
        val previous = entries
        setEntries(newEntries)
        listOnThisLink = true
        listFailed = false
        listGeneration++
        previous?.forEach { if (it.chat.id !in entryByID) leftList(it.chat.id) }
        val firstMarks = HashMap<String, Double>()
        for (entry in newEntries) {
            val chatID = entry.chat.id
            leftWhileOpen -= chatID
            val modified = entry.chat.lastModifiedMillis
            val mark = seen.seen(chatID)
            val moved = previousModified[chatID]?.let { modified > it } == true
            val pastSend = sendMarks[chatID]?.let { modified > it } == true
            when {
                mark == null -> firstMarks[chatID] = modified
                isVisible(chatID) -> seen.markSeen(chatID, modified)
                pastSend -> seen.markSeen(chatID, modified)
                moved && newestDeliveryAuthor[chatID] == Participant.USER -> seen.markSeen(chatID, modified)
            }
            if (pastSend) sendMarks -= chatID
            previousModified[chatID] = modified
        }
        // One write for every first appearance (a fresh pairing lists them all at once).
        if (firstMarks.isNotEmpty()) seen.markSeenAll(firstMarks)
        planNeeded = true
    }

    private fun setEntries(newEntries: List<ChatListEntry>?) {
        entries = newEntries
        entryByID.clear()
        newEntries?.forEach { entryByID[it.chat.id] = it }
    }

    /**
     * [chatID] left the list. An open chat keeps its activity and mark while the owner reads it
     * (it is latched deleted), and is forgotten when closed unless listed again.
     */
    private fun leftList(chatID: String) {
        if (chatID in open) leftWhileOpen += chatID else forgetChat(chatID)
    }

    /** A chat that is gone: its cached transcript, activity, marks and too-large mark go (`AM:2242-2254`). */
    private fun forgetChat(chatID: String) {
        leftWhileOpen -= chatID
        tracker.forget(chatID)
        seen.forget(chatID)
        oversized.tryAgain(chatID)
        sendMarks -= chatID
        previousModified -= chatID
        newestDeliveryAuthor -= chatID
        closed -= chatID
        conversations -= chatID
    }

    /** `chatCreated`: inserted at the top only if missing (`AM:2587-2591`); with no list yet the next list brings it. */
    private fun chatCreated(entry: ChatListEntry) {
        val current = entries ?: return
        if (!ai || entry.chat.id in entryByID) return
        setEntries(listOf(entry) + current)
        leftWhileOpen -= entry.chat.id
        if (seen.seen(entry.chat.id) == null) seen.markSeen(entry.chat.id, entry.chat.lastModifiedMillis)
        previousModified[entry.chat.id] = entry.chat.lastModifiedMillis
        planNeeded = true
    }

    /**
     * The list reply arrived whole through the sink (decoded, undecodable or over the chat cap):
     * the list did not end a link.
     */
    private fun listAnswered() {
        listUnanswered = false
        if (listSuspect) {
            listSuspect = false
            log("the chat list answered: no longer a suspect")
        }
        planNeeded = true
    }

    /**
     * Fetches the list on the current link. Accepted, as on iOS: a request sent just before the
     * Mac's `aiAvailabilityChanged(false)` can come back refused or empty; that is handled as AI off.
     */
    private fun requestList() {
        val linkId = currentLink ?: return
        if (listInFlight) return
        if (listTooLarge) {
            log("the chat list is too large to fetch; waiting for a refresh")
            listFailed = true
            return
        }
        listInFlight = true
        listUnanswered = true
        listFailed = false
        val generation = listGeneration
        launchRequest(PhoneMessage.ListChatsAndSessions, linkId, timeouts.list, onSent = {}) { outcome ->
            listInFlight = false
            when (outcome) {
                is Outcome.Replied -> when (val reply = outcome.reply) {
                    is MacMessage.ChatPayloadTaken -> Unit
                    is MacMessage.Error -> if (reply.isAiUnavailable) {
                        listUnanswered = false
                        planNeeded = true
                        aiOff()
                    } else {
                        log("the chat list request was refused: ${reply.code}")
                        listUnanswered = false
                        planNeeded = true
                        listFailed = true
                    }
                    else -> log("unexpected reply to the chat list request: ${reply.javaClass.simpleName}")
                }
                is Outcome.NotSent -> {
                    log("the chat list request failed: $outcome")
                    listUnanswered = false
                    planNeeded = true
                    if (listGeneration == generation) listFailed = true
                }
                // A timeout leaves the list unanswered: its reply may still be arriving.
                is Outcome.Failed -> {
                    log("the chat list request failed: $outcome")
                    if (listGeneration == generation) listFailed = true
                }
            }
        }
    }

    // Subscriptions

    private fun plan() {
        val linkId = currentLink ?: return
        // A suspect list goes first and alone: nothing else is out until it is answered.
        if (listSuspect && listUnanswered) return
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
            is Outcome.NotSent -> {
                // The Mac never saw it: nothing of it can still arrive, and it cannot have ended a link.
                log("chat $chatID subscribe was not sent: ${outcome.reason}")
                subscriptions.settled(ticket, SubscribeOutcome.ERROR)
                if (chatID !in subscriptions.subscribedChats) failedThisLink += chatID
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
        if (chatID in leftWhileOpen) {
            // Deleted while open: readable only until the owner leaves it (AM:441-442).
            forgetChat(chatID)
        } else if (conversations[chatID]?.transcript != null) {
            closed += chatID
            while (closed.size > budgets.closedTranscripts) {
                val evicted = closed.first()
                closed -= evicted
                conversations -= evicted
            }
        } else {
            conversations -= chatID
        }
        planNeeded = true
    }

    private fun isVisible(chatID: String): Boolean = foreground && visibleChat == chatID

    private fun visibilityChanged() {
        val shown = visibleChat.takeIf { foreground }
        tracker.visibilityChanged(shown)
        if (shown != null) ownerSaw(shown)
    }

    /** The owner sees [chatID] now: its mark moves to the Mac's latest value and Done ends. */
    private fun ownerSaw(chatID: String) {
        entryByID[chatID]?.let { seen.markSeen(chatID, it.chat.lastModifiedMillis) }
        tracker.markRead(chatID)
    }

    // Unpair

    private fun clearNow() {
        // The barrier is taken when the consumer runs this, not when clear() was called: every
        // input already in the inbox was consumed before it, and every later one names a link up
        // to newestLink (dropped) or a newer link, which can only come from a new pairing because
        // the client numbers links in order and the old pairing's links have all been seen here.
        clearedThrough = maxOf(newestLink, currentLink ?: 0)
        log("chat state cleared through link $clearedThrough")
        currentLink = null
        subscriptions = ChatSubscriptions(oversized)
        for (chatID in tracker.states.value.keys) tracker.forget(chatID)
        setEntries(null)
        listOnThisLink = false
        listFailed = false
        listInFlight = false
        listTooLarge = false
        listUnanswered = false
        listSuspect = false
        subscribeTickets.clear()
        failedThisLink.clear()
        open.clear()
        closed.clear()
        conversations.clear()
        leftWhileOpen.clear()
        conversationFlows.values.forEach { it.value = ConversationState.Loading }
        visibleChat = null
        tracker.visibilityChanged(null)
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
        timerSerial++
        oversized.clear()
        seen.clear()
        planNeeded = false
        publish(nowMillis())
    }

    // Requests

    private sealed interface Outcome {
        class Replied(val reply: MacMessage) : Outcome

        /** It failed after it may have reached the Mac (a timeout, the connection closed). */
        class Failed(val reason: String) : Outcome {
            override fun toString(): String = reason
        }

        /** It never reached the Mac ([RequestNotSentException]). */
        class NotSent(val reason: String) : Outcome {
            override fun toString(): String = "not sent: $reason"
        }
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
        // An outcome reached before the launch returns (a request refused at once, say "not
        // connected" while the link's LinkDown is already queued) is settled here, on the
        // consumer, before anything queued behind; a later one is posted.
        val handoff = AtomicInteger(LAUNCHING)
        val early = AtomicReference<Outcome?>(null)
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
            } catch (e: RequestNotSentException) {
                Outcome.NotSent(e.javaClass.simpleName)
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName)
            }
            early.set(outcome)
            if (!handoff.compareAndSet(LAUNCHING, FINISHED_EARLY)) post { if (currentLink == linkId) done(outcome) }
        }
        if (!handoff.compareAndSet(LAUNCHING, LAUNCHED)) {
            val outcome = checkNotNull(early.get())
            if (currentLink == linkId) done(outcome)
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
        // A request refused at once settles during plan() and may free a slot: plan again. Each
        // pass starts or settles something, so this ends (a chat is not planned twice on a link).
        var passes = 0
        while (planNeeded && passes++ < MAX_PLAN_PASSES) {
            planNeeded = false
            plan()
        }
        val now = nowMillis()
        val interval = timeouts.snapshotInterval.inWholeMilliseconds
        val last = publishedAtMillis
        if (drained || last == null || now - last >= interval) publish(now) else scheduleAt(last + interval)
        tracker.nextDeadlineMillis()?.let(::scheduleAt)
    }

    private fun publish(now: Long) {
        publishedAtMillis = now
        aiFlow.value = ai
        val listed = entries
        listFlow.value = when {
            currentLink != null && !ai -> ChatListState.AiUnavailable
            listed == null -> if (listFailed) ChatListState.Failed(retryable = true) else ChatListState.Loading
            else -> ChatListState.Loaded(listed, refreshing = listInFlight, refreshFailed = listFailed)
        }
        val states = tracker.states.value
        rowsFlow.value = listed.orEmpty().map { entry ->
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
            if (conversation.snapshotOf !== transcript || conversation.snapshotOfVersion != transcript.version) {
                val interval = timeouts.snapshotInterval.inWholeMilliseconds
                val last = conversation.snapshotAtMillis
                // A new history shows at once; a streaming transcript at most once per interval.
                if (conversation.snapshotOf !== transcript || last == null || now - last >= interval) {
                    conversation.snapshot = transcript.messages()
                    conversation.snapshotOf = transcript
                    conversation.snapshotOfVersion = transcript.version
                    conversation.snapshotAtMillis = now
                    conversation.snapshotSerial = ++snapshotSerials
                } else {
                    scheduleAt(last + interval)
                }
            }
            val linkId = currentLink
            val current = linkId != null && conversation.historyLink == linkId
            val deleted = isDeleted(chatID)
            return ConversationState.Shown(
                messages = conversation.snapshot,
                version = conversation.snapshotSerial,
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
    private fun isDeleted(chatID: String): Boolean = entries != null && chatID !in entryByID

    /** Has the consumer run [onTimer] at [atMillis] (or sooner, if already due sooner). */
    private fun scheduleAt(atMillis: Long) {
        if (timer?.isActive == true && timerAtMillis <= atMillis) return
        timer?.cancel()
        timerAtMillis = atMillis
        val serial = ++timerSerial
        timer = scope.launch {
            delay((atMillis - nowMillis()).coerceAtLeast(0))
            post { onTimer(serial) }
        }
    }

    private fun onTimer(serial: Long) {
        // A superseded timer's post still ticks (harmless), but leaves the current schedule alone.
        if (serial == timerSerial) timerAtMillis = Long.MAX_VALUE
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

/** [ChatRepository]'s request hand-off: the launch has not returned yet / has / the request finished first. */
private const val LAUNCHING = 0
private const val LAUNCHED = 1
private const val FINISHED_EARLY = 2

/** A bound on [ChatRepository]'s plan passes per step; a leftover plan runs at the next step. */
private const val MAX_PLAN_PASSES = 64

/** The payload case of the `listChatsAndSessions` reply. */
private const val LIST_REPLY_CASE = "chatsAndSessions"

private val MacMessage.Error.isAiUnavailable: Boolean get() = code == "aiUnavailable"
