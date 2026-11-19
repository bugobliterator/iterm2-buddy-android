package dev.bugobliterator.itermbuddy.core.chat

import java.util.Collections
import java.util.Locale

/**
 * One chat's visible messages in order, as iOS holds an open transcript (`AppModel.swift:5208-5277`),
 * merged by [applyDelivery] and truncated by [removeAll].
 *
 * - An index by `uniqueID` (the first message with an ID, as iOS's `firstIndex(where:)`) makes an
 *   upsert or a delta cost nothing in the transcript's length; a delta mutates only its target.
 * - The last segment of the message being streamed (its text, a trailing code attachment or a
 *   trailing status update) is held in one open buffer, so a reply of many deltas allocates
 *   linearly in what they carry: the target's body is rebuilt from the buffer only when it is
 *   read ([messages]), at most once per read. At most one buffer is open: streaming another
 *   message, or adding one, folds the open buffer into its body and closes it.
 * - File attachment bytes are under one [AttachmentBudgets.maxTranscriptImageBytes] allowance for
 *   the whole transcript, held across live deliveries (each of which was decoded with a fresh
 *   allowance), and under the process's [SharedImageAllowance] when one is given (every transcript
 *   held draws on it, and [release] gives this one's bytes back): over either, an attachment keeps
 *   its name, type and size and drops its bytes.
 * - IDs passed to [removeAll] stay as tombstones for this transcript's life, so a delta naming one
 *   is ignored; a new `history` builds a new transcript, without them.
 *
 * Built from a `history`'s messages, hidden ones dropped. Not thread-safe, reads included (a read
 * may rebuild a streamed body): confine a transcript to one thread and hand others [messages].
 * [toString] shows counts only.
 */
