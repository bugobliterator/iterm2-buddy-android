package dev.bugobliterator.itermbuddy.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupWatch
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.chats.ChatListActions
import dev.bugobliterator.itermbuddy.ui.chats.ChatListContent
import dev.bugobliterator.itermbuddy.ui.chats.NewChatStatus
import dev.bugobliterator.itermbuddy.ui.conversation.ChatDrafts
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationActions
import dev.bugobliterator.itermbuddy.ui.conversation.ConversationContent
import kotlinx.coroutines.flow.StateFlow

/**
 * What the chat destinations of the home read and call: app-scoped flows and owners
 * ([dev.bugobliterator.itermbuddy.HomeGraph] provides them), so each destination collects only
 * its own flows and the home's other screens do not recompose with every chat change. Nothing
 * here is composition state.
 */
interface ChatsHome {
    /** The Chats root's content. */
    val chatList: StateFlow<ChatListContent>

    /** The chat search, app-scoped. */
    val chatQuery: StateFlow<String>

    val listActions: ChatListActions

    /** Whether AI is on on the Mac (New Chat shows the AI-off panel otherwise). */
    val aiAvailable: StateFlow<Boolean>

    val newChatStatus: StateFlow<NewChatStatus>

    fun createChat(mode: NewChatMode)

    fun retryCreateChat()

    fun dismissCreateChatFailure()

    /** The chat icons' decode cache. */
    val icons: ChatIconCache<ImageBitmap>

    /** The conversations' inline images' decode cache. */
    val images: ChatIconCache<ImageBitmap>

    /** The composer drafts, by chat. */
    val drafts: ChatDrafts

    /** An open conversation's shared content; null until the first is computed. */
    fun conversation(chatID: String): StateFlow<ConversationContent?>

    fun conversationActions(chatID: String): ConversationActions

    /** The composer's Send for [chatID]. */
    suspend fun send(chatID: String, draft: MentionDraft): SendOutcome

    /** The watch of a workgroup view on the navigator's stacks; null once it is popped. */
    fun workgroupWatch(workgroupID: String): WorkgroupWatch?

    /** The session tree now (a member tap reads it), or null before one loaded. */
    fun currentTree(): SessionTree?
}
