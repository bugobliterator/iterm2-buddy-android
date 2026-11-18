package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.array
import dev.bugobliterator.itermbuddy.core.wire.boolean
import dev.bugobliterator.itermbuddy.core.wire.ifPresent
import dev.bugobliterator.itermbuddy.core.wire.millis
import dev.bugobliterator.itermbuddy.core.wire.obj
import dev.bugobliterator.itermbuddy.core.wire.string
import dev.bugobliterator.itermbuddy.core.wire.uuid
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MEBIBYTE = 1024 * 1024

/**
 * How many file attachment bytes decoding keeps. Bytes are kept only for an `image/` type, at
 * most [maxImageBytes] per file and [maxTranscriptImageBytes] across one [decodeChatMessages]
 * call (one transcript); over either budget a file keeps its name, type and size only.
 */
data class AttachmentBudgets(
    val maxImageBytes: Int = 4 * MEBIBYTE,
    val maxTranscriptImageBytes: Long = 32L * MEBIBYTE,
) {
    init {
        require(maxImageBytes >= 0 && maxTranscriptImageBytes >= 0) { "budgets must not be negative" }
    }
}

/**
 * Decodes one wire `Message` (`Message.swift:707-757`) as upstream does, with these differences:
 * - Unknown keys are ignored and only fields the phone reads are validated.
 * - `chatID`, `author` (strictly `user` or `agent`), `sentDate` and `uniqueID` must be valid,
 *   and `content` must be an object with one key, or the message is rejected.
 * - An unknown content case, or an unknown client-local action in a known `clientLocal`, is
 *   [MessageBody.Unsupported], as upstream.
 * - A hidden kind is [MessageBody.Hidden] (or [MessageBody.Commit]) without reading its body.
 * - A visible known case whose body does not decode is [MessageBody.Unreadable] naming the case
 *   (upstream rejects the message; an Android leniency).
 *
 * @throws WireDecodeException naming the key and case only, never a value, with no cause.
 */
fun decodeChatMessage(obj: JsonObject, budgets: AttachmentBudgets): ChatMessage =
    withoutCause { decodeMessage(obj, ImageAllowance(budgets)) }

/**
 * Decodes a `history`'s `messages` array: each element as [decodeChatMessage], sharing one
 * transcript image budget. One bad message rejects the whole array, as upstream's decode does.
 */
fun decodeChatMessages(array: JsonArray, budgets: AttachmentBudgets): List<ChatMessage> {
    val allowance = ImageAllowance(budgets)
    return array.mapIndexed { index, element ->
        withoutCause {
            val obj = element as? JsonObject ?: throw WireDecodeException("messages[$index] is not an object")
            try {
                decodeMessage(obj, allowance)
            } catch (e: WireDecodeException) {
                throw WireDecodeException("messages[$index]: ${e.message}")
            }
        }
    }
}

/** Rethrows a [WireDecodeException] with its message only: a cause could quote the input. */
private inline fun <T> withoutCause(block: () -> T): T = try {
    block()
} catch (e: WireDecodeException) {
    if (e.cause == null) throw e
    throw WireDecodeException(e.message ?: "chat message did not decode")
}

/** The image bytes still allowed in one transcript. */
private class ImageAllowance(val budgets: AttachmentBudgets) {
    var remaining: Long = budgets.maxTranscriptImageBytes

    fun admits(size: Int): Boolean = size <= budgets.maxImageBytes && size <= remaining
}

private const val MESSAGE = "message"

private fun decodeMessage(obj: JsonObject, allowance: ImageAllowance): ChatMessage {
    val chatID = obj.string(MESSAGE, "chatID")
    val author = Participant.fromRaw(obj.string(MESSAGE, "author"))
        ?: throw WireDecodeException("$MESSAGE.author is not a known participant")
    val content = obj["content"] as? JsonObject
        ?: throw WireDecodeException("$MESSAGE.content is missing or not an object")
    val case = content.keys.singleOrNull()
        ?: throw WireDecodeException("$MESSAGE.content must have exactly one key, has ${content.size}")
    val body = decodeBody(case, content.getValue(case), allowance)
    val sentMillis = obj.millis(MESSAGE, "sentDate")
    val uniqueID = obj.uuid(MESSAGE, "uniqueID")
    return ChatMessage(chatID, author, body, sentMillis, uniqueID)
}

