package dev.bugobliterator.itermbuddy.core.chat

/** What [applyDelivery] did. Carries indexes and reasons only, never message content. */
sealed interface MergeResult {
    /** A new message was added at [index], the end of the transcript. */
    data class Appended(val index: Int) : MergeResult

    /** The message at [index] was replaced or grew. */
    data class Replaced(val index: Int) : MergeResult

    /** The transcript did not change. */
    data class Ignored(val reason: IgnoreReason) : MergeResult
}

/** Why a delivery left the transcript unchanged. */
enum class IgnoreReason {
    /** A hidden message, `commit` included (`Message.swift:549-562`, `AppModel.swift:4296-4297`). */
    HIDDEN,

    /** A streamed delta whose target is neither text nor multipart (`AppModel.swift:5262-5267`). */
    TARGET_NOT_TEXT,

    /** A streamed delta whose target was removed from this transcript ([Transcript.removeAll]). */
    TARGET_REMOVED,

    /** A streamed delta whose body did not decode, so its target cannot be known. */
    UNREADABLE_DELTA,

    /** A message whose body did not decode, naming a held message that did: the held one is kept. */
    UNREADABLE_REPLACEMENT,
}

/**
 * Applies one delivered [message] to the transcript of chat [chatID], as iOS applies it
 * (`AppModel.swift:5215-5277`):
 * - a hidden message, `commit` included, is ignored;
 * - `append` grows a `PlainText`, `Markdown` or `Multipart` target with upstream's
 *   `append(_:useMarkdownIfAmbiguous: true)` (`Message.swift:655-690`), and `appendAttachment` with
 *   `append(_:vectorStoreID: nil)` (`Message.swift:627-653`) and `Attachment.appending`
 *   (`LLM.swift:158-190`); any other target is left alone;
 * - a delta whose target is missing starts an agent message in [chatID], dated by the delta, with
 *   `Markdown(chunk)` or `Multipart([attachment])`; one whose target was removed is ignored, as
 *   the Mac drops a delta for a missing target (`ChatListModel.swift:325-350`);
 * - anything else replaces the message with its `uniqueID` in place, or is appended.
 *
 * Android only: a delta whose body did not decode ([MessageBody.Unreadable] of `append` or
 * `appendAttachment`) is ignored rather than shown as an unreadable message of its own, and an
 * unreadable message never replaces a readable one with its `uniqueID`; only a new ID is
 * appended as unreadable.
 *
 * Streamed images: the decoder keeps a file's bytes only when its own type starts with `image/`
 * ([AttachmentBudgets]), and a continuation chunk's type is the rest of the joined type (`"png"`
 * after `"image/"`), so its bytes are dropped and the joined file keeps its name, type and size
 * only. A streamed image keeps its bytes only when every chunk's type starts with `image/`.
 */
fun Transcript.applyDelivery(message: ChatMessage, chatID: String): MergeResult {
    if (message.hidden) return MergeResult.Ignored(IgnoreReason.HIDDEN)
    return when (val body = message.body) {
        is MessageBody.Append ->
            applyDelta(body.target, message, chatID, { MessageBody.Markdown(body.text) }, StreamDelta.Chunk(body.text)) {
                it.appendingText(body.text)
            }
        is MessageBody.AppendAttachment -> {
            val kind = body.attachment.kind
            val delta = when (kind) {
                is AttachmentKind.Code -> StreamDelta.Code(body.attachment, kind.text)
                is AttachmentKind.StatusUpdate -> StreamDelta.Status(kind.update)
                else -> null
            }
            val start = { MessageBody.Multipart(listOf(Subpart.AttachmentPart(body.attachment))) }
            applyDelta(body.target, message, chatID, start, delta) { it.appendingAttachment(body.attachment, budgets) }
        }
        is MessageBody.Unreadable -> when {
            body.case in DELTA_CASES -> MergeResult.Ignored(IgnoreReason.UNREADABLE_DELTA)
            replacesReadable(message.uniqueID) -> MergeResult.Ignored(IgnoreReason.UNREADABLE_REPLACEMENT)
            else -> upsert(message)
        }
        else -> upsert(message)
    }
}

private val DELTA_CASES = setOf("append", "appendAttachment")

/** Whether a message with [uniqueID] is held and readable, which an unreadable copy must not replace. */
private fun Transcript.replacesReadable(uniqueID: String): Boolean {
    val index = indexOf(uniqueID)
    return index >= 0 && storedAt(index).body !is MessageBody.Unreadable
}

private fun Transcript.upsert(message: ChatMessage): MergeResult {
    val index = indexOf(message.uniqueID)
    return if (index >= 0) {
        replaceMessage(index, message)
        MergeResult.Replaced(index)
    } else {
        MergeResult.Appended(appendMessage(message))
    }
}

/**
 * Merges a streamed delta into its [target]: in the open buffer when [textDelta]
 * can grow it, else by rebuilding the body with [merge] (null for a target that takes no delta).
 * A missing target starts an agent message with the body [start] makes.
 */
