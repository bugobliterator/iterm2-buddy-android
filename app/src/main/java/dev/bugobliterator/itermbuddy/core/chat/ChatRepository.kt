package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.RequestNotSentException
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Where a message the owner sent from this phone stands. [SENDING]: published, its echo not back
 * yet. [NOT_DELIVERED]: the send failed, no echo came within [ChatTimeouts.echo], or a history
 * that replaced the transcript lacked it; shown with Retry and Delete (an Android addition: iOS
 * leaves a silent phantom, `AM:3120-3135`). [DELIVERED]: the Mac's `delivery` with its
 * `uniqueID` came back (`HB:1812-1823`), or a history held it.
 */
enum class EchoState { SENDING, NOT_DELIVERED, DELIVERED }

/** Why a chat takes no message now. */
enum class NotSendableReason {
    /** No link to the Mac. */
    NOT_CONNECTED,

    /** AI is turned off on the Mac, which drops a publish (`HB:2289-2293`). */
    AI_UNAVAILABLE,

    /** Missing from the Mac's list (`AM:2877-2900`); nothing new can be sent (`AM:3087-3091`). */
    DELETED,

    /** No history for the chat on this link yet: its echo could not come back (it comes through the subscription). */
    NOT_LOADED,

    /** The chat's history is too large to open on this phone. */
    TOO_LARGE,

    /** The chat's history could not be loaded on this link (Try again). */
    LOAD_FAILED,

    /** The publish could not be written to the link: nothing reached the Mac, and the draft is the only copy. */
    SEND_FAILED,

    /** Retry named a message that is not one of this phone's sends marked not delivered. */
    NOT_RETRYABLE,
}

/** What [ChatRepository.send] did. Carries no content. */
sealed interface SendOutcome {
    /** Published; its echo shows as [EchoState.SENDING]. The composer clears its draft. */
    data object Sent : SendOutcome

    /** Nothing but whitespace: nothing sent (`AM:3087-3091`). */
    data object Empty : SendOutcome

    /** Not sent, for [reason]; the composer keeps its draft. */
    data class NotSendable(val reason: NotSendableReason) : SendOutcome
}

/**
 * Whether the composer warns that sending cancels a pending request: the chat is
 * [AgentState.WAITING] and its newest visible message [ChatMessage.isAbandonableRequest] (a
 * classic command approval or an enable-orchestration request, `CS:192-237`, `CA:1289-1292`). An
 * `executingCommand` notice after it is newer (the approved command already runs and is not
 * killed), and select-session and workgroup-permission requests are not abandoned by typing.
 */
fun warnsOfPendingRequest(activity: AgentState?, messages: List<ChatMessage>): Boolean =
    activity == AgentState.WAITING && messages.lastOrNull { !it.hidden }?.isAbandonableRequest == true

/**
 * One conversation as the conversation screen shows it. [whyNotSendable] is why the composer
 * takes no message now, in every state; null only in a [Shown] that is sendable.
 */
sealed interface ConversationState {
    val whyNotSendable: NotSendableReason?

    /** No transcript yet: [whyNotSendable] says whether a link, AI or the chat itself is missing too. */
    data class Loading(override val whyNotSendable: NotSendableReason = NotSendableReason.NOT_LOADED) : ConversationState

    /**
     * The transcript: [messages] is a read-only snapshot (the transcript itself stays on the chat
     * consumer); [version] is unique to that snapshot within the process. [stale]: served from the
     * cache while a refetch is out, after it failed, or after an append was lost (iOS's soft
     * banner, `AM:2934-3010`). [deleted]: missing from the Mac's list (`AM:2877-2900`), shown with
     * [ChatRepository.DELETED_CHAT_NOTICE]. [sendable]: connected, AI available, not deleted, and
     * a history for this chat received on the current link.
     *
     * [echoes]: the messages in [messages] sent from this phone, by `uniqueID`, with where each
     * stands. [whyNotSendable]: null exactly when [sendable].
     *
     * Equality compares [version], the flags and [echoes] only, never the messages: the version
     * names the snapshot, so a republish of the same snapshot is equal at no cost.
     */
    class Shown(
        val messages: List<ChatMessage>,
        val version: Long,
        val stale: Boolean,
        val deleted: Boolean,
        val sendable: Boolean,
        val echoes: Map<String, EchoState> = emptyMap(),
        override val whyNotSendable: NotSendableReason? = if (sendable) null else NotSendableReason.NOT_LOADED,
    ) : ConversationState {
        override fun equals(other: Any?): Boolean =
            other is Shown && other.version == version && other.stale == stale && other.deleted == deleted &&
                other.sendable == sendable && other.echoes == echoes && other.whyNotSendable == whyNotSendable

        override fun hashCode(): Int = listOf(version, stale, deleted, sendable, echoes, whyNotSendable).hashCode()

        override fun toString(): String =
            "Shown(messages=${messages.size}, version=$version, stale=$stale, deleted=$deleted, sendable=$sendable, " +
                "echoes=${echoes.size}, whyNotSendable=$whyNotSendable)"
    }

    /** The history could not be loaded; [retryable] offers Try again (false while AI is off). */
    data class Failed(
        val retryable: Boolean,
        override val whyNotSendable: NotSendableReason =
            if (retryable) NotSendableReason.LOAD_FAILED else NotSendableReason.AI_UNAVAILABLE,
    ) : ConversationState