class Transcript(
    history: List<ChatMessage>,
    internal val budgets: AttachmentBudgets = AttachmentBudgets(),
    private var shared: SharedImageAllowance? = null,
) {
    private val entries = ArrayList<ChatMessage>(history.size)
    private val indexByID = HashMap<String, Int>(history.size * 2)
    private val removedIDs = HashSet<String>()

    /** The one open buffer, for the first message with its [OpenBuffer.messageID]; null when none is open. */
    private var open: OpenBuffer? = null

    /** File attachment bytes kept across the whole transcript (counted in the shared allowance too). */
    var keptImageBytes: Long = 0
        private set(value) {
            shared?.let { it.heldBytes += value - field }
            field = value
        }

    /** Bumped by every change: an upsert, a merged delta or a removal. */
    var version: Long = 0
        private set

    /** The index the last change touched; null before any change or after a removal. */
    var lastChangedIndex: Int? = null
        private set

    init {
        for (message in history) {
            if (message.hidden) continue
            val admitted = admitImages(message, keptImageBytes)
            keptImageBytes += admitted.keptImageBytes()
            indexByID.putIfAbsent(admitted.uniqueID, entries.size)
            entries.add(admitted)
        }
    }

    val size: Int get() = entries.size

    /** The message at [index], with everything streamed into it. Internal: others read [messages]. */
    internal operator fun get(index: Int): ChatMessage {
        val message = entries[index]
        val buffer = open
        return if (buffer != null && buffer.stale && ownsBuffer(index, buffer)) materialise(index, buffer) else message
    }

    /** A read-only snapshot of every message, in order; later changes do not show in it. */
    fun messages(): List<ChatMessage> {
        open?.let { buffer -> if (buffer.stale) materialise(indexByID.getValue(buffer.messageID), buffer) }
        return Collections.unmodifiableList(ArrayList(entries))
    }

    /** The index of the first message with [uniqueID] (any case), or -1. */
    fun indexOf(uniqueID: String): Int = indexByID[uniqueID.upper()] ?: -1

    /**
     * The `uniqueID`s of [uniqueID]'s message and every message after it, the suffix a Delete From
     * Here removes (`AppModel.swift:2660-2669`); empty when no message has that ID.
     */
    fun suffixFrom(uniqueID: String): List<String> {
        val start = indexOf(uniqueID)
        if (start < 0) return emptyList()
        return (start until entries.size).map { entries[it].uniqueID }
    }

    /**
     * Removes every message whose `uniqueID` is in [ids] (any case), as iOS does for
     * `messagesRemoved` (`AppModel.swift:4336-4345`), and keeps all of [ids] as tombstones. Returns
     * how many messages went; removing again changes nothing.
     */
    fun removeAll(ids: Set<String>): Int {
        val upper = ids.mapTo(HashSet(ids.size * 2)) { it.upper() }
        removedIDs.addAll(upper)
        if (open?.messageID in upper) open = null
        val before = entries.size
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.uniqueID in upper) {
                keptImageBytes -= message.keptImageBytes()
                iterator.remove()
            }
        }
        val removed = before - entries.size
        if (removed > 0) {
            indexByID.clear()
            entries.forEachIndexed { index, message -> indexByID.putIfAbsent(message.uniqueID, index) }
            changed(null)
        }
        return removed
    }

    /**
     * Removes the first message with [uniqueID] (any case) without keeping a tombstone: a message
     * sent from this phone and not delivered, withdrawn by Retry or Delete. If the Mac's echo of it
     * comes after all, it is upserted again. Returns whether a message went.
     */
    fun withdraw(uniqueID: String): Boolean {
        val upper = uniqueID.upper()
        val index = indexByID[upper] ?: return false
        if (open?.messageID == upper) open = null
        keptImageBytes -= entries[index].keptImageBytes()
        entries.removeAt(index)
        indexByID.clear()
        entries.forEachIndexed { at, message -> indexByID.putIfAbsent(message.uniqueID, at) }
        changed(null)
        return true
    }

    /**
     * The transcript is dropped: its kept bytes go back to the shared allowance. Call it once,
     * when the transcript is no longer held; it draws on no allowance after.
     */
    fun release() {
        shared?.let { it.heldBytes -= keptImageBytes }
        shared = null
    }

    override fun toString(): String =
        "Transcript(size=${entries.size}, version=$version, keptImageBytes=$keptImageBytes, " +
            "streaming=${if (open != null) 1 else 0}, removed=${removedIDs.size})"

    // The merge's primitives, used by applyDelivery (TranscriptMerge.kt).

    internal fun isRemoved(uniqueID: String): Boolean = uniqueID.upper() in removedIDs

    /** The message stored at [index], without folding in a buffer: its body's case is current. */
    internal fun storedAt(index: Int): ChatMessage = entries[index]

    /** The current message at [index], everything streamed folded in; closes its buffer if it has one. */
    internal fun settle(index: Int): ChatMessage {
        val message = get(index)
        val buffer = open
        if (buffer != null && ownsBuffer(index, buffer)) open = null
        return message
    }

    /** Appends [message] under the transcript's image allowance; returns its index. Closes the open buffer. */
    internal fun appendMessage(message: ChatMessage): Int {
        closeBuffer()
        val admitted = admitImages(message, keptImageBytes)
        keptImageBytes += admitted.keptImageBytes()
        val index = entries.size
        indexByID.putIfAbsent(admitted.uniqueID, index)
        entries.add(admitted)
        changed(index)
        return index
    }

    /** Replaces the message at [index] with [message] (same `uniqueID`) under the image allowance. */
    internal fun replaceMessage(index: Int, message: ChatMessage) {
        val buffer = open
        if (buffer != null && ownsBuffer(index, buffer)) open = null
        val old = entries[index]
        val others = keptImageBytes - old.keptImageBytes()
        val admitted = admitImages(message, others)
        keptImageBytes = others + admitted.keptImageBytes()
        entries[index] = admitted
        changed(index)
    }

    /**
     * Grows the message at [index] by [delta] in the open buffer, as upstream's merge would grow
     * its last segment (`Message.swift:627-690`, `LLM.swift:158-190`). A buffer that cannot take
     * [delta], or belongs to another message, is folded into its body and closed first. Returns
     * false when the last segment cannot grow by [delta]; the caller then merges by rebuilding
     * the body.
     */
    internal fun growText(index: Int, delta: StreamDelta): Boolean {
        val buffer = open
        if (buffer != null && ownsBuffer(index, buffer) && buffer.take(delta)) {
            changed(index)
            return true
        }
        closeBuffer()
        val message = entries[index]
        val opened = OpenBuffer.openFor(message.uniqueID, message.body, delta) ?: return false
        open = opened
        changed(index)
        return true
    }

    private fun ownsBuffer(index: Int, buffer: OpenBuffer): Boolean =
        entries[index].uniqueID == buffer.messageID && indexByID[buffer.messageID] == index

    /** Folds the open buffer, if any, into its message and closes it. */
    private fun closeBuffer() {
        val buffer = open ?: return
        if (buffer.stale) materialise(indexByID.getValue(buffer.messageID), buffer)
        open = null
    }

    private fun changed(index: Int?) {
        version++
        lastChangedIndex = index
    }

    /** Rebuilds the body at [index] from [buffer]; the buffer stays open for later deltas. */
    private fun materialise(index: Int, buffer: OpenBuffer): ChatMessage {
        val message = entries[index]
        val rebuilt = message.copy(body = buffer.bodyFrom(message.body))
        entries[index] = rebuilt
        buffer.stale = false
        return rebuilt
    }

    /**
     * [message] with each kept file's bytes dropped once they exceed the per-image limit or the
     * transcript allowance, given [alreadyKept] bytes elsewhere in the transcript.
     */
    private fun admitImages(message: ChatMessage, alreadyKept: Long): ChatMessage {
        val body = message.body as? MessageBody.Multipart ?: return message
        var remaining = budgets.maxTranscriptImageBytes - alreadyKept
        // What every other transcript holds counts too; this one's own, beside [alreadyKept], is being replaced.
        shared?.let { remaining = minOf(remaining, it.limitBytes - (it.heldBytes - keptImageBytes) - alreadyKept) }
        var stripped = false
        val parts = body.parts.map { part ->
            val attachment = (part as? Subpart.AttachmentPart)?.attachment
            val file = attachment?.kind as? AttachmentKind.File
            if (attachment == null || file == null || file.keptByteCount == 0) return@map part
            val kept = file.keptByteCount
            if (kept <= budgets.maxImageBytes && kept <= remaining) {
                remaining -= kept
                part
            } else {
                stripped = true
                Subpart.AttachmentPart(attachment.copy(kind = AttachmentKind.File.adopting(file.name, file.mimeType, file.size, null)))
            }
        }
        return if (stripped) message.copy(body = MessageBody.Multipart(parts)) else message
    }
}

