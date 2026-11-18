package dev.bugobliterator.itermbuddy.core.chat

import java.nio.ByteBuffer

/**
 * What a chat message says: upstream `Message.Content` (`Message.swift:286-336`), reduced to what
 * the phone shows or needs to merge a streamed reply.
 *
 * Every case that carries chat content overrides `toString()` to show lengths only.
 */
sealed interface MessageBody {
    /** `plainText(String, context:)`. The context rides along for the model and is not kept. */
    data class PlainText(val text: String) : MessageBody {
        override fun toString(): String = "PlainText(text(len=${text.length}))"
    }

    /** `markdown(String)`. */
    data class Markdown(val text: String) : MessageBody {
        override fun toString(): String = "Markdown(text(len=${text.length}))"
    }

    /** `multipart([Subpart], vectorStoreID:)`; the vector store is not kept. */
    data class Multipart(val parts: List<Subpart>) : MessageBody {
        override fun toString(): String = "Multipart($parts)"
    }

    /** `append(string:uuid:)`: a streamed text delta for the message whose `uniqueID` is [target]. */
    data class Append(val text: String, val target: String) : MessageBody {
        override fun toString(): String = "Append(text(len=${text.length}), target=$target)"
    }

    /** `appendAttachment(attachment:uuid:)`: a streamed attachment delta for [target]. */
    data class AppendAttachment(val attachment: Attachment, val target: String) : MessageBody {
        override fun toString(): String = "AppendAttachment($attachment, target=$target)"
    }

    /** `commit(UUID)`: the end of a streamed reply. Hidden; the Mac never forwards it (`Message.swift:549-562`). */
    data class Commit(val target: String) : MessageBody

    /**
     * `remoteCommandRequest(.classic(RemoteCommand), safe:)`: a session-bound tool call that waits
     * for the owner's Allow or Deny (`RemoteCommand.swift:29-31, 104`). [safe] is the Mac's safety
     * check verdict, nil when it was not run.
     */
    data class ClassicCommand(val content: CommandContent, val safe: Boolean?) : MessageBody

    /**
     * `remoteCommandRequest(.external(ExternalRemoteCommand), safe:)`: an orchestration tool call,
     * auto-executed, shown as information only (`RemoteCommand.swift:67-102`).
     */
    data class ExternalCommand(val name: String, val markdownDescription: String, val safe: Boolean?) : MessageBody {
        override fun toString(): String =
            "ExternalCommand(name(len=${name.length}), markdownDescription(len=${markdownDescription.length}), safe=$safe)"
    }

    /** `selectSessionRequest(Message, terminal:)`: the agent needs a terminal (or browser) session. */
    data class SelectSessionRequest(val terminal: Boolean) : MessageBody

    /** `clientLocal(ClientLocal)`: a system notice or prompt (`Message.swift:131-199`). */
    data class ClientNotice(val action: ClientAction) : MessageBody

    /** `watcherEvent(StatusUpdate)`: a workgroup watcher fired; [detail] is its human-readable body. */
    data class WatcherEvent(val detail: String) : MessageBody {
        override fun toString(): String = "WatcherEvent(detail(len=${detail.length}))"
    }

    /** `terminalCommand(TerminalCommand)`: a command the owner ran, with its output. */
    data class TerminalCommand(val command: String, val output: String) : MessageBody {
        override fun toString(): String = "TerminalCommand(command(len=${command.length}), output(len=${output.length}))"
    }

    /** `explanationRequest(request:)`, shown as the request's `snippetText` (`AIExplanationRequest.swift:32-40`). */
    data class ExplanationRequest(val snippet: String) : MessageBody {
        override fun toString(): String = "ExplanationRequest(snippet(len=${snippet.length}))"
    }

    /**
     * `explanationResponse(response, update, markdown:)`, shown as [markdown]: the case's markdown,
     * or the response's `mainResponse` when that is empty, as iOS renders it
     * (`MessageBubbleView.swift:231-234`).
     */
    data class ExplanationResponse(val markdown: String) : MessageBody {
        override fun toString(): String = "ExplanationResponse(markdown(len=${markdown.length}))"
    }

    /**
     * A hidden kind other than `commit` (`remoteCommandResponse`, `renameChat`, `setPermissions`,
     * `vectorStoreCreated`, `userCommand`), named by [case]. Its body is not read.
     */
    data class Hidden(val case: String) : MessageBody

