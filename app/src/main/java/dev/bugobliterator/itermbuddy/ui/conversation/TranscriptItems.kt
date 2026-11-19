package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.AttachmentKind
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.EchoState
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.Subpart

/** The typing indicator's key; never a message `uniqueID` (those are uppercase UUIDs). */
const val TYPING_ITEM_KEY = "typing-indicator"

/**
 * One row of the conversation, with what it shows already rendered (off the main thread). [key]
 * is the message's `uniqueID` (a later duplicate gets a suffix), stable across streamed deltas, so
 * the list keeps its place and a delta recomposes only its row. Items compare by identity: a row
 * whose message and mentions did not change is the same instance ([TranscriptItemCache]).
 * [toString] shows no content.
 */
sealed interface TranscriptItem {
    val key: String

    /**
     * A message bubble: the owner's ([fromUser]) right, the agent's left. [echo] is set only for a
     * message sent from this phone: where it stands.
     */
    class Bubble(
        val message: ChatMessage,
        val fromUser: Boolean,
        val blocks: List<BubbleBlock>,
        override val key: String,
        val echo: EchoState? = null,
    ) : TranscriptItem {
        override fun toString(): String = "Bubble(key=$key, fromUser=$fromUser, blocks=${blocks.size}, echo=$echo)"
    }

    /** A centred notice: a client-local notice or a watcher event (`IOS/Views/MessageBubbleView.swift:19-26`). */
    class Notice(val message: ChatMessage, val blocks: List<BubbleBlock>, override val key: String) : TranscriptItem {
        override fun toString(): String = "Notice(key=$key, blocks=${blocks.size})"
    }

    /** An interactive request, shown read-only in this phase. */
    class RequestCard(val message: ChatMessage, val card: RequestCardContent, override val key: String) : TranscriptItem {
        override fun toString(): String = "RequestCard(key=$key, lines=${card.lines.size})"
    }

    /** The agent is working: three dots after the last message. */
    data object Typing : TranscriptItem {
        override val key: String = TYPING_ITEM_KEY
    }
}

/**
 * Whether [item] offers "Delete From Here": the owner's own bubbles only, as iOS and the Mac window
 * offer it (`IOS/Views/MessageBubbleView.swift:40-47`), not notices or request cards, and not a send
 * of this phone's whose echo has not come back (the Mac does not hold it yet; "Not delivered" has
 * its own Delete). The screen offers it only when the conversation's
 * [ConversationContent.deletesFromHere] holds too.
 */
fun offersDeleteFromHere(item: TranscriptItem): Boolean =
    item is TranscriptItem.Bubble && item.fromUser && (item.echo == null || item.echo == EchoState.DELIVERED)

/** The typing indicator shows while the agent is working (not while it waits on the owner). */
fun showsTyping(state: AgentState?): Boolean = state == AgentState.WORKING

/**
 * The rows for [messages] (a [dev.bugobliterator.itermbuddy.core.chat.ConversationState.Shown]
 * snapshot), with no earlier rows to reuse: hidden messages left out, then [TranscriptItem.Typing]
 * when [typing]. See [TranscriptItemCache.items].
 */
fun transcriptItems(
    messages: List<ChatMessage>,
    typing: Boolean,
    resolutions: Map<String, MentionResolution> = emptyMap(),
    echoes: Map<String, EchoState> = emptyMap(),
): List<TranscriptItem> = TranscriptItemCache().items(messages, typing, resolutions, echoes)

/**
 * Builds the conversation's rows, reusing the previous ones: a message that is the same instance
 * as before (the transcript keeps every message a delta did not touch) and none of whose mentions
 * changed resolution keeps its row instance, so a streamed delta rebuilds, and Compose recomposes,
 * only its own bubble. Classifies as iOS does (`IOS/Views/MessageBubbleView.swift:19-26`):
 * interactive requests are cards, client-local notices and watcher events centred notices, the
 * rest bubbles. Single-threaded: one cache per collector, off the main thread.
 */
class TranscriptItemCache {
    private class Built(val message: ChatMessage, val item: TranscriptItem, val mentions: Set<String>, val echo: EchoState?)

    private var previous: Map<String, Built> = emptyMap()
    private var previousResolutions: Map<String, MentionResolution> = emptyMap()
    private var built: MutableList<ChatMessage> = ArrayList()

    /**
     * The rows for [messages]; [echoes] (`ConversationState.Shown.echoes`) marks the messages sent
     * from this phone. A row is reused only when its message, the resolutions of its mentions and
     * its echo state are unchanged.
     */
    fun items(
        messages: List<ChatMessage>,
        typing: Boolean,
        resolutions: Map<String, MentionResolution>,
        echoes: Map<String, EchoState> = emptyMap(),
    ): List<TranscriptItem> {
        val next = HashMap<String, Built>(messages.size * 2)
        val items = ArrayList<TranscriptItem>(messages.size + 1)
        val seen = HashMap<String, Int>()
        for (message in messages) {
            if (message.hidden) continue
            val repeats = seen.merge(message.uniqueID, 1, Int::plus)!!
            val key = if (repeats == 1) message.uniqueID else "${message.uniqueID}#$repeats"
            // A duplicate ID is a Mac row of its own, never this phone's echo.
            val echo = if (repeats == 1) echoes[message.uniqueID] else null
            val prior = previous[key]
            val reusable = prior != null && prior.message === message && prior.echo == echo &&
                (resolutions === previousResolutions || prior.mentions.all { resolutions[it] == previousResolutions[it] })
            val entry = if (reusable) prior else build(message, key, resolutions, echo).also { if (prior?.message !== message) built += message }
            next[key] = entry
            items += entry.item
        }
        if (typing) items += TranscriptItem.Typing
        previous = next
        previousResolutions = resolutions
        return items
    }