/** A streamed delta that can grow a message's last segment in place. */
internal sealed interface StreamDelta {
    /** `append(string)`. */
    class Chunk(val text: String) : StreamDelta

    /** `appendAttachment` of a code attachment. */
    class Code(val attachment: Attachment, val text: String) : StreamDelta

    /** `appendAttachment` of a status update. */
    class Status(val update: AgentStatusUpdate) : StreamDelta
}

/** The open buffer of the message being streamed: its last segment, growing. */
private sealed class OpenBuffer(val messageID: String) {
    /** True when the buffer holds more than the message's body shows. */
    var stale = true

    /** Grows the buffer by [delta] when upstream's rules grow this segment by it; false otherwise. */
    abstract fun take(delta: StreamDelta): Boolean

    /** [body] (the message's stored body) with this segment as the buffer holds it. */
    abstract fun bodyFrom(body: MessageBody): MessageBody

    /** Text: a plain text or markdown body, a multipart's trailing text part, or a trailing code attachment. */
    class Text(messageID: String, val kind: TextKind, val text: StringBuilder) : OpenBuffer(messageID) {
        override fun take(delta: StreamDelta): Boolean {
            val grows = when (delta) {
                is StreamDelta.Chunk -> kind !is TextKind.CodePart
                is StreamDelta.Code -> kind is TextKind.CodePart && kind.id == delta.attachment.id
                is StreamDelta.Status -> false
            }
            if (!grows) return false
            text.append(if (delta is StreamDelta.Chunk) delta.text else (delta as StreamDelta.Code).text)
            stale = true
            return true
        }

        override fun bodyFrom(body: MessageBody): MessageBody {
            val text = text.toString()
            return when (kind) {
                TextKind.PlainText -> MessageBody.PlainText(text)
                TextKind.Markdown -> MessageBody.Markdown(text)
                TextKind.PlainTextPart -> body.withLastPart(Subpart.PlainText(text))
                TextKind.MarkdownPart -> body.withLastPart(Subpart.Markdown(text))
                is TextKind.CodePart ->
                    body.withLastPart(Subpart.AttachmentPart(Attachment(kind.inline, kind.id, AttachmentKind.Code(text))))
            }
        }
    }