    /** The history is over the raw chat cap, or ended a link by itself: not loaded (owner decision). */
    data object TooLarge : ConversationState {
        override val whyNotSendable: NotSendableReason get() = NotSendableReason.TOO_LARGE
    }
}

/** What [ChatRepository.create] did. Carries no content. */
sealed interface CreateOutcome {
    /** The Mac created [chatID] (`chatCreated`); it is in the list, and the caller opens it. */
    data class Created(val chatID: String) : CreateOutcome

    /** Not created; [retryable] unless AI is off on the Mac (Try again then cannot help). */
    data class Failed(val retryable: Boolean) : CreateOutcome
}

/** What [ChatRepository.deleteFrom] did. */
enum class DeleteFromOutcome {
    /** Truncated here and `deleteMessages` sent. */
    DELETED,

    /** The Mac's revision is below 12: it would ignore the request (`AM:2678-2680`). */
    MAC_TOO_OLD,

    /** No link to the Mac. */
    NOT_CONNECTED,

    /** AI is off on the Mac, which drops `deleteMessages` (`HB:2289-2293`, `HB:2325`). */
    AI_UNAVAILABLE,

    /** The chat is not listed (deleted) or has no history on this link. */
    NOT_LOADED,

    /** No message with that ID in the transcript. */
    NOT_FOUND,

    /** Not one of the owner's own messages. */
    NOT_OWN_MESSAGE,

    /**
     * A message of this phone's in the suffix is still [EchoState.SENDING]: the Mac appends a
     * publish asynchronously, so it could land after the truncate and come back.
     */
    SEND_IN_FLIGHT,
}

/** A chat-list row: the Mac's entry, the agent's state and whether it moved since the owner looked. */
data class ChatRow(val entry: ChatListEntry, val state: AgentState, val unread: Boolean)

/** A chat link up as the home sees it: its [generation] ([ChatRepository.linkGeneration]) and the hello's [aiAvailable]. */
data class ChatLinkUp(val generation: Long, val aiAvailable: Boolean)

/**
 * The chat list, its rows and AI availability as one value, published together, so a reader
 * never sees a new list with the previous rows. [toString] shows counts only.
 */
data class ChatListSnapshot(val list: ChatListState, val rows: List<ChatRow>, val aiAvailable: Boolean) {
    override fun toString(): String = "ChatListSnapshot(list=$list, rows=${rows.size}, aiAvailable=$aiAvailable)"
}

/**
 * The repository's bounds, all injectable: request bounds as iOS's (`AM:2223`, `AM:2939`), the
 * send echo and delete waits used by later actions, how often an open transcript's snapshot may
 * be republished while it streams ([snapshotInterval]), and the activity tracker's timings
 * ([ChatActivityTracker]).
 */
