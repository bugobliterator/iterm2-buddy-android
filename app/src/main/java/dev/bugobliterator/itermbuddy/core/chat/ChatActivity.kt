package dev.bugobliterator.itermbuddy.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a chat's agent is doing, as the chat list shows it. Upstream has no chat-level state on the
 * phone; [ChatActivityTracker] derives it from upstream's inputs.
 */
enum class AgentState {
    /** No authoritative word since the link (or the subscription) went: typing is not re-sent after a drop. */
    UNKNOWN,

    /** No turn in progress, and nothing unseen. */
    IDLE,

    /** A turn is in progress and the agent is not parked on the owner. */
    WORKING,

    /** A turn is in progress and parked on the owner: a request waits, or typing has stayed off. */
    WAITING,

    /** A turn ended while the owner was not looking; latched until read or a new turn starts. */
    DONE,
}

/**
 * The per-chat working / waiting / done state machine.
 *
 * Inputs and their meaning are upstream's:
 * - `turnLifecycle` is the turn boundary (`ChatService.swift:494-505`, `688-695`). `started` is sent
 *   just before typing goes true and `ended` just after it goes false, and a mid-turn park keeps the
 *   turn in progress (`ChatListModel.swift:776-802`).
 * - Agent typing is only the spinner. ChatAgent clears it when a turn parks on an approval (a classic
 *   command, a select-session it implies, or enable orchestration) and sets it again on resume
 *   (`ChatAgent.swift:154-167`, `240-263`, `2187-2214`, `1424-1432`); an orchestration tool call parks
 *   on a workgroup-permission request with typing still on. So a visible [ChatMessage.isBlockingRequest]
 *   latches Waiting whatever typing says, and typing off without a request reads as parked only after
 *   [parkDebounceMillis], which absorbs the end-of-turn and start-of-turn edges.
 * - The subscribe reply is `history` followed, in the same synchronous block, by `typingStatus(true)`
 *   and `turnLifecycle(started)` only when they hold (`CompanionHostBridge.swift:1411-1443`,
 *   `1091-1107`). [onHistory] therefore resets typing and turn, and the snapshot is judged complete at
 *   its `started`, at the next input for any chat, or [snapshotSettleMillis] later on [tick]; until
 *   then the chat keeps showing its previous state, so a reconnect never flashes Done.
 *
 * Done is latched: it holds until [markRead] or a new `started`, and survives [onLinkDown],
 * [onUnsubscribed] and [onHistory]. A chat busy when it was lost from sight whose next history brings
 * no `started` ended its turn unseen, and becomes Done (Idle if visible).
 *
 * Android additions beyond the plain rules: a request latch is cleared by typing only on a real
 * resume edge (typing seen off after the latch, then on), so the snapshot's `typingStatus(true)`
 * cannot clear a typing-on park after a reconnect; the owner's own messages do not clear the latch
 * (a select-session or workgroup request is not abandoned by a typed message, `ChatService.swift:229-237`);
 * and an `executingCommand` notice shows Working while an approved command runs with typing still off
 * (`ChatClient.swift:230-270`), until typing resumes.
 *
 * Not thread-safe: call it from one thread (the chat consumer). [states] may be read anywhere. Time
 * only moves on calls; the owner calls [tick] at [nextDeadlineMillis]. `visible` means the
 * conversation is on screen with the app resumed: a locked phone is not visible. Holds no chat content.
 */
