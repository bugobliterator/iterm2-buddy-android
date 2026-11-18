package dev.bugobliterator.itermbuddy.core.chat

import java.util.Collections
import java.util.Locale

/**
 * One chat's visible messages in order, as iOS holds an open transcript (`AppModel.swift:5208-5277`),
 * merged by [applyDelivery] and truncated by [removeAll].
 *
 * - An index by `uniqueID` (the first message with an ID, as iOS's `firstIndex(where:)`) makes an
 *   upsert or a delta cost nothing in the transcript's length; a delta mutates only its target.
 * - Streamed text is held in an open buffer for its message, so a reply of many deltas allocates
 *   linearly in its text: the target's body is rebuilt from the buffer only when it is read
 *   ([get], [messages]), at most once per read.
 * - File attachment bytes are under one [AttachmentBudgets.maxTranscriptImageBytes] allowance for
 *   the whole transcript, held across live deliveries (each of which was decoded with a fresh
 *   allowance): over it, an attachment keeps its name, type and size and drops its bytes.
 * - IDs passed to [removeAll] stay as tombstones for this transcript's life, so a delta naming one
 *   is ignored; a new `history` builds a new transcript, without them.
 *
 * Built from a `history`'s messages, hidden ones dropped. Not thread-safe, reads included (a read
 * may rebuild a streamed body): confine a transcript to one thread and hand others [messages].
 * [toString] shows counts only.
 */
class Transcript(history: List<ChatMessage>, internal val budgets: AttachmentBudgets = AttachmentBudgets()) {
    private val entries = ArrayList<ChatMessage>(history.size)
    private val indexByID = HashMap<String, Int>(history.size * 2)
    private val removedIDs = HashSet<String>()
    private val openText = HashMap<String, OpenText>()

    /** File attachment bytes kept across the whole transcript. */
    var keptImageBytes: Long = 0
        private set

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

    /** The message at [index], with any streamed text it has received. */
    operator fun get(index: Int): ChatMessage {
        val message = entries[index]
        val open = openText[message.uniqueID] ?: return message
        // A buffer belongs to the first message with its ID, the one deltas target.
        return if (open.stale && indexByID[message.uniqueID] == index) materialise(index, open) else message
    }