/** `Message.Content.knownContentKeys` (`Message.swift:342-348`). */
private val KNOWN_CONTENT_CASES = setOf(
    "plainText", "markdown", "explanationRequest", "explanationResponse",
    "remoteCommandRequest", "watcherEvent", "remoteCommandResponse",
    "selectSessionRequest", "clientLocal", "renameChat", "append",
    "appendAttachment", "commit", "userCommand", "setPermissions",
    "vectorStoreCreated", "terminalCommand", "multipart", "unsupported",
)

/** The hidden kinds other than `commit` (`Message.swift:549-552`). */
private val HIDDEN_CASES = setOf("remoteCommandResponse", "renameChat", "setPermissions", "vectorStoreCreated", "userCommand")

/** `ClientLocal.Action.knownActionKeys` (`Message.swift:191-196`). */
private val KNOWN_ACTIONS = setOf(
    "pickingSession", "executingCommand", "notice", "streamingChanged",
    "offerLink", "offerOrchestration", "permissions",
    "workgroupPermissionRequest", "enableOrchestrationRequest",
    "orchestrationPermissionGranted",
)

private fun decodeBody(case: String, element: JsonElement, allowance: ImageAllowance): MessageBody = when {
    case !in KNOWN_CONTENT_CASES || case == "unsupported" -> MessageBody.Unsupported
    case in HIDDEN_CASES -> MessageBody.Hidden(case)
    case == "commit" -> try {
        MessageBody.Commit(element.asBody(case).uuid(case, "_0"))
    } catch (_: WireDecodeException) {
        MessageBody.Hidden(case)
    }
    else -> try {
        decodeVisibleBody(case, element.asBody(case), allowance)
    } catch (_: WireDecodeException) {
        MessageBody.Unreadable(case)
    }
}

private fun JsonElement.asBody(path: String): JsonObject =
    this as? JsonObject ?: throw WireDecodeException("$path is not an object")

private fun decodeVisibleBody(case: String, body: JsonObject, allowance: ImageAllowance): MessageBody = when (case) {
    "plainText" -> MessageBody.PlainText(body.string(case, "_0"))
    "markdown" -> MessageBody.Markdown(body.string(case, "_0"))
    "multipart" -> {
        val parts = body.array(case, "_0")
        MessageBody.Multipart(parts.mapIndexed { index, part -> decodeSubpart(part.asBody("$case[$index]"), allowance) })
    }
    "explanationRequest" -> MessageBody.ExplanationRequest(explanationSnippet(body.obj(case, "request")))
    "explanationResponse" -> {
        val markdown = body.string(case, "markdown")
        MessageBody.ExplanationResponse(
            markdown.ifEmpty { body.obj(case, "_0").ifPresent("mainResponse") { string("$case._0", it) } ?: "" },
        )
    }
    "remoteCommandRequest" -> decodeRemoteCommandRequest(body)
    "watcherEvent" -> MessageBody.WatcherEvent(body.obj(case, "_0").string("$case._0", "detail"))
    "selectSessionRequest" -> MessageBody.SelectSessionRequest(body.boolean(case, "terminal"))
    "clientLocal" -> decodeClientAction(body.obj(case, "_0").obj("$case._0", "action"))
        ?.let { MessageBody.ClientNotice(it) } ?: MessageBody.Unsupported
    "append" -> MessageBody.Append(body.string(case, "string"), body.uuid(case, "uuid"))
    "appendAttachment" -> MessageBody.AppendAttachment(
        decodeAttachment(body.obj(case, "attachment"), "$case.attachment", allowance),
        body.uuid(case, "uuid"),
    )
    "terminalCommand" -> {
        val command = body.obj(case, "_0")
        MessageBody.TerminalCommand(command.string("$case._0", "command"), command.string("$case._0", "output"))
    }
    else -> throw WireDecodeException("$case is not a visible content case")
}