class ChatActivityTracker(
    private val nowMillis: () -> Long,
    private val parkDebounceMillis: Long = 1_500,
    private val snapshotSettleMillis: Long = 1_000,
) {
    private class Chat {
        /** False until an authoritative input (history or a turn event) on the current link. */
        var known = false
        var turnInProgress = false
        var typing = false

        /** When typing (or the agent's last visible activity) last went quiet. */
        var quietSinceMillis = 0L
        var requestLatched = false

        /** Typing was seen off since the latch was set, so typing on is a resume. */
        var resumeArmed = false
        var commandRunning = false
        var done = false

        /** The turn was in progress when the chat was last lost from sight. */
        var busyUnseen = false

        /** A history arrived and its snapshot may still be coming. */
        var snapshotPendingSinceMillis: Long? = null
        var historyVisible = false
        var shown = AgentState.UNKNOWN
    }

    private val chats = HashMap<String, Chat>()
    private val mutableStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())

    /** Every chat the tracker has heard of; an absent chat is [AgentState.UNKNOWN]. */
    val states: StateFlow<Map<String, AgentState>> = mutableStates

    /** A `history` reply replaced [chatID]'s transcript; [newestVisible] seeds the request latch. */
    fun onHistory(chatID: String, newestVisible: ChatMessage?, visible: Boolean) {
        settleSnapshots(except = chatID)
        val chat = chats.getOrPut(chatID, ::Chat)
        val now = nowMillis()
        chat.busyUnseen = chat.busyUnseen || chat.turnInProgress
        chat.known = true
        chat.turnInProgress = false
        chat.typing = false
        chat.quietSinceMillis = now
        val agentNewest = newestVisible?.takeIf { it.author == Participant.AGENT && !it.hidden }
        chat.requestLatched = agentNewest?.isBlockingRequest == true
        chat.resumeArmed = false
        chat.commandRunning = agentNewest?.isExecutingCommand == true
        chat.snapshotPendingSinceMillis = now
        chat.historyVisible = visible
        publish()
    }

    /** A `typingStatus`; the owner's own typing is ignored (`AppModel.swift:4323-4331`). */
    fun onTyping(chatID: String, participant: Participant, isTyping: Boolean) {
        if (participant != Participant.AGENT) return
        val chat = chats.getOrPut(chatID, ::Chat)
        val inSnapshot = isTyping && chat.snapshotPendingSinceMillis != null
        settleSnapshots(except = if (inSnapshot) chatID else null)
        if (isTyping) {
            if (chat.requestLatched && chat.resumeArmed) chat.requestLatched = false
            chat.commandRunning = false
        } else {
            if (chat.requestLatched) chat.resumeArmed = true
            if (chat.typing) chat.quietSinceMillis = nowMillis()
        }
        chat.typing = isTyping
        publish()
    }

    /** A `turnLifecycle`; [TurnEvent.UNKNOWN_FUTURE] is ignored (`AppModel.swift:3664-3671`). */
    fun onTurn(chatID: String, event: TurnEvent, visible: Boolean) {
        if (event == TurnEvent.UNKNOWN_FUTURE) return
        settleSnapshots(except = chatID)
        val chat = chats.getOrPut(chatID, ::Chat)
        chat.known = true
        when (event) {
            TurnEvent.STARTED -> {
                chat.snapshotPendingSinceMillis = null
                chat.busyUnseen = false
                chat.done = false
                if (!chat.turnInProgress && !chat.typing) chat.quietSinceMillis = nowMillis()
                chat.turnInProgress = true
                // After a history, a snapshot without typing(true) means the Mac's typing is off.
                if (chat.requestLatched && !chat.typing) chat.resumeArmed = true
            }
            else -> {
                settle(chat)
                chat.turnInProgress = false
                chat.typing = false
                chat.requestLatched = false
                chat.resumeArmed = false
                chat.commandRunning = false
                chat.busyUnseen = false
                chat.done = !visible
            }
        }
        publish()
    }

    /** A live `delivery` (decoded); only visible agent messages change the state. */
    fun onDelivery(chatID: String, message: ChatMessage) {
        settleSnapshots(except = null)
        if (message.hidden || message.author != Participant.AGENT) return
        val chat = chats.getOrPut(chatID, ::Chat)
        when {
            message.isBlockingRequest -> {
                chat.requestLatched = true
                chat.resumeArmed = !chat.typing
                chat.commandRunning = false
            }
            message.isExecutingCommand -> {
                chat.requestLatched = false
                chat.commandRunning = true
            }
            else -> chat.requestLatched = false
        }
        chat.quietSinceMillis = nowMillis()
        publish()
    }

    /** The link ended: every chat not Done is Unknown, and the busy ones are remembered. */
    fun onLinkDown() {
        for (chat in chats.values) loseSight(chat)
        publish()
    }

    /** [chatID] is no longer subscribed: Unknown, unless Done, and remembered if busy. */
    fun onUnsubscribed(chatID: String) {
        chats[chatID]?.let(::loseSight)
        publish()
    }

    /** The owner has seen [chatID]: Done ends. */
    fun markRead(chatID: String) {
        chats[chatID]?.done = false
        publish()
    }

    /** Drops [chatID] entirely (deleted, or no longer listed). */
    fun forget(chatID: String) {
        chats.remove(chatID)
        publish()
    }

    /** Applies whatever time has made due: a park debounce or a snapshot settle. */
    fun tick() {
        val now = nowMillis()
        for (chat in chats.values) {
            val since = chat.snapshotPendingSinceMillis
            if (since != null && now - since >= snapshotSettleMillis) settle(chat)
        }
        publish()
    }

    /** The earliest time at which [tick] would change something, or null when nothing is timed. */
    fun nextDeadlineMillis(): Long? {
        val now = nowMillis()
        var next: Long? = null
        for (chat in chats.values) {
            val deadline = chat.snapshotPendingSinceMillis?.let { it + snapshotSettleMillis }
                ?: chat.parkDeadline()?.takeIf { it > now }
                ?: continue
            if (next == null || deadline < next) next = deadline
        }
        return next
    }

    private fun Chat.parkDeadline(): Long? =
        if (known && turnInProgress && !requestLatched && !commandRunning && !typing) {
            quietSinceMillis + parkDebounceMillis
        } else {
            null
        }

    private fun loseSight(chat: Chat) {
        chat.busyUnseen = chat.busyUnseen || chat.turnInProgress
        chat.known = false
        chat.turnInProgress = false
        chat.typing = false
        chat.requestLatched = false
        chat.resumeArmed = false
        chat.commandRunning = false
        chat.snapshotPendingSinceMillis = null
    }

    private fun settleSnapshots(except: String?) {
        for ((chatID, chat) in chats) if (chatID != except) settle(chat)
    }

    /** The snapshot is complete without a `started`: a turn that was busy ended unseen. */
    private fun settle(chat: Chat) {
        if (chat.snapshotPendingSinceMillis == null) return
        chat.snapshotPendingSinceMillis = null
        if (chat.busyUnseen && !chat.turnInProgress) chat.done = !chat.historyVisible
        chat.busyUnseen = false
    }

    private fun compute(chat: Chat, now: Long): AgentState = when {
        chat.snapshotPendingSinceMillis != null -> chat.shown
        chat.done && !chat.turnInProgress -> AgentState.DONE
        !chat.known -> AgentState.UNKNOWN
        !chat.turnInProgress -> AgentState.IDLE
        chat.requestLatched -> AgentState.WAITING
        chat.commandRunning || chat.typing -> AgentState.WORKING
        now - chat.quietSinceMillis >= parkDebounceMillis -> AgentState.WAITING
        else -> AgentState.WORKING
    }

    private fun publish() {
        val now = nowMillis()
        var changed = chats.size != mutableStates.value.size
        for ((chatID, chat) in chats) {
            chat.shown = compute(chat, now)
            if (mutableStates.value[chatID] != chat.shown) changed = true
        }
        if (changed) mutableStates.value = chats.mapValues { it.value.shown }
    }

    override fun toString(): String = "ChatActivityTracker(chats=${chats.size})"
}

private val ChatMessage.isExecutingCommand: Boolean
    get() = (body as? MessageBody.ClientNotice)?.action is ClientAction.ExecutingCommand