data class ChatTimeouts(
    val list: Duration = 15.seconds,
    val subscribe: Duration = 15.seconds,
    val create: Duration = 15.seconds,
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
 * unanswered makes it a suspect (the frame may have been anything early on the link); the next
 * link fetches it first and alone, and if it ends that link alone too it is too large for the
 * process: not fetched again until [refresh]. With subscribes unanswered too, those chats (even
 * one alone) become suspects beside it. A suspect list that times out stops holding the chats
 * back on its link.
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
 * **Sending** ([send], `AM:3087-3135`) needs a history for the chat on the current link: the echo
 * comes back only through the subscription. The owner's message is published fire-and-forget on
 * that link with a new uppercase `uniqueID` and a whole-millisecond date, and its optimistic echo
 * appended at once, [EchoState.SENDING]. The Mac's `delivery` with that `uniqueID` replaces it
 * (`HB:1812-1823`). It is [EchoState.NOT_DELIVERED] when the write fails, when no echo comes within
 * [ChatTimeouts.echo] (the Mac swallows publish errors, `HB:1822`, and drops a publish while AI is
 * off, `HB:2289-2293`), or when a history that replaces the transcript lacks it while it is
 * overdue or came on another link than the publish: it is then put back at the end of the new
 * transcript (still [EchoState.SENDING] when neither holds). A publish that could not be written
 * at all ([RequestNotSentException]: nothing reached the Mac) takes its echo and its own-send mark
 * back, so the draft the composer keeps is the only copy (owner decision). [retrySend] publishes
 * a copy with a new `uniqueID` (the Mac appends without deduping, `CB:146-160`) in its place;
 * [discardUnsent] drops it. A late echo of an original withdrawn by Retry or Delete still upserts:
 * the original reappears, delivered, at the end of the transcript, after the copy, and only the
 * next history restores the Mac's order.
 *
 * **Managing chats** (`AM:2582-2765`). [create] asks the Mac for a chat (`createChat`, a request in
 * its own coroutine); its `chatCreated` reply inserts the entry at the top if missing, and the
 * caller opens it. [delete] removes the row, its transcript, activity and marks at once and sends
 * `deleteChat`; since the Mac pushes the list only when it changes and drops the delete silently
 * while AI is off (`HB:2289-2293`), a list that does not come within [ChatTimeouts.deleteListWait]
 * is fetched (an Android addition), and an unsolicited `error` while a delete is pending (a
 * refused delete, `HB:458-470`) fetches it at once; its text is never shown or logged. [setMuted]
 * flips the entry at once and sends `setChatMuted`; the Mac persists it without a push
 * (`CompanionChatMuteRegistry.swift:28-38`), so the flip is held over every list until one carries
 * it (a list in flight before the flip would undo it), or until the link ends or [clear]. A list
 * that still holds a chat deleted moments ago (sent before the Mac deleted it) does not end the
 * delete's wait. [deleteFrom] truncates an open transcript from one of the owner's own messages at
 * once and sends `deleteMessages` (Mac revision 12 and later), never while a send of this phone's
 * in the suffix is still out; the `messagesRemoved` echo removes nothing more.
 *
 * [clear] (unpair) drops everything, ignores every input from links up to then, and clears the
 * [OversizedChats] and [seen] on the consumer, so no mark lands after the wipe.
 *
 * @param nowMillis a monotonic clock for timers and rate limits only; never compared with the Mac's times.
 * @param log diagnostics: chat IDs, counts, sizes, cases and exception classes only, never content.
 * @param newUuid the `uniqueID` of each message sent (sent uppercase).
 * @param wallClockMillis the phone's wall clock: a sent message's `sentDate`, nothing else.
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
    private val newUuid: () -> UUID = UUID::randomUUID,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
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

    private val snapshotFlow = MutableStateFlow(ChatListSnapshot(ChatListState.Loading, emptyList(), aiAvailable = true))

    /** [list], [rows] and [aiAvailable] as one value, published at the same time as each of them. */
    val listSnapshot: StateFlow<ChatListSnapshot> = snapshotFlow.asStateFlow()

    private val linkGenerationFlow = MutableStateFlow(0L)

    /**
     * Bumped on every chat link up the repository accepts (0 before the first). A reader sees a
     * change even when it missed the link's states in between, so it never misses a reconnect.
     */
    val linkGeneration: StateFlow<Long> = linkGenerationFlow.asStateFlow()

    private val lastLinkUpFlow = MutableStateFlow<ChatLinkUp?>(null)

    /**
     * The latest chat link up the repository accepted, with the hello's `aiAvailable`: set as
     * [linkGeneration] is bumped (after it), so a reader woken by a link up reads that link's AI
     * flag, which [aiAvailable] and [listSnapshot] carry only from the next publish. The home
     * chooses its default tab from the first one. Null before the first link.
     */
    val lastLinkUp: StateFlow<ChatLinkUp?> = lastLinkUpFlow.asStateFlow()

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

    /** The list request on this link went out as a suspect: ending this link alone latches it. */
    private var listRequestedAsSuspect = false

    /**
     * Process-wide: oversized link ends with the list unanswered since a list reply last landed
     * (or [refresh], [clear]). At [LIST_SUSPICIONS_TO_LATCH] the list is latched too large whatever
     * else was out, so a slow link where the suspect list times out and a subscribe is always out
     * when its frame lands cannot loop forever.
     */
    private var listOversizedEnds = 0

    /**
     * A request on this link could not be sent ([RequestNotSentException]): the link is gone and
     * its LinkDown is on the way. Nothing more is planned on it, and nothing is marked failed.
     */
    private var linkGone = false

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
    private val sendMarks = HashMap<String, SendMark>()

    /** An own send's mark: the chat's `lastModified` before it, which send set it, and the mark it replaced. */
    private class SendMark(val modified: Double, val uniqueID: String?, val previous: SendMark?)

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

    /** A message sent from this phone: its echo, where it stands, and when it is due back. */
    private class Echo(val message: ChatMessage, val text: String, var state: EchoState, val dueAtMillis: Long, val linkId: Long) {
        /** Taken out of the transcript by Retry or Delete; back in if its echo comes after all. */
        var withdrawn = false
    }

    /** This phone's sends by chat, then by `uniqueID`, in sending order. */
    private val echoes = HashMap<String, LinkedHashMap<String, Echo>>()

    /** Chats deleted on this link with no list since: a list fetch is due if none comes. */
    private val pendingDeletes = HashSet<String>()

    /** Mute flips sent on this link and not yet carried by a list: held over every list until then. */
    private val pendingMutes = HashMap<String, Boolean>()
    private var deleteWait: Job? = null

    /** Names the scheduled delete wait; a superseded one's post does nothing. */
    private var deleteWaitSerial = 0L

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
        conversationFlows.computeIfAbsent(chatID) { MutableStateFlow(ConversationState.Loading()) }

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
        listOversizedEnds = 0
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
    internal fun noteOwnSend(chatID: String) = post { noteOwnSendNow(chatID) }

    private fun noteOwnSendNow(chatID: String, uniqueID: String? = null) {
        entryByID[chatID]?.let { sendMarks[chatID] = SendMark(it.chat.lastModifiedMillis, uniqueID, sendMarks[chatID]) }
    }

    /** [uniqueID]'s send never left the phone: its mark goes, wherever it sits in the chat's chain of marks. */
    private fun forgetOwnSend(chatID: String, uniqueID: String) {
        val mark = sendMarks[chatID] ?: return
        val rest = mark.without(uniqueID)
        if (rest === mark) return
        if (rest == null) sendMarks -= chatID else sendMarks[chatID] = rest
    }

    /** This chain with [uniqueID]'s mark unlinked; the same instance when it holds none. */
    private fun SendMark.without(uniqueID: String): SendMark? {
        if (this.uniqueID == uniqueID) return previous
        val below = previous ?: return this
        val rest = below.without(uniqueID)
        return if (rest === below) this else SendMark(modified, this.uniqueID, rest)
    }

    /**
     * Sends [draft] to [chatID] (`AM:3087-3135`): refused unless the chat is sendable (see
     * [ConversationState.Shown.whyNotSendable]); [SendOutcome.Empty] when its text is only
     * whitespace and newlines once trimmed; otherwise published with its echo appended, and
     * [SendOutcome.Sent] once the publish is handed to the link ([NotSendableReason.SEND_FAILED],
     * the echo not delivered, when it cannot be). Suspends the caller only, never the consumer.
     */
    suspend fun send(chatID: String, draft: MentionDraft): SendOutcome = onConsumer(SEND_WITHOUT_CONSUMER, SEND_THAT_THREW) { result ->
        val refused = whyNotSendable(chatID)
        val text = draft.serialised().trim()
        when {
            refused != null -> result.complete(SendOutcome.NotSendable(refused))
            text.isEmpty() -> result.complete(SendOutcome.Empty)
            else -> publishNow(chatID, text, result)
        }
    }

    /**
     * Sends again a message of this phone's marked [EchoState.NOT_DELIVERED]: a copy with a new
     * `uniqueID` takes its place at the end of the transcript.
     */
    suspend fun retrySend(chatID: String, uniqueID: String): SendOutcome = onConsumer(SEND_WITHOUT_CONSUMER, SEND_THAT_THREW) { result ->
        val echo = echoes[chatID]?.get(uniqueID.uppercase(Locale.ROOT))
        val refused = whyNotSendable(chatID)
        when {
            echo == null || echo.state != EchoState.NOT_DELIVERED || echo.withdrawn ->
                result.complete(SendOutcome.NotSendable(NotSendableReason.NOT_RETRYABLE))
            refused != null -> result.complete(SendOutcome.NotSendable(refused))
            else -> {
                withdraw(chatID, echo)
                publishNow(chatID, echo.text, result, replacing = echo)
            }
        }
    }

    /** Drops a message of this phone's marked [EchoState.NOT_DELIVERED] from the transcript. */
    fun discardUnsent(chatID: String, uniqueID: String) = post {
        val echo = echoes[chatID]?.get(uniqueID.uppercase(Locale.ROOT))
        if (echo != null && echo.state == EchoState.NOT_DELIVERED && !echo.withdrawn) withdraw(chatID, echo)
    }

    /**
     * Creates a chat (`AM:2582-2602`): `createChat` titled "Orchestrator" or "New Chat" by [mode],
     * on the current link, bounded by [ChatTimeouts.create]. [CreateOutcome.Created] once the
     * Mac's `chatCreated` is in the list (inserted at the top if missing); the caller opens the
     * conversation. Not connected, a timeout or a refusal is retryable; AI off is not (and is
     * not asked). Suspends the caller only, never the consumer.
     */
    suspend fun create(mode: NewChatMode): CreateOutcome =
        onConsumer(CREATE_WITHOUT_CONSUMER, CREATE_WITHOUT_CONSUMER) { result -> createNow(mode, result) }

    /**
     * Deletes [chatID] (the owner confirmed): the row, its transcript, activity and marks go at
     * once (`AM:2605-2632`), and `deleteChat` is sent; see the class notes for the list fetch that
     * follows when the Mac pushes none. Offline, the row goes and the next link's list brings it
     * back (iOS removes it and the send fails).
     */
    fun delete(chatID: String) = post { deleteNow(chatID) }

    /**
     * Mutes or unmutes [chatID] (`AM:2748-2765`): its entry flips at once and `setChatMuted` is
     * sent (not AI-gated on the Mac, `HB:481-486`); the flip is held over lists that do not carry
     * it yet. Nothing changes while there is no link, since nothing would reach the Mac.
     */
    fun setMuted(chatID: String, muted: Boolean) = post { setMutedNow(chatID, muted) }

    /**
     * Delete From Here (`AM:2637-2669`): removes [messageID]'s message and every one after it from
     * [chatID]'s transcript at once and sends their IDs in `deleteMessages`. Only for the owner's
     * own message ([Participant.USER]), only when the Mac's revision is 12 or later (an older Mac
     * ignores it, `AM:2678-2680`), when the chat is sendable (a history on this link, AI on,
     * listed), and when no send of this phone's in the suffix is still [EchoState.SENDING]. Says
     * what it did.
     */
    suspend fun deleteFrom(chatID: String, messageID: String): DeleteFromOutcome =
        onConsumer(DeleteFromOutcome.NOT_CONNECTED, DeleteFromOutcome.NOT_CONNECTED) { result ->
            result.complete(deleteFromNow(chatID, messageID))
        }

    /**
     * Runs [block] on the consumer and returns what it completes; a dead consumer does nothing and
     * answers [stopped]; [block] throwing answers [failed].
     */
    private suspend fun <T> onConsumer(stopped: T, failed: T, block: (CompletableDeferred<T>) -> Unit): T {
        if (inbox.stopped) return stopped
        val result = CompletableDeferred<T>()
        // The consumer may stop after the check: the dropped post answers then.
        post(onDropped = { result.complete(stopped) }) {
            try {
                block(result)
            } catch (e: Exception) {
                result.complete(failed)
                throw e
            }
        }
        return result.await()
    }

    // Managing chats

    private fun createNow(mode: NewChatMode, result: CompletableDeferred<CreateOutcome>) {
        val linkId = currentLink
        if (linkId == null) {
            result.complete(CreateOutcome.Failed(retryable = true))
            return
        }
        if (!ai) {
            // HB:2325: the Mac refuses it; iOS hides New Chat behind the AI-off panel.
            result.complete(CreateOutcome.Failed(retryable = false))
            return
        }
        val title = if (mode == NewChatMode.Orchestrator) ORCHESTRATOR_TITLE else NEW_CHAT_TITLE
        log("creating a chat: ${if (mode == NewChatMode.Orchestrator) "orchestrator" else "session-bound"}")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome = try {
                Outcome.Replied(link.request(PhoneMessage.CreateChat(title, mode), onLink = linkId, timeout = timeouts.create) {})
            } catch (e: CancellationException) {
                result.complete(CreateOutcome.Failed(retryable = true))
                throw e
            } catch (e: RequestNotSentException) {
                Outcome.NotSent(e.javaClass.simpleName)
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName)
            }
            post(onDropped = { result.complete(CreateOutcome.Failed(retryable = true)) }) {
                result.complete(createOutcome(outcome, linkId))
            }
        }
    }

    private fun createOutcome(outcome: Outcome, linkId: Long): CreateOutcome {
        val reply = (outcome as? Outcome.Replied)?.reply
        return when {
            reply is MacMessage.ChatCreated -> {
                // The reply reached the sink ahead of this outcome and was inserted there (chatCreated).
                log("chat ${reply.entry.chat.id} created")
                CreateOutcome.Created(reply.entry.chat.id)
            }
            reply is MacMessage.Error && reply.isAiUnavailable -> {
                if (currentLink == linkId) aiOff()
                CreateOutcome.Failed(retryable = false)
            }
            reply is MacMessage.Error -> {
                log("creating a chat was refused: ${reply.code}")
                CreateOutcome.Failed(retryable = true)
            }
            reply != null -> {
                log("unexpected reply to creating a chat: ${reply.javaClass.simpleName}")
                CreateOutcome.Failed(retryable = true)
            }
            else -> {
                log("creating a chat failed: $outcome")
                CreateOutcome.Failed(retryable = true)
            }
        }
    }

    private fun deleteNow(chatID: String) {
        val listed = entries
        if (listed != null && chatID in entryByID) setEntries(listed.filter { it.chat.id != chatID })
        // At once, as iOS does: no typing or activity lingers until the Mac's list comes.
        tracker.forget(chatID)
        failedThisLink -= chatID
        leftList(chatID)
        // No longer wanted: the plan unsubscribes it (iOS tears its watch down too).
        planNeeded = true
        val linkId = currentLink
        if (linkId == null) {
            log("chat $chatID deleted while not connected: the next list restores it")
            return
        }
        log("chat $chatID: deleting")
        launchSend(PhoneMessage.DeleteChat(chatID), linkId)
        pendingDeletes += chatID
        deleteWait?.cancel()
        val serial = ++deleteWaitSerial
        deleteWait = scope.launch {
            delay(timeouts.deleteListWait)
            post { if (serial == deleteWaitSerial && pendingDeletes.isNotEmpty()) refetchAfterDelete("no list came") }
        }
    }

    /** A delete is pending and the Mac refused it or pushed no list: the list is fetched now. */
    private fun refetchAfterDelete(why: String) {
        log("fetching the chat list after ${pendingDeletes.size} deletes: $why")
        endDeleteWait()
        if (ai) requestList()
    }

    private fun endDeleteWait() {
        pendingDeletes.clear()
        deleteWait?.cancel()
        deleteWait = null
        deleteWaitSerial++
    }

    private fun setMutedNow(chatID: String, muted: Boolean) {
        val linkId = currentLink
        if (linkId == null) {
            log("chat $chatID mute not changed: not connected")
            return
        }
        val listed = entries
        if (listed != null && entryByID[chatID]?.muted != muted && chatID in entryByID) {
            setEntries(listed.map { if (it.chat.id == chatID) it.copy(muted = muted) else it })
        }
        pendingMutes[chatID] = muted
        log("chat $chatID: muted $muted")
        launchSend(PhoneMessage.SetChatMuted(chatID, muted), linkId)
    }

    private fun deleteFromNow(chatID: String, messageID: String): DeleteFromOutcome {
        val revision = revisionFlow.value ?: 0
        if (revision < MESSAGE_DELETION_REVISION) {
            log("chat $chatID: Delete From Here needs Mac revision $MESSAGE_DELETION_REVISION, not $revision")
            return DeleteFromOutcome.MAC_TOO_OLD
        }
        val refused = whyNotSendable(chatID)
        if (refused != null) {
            log("chat $chatID: Delete From Here refused: $refused")
            return when (refused) {
                NotSendableReason.NOT_CONNECTED -> DeleteFromOutcome.NOT_CONNECTED
                NotSendableReason.AI_UNAVAILABLE -> DeleteFromOutcome.AI_UNAVAILABLE
                else -> DeleteFromOutcome.NOT_LOADED
            }
        }
        val linkId = checkNotNull(currentLink)
        val conversation = checkNotNull(conversations[chatID])
        val transcript = checkNotNull(conversation.transcript)
        val index = transcript.indexOf(messageID)
        if (index < 0) return DeleteFromOutcome.NOT_FOUND
        val message = transcript[index]
        if (message.author != Participant.USER || message.hidden) {
            log("chat $chatID: Delete From Here is only for the owner's own messages")
            return DeleteFromOutcome.NOT_OWN_MESSAGE
        }
        // A suffix, in the transcript's order: the Mac truncates from the earliest it holds (CLM:253-281).
        val ids = transcript.suffixFrom(messageID)
        val sent = echoes[chatID]
        // The Mac appends a publish after it returns (HB:1812-1823): one still out could land after
        // the truncate and come back, so wait for it.
        if (sent != null && ids.any { sent[it]?.state == EchoState.SENDING }) {
            log("chat $chatID: Delete From Here waits for a send in flight")
            return DeleteFromOutcome.SEND_IN_FLIGHT
        }
        transcript.removeAll(ids.toSet())
        if (sent != null) {
            // Sends of this phone's in the suffix go with it: none is put back by a later history.
            for (id in ids) if (sent.remove(id) != null) forgetOwnSend(chatID, id)
            if (sent.isEmpty()) echoes -= chatID
        }
        // Shown at once, not after the streaming rate limit.
        conversation.snapshotAtMillis = null
        log("chat $chatID: deleting ${ids.size} messages from ${message.uniqueID}")
        launchSend(PhoneMessage.DeleteMessages(chatID, ids), linkId)
        return DeleteFromOutcome.DELETED
    }

    // Sending

    /** Why [chatID] takes no message now, or null when it does. */
    private fun whyNotSendable(chatID: String): NotSendableReason? {
        val linkId = currentLink ?: return NotSendableReason.NOT_CONNECTED
        if (!ai) return NotSendableReason.AI_UNAVAILABLE
        if (isDeleted(chatID)) return NotSendableReason.DELETED
        val conversation = conversations[chatID]
        if (conversation?.transcript == null || conversation.historyLink != linkId) return NotSendableReason.NOT_LOADED
        return null
    }

    /**
     * Publishes [text] to [chatID], whose transcript is current ([whyNotSendable] said so): the
     * user message iOS builds (`AM:3100-3118`), its echo appended, and the publish sent on the
     * current link in its own coroutine, which completes [result].
     */
    private fun publishNow(chatID: String, text: String, result: CompletableDeferred<SendOutcome>, replacing: Echo? = null) {
        val linkId = checkNotNull(currentLink)
        val transcript = checkNotNull(conversations[chatID]?.transcript)
        val uniqueID = newUuid().toString().uppercase(Locale.ROOT)
        val sentMillis = wallClockMillis()
        val outgoing = UserTextMessage(chatID, text, sentMillis, uniqueID)
        val echo = ChatMessage(chatID, Participant.USER, MessageBody.PlainText(text), sentMillis.toDouble(), uniqueID)
        transcript.applyDelivery(echo, chatID)
        echoes.getOrPut(chatID, ::LinkedHashMap)[uniqueID] =
            Echo(echo, text, EchoState.SENDING, nowMillis() + timeouts.echo.inWholeMilliseconds, linkId)
        noteOwnSendNow(chatID, uniqueID)
        log("chat $chatID: sending $uniqueID")
        val failed = SendOutcome.NotSendable(NotSendableReason.SEND_FAILED)
        val disconnected = SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.send(PhoneMessage.Publish(outgoing), onLink = linkId)
                result.complete(SendOutcome.Sent)
            } catch (e: CancellationException) {
                result.complete(disconnected)
                throw e
            } catch (e: RequestNotSentException) {
                // Nothing reached the Mac: take the echo and its mark back before answering, so
                // the draft the composer keeps is the only copy (owner decision).
                log("chat $chatID: $uniqueID was not sent: ${e.javaClass.simpleName}")
                post(onDropped = { result.complete(disconnected) }) {
                    notSent(chatID, uniqueID, replacing)
                    result.complete(failed)
                }
            } catch (e: Exception) {
                // It may have reached the Mac: the echo stays, and its timeout decides.
                log("chat $chatID: $uniqueID may not have been sent: ${e.javaClass.simpleName}")
                result.complete(SendOutcome.Sent)
            }
        }
    }

    /**
     * [uniqueID]'s publish was never written: its echo leaves the transcript and the tracking, its
     * own-send mark goes, and an original it was retrying ([replacing]) is put back, not delivered.
     */
    private fun notSent(chatID: String, uniqueID: String, replacing: Echo?) {
        val sent = echoes[chatID]
        sent?.remove(uniqueID)
        conversations[chatID]?.transcript?.withdraw(uniqueID)
        forgetOwnSend(chatID, uniqueID)
        if (replacing != null && replacing.withdrawn && replacing.state == EchoState.NOT_DELIVERED) {
            replacing.withdrawn = false
            conversations[chatID]?.transcript?.applyDelivery(replacing.message, chatID)
        }
        if (sent != null && sent.isEmpty()) echoes -= chatID
        // Shown at once, not after the streaming rate limit: the caller is about to keep the draft.
        conversations[chatID]?.snapshotAtMillis = null
    }

    /** Takes [echo] out of [chatID]'s transcript (Retry, Delete); its late echo would bring it back. */
    private fun withdraw(chatID: String, echo: Echo) {
        echo.withdrawn = true
        conversations[chatID]?.transcript?.withdraw(echo.message.uniqueID)
    }

    /** The Mac's echo of one of this phone's sends arrived (a delivery, or a history holding it). */
    private fun echoed(chatID: String, uniqueID: String) {
        val echo = echoes[chatID]?.get(uniqueID) ?: return
        echo.state = EchoState.DELIVERED
        echo.withdrawn = false
    }

    /**
     * A history replaced [chatID]'s transcript (or arrived for a chat with none): sends it holds
     * are delivered; the others not yet delivered are put back at its end, not delivered.
     */
    private fun echoesAfterHistory(chatID: String, history: List<ChatMessage>, linkId: Long, transcript: Transcript?) {
        val sent = echoes[chatID] ?: return
        val held = history.mapTo(HashSet()) { it.uniqueID }
        val now = nowMillis()
        for ((uniqueID, echo) in sent) {
            if (uniqueID in held) {
                echoed(chatID, uniqueID)
            } else if (echo.state != EchoState.DELIVERED && !echo.withdrawn && transcript != null) {
                // On the publish's own link and not yet due, its echo may still be on the way
                // (the Mac appends a publish asynchronously): it stays sending.
                if (echo.linkId != linkId || now >= echo.dueAtMillis) echo.state = EchoState.NOT_DELIVERED
                transcript.applyDelivery(echo.message, chatID)
            }
        }
    }

    /** Marks the sends whose echo is overdue at [now] not delivered. */
    private fun expireEchoes(now: Long) {
        for (sent in echoes.values) {
            for (echo in sent.values) {
                if (echo.state == EchoState.SENDING && now >= echo.dueAtMillis) echo.state = EchoState.NOT_DELIVERED
            }
        }
    }

    /** When the next send still out is due, or null. */
    private fun nextEchoDueMillis(): Long? =
        echoes.values.asSequence().flatMap { it.values.asSequence() }.filter { it.state == EchoState.SENDING }.minOfOrNull { it.dueAtMillis }

    private fun post(onDropped: () -> Unit = {}, block: () -> Unit) {
        inbox.post(onDropped) {
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
        listRequestedAsSuspect = false
        linkGone = false
        subscribeTickets.clear()
        failedThisLink.clear()
        newestDeliveryAuthor.clear()
        cost = LinkCost(up.linkId, nowMillis())
        log("chat link up ${up.linkId}: revision ${up.macRevision}, ai ${up.aiAvailable}")
        linkGenerationFlow.value += 1
        lastLinkUpFlow.value = ChatLinkUp(linkGenerationFlow.value, ai)
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
        if (listOut) listOversizedEnds++
        val onlyTheSuspectList = subscriptions.outstanding.isEmpty() && (listSuspect || listRequestedAsSuspect)
        if (listOut && (onlyTheSuspectList || listOversizedEnds >= LIST_SUSPICIONS_TO_LATCH)) {
            // The suspect list was the only thing out, or has been out at every one of the last
            // oversized ends: it is what ends the links. Fetching it on every link would end
            // every link (and the live view with it). Subscribes out still become suspects below.
            listTooLarge = true
            listSuspect = false
            listFailed = true
            log("chat link $linkId ended on a frame over the Noise cap with only the list out: the list is too large")
        } else if (listOut) {
            // The list, a subscribe, or some other frame that came early on the link: the list
            // goes first and alone on the next link, and is latched only if it ends that one alone.
            listSuspect = true
            log("chat link $linkId ended on a frame over the Noise cap with the list out: the list is a suspect")
        }
        listUnanswered = false
        listRequestedAsSuspect = false
        linkGone = false
        val before = oversized.tooLarge
        subscriptions.linkEnded(messageTooLarge, listOut)
        val named = oversized.tooLarge - before
        if (messageTooLarge) {
            log("chat link $linkId ended on a frame over the Noise cap: too large ${named.joinToString()}; suspects ${oversized.suspects.size}")
        }
        subscriptions = ChatSubscriptions(oversized)
        tracker.onLinkDown()
        // The next link fetches the list anyway, and its list is the Mac's word on mutes.
        endDeleteWait()
        pendingMutes.clear()
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
            is MacMessage.Error -> {
                log("chat error from the Mac: ${message.code}")
                // A refused deleteChat comes back as an unsolicited error (HB:458-470): the list
                // says what the Mac holds now.
                if (requestID == null && pendingDeletes.isNotEmpty()) refetchAfterDelete("an error came")
            }
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
        val conversation = conversations[chatID]
        if (conversation != null) {
            conversation.transcript = Transcript(history.messages, budgets.attachments)
            conversation.historyLink = linkId
            conversation.lostAppend = false
        }
        echoesAfterHistory(chatID, history.messages, linkId, conversation?.transcript)
        planNeeded = true
        noteAllWantedLanded()
    }

    private fun delivery(delivery: MacMessage.Delivery, linkId: Long) {
        val chatID = delivery.chatID
        val message = delivery.message
        if (!message.hidden) {
            newestDeliveryAuthor[chatID] = message.author
            echoed(chatID, message.uniqueID)
        }
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
        setEntries(withPendingMutes(newEntries))
        if (pendingDeletes.isNotEmpty()) {
            // A list without the deleted chats says the Mac deleted them: no fetch of its own. One
            // that still holds them may predate the delete: the wait goes on (a refusal comes as
            // an error and fetches at once).
            pendingDeletes.retainAll(entryByID.keys)
            if (pendingDeletes.isEmpty()) endDeleteWait()
        }
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
            val pastSend = sendMarks[chatID]?.let { modified > it.modified } == true
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
    /**
     * [newEntries] with this link's pending mute flips held over them; a flip the list carries is
     * no longer pending (the Mac persisted it).
     */
    private fun withPendingMutes(newEntries: List<ChatListEntry>): List<ChatListEntry> {
        if (pendingMutes.isEmpty()) return newEntries
        return newEntries.map { entry ->
            val wanted = pendingMutes[entry.chat.id]
            when {
                wanted == null -> entry
                entry.muted == wanted -> {
                    pendingMutes -= entry.chat.id
                    entry
                }
                else -> entry.copy(muted = wanted)
            }
        }
    }

    private fun forgetChat(chatID: String) {
        pendingMutes -= chatID
        leftWhileOpen -= chatID
        tracker.forget(chatID)
        seen.forget(chatID)
        oversized.tryAgain(chatID)
        sendMarks -= chatID
        previousModified -= chatID
        newestDeliveryAuthor -= chatID
        closed -= chatID
        conversations -= chatID
        echoes -= chatID
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
        listOversizedEnds = 0
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
        if (listSuspect) listRequestedAsSuspect = true
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
                    linkGone = true
                }
                // A timeout leaves the list unanswered: its reply may still be arriving.
                is Outcome.Failed -> {
                    log("the chat list request failed: $outcome")
                    if (listGeneration == generation) listFailed = true
                    if (listSuspect) {
                        // Waiting on it any longer would hold every chat back on this link. It
                        // stays unanswered, so if it ends this link alone it is still latched.
                        listSuspect = false
                        planNeeded = true
                        log("the suspect chat list timed out: subscribing meanwhile")
                    }
                }
            }
        }
    }

    // Subscriptions

    private fun plan() {
        val linkId = currentLink ?: return
        // The link is gone (a request could not be sent): its LinkDown is on the way.
        if (linkGone) return
        // A suspect list goes first and alone: nothing else is out until it is answered or times out.
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
                // The link is gone: stop planning on it, and show no failure (the conversation
                // waits for the next link, as on any drop).
                log("chat $chatID subscribe was not sent: ${outcome.reason}")
                subscriptions.settled(ticket, SubscribeOutcome.ERROR)
                linkGone = true
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
                dropSettledEchoes(evicted)
            }
        } else {
            conversations -= chatID
            dropSettledEchoes(chatID)
        }
        planNeeded = true
    }

    /** [chatID]'s transcript is gone: its delivered and withdrawn sends need no tracking. Those not delivered are put back by the next history. */
    private fun dropSettledEchoes(chatID: String) {
        val sent = echoes[chatID] ?: return
        sent.values.removeIf { it.state == EchoState.DELIVERED || it.withdrawn }
        if (sent.isEmpty()) echoes -= chatID
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
        listRequestedAsSuspect = false
        linkGone = false
        listOversizedEnds = 0
        subscribeTickets.clear()
        failedThisLink.clear()
        open.clear()
        closed.clear()
        conversations.clear()
        leftWhileOpen.clear()
        conversationFlows.values.forEach { it.value = ConversationState.Loading(NotSendableReason.NOT_CONNECTED) }
        visibleChat = null
        tracker.visibilityChanged(null)
        newestDeliveryAuthor.clear()
        sendMarks.clear()
        previousModified.clear()
        echoes.clear()
        endDeleteWait()
        pendingMutes.clear()
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
        nextEchoDueMillis()?.let(::scheduleAt)
    }

    private fun publish(now: Long) {
        publishedAtMillis = now
        expireEchoes(now)
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
        snapshotFlow.value = ChatListSnapshot(listFlow.value, rowsFlow.value, ai)
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
            val refused = whyNotSendable(chatID)
            return ConversationState.Shown(
                messages = conversation.snapshot,
                version = conversation.snapshotSerial,
                stale = !current || conversation.lostAppend,
                deleted = isDeleted(chatID),
                sendable = refused == null,
                echoes = shownEchoes(chatID),
                whyNotSendable = refused,
            )
        }
        return when {
            currentLink != null && !ai -> ConversationState.Failed(retryable = false)
            chatID in failedThisLink -> ConversationState.Failed(retryable = true)
            else -> ConversationState.Loading(whyNotSendable(chatID) ?: NotSendableReason.NOT_LOADED)
        }
    }

    /** [chatID]'s sends in its transcript and where each stands, for [ConversationState.Shown.echoes]. */
    private fun shownEchoes(chatID: String): Map<String, EchoState> {
        val sent = echoes[chatID] ?: return emptyMap()
        val shown = HashMap<String, EchoState>(sent.size * 2)
        for ((uniqueID, echo) in sent) if (!echo.withdrawn) shown[uniqueID] = echo.state
        return shown
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

/** The title iOS gives an orchestrator chat it creates, and any other (`AM:2586`). */
private const val ORCHESTRATOR_TITLE = "Orchestrator"
private const val NEW_CHAT_TITLE = "New Chat"

/** The Mac applies `deleteMessages` from this revision (`CPV` `messageDeletionRevision`, `AM:2678-2680`). */
const val MESSAGE_DELETION_REVISION = 12

private val SEND_WITHOUT_CONSUMER: SendOutcome = SendOutcome.NotSendable(NotSendableReason.NOT_CONNECTED)
private val SEND_THAT_THREW: SendOutcome = SendOutcome.NotSendable(NotSendableReason.SEND_FAILED)
private val CREATE_WITHOUT_CONSUMER: CreateOutcome = CreateOutcome.Failed(retryable = true)

/** [ChatRepository]'s request hand-off: the launch has not returned yet / has / the request finished first. */
private const val LAUNCHING = 0
private const val LAUNCHED = 1
private const val FINISHED_EARLY = 2

/** Oversized link ends with the list unanswered after which the list is latched too large. */
private const val LIST_SUSPICIONS_TO_LATCH = 3

/** A bound on [ChatRepository]'s plan passes per step; a leftover plan runs at the next step. */
private const val MAX_PLAN_PASSES = 64

/** The payload case of the `listChatsAndSessions` reply. */
private const val LIST_REPLY_CASE = "chatsAndSessions"

private val MacMessage.Error.isAiUnavailable: Boolean get() = code == "aiUnavailable"