/** `AIExplanationRequest.snippetText` (`AIExplanationRequest.swift:32-40`). */
private fun explanationSnippet(request: JsonObject): String {
    val path = "explanationRequest.request"
    val command = request.ifPresent("command") { string(path, it) }
    val question = request.string(path, "question")
    val subjectMatter = request.string(path, "subjectMatter")
    return when {
        command != null -> "Explain $command"
        question.isNotEmpty() -> question
        else -> "Explain $subjectMatter"
    }
}

/**
 * `remoteCommandRequest(RemoteCommandPayload, safe:)`: external when the payload's `kind` is the
 * string `"external"`, else a bare classic `RemoteCommand` (`RemoteCommand.swift:82-92`).
 */
private fun decodeRemoteCommandRequest(body: JsonObject): MessageBody {
    val case = "remoteCommandRequest"
    val safe = body.ifPresent("safe") { boolean(case, it) }
    val payload = body.obj(case, "_0")
    val kind = payload["kind"] as? JsonPrimitive
    return if (kind != null && kind.isString && kind.content == "external") {
        MessageBody.ExternalCommand(
            payload.string("$case._0", "name"),
            payload.string("$case._0", "markdownDescription"),
            safe,
        )
    } else {
        MessageBody.ClassicCommand(decodeCommandContent(payload.obj("$case._0", "content"), "$case._0.content"), safe)
    }
}

/** Null for an action this build does not know, which upstream degrades to `.unsupported`. */
private fun decodeClientAction(action: JsonObject): ClientAction? {
    val path = "clientLocal._0.action"
    val case = action.keys.firstOrNull() ?: throw WireDecodeException("$path has no case")
    if (case !in KNOWN_ACTIONS) return null
    if (action.size != 1) throw WireDecodeException("$path must have exactly one key")
    val body = action.getValue(case).asBody("$path.$case")
    val at = "$path.$case"
    return when (case) {
        "pickingSession" -> ClientAction.PickingSession
        "executingCommand" -> ClientAction.ExecutingCommand(
            decodeCommandContent(body.obj(at, "_0").obj("$at._0", "content"), "$at._0.content"),
        )
        "notice" -> ClientAction.Notice(body.string(at, "_0"))
        "streamingChanged" -> {
            val raw = body.string(at, "_0")
            ClientAction.StreamingChanged(
                StreamingState.entries.firstOrNull { it.raw == raw }
                    ?: throw WireDecodeException("$at._0 is not a known streaming state"),
            )
        }
        "offerLink" -> ClientAction.OfferLink(
            body.boolean(at, "terminal"),
            body.string(at, "guid"),
            body.ifPresent("name") { string(at, it) },
        )
        "offerOrchestration" -> ClientAction.OfferOrchestration
        "permissions" -> ClientAction.Permissions(body.boolean(at, "terminal"), body.string(at, "guid"))
        "workgroupPermissionRequest" -> ClientAction.WorkgroupPermissionRequest(
            body.string(at, "requestID"),
            body.string(at, "workgroupID"),
            body.string(at, "workgroupName"),
            body.string(at, "summary"),
        )
        "enableOrchestrationRequest" -> ClientAction.EnableOrchestrationRequest(body.string(at, "requestID"))
        "orchestrationPermissionGranted" ->
            ClientAction.OrchestrationPermissionGranted(body.string(at, "scope"), body.string(at, "name"))
        else -> throw WireDecodeException("$path has an unhandled case")
    }
}