    /** A content case or client-local action this build does not know (`Message.swift:328-336`). */
    data object Unsupported : MessageBody

    /**
     * A known, visible content [case] whose body did not decode. Upstream rejects the whole message
     * (`Message.swift:744-757`); the phone keeps it so one bad message cannot cost a history.
     */
    data class Unreadable(val case: String) : MessageBody
}

/** `Message.Subpart` (`Message.swift:246-252`). */
sealed interface Subpart {
    data class PlainText(val text: String) : Subpart {
        override fun toString(): String = "PlainText(text(len=${text.length}))"
    }

    data class Markdown(val text: String) : Subpart {
        override fun toString(): String = "Markdown(text(len=${text.length}))"
    }

    data class AttachmentPart(val attachment: Attachment) : Subpart

    /** Extra context for the model; iOS does not display it (`MessageBubbleView.swift`, `subpart`). */
    data class Context(val text: String) : Subpart {
        override fun toString(): String = "Context(text(len=${text.length}))"
    }
}

/** `LLM.Message.Attachment` (`LLM.swift:140-155`). */
data class Attachment(val inline: Boolean, val id: String, val kind: AttachmentKind) {
    override fun toString(): String = "Attachment(inline=$inline, id(len=${id.length}), kind=$kind)"
}

/** `LLM.Message.Attachment.AttachmentType` (`LLM.swift:141-153`). */
sealed interface AttachmentKind {
    data class Code(val text: String) : AttachmentKind {
        override fun toString(): String = "Code(text(len=${text.length}))"
    }

    /**
     * `statusUpdate(StatusUpdate)`: ephemeral agent progress. The whole update is kept, since
     * streamed status updates always coalesce (`LLM.swift:158-164`); [display] is what is shown.
     */
    data class StatusUpdate(val update: AgentStatusUpdate) : AttachmentKind {
        val display: String get() = update.displayString

        override fun toString(): String = "StatusUpdate($update)"
    }

    /**
     * `file(File)`. [size] is the decoded byte count of the wire's `content`. Bytes are kept only
     * for an `image/` type within the attachment budgets ([AttachmentBudgets]); otherwise null and
     * only the name, type and size are shown. `localPath` is not kept.
     */
    class File private constructor(
        val name: String,
        val mimeType: String,
        val size: Int,
        private val content: ByteArray?,
        @Suppress("UNUSED_PARAMETER") adopted: Adopted,
    ) : AttachmentKind {
        /** Holds its own copy of [bytes]. */
        constructor(name: String, mimeType: String, size: Int, bytes: ByteArray?) :
            this(name, mimeType, size, bytes?.copyOf(), Adopted)

        /** A fresh copy of the kept bytes, or null when they were not kept. */
        fun copyBytes(): ByteArray? = content?.copyOf()

        /** The kept bytes as a read-only view, without copying, or null when they were not kept. */
        fun bytesView(): ByteBuffer? = content?.let { ByteBuffer.wrap(it).asReadOnlyBuffer() }

        /** How many bytes are kept: 0 when none are. */
        val keptByteCount: Int get() = content?.size ?: 0

        val isImage: Boolean get() = isImageMimeType(mimeType)

        override fun equals(other: Any?): Boolean =
            other is File && other.name == name && other.mimeType == mimeType && other.size == size &&
                other.content.contentEquals(content)

        override fun hashCode(): Int = ((name.hashCode() * 31 + mimeType.hashCode()) * 31 + size) * 31 +
            content.contentHashCode()

        override fun toString(): String =
            "File(name(len=${name.length}), mimeType(len=${mimeType.length}), size=$size, " +
                "bytes=${content?.let { "${it.size} kept" } ?: "not kept"})"

        internal companion object {
            /** Takes ownership of [bytes] without copying: the caller must not keep or change them. */
            fun adopting(name: String, mimeType: String, size: Int, bytes: ByteArray?): File =
                File(name, mimeType, size, bytes, Adopted)
        }
    }

    /** `fileID(id:name:)`: a file held by the AI provider; only its name is shown. */
    data class FileId(val id: String, val name: String) : AttachmentKind {
        override fun toString(): String = "FileId(id(len=${id.length}), name(len=${name.length}))"
    }
}

/** iOS shows a file as an image when its type has the case-sensitive prefix `image/` (`MessageBubbleView.swift:407`). */
internal fun isImageMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

/** Marks the private constructors that take ownership of a byte array instead of copying it. */
internal object Adopted