    /** A read-only snapshot of every message, in order; later changes do not show in it. */
    fun messages(): List<ChatMessage> {
        for ((id, open) in openText) {
            if (open.stale) materialise(indexByID.getValue(id), open)
        }
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
        val before = entries.size
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.uniqueID in upper) {
                keptImageBytes -= message.keptImageBytes()
                openText.remove(message.uniqueID)
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

    override fun toString(): String =
        "Transcript(size=${entries.size}, version=$version, keptImageBytes=$keptImageBytes, " +
            "streaming=${openText.size}, removed=${removedIDs.size})"

    // The merge's primitives, used by applyDelivery (TranscriptMerge.kt).

    internal fun isRemoved(uniqueID: String): Boolean = uniqueID in removedIDs

    /** The message stored at [index], without folding in streamed text: its body's case is current. */
    internal fun storedAt(index: Int): ChatMessage = entries[index]

    /** The current message at [index], its streamed text folded in, and closes its open buffer. */
    internal fun settle(index: Int): ChatMessage {
        val message = get(index)
        openText.remove(message.uniqueID)
        return message
    }

    /** Appends [message] under the transcript's image allowance; returns its index. */
    internal fun appendMessage(message: ChatMessage): Int {
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
        val old = entries[index]
        openText.remove(old.uniqueID)
        val others = keptImageBytes - old.keptImageBytes()
        val admitted = admitImages(message, others)
        keptImageBytes = others + admitted.keptImageBytes()
        entries[index] = admitted
        changed(index)
    }

    /**
     * Grows the message at [index] by [delta] in its open buffer, as upstream's merge would grow its
     * last segment (`Message.swift:627-690`, `LLM.swift:158-190`). A buffer that cannot take
     * [delta] is folded into the body and closed first. Returns false when the last segment cannot
     * grow by [delta]; the caller then merges by rebuilding the body.
     */
    internal fun growText(index: Int, delta: TextDelta): Boolean {
        val id = entries[index].uniqueID
        val open = openText[id]
        if (open != null && open.takes(delta)) {
            open.text.append(delta.text)
            open.stale = true
            changed(index)
            return true
        }
        val opened = OpenText.openFor(settle(index).body, delta) ?: return false
        openText[id] = opened
        changed(index)
        return true
    }

    private fun changed(index: Int?) {
        version++
        lastChangedIndex = index
    }

    /** Rebuilds the body at [index] from its open buffer; the buffer stays open for later deltas. */
    private fun materialise(index: Int, open: OpenText): ChatMessage {
        val message = entries[index]
        val text = open.text.toString()
        val body = when (val kind = open.kind) {
            OpenText.Kind.PlainText -> MessageBody.PlainText(text)
            OpenText.Kind.Markdown -> MessageBody.Markdown(text)
            OpenText.Kind.PlainTextPart -> message.withLastPart(Subpart.PlainText(text))
            OpenText.Kind.MarkdownPart -> message.withLastPart(Subpart.Markdown(text))
            is OpenText.Kind.CodePart ->
                message.withLastPart(Subpart.AttachmentPart(Attachment(kind.inline, kind.id, AttachmentKind.Code(text))))
        }
        val rebuilt = message.copy(body = body)
        entries[index] = rebuilt
        open.stale = false
        return rebuilt
    }

    /**
     * [message] with each kept file's bytes dropped once they exceed the per-image limit or the
     * transcript allowance, given [alreadyKept] bytes elsewhere in the transcript.
     */
    private fun admitImages(message: ChatMessage, alreadyKept: Long): ChatMessage {
        val body = message.body as? MessageBody.Multipart ?: return message
        var remaining = budgets.maxTranscriptImageBytes - alreadyKept
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
internal sealed interface TextDelta {
    val text: String

    /** `append(string)`. */
    class Chunk(override val text: String) : TextDelta

    /** `appendAttachment` of a code attachment. */
    class Code(val attachment: Attachment, override val text: String) : TextDelta
}

/** The open buffer of a message being streamed: its last segment's text, growing. */
private class OpenText(val kind: Kind, val text: StringBuilder) {
    /** True when [text] holds more than the message's body shows. */
    var stale = true

    sealed interface Kind {
        data object PlainText : Kind
        data object Markdown : Kind
        data object PlainTextPart : Kind
        data object MarkdownPart : Kind

        /** A trailing code attachment, which code attachments with its [id] coalesce into (`LLM.swift:166-178`). */
        data class CodePart(val inline: Boolean, val id: String) : Kind
    }

    /** Whether [delta] grows this buffer with upstream's append rules. */
    fun takes(delta: TextDelta): Boolean = when (delta) {
        is TextDelta.Chunk -> kind !is Kind.CodePart
        is TextDelta.Code -> kind is Kind.CodePart && kind.id == delta.attachment.id
    }

    companion object {
        /**
         * A buffer holding [body]'s last segment grown by [delta], when upstream's merge would grow
         * that segment in place (`Message.swift:627-690`, `LLM.swift:158-190`); null otherwise.
         */
        fun openFor(body: MessageBody, delta: TextDelta): OpenText? {
            val (kind, existing) = when (delta) {
                is TextDelta.Chunk -> when (body) {
                    is MessageBody.PlainText -> Kind.PlainText to body.text
                    is MessageBody.Markdown -> Kind.Markdown to body.text
                    is MessageBody.Multipart -> when (val last = body.parts.lastOrNull()) {
                        is Subpart.PlainText -> Kind.PlainTextPart to last.text
                        is Subpart.Markdown -> Kind.MarkdownPart to last.text
                        else -> return null
                    }
                    else -> return null
                }
                is TextDelta.Code -> {
                    val last = ((body as? MessageBody.Multipart)?.parts?.lastOrNull() as? Subpart.AttachmentPart)?.attachment
                    val code = last?.kind as? AttachmentKind.Code
                    if (last == null || code == null || last.id != delta.attachment.id) return null
                    Kind.CodePart(last.inline, last.id) to code.text
                }
            }
            val text = StringBuilder(existing.length + delta.text.length + MIN_GROWTH).append(existing).append(delta.text)
            return OpenText(kind, text)
        }

        private const val MIN_GROWTH = 64
    }
}

/** This multipart message's body with its last part replaced by [last]. */
private fun ChatMessage.withLastPart(last: Subpart): MessageBody.Multipart {
    val parts = (body as MessageBody.Multipart).parts
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