private inline fun Transcript.applyDelta(
    target: String,
    delta: ChatMessage,
    chatID: String,
    start: () -> MessageBody,
    textDelta: StreamDelta?,
    merge: (MessageBody) -> MessageBody?,
): MergeResult {
    val index = indexOf(target)
    if (index < 0) {
        if (isRemoved(target)) return MergeResult.Ignored(IgnoreReason.TARGET_REMOVED)
        return MergeResult.Appended(appendMessage(ChatMessage(chatID, Participant.AGENT, start(), delta.sentMillis, target)))
    }
    // The stored body's case is current even while its text is buffered, so this reads nothing.
    if (!isStreamable(storedAt(index).body)) return MergeResult.Ignored(IgnoreReason.TARGET_NOT_TEXT)
    if (textDelta != null && growText(index, textDelta)) return MergeResult.Replaced(index)
    val current = settle(index)
    val merged = merge(current.body) ?: return MergeResult.Ignored(IgnoreReason.TARGET_NOT_TEXT)
    replaceMessage(index, current.copy(body = merged))
    return MergeResult.Replaced(index)
}

/** Only text-bearing targets take a delta (`AppModel.swift:5262-5267`). */
private fun isStreamable(body: MessageBody): Boolean =
    body is MessageBody.PlainText || body is MessageBody.Markdown || body is MessageBody.Multipart

/**
 * `Message.append(_:useMarkdownIfAmbiguous: true)` (`Message.swift:655-690`): a plain text or
 * markdown body grows (a plain text's context is dropped); a multipart's trailing markdown or plain
 * text part grows; otherwise a markdown part is added. Null for any other body.
 */
internal fun MessageBody.appendingText(chunk: String): MessageBody? = when (this) {
    is MessageBody.PlainText -> MessageBody.PlainText(text + chunk)
    is MessageBody.Markdown -> MessageBody.Markdown(text + chunk)
    is MessageBody.Multipart -> when (val last = parts.lastOrNull()) {
        is Subpart.Markdown -> MessageBody.Multipart(parts.subList(0, parts.size - 1) + Subpart.Markdown(last.text + chunk))
        is Subpart.PlainText -> MessageBody.Multipart(parts.subList(0, parts.size - 1) + Subpart.PlainText(last.text + chunk))
        else -> MessageBody.Multipart(parts + Subpart.Markdown(chunk))
    }
    else -> null
}

/**
 * `Message.append(_:vectorStoreID: nil)` (`Message.swift:627-653`): a plain text or markdown body
 * becomes a multipart of its text and [attachment]; a multipart coalesces [attachment] into its
 * trailing attachment when `Attachment.appending` allows, else adds it. Null for any other body.
 */
internal fun MessageBody.appendingAttachment(attachment: Attachment, budgets: AttachmentBudgets): MessageBody? = when (this) {
    is MessageBody.PlainText -> MessageBody.Multipart(listOf(Subpart.PlainText(text), Subpart.AttachmentPart(attachment)))
    is MessageBody.Markdown -> MessageBody.Multipart(listOf(Subpart.Markdown(text), Subpart.AttachmentPart(attachment)))
    is MessageBody.Multipart -> {
        val combined = ((parts.lastOrNull() as? Subpart.AttachmentPart)?.attachment)?.appending(attachment, budgets)
        if (combined != null) {
            MessageBody.Multipart(parts.subList(0, parts.size - 1) + Subpart.AttachmentPart(combined))
        } else {
            MessageBody.Multipart(parts + Subpart.AttachmentPart(attachment))
        }
    }
    else -> null
}

/**
 * `Attachment.appending` (`LLM.swift:158-190`): two status updates always coalesce into one flat
 * multipart of both, exploded (whatever their IDs); otherwise only attachments with the same `id`
 * coalesce, code into code and file into file, keeping this attachment's `inline` and `id`. A
 * file's name, type and size concatenate; its bytes concatenate only when both chunks kept theirs
 * and the result is an image within [AttachmentBudgets.maxImageBytes], else none are kept.
 */
internal fun Attachment.appending(other: Attachment, budgets: AttachmentBudgets): Attachment? {
    val lhs = kind
    val rhs = other.kind
    if (lhs is AttachmentKind.StatusUpdate && rhs is AttachmentKind.StatusUpdate) {
        val updates = ArrayList<AgentStatusUpdate>()
        lhs.update.explodeInto(updates)
        rhs.update.explodeInto(updates)
        return copy(kind = AttachmentKind.StatusUpdate(AgentStatusUpdate.Multipart(updates)))
    }
    if (other.id != id) return null
    return when {
        lhs is AttachmentKind.Code && rhs is AttachmentKind.Code -> Attachment(inline, id, AttachmentKind.Code(lhs.text + rhs.text))
        lhs is AttachmentKind.File && rhs is AttachmentKind.File -> Attachment(inline, id, lhs.joining(rhs, budgets))
        else -> null
    }
}

private fun AttachmentKind.File.joining(other: AttachmentKind.File, budgets: AttachmentBudgets): AttachmentKind.File {
    val mimeType = mimeType + other.mimeType
    val size = (size.toLong() + other.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val first = bytesView()
    val second = other.bytesView()
    val bytes = if (
        first != null && second != null && isImageMimeType(mimeType) &&
        first.remaining().toLong() + second.remaining() <= budgets.maxImageBytes
    ) {
        val firstSize = first.remaining()
        val joined = ByteArray(firstSize + second.remaining())
        first.get(joined, 0, firstSize)
        second.get(joined, firstSize, second.remaining())
        joined
    } else {
        null
    }
    return AttachmentKind.File.adopting(name + other.name, mimeType, size, bytes)
}
