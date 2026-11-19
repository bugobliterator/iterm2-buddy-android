package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.NotSendableReason
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A chat's Send as the composer follows it: [sending] while one is out; [finished] counts the
 * sends that ended, and [outcome] is the latest one's. Carries no content.
 */
data class DraftSend(val sending: Boolean = false, val finished: Long = 0, val outcome: SendOutcome? = null)

/**
 * What the owner is typing to each chat, by chat ID, in memory for the process's life: drafts
 * survive rotation and leaving the conversation, but are never written to saved state (a draft
 * may hold anything the owner typed). App-scoped, cleared on unpair. Thread-safe. [toString]
 * shows a count only.
 */
class ChatDrafts {
    private val drafts = ConcurrentHashMap<String, MentionDraft>()

    private val sends = ConcurrentHashMap<String, MutableStateFlow<DraftSend>>()

    /** [chatID]'s Send: whether one is out, and how the latest ended. */
    fun sendState(chatID: String): StateFlow<DraftSend> = sendFlow(chatID).asStateFlow()

    private fun sendFlow(chatID: String): MutableStateFlow<DraftSend> = sends.computeIfAbsent(chatID) { MutableStateFlow(DraftSend()) }

    /**
     * Sends [chatID]'s draft with [send] in [scope] (app-scoped: a rotation or leaving the screen
     * does not cut it short) and clears the draft from the outcome ([send]'s rules), not from a
     * composable that may be gone by then. False, and nothing sent, while a send for [chatID] is
     * still out: the composer that comes back after a rotation cannot publish it twice.
     */
    fun sendInBackground(scope: CoroutineScope, chatID: String, send: suspend (MentionDraft) -> SendOutcome): Boolean {
        val state = sendFlow(chatID)
        while (true) {
            val now = state.value
            if (now.sending) return false
            if (state.compareAndSet(now, now.copy(sending = true))) break
        }
        scope.launch {
            var outcome: SendOutcome = SendOutcome.NotSendable(NotSendableReason.SEND_FAILED)
            try {
                outcome = send(chatID, send)
            } finally {
                state.update { DraftSend(sending = false, finished = it.finished + 1, outcome = outcome) }
            }
        }
        return true
    }

    fun get(chatID: String): MentionDraft = drafts[chatID] ?: MentionDraft.EMPTY

    fun set(chatID: String, draft: MentionDraft) {
        if (draft.text.isEmpty()) drafts.remove(chatID) else drafts[chatID] = draft
    }

    fun clear() {
        drafts.clear()
        // A send still out ends on its own; its outcome clears nothing (the draft it took is gone).
        sends.values.forEach { flow -> flow.update { it.copy(outcome = null) } }
    }

    override fun toString(): String = "ChatDrafts(chats=${drafts.size})"
}

/**
 * Sends [chatID]'s draft with [send] (`ChatRepository.send`) and clears it only when it was
 * [SendOutcome.Sent]: refused, empty or failed, the owner's words stay to send again. A draft the
 * owner changed while the send was out is left as it now is.
 */
suspend fun ChatDrafts.send(chatID: String, send: suspend (MentionDraft) -> SendOutcome): SendOutcome {
    val draft = get(chatID)
    val outcome = send(draft)
    if (outcome == SendOutcome.Sent && get(chatID) == draft) set(chatID, MentionDraft.EMPTY)
    return outcome
}