/** `Message.Subpart` (`Message.swift:246-252`): a synthesized enum, so an unknown case is corrupt. */
private fun decodeSubpart(part: JsonObject, allowance: ImageAllowance): Subpart {
    val path = "multipart.part"
    val case = part.keys.singleOrNull() ?: throw WireDecodeException("$path must have exactly one key")
    val body = part.getValue(case).asBody("$path.$case")
    return when (case) {
        "plainText" -> Subpart.PlainText(body.string("$path.$case", "_0"))
        "markdown" -> Subpart.Markdown(body.string("$path.$case", "_0"))
        "context" -> Subpart.Context(body.string("$path.$case", "_0"))
        "attachment" -> Subpart.AttachmentPart(decodeAttachment(body.obj("$path.$case", "_0"), "$path.$case._0", allowance))
        else -> throw WireDecodeException("$path has an unknown case")
    }
}

/** `LLM.Message.Attachment` (`LLM.swift:140-155`). */
private fun decodeAttachment(attachment: JsonObject, path: String, allowance: ImageAllowance): Attachment {
    val inline = attachment.boolean(path, "inline")
    val id = attachment.string(path, "id")
    val type = attachment.obj(path, "type")
    val case = type.keys.singleOrNull() ?: throw WireDecodeException("$path.type must have exactly one key")
    val at = "$path.type.$case"
    val body = type.getValue(case).asBody(at)
    val kind = when (case) {
        "code" -> AttachmentKind.Code(body.string(at, "_0"))
        "statusUpdate" -> AttachmentKind.StatusUpdate(decodeStatusUpdate(body.obj(at, "_0"), "$at._0"))
        "file" -> decodeFile(body.obj(at, "_0"), "$at._0", allowance)
        "fileID" -> AttachmentKind.FileId(body.string(at, "id"), body.string(at, "name"))
        else -> throw WireDecodeException("$path.type has an unknown case")
    }
    return Attachment(inline, id, kind)
}

/** `LLM.Message.StatusUpdate` (`LLM.swift:106-113`). */
private fun decodeStatusUpdate(update: JsonObject, path: String): AgentStatusUpdate {
    val case = update.keys.singleOrNull() ?: throw WireDecodeException("$path must have exactly one key")
    val at = "$path.$case"
    val body = update.getValue(case).asBody(at)
    return when (case) {
        "webSearchStarted" -> AgentStatusUpdate.WebSearchStarted
        "webSearchFinished" -> AgentStatusUpdate.WebSearchFinished(body.ifPresent("_0") { string(at, it) })
        "codeInterpreterStarted" -> AgentStatusUpdate.CodeInterpreterStarted
        "codeInterpreterFinished" -> AgentStatusUpdate.CodeInterpreterFinished
        "reasoningSummaryUpdate" -> AgentStatusUpdate.ReasoningSummaryUpdate(body.string(at, "_0"))
        "multipart" -> AgentStatusUpdate.Multipart(
            body.array(at, "_0").mapIndexed { index, part -> decodeStatusUpdate(part.asBody("$at[$index]"), "$at[$index]") },
        )
        else -> throw WireDecodeException("$path has an unknown case")
    }
}

/**
 * `AttachmentType.File` (`LLM.swift:145-150`). `content` is standard padded base64; its decoded
 * size is computed from its length, and the bytes are decoded only when they will be kept (an
 * `image/` type within [allowance]), so a large non-image file is never copied. A non-image's
 * base64 alphabet is therefore not checked; its padding is.
 */
private fun decodeFile(file: JsonObject, path: String, allowance: ImageAllowance): AttachmentKind.File {
    val name = file.string(path, "name")
    val mimeType = file.string(path, "mimeType")
    val encoded = file.string(path, "content")
    val size = base64DecodedSize(encoded, path)
    val bytes = if (isImageMimeType(mimeType) && allowance.admits(size)) {
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw WireDecodeException("$path.content is not base64")
        }
        allowance.remaining -= decoded.size
        decoded
    } else {
        null
    }
    return AttachmentKind.File(name, mimeType, size, bytes)
}

private fun base64DecodedSize(encoded: String, path: String): Int {
    if (encoded.length % 4 != 0) throw WireDecodeException("$path.content is not padded base64")
    val padding = when {
        encoded.endsWith("==") -> 2
        encoded.endsWith("=") -> 1
        else -> 0
    }
    return encoded.length / 4 * 3 - padding
}