    /** The messages rebuilt as new (not for a resolution) since the last call, for mention resolving. */
    fun takeBuilt(): List<ChatMessage> {
        val taken = built
        built = ArrayList()
        return taken
    }

    private fun build(message: ChatMessage, key: String, resolutions: Map<String, MentionResolution>, echo: EchoState?): Built {
        val asked = HashSet<String>()
        val resolve: (String) -> MentionResolution? = { identifier ->
            asked += identifier
            resolutions[identifier]
        }
        val item = when {
            message.isInteractiveRequest -> TranscriptItem.RequestCard(message, requestCard(message, resolve), key)
            message.body is MessageBody.ClientNotice || message.body is MessageBody.WatcherEvent ->
                TranscriptItem.Notice(message, bubbleBlocks(message, resolve), key)
            else -> TranscriptItem.Bubble(message, message.author == Participant.USER, bubbleBlocks(message, resolve), key, echo)
        }
        return Built(message, item, asked, echo)
    }

    override fun toString(): String = "TranscriptItemCache(items=${previous.size})"
}

/**
 * A cheap stand-in for a message's content, for telling a grown message from the same one
 * rebuilt: the body's case, how many parts it has and how long its text is (attachment bytes are
 * counted by size, never compared). Equal for a history that replaced the transcript with the
 * same message and for a row rebuilt for a mention's resolution; different after a streamed delta.
 */
data class ContentSignature(val case: Class<*>, val parts: Int, val length: Long)

/** [message]'s [ContentSignature]. */
fun contentSignature(message: ChatMessage): ContentSignature {
    val body = message.body
    return when (body) {
        is MessageBody.PlainText -> ContentSignature(body.javaClass, 1, body.text.length.toLong())
        is MessageBody.Markdown -> ContentSignature(body.javaClass, 1, body.text.length.toLong())
        is MessageBody.Multipart -> ContentSignature(body.javaClass, body.parts.size, body.parts.sumOf(::partLength))
        else -> ContentSignature(body.javaClass, 0, 0)
    }
}

private fun partLength(part: Subpart): Long = when (part) {
    is Subpart.PlainText -> part.text.length.toLong()
    is Subpart.Markdown -> part.text.length.toLong()
    is Subpart.Context -> part.text.length.toLong()
    is Subpart.AttachmentPart -> when (val kind = part.attachment.kind) {
        is AttachmentKind.Code -> kind.text.length.toLong()
        is AttachmentKind.StatusUpdate -> kind.update.exploded.size.toLong() + kind.display.length
        is AttachmentKind.File -> kind.size.toLong()
        is AttachmentKind.FileId -> 1
    }
}

/**
 * The end of the transcript that scrolling follows: [newest] is the newest row other than the
 * typing dots, [newestSignature] its message's [ContentSignature], and whether the dots show.
 */
class TranscriptTail(val newest: TranscriptItem?, val typing: Boolean) {
    val newestSignature: ContentSignature? = newest?.shownMessage?.let(::contentSignature)

    override fun toString(): String = "TranscriptTail(newest=${newest?.key}, typing=$typing)"
}

/** [items]' [TranscriptTail]. */
fun transcriptTail(items: List<TranscriptItem>): TranscriptTail =
    TranscriptTail(items.lastOrNull { it !== TranscriptItem.Typing }, items.lastOrNull() === TranscriptItem.Typing)

/** What the transcript does when its rows change. */
enum class FollowAction {
    /** Nothing at the end changed, or the change is not news (the dots alone while scrolled up). */
    NONE,

    /** Scroll to the newest row: it was in view, or the owner just sent a message. */
    PIN,

    /** Show the "New messages ↓" pill: the owner is reading further up. */
    PILL,
}

/** The message a row shows, or null for the typing dots. */
private val TranscriptItem.shownMessage: ChatMessage?
    get() = when (this) {
        is TranscriptItem.Bubble -> message
        is TranscriptItem.Notice -> message
        is TranscriptItem.RequestCard -> message
        TranscriptItem.Typing -> null
    }

/**
 * How the transcript follows a change from [before] to [after], given whether it was at the
 * bottom before the change. The newest row changed when its key did or its [ContentSignature]
 * did (a history that replaced the transcript with the same messages, or a row rebuilt for a
 * mention's resolution, is not news; a streamed delta is). At the bottom, any change at the end
 * (a new row, a streamed delta, the dots coming or going) keeps the newest row in view; a new
 * message being sent from this phone (its echo, [EchoState.SENDING]) scrolls to it from anywhere,
 * as sending does in a chat, but the owner's words typed on the Mac, or an old send put back by
 * a history, do not; scrolled up, a new or grown newest row shows the pill instead, and the dots
 * alone do nothing. The first rows ([before] null) need nothing: the list starts at the bottom.
 */
fun followAction(wasAtBottom: Boolean, before: TranscriptTail?, after: TranscriptTail): FollowAction {
    if (before == null) return FollowAction.NONE
    val newest = after.newest
    val newKey = before.newest?.key != newest?.key
    val newestChanged = newKey || before.newestSignature != after.newestSignature
    if (!newestChanged && before.typing == after.typing) return FollowAction.NONE
    val ownNewMessage = newKey && newest is TranscriptItem.Bubble && newest.echo == EchoState.SENDING
    return when {
        wasAtBottom || ownNewMessage -> FollowAction.PIN
        newestChanged -> FollowAction.PILL
        else -> FollowAction.NONE
    }
}
