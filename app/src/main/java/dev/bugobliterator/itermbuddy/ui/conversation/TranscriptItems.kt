package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.Participant

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

    /** A message bubble: the owner's ([fromUser]) right, the agent's left. */
    class Bubble(val message: ChatMessage, val fromUser: Boolean, val blocks: List<BubbleBlock>, override val key: String) : TranscriptItem {
        override fun toString(): String = "Bubble(key=$key, fromUser=$fromUser, blocks=${blocks.size})"
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
): List<TranscriptItem> = TranscriptItemCache().items(messages, typing, resolutions)

/**
 * Builds the conversation's rows, reusing the previous ones: a message that is the same instance
 * as before (the transcript keeps every message a delta did not touch) and none of whose mentions
 * changed resolution keeps its row instance, so a streamed delta rebuilds, and Compose recomposes,
 * only its own bubble. Classifies as iOS does (`IOS/Views/MessageBubbleView.swift:19-26`):
 * interactive requests are cards, client-local notices and watcher events centred notices, the
 * rest bubbles. Single-threaded: one cache per collector, off the main thread.
 */
class TranscriptItemCache {
    private class Built(val message: ChatMessage, val item: TranscriptItem, val mentions: Set<String>)

    private var previous: Map<String, Built> = emptyMap()
    private var previousResolutions: Map<String, MentionResolution> = emptyMap()
    private var built: MutableList<ChatMessage> = ArrayList()

    fun items(messages: List<ChatMessage>, typing: Boolean, resolutions: Map<String, MentionResolution>): List<TranscriptItem> {
        val next = HashMap<String, Built>(messages.size * 2)
        val items = ArrayList<TranscriptItem>(messages.size + 1)
        val seen = HashMap<String, Int>()
        for (message in messages) {
            if (message.hidden) continue
            val repeats = seen.merge(message.uniqueID, 1, Int::plus)!!
            val key = if (repeats == 1) message.uniqueID else "${message.uniqueID}#$repeats"
            val prior = previous[key]
            val reusable = prior != null && prior.message === message &&
                (resolutions === previousResolutions || prior.mentions.all { resolutions[it] == previousResolutions[it] })
            val entry = if (reusable) prior else build(message, key, resolutions).also { if (prior?.message !== message) built += message }
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

    private fun build(message: ChatMessage, key: String, resolutions: Map<String, MentionResolution>): Built {
        val asked = HashSet<String>()
        val resolve: (String) -> MentionResolution? = { identifier ->
            asked += identifier
            resolutions[identifier]
        }
        val item = when {
            message.isInteractiveRequest -> TranscriptItem.RequestCard(message, requestCard(message, resolve), key)
            message.body is MessageBody.ClientNotice || message.body is MessageBody.WatcherEvent ->
                TranscriptItem.Notice(message, bubbleBlocks(message, resolve), key)
            else -> TranscriptItem.Bubble(message, message.author == Participant.USER, bubbleBlocks(message, resolve), key)
        }
        return Built(message, item, asked)
    }

    override fun toString(): String = "TranscriptItemCache(items=${previous.size})"
}
