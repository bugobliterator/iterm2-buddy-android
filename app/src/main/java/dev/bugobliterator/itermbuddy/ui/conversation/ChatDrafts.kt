package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import java.util.concurrent.ConcurrentHashMap

/**
 * What the owner is typing to each chat, by chat ID, in memory for the process's life: drafts
 * survive rotation and leaving the conversation, but are never written to saved state (a draft
 * may hold anything the owner typed). App-scoped, cleared on unpair. Thread-safe. [toString]
 * shows a count only.
 */
class ChatDrafts {
    private val drafts = ConcurrentHashMap<String, MentionDraft>()

    fun get(chatID: String): MentionDraft = drafts[chatID] ?: MentionDraft.EMPTY

    fun set(chatID: String, draft: MentionDraft) {
        if (draft.text.isEmpty()) drafts.remove(chatID) else drafts[chatID] = draft
    }

    fun clear() = drafts.clear()

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