/** `LLM.Message.StatusUpdate` (`LLM.swift:106-133`) with its `displayString` (`LLM.swift:517-553`). */
sealed interface AgentStatusUpdate {
    data object WebSearchStarted : AgentStatusUpdate

    data class WebSearchFinished(val query: String?) : AgentStatusUpdate {
        override fun toString(): String = "WebSearchFinished(query(len=${query?.length}))"
    }

    data object CodeInterpreterStarted : AgentStatusUpdate

    data object CodeInterpreterFinished : AgentStatusUpdate

    data class ReasoningSummaryUpdate(val text: String) : AgentStatusUpdate {
        override fun toString(): String = "ReasoningSummaryUpdate(text(len=${text.length}))"
    }

    data class Multipart(val parts: List<AgentStatusUpdate>) : AgentStatusUpdate

    /** The update flattened: nested multiparts expanded in order (`exploded`). */
    val exploded: List<AgentStatusUpdate>
        get() = if (this is Multipart) parts.flatMap { it.exploded } else listOf(this)

    val displayString: String
        get() = when (this) {
            WebSearchStarted -> "Searching the web…"
            is WebSearchFinished -> if (query != null) "Finished searching the web for $query." else "Finished searching the web."
            CodeInterpreterStarted -> "Executing code…"
            CodeInterpreterFinished -> "Finished executing code"
            is ReasoningSummaryUpdate -> text
            is Multipart -> partsForDisplay(parts).joinToString("\n") { it.displayString }
        }
}

/** Keeps the last "long" update and everything after it (`LLM.swift:518-532`, `subpartsForDisplay`). */
private fun partsForDisplay(parts: List<AgentStatusUpdate>): List<AgentStatusUpdate> {
    val single = parts.flatMap { it.exploded }
    val keepStart = listOf(
        single.indexOfLast { it is AgentStatusUpdate.ReasoningSummaryUpdate },
        single.indexOfLast { it is AgentStatusUpdate.WebSearchFinished },
        single.indexOfLast { it == AgentStatusUpdate.CodeInterpreterFinished },
    ).max()
    return if (keepStart > 0) single.subList(keepStart, single.size) else single
}

/** `ClientLocal.Action` (`Message.swift:132-196`): the ten actions, with what is displayed or later answered. */
sealed interface ClientAction {
    data object PickingSession : ClientAction

    /** `executingCommand(RemoteCommand)`: an approved command is running. */
    data class ExecutingCommand(val content: CommandContent) : ClientAction

    data class Notice(val text: String) : ClientAction {
        override fun toString(): String = "Notice(text(len=${text.length}))"
    }

    data class StreamingChanged(val state: StreamingState) : ClientAction

    /** `offerLink(terminal:guid:name:)`: offers to link the chat to a session. */
    data class OfferLink(val terminal: Boolean, val guid: String, val name: String?) : ClientAction {
        override fun toString(): String = "OfferLink(terminal=$terminal, guid=$guid, name(len=${name?.length}))"
    }

    data object OfferOrchestration : ClientAction

    data class Permissions(val terminal: Boolean, val guid: String) : ClientAction

    /** The agent asks to control a workgroup or session; answered in the Approvals phase. */
    data class WorkgroupPermissionRequest(
        val requestID: String,
        val workgroupID: String,
        val workgroupName: String,
        val summary: String,
    ) : ClientAction {
        override fun toString(): String =
            "WorkgroupPermissionRequest(requestID(len=${requestID.length}), workgroupID=$workgroupID, " +
                "workgroupName(len=${workgroupName.length}), summary(len=${summary.length}))"
    }

    /** The agent asks to switch the chat to orchestration; answered in the Approvals phase. */
    data class EnableOrchestrationRequest(val requestID: String) : ClientAction {
        override fun toString(): String = "EnableOrchestrationRequest(requestID(len=${requestID.length}))"
    }

    /** `orchestrationPermissionGranted(scope:name:)`: a mention granted control of [scope]. */
    data class OrchestrationPermissionGranted(val scope: String, val name: String) : ClientAction {
        override fun toString(): String = "OrchestrationPermissionGranted(scope=$scope, name(len=${name.length}))"
    }
}

/** `ClientLocal.Action.StreamingState` (`Message.swift:181-185`). */
enum class StreamingState(val raw: String) {
    STOPPED("stopped"),
    ACTIVE("active"),
    STOPPED_AUTOMATICALLY("stoppedAutomatically"),
}