    /**
     * A trailing status update. Status updates always coalesce, whatever their IDs, into one flat
     * multipart under the first attachment's `inline` and `id` (`LLM.swift:158-164`).
     */
    class Status(messageID: String, val inline: Boolean, val id: String, val updates: ArrayList<AgentStatusUpdate>) :
        OpenBuffer(messageID) {
        override fun take(delta: StreamDelta): Boolean {
            if (delta !is StreamDelta.Status) return false
            delta.update.explodeInto(updates)
            stale = true
            return true
        }

        override fun bodyFrom(body: MessageBody): MessageBody {
            val update = AgentStatusUpdate.Multipart(ArrayList(updates))
            return body.withLastPart(Subpart.AttachmentPart(Attachment(inline, id, AttachmentKind.StatusUpdate(update))))
        }
    }

    sealed interface TextKind {
        data object PlainText : TextKind
        data object Markdown : TextKind
        data object PlainTextPart : TextKind
        data object MarkdownPart : TextKind

        /** A trailing code attachment, which code attachments with its [id] coalesce into (`LLM.swift:166-178`). */
        data class CodePart(val inline: Boolean, val id: String) : TextKind
    }

    companion object {
        /**
         * A buffer holding [body]'s last segment grown by [delta], when upstream's merge would grow
         * that segment in place (`Message.swift:627-690`, `LLM.swift:158-190`); null otherwise.
         */
        fun openFor(messageID: String, body: MessageBody, delta: StreamDelta): OpenBuffer? {
            val last = (body as? MessageBody.Multipart)?.parts?.lastOrNull()
            val lastAttachment = (last as? Subpart.AttachmentPart)?.attachment
            val (kind, existing, added) = when (delta) {
                is StreamDelta.Chunk -> when {
                    body is MessageBody.PlainText -> Triple(TextKind.PlainText, body.text, delta.text)
                    body is MessageBody.Markdown -> Triple(TextKind.Markdown, body.text, delta.text)
                    last is Subpart.PlainText -> Triple(TextKind.PlainTextPart, last.text, delta.text)
                    last is Subpart.Markdown -> Triple(TextKind.MarkdownPart, last.text, delta.text)
                    else -> return null
                }
                is StreamDelta.Code -> {
                    val code = lastAttachment?.kind as? AttachmentKind.Code
                    if (code == null || lastAttachment.id != delta.attachment.id) return null
                    Triple(TextKind.CodePart(lastAttachment.inline, lastAttachment.id), code.text, delta.text)
                }
                is StreamDelta.Status -> {
                    val status = lastAttachment?.kind as? AttachmentKind.StatusUpdate ?: return null
                    val updates = ArrayList<AgentStatusUpdate>()
                    status.update.explodeInto(updates)
                    delta.update.explodeInto(updates)
                    return Status(messageID, lastAttachment.inline, lastAttachment.id, updates)
                }
            }
            val text = StringBuilder(existing.length + added.length + MIN_GROWTH).append(existing).append(added)
            return Text(messageID, kind, text)
        }

        private const val MIN_GROWTH = 64
    }
}

/** This multipart body with its last part replaced by [last]. */
private fun MessageBody.withLastPart(last: Subpart): MessageBody.Multipart {
    val parts = (this as MessageBody.Multipart).parts
    return MessageBody.Multipart(parts.subList(0, parts.size - 1) + last)
}

/** The bytes this message's file attachments keep. */
private fun ChatMessage.keptImageBytes(): Long {
    val body = body as? MessageBody.Multipart ?: return 0
    var total = 0L
    for (part in body.parts) {
        val file = (part as? Subpart.AttachmentPart)?.attachment?.kind as? AttachmentKind.File ?: continue
        total += file.keptByteCount
    }
    return total
}

private fun String.upper(): String = uppercase(Locale.ROOT)

/**
 * Image bytes kept across every [Transcript] the process holds, under [limitBytes]
 * ([ChatBudgets.sharedImageBytes]), so the worst case does not grow with how many transcripts
 * are held. Single-threaded, like the transcripts drawing on it (the chat consumer).
 */
class SharedImageAllowance(val limitBytes: Long) {
    /** Bytes the transcripts drawing on this keep now. */
    var heldBytes: Long = 0
        internal set

    override fun toString(): String = "SharedImageAllowance(limitBytes=$limitBytes, heldBytes=$heldBytes)"
}
