package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.isCanonicalUppercaseUuid
import java.nio.ByteBuffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who wrote a chat message: upstream `Participant` (`Message.swift:57-60`). Strict, as upstream:
 * any other raw value is rejected ([fromRaw] returns null and the decoder throws).
 */
enum class Participant(val raw: String) {
    USER("user"),
    AGENT("agent"),
    ;

    companion object {
        fun fromRaw(raw: String): Participant? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * `turnLifecycle`'s event (`Message.swift:68-81`). An unknown raw value is [UNKNOWN_FUTURE],
 * as upstream's custom decoder degrades it.
 */
enum class TurnEvent(val raw: String) {
    STARTED("started"),
    ENDED("ended"),
    UNKNOWN_FUTURE("unknownFuture"),
    ;

    companion object {
        fun fromRaw(raw: String): TurnEvent = entries.firstOrNull { it.raw == raw } ?: UNKNOWN_FUTURE
    }
}

/**
 * A workgroup member's state (`WorkgroupWatcher.swift:170-175`). Android leniency: an unknown raw
 * value is [UNKNOWN] (upstream's synthesized enum throws, losing the whole `workgroupInfo`).
 */
enum class SessionState(val raw: String) {
    IDLE("idle"),
    WORKING("working"),
    WAITING("waiting"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromRaw(raw: String): SessionState = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * The fields of upstream `Chat` (`Chat.swift:14-88`) the phone uses; the rest (`permissions`,
 * `watchers`, `modelName`, ...) are read past, not validated. Dates are milliseconds since 1970.
 * The icon is the chat's PNG, held privately ([copyIcon], [iconView]). Equality compares content, the icon's bytes
 * included; [toString] shows lengths only.
 */
class ChatSummary private constructor(
    val id: String,
    val title: String,
    val createdMillis: Double,
    val lastModifiedMillis: Double,
    val orchestrationEnabled: Boolean,
    val terminalSessionGuid: String?,
    val browserSessionGuid: String?,
    val claimedScopes: List<String>,
    private val iconBytes: ByteArray?,
    @Suppress("UNUSED_PARAMETER") adopted: Adopted,
) {
    /** Holds its own copy of [icon]. */
    constructor(
        id: String,
        title: String,
        createdMillis: Double,
        lastModifiedMillis: Double,
        orchestrationEnabled: Boolean,
        terminalSessionGuid: String?,
        browserSessionGuid: String?,
        claimedScopes: List<String>,
        icon: ByteArray?,
    ) : this(
        id, title, createdMillis, lastModifiedMillis, orchestrationEnabled, terminalSessionGuid, browserSessionGuid,
        claimedScopes, icon?.copyOf(), Adopted,
    )

    /** A fresh copy of the icon's bytes, or null when the chat has none. */
    fun copyIcon(): ByteArray? = iconBytes?.copyOf()

    /** The icon's bytes as a read-only view, without copying, or null when the chat has none. */
    fun iconView(): ByteBuffer? = iconBytes?.let { ByteBuffer.wrap(it).asReadOnlyBuffer() }

    /** The icon's size in bytes, without copying it. */
    val iconSize: Int? get() = iconBytes?.size

    /** [claimedScopes] that name real workgroups: without the synthetic `session:<guid>` scopes (`Chat.swift:59-68`). */
    val workgroupScopes: List<String> get() = claimedScopes.filterNot { it.startsWith(SESSION_SCOPE_PREFIX) }

    override fun equals(other: Any?): Boolean =
        other is ChatSummary && other.id == id && other.title == title && other.createdMillis == createdMillis &&
            other.lastModifiedMillis == lastModifiedMillis && other.orchestrationEnabled == orchestrationEnabled &&
            other.terminalSessionGuid == terminalSessionGuid && other.browserSessionGuid == browserSessionGuid &&
            other.claimedScopes == claimedScopes && other.iconBytes.contentEquals(iconBytes)

    override fun hashCode(): Int = listOf(
        id, title, createdMillis, lastModifiedMillis, orchestrationEnabled, terminalSessionGuid, browserSessionGuid,
        claimedScopes, iconBytes.contentHashCode(),
    ).hashCode()

    override fun toString(): String =
        "ChatSummary(id=$id, title(len=${title.length}), createdMillis=$createdMillis, " +
            "lastModifiedMillis=$lastModifiedMillis, orchestrationEnabled=$orchestrationEnabled, " +
            "terminalSessionGuid=$terminalSessionGuid, browserSessionGuid=$browserSessionGuid, " +
            "claimedScopes=${claimedScopes.size}, icon=${iconBytes?.size?.let { "$it bytes" }})"

    companion object {
        /** The prefix of a synthetic single-session scope (`Chat.swift:59-68`). */
        const val SESSION_SCOPE_PREFIX = "session:"

        /** Takes ownership of [icon] without copying: the caller must not keep or change it. */
        internal fun adopting(
            id: String,
            title: String,
            createdMillis: Double,
            lastModifiedMillis: Double,
            orchestrationEnabled: Boolean,
            terminalSessionGuid: String?,
            browserSessionGuid: String?,
            claimedScopes: List<String>,
            icon: ByteArray?,
        ): ChatSummary = ChatSummary(
            id, title, createdMillis, lastModifiedMillis, orchestrationEnabled, terminalSessionGuid, browserSessionGuid,
            claimedScopes, icon, Adopted,
        )
    }
}

/**
 * A chat-list row (`CompanionMessages.swift:64-71`): the chat, the Mac's snippet of its last
 * visible message, and whether it is muted (nil from a Mac older than revision 5).
 */
data class ChatListEntry(val chat: ChatSummary, val snippet: String?, val muted: Boolean?) {
    override fun toString(): String = "ChatListEntry(chat=$chat, snippet(len=${snippet?.length}), muted=$muted)"
}

/**
 * One chat message (`Message.swift:242-545`): the fields the phone uses. [uniqueID] is held
 * uppercase; [sentMillis] is milliseconds since 1970 as the Mac sent it.
 */
data class ChatMessage(
    val chatID: String,
    val author: Participant,
    val body: MessageBody,
    val sentMillis: Double,
    val uniqueID: String,
) {
    /** Not shown as a message of its own (`hiddenFromClient`, `Message.swift:549-562`). */
    val hidden: Boolean get() = body is MessageBody.Hidden || body is MessageBody.Commit

    /**
     * Rendered as a request card (`MessageBubbleView.swift:216-379`): a classic command request,
     * a select-session request, the client-local workgroup-permission, enable-orchestration and
     * offer-link prompts, and the orchestration-permission-granted card with its Revoke button
     * (`MessageBubbleView.swift:367-379`), which neither blocks nor is abandoned.
     */
    val isInteractiveRequest: Boolean
        get() = when (body) {
            is MessageBody.ClassicCommand, is MessageBody.SelectSessionRequest -> true
            is MessageBody.ClientNotice -> body.action is ClientAction.WorkgroupPermissionRequest ||
                body.action is ClientAction.EnableOrchestrationRequest || body.action is ClientAction.OfferLink ||
                body.action is ClientAction.OrchestrationPermissionGranted
            else -> false
        }

    /**
     * The agent is parked on the owner: a classic command request or a select-session request, as
     * the Mac's notifier counts them (`CompanionAgentActivityNotifier.swift:64-77`), plus the two
     * client-local parks, workgroup permission and enable orchestration. An external command is
     * auto-executed and does not block.
     */
    val isBlockingRequest: Boolean
        get() = when (body) {
            is MessageBody.ClassicCommand, is MessageBody.SelectSessionRequest -> true
            is MessageBody.ClientNotice -> body.action is ClientAction.WorkgroupPermissionRequest ||
                body.action is ClientAction.EnableOrchestrationRequest
            else -> false
        }

    /**
     * A message typed now abandons this request: a classic command approval or an
     * enable-orchestration request (`ChatAgent.swift:1289-1292`, `ChatService.swift:229-237`).
     */
    val isAbandonableRequest: Boolean
        get() = body is MessageBody.ClassicCommand ||
            (body is MessageBody.ClientNotice && body.action is ClientAction.EnableOrchestrationRequest)

    override fun toString(): String =
        "ChatMessage(chatID=$chatID, author=$author, body=$body, sentMillis=$sentMillis, uniqueID=$uniqueID)"
}

/** How one @-mention resolved on the Mac (`CompanionMessages.swift:76-89`). */
data class MentionResolution(
    val identifier: String,
    val displayName: String?,
    val sessionGuid: String?,
    val workgroupID: String?,
) {
    override fun toString(): String =
        "MentionResolution(identifier=$identifier, displayName(len=${displayName?.length}), " +
            "sessionGuid=$sessionGuid, workgroupID=$workgroupID)"
}

/** One member of a workgroup (`CompanionMessages.swift:117-129`). */
data class WorkgroupMember(
    val roleName: String,
    val sessionGuid: String?,
    val sessionName: String?,
    val statusText: String?,
    val detailText: String?,
    val state: SessionState,
) {
    override fun toString(): String =
        "WorkgroupMember(roleName(len=${roleName.length}), sessionGuid=$sessionGuid, " +
            "sessionName(len=${sessionName?.length}), statusText(len=${statusText?.length}), " +
            "detailText(len=${detailText?.length}), state=$state)"
}

/** A workgroup and its members (`CompanionMessages.swift:131-136`). */
data class WorkgroupInfo(val workgroupID: String, val name: String, val members: List<WorkgroupMember>) {
    override fun toString(): String = "WorkgroupInfo(workgroupID=$workgroupID, name(len=${name.length}), members=$members)"
}

/**
 * What a new chat is bound to (`createChat`'s `mode`), encoded as Swift's synthesized enum
 * coding writes it: `{"orchestrator":{}}` or `{"session":{"guid":…}}`.
 */
sealed interface NewChatMode {
    fun encode(): JsonObject

    data object Orchestrator : NewChatMode {
        override fun encode(): JsonObject = JsonObject(mapOf("orchestrator" to JsonObject(emptyMap())))
    }

    data class Session(val guid: String) : NewChatMode {
        override fun encode(): JsonObject =
            JsonObject(mapOf("session" to JsonObject(mapOf("guid" to JsonPrimitive(guid)))))
    }
}

/**
 * A message the owner sends: the user `plainText` message iOS publishes (`AppModel.swift:3100-3118`).
 * [uniqueID] must be a canonical uppercase UUID and [sentMillis] whole milliseconds since 1970.
 */
data class UserTextMessage(val chatID: String, val text: String, val sentMillis: Long, val uniqueID: String) {
    init {
        require(isCanonicalUppercaseUuid(uniqueID)) { "uniqueID must be an uppercase canonical UUID" }
        require(sentMillis >= 0) { "sentMillis must not be negative" }
    }

    override fun toString(): String =
        "UserTextMessage(chatID=$chatID, text(len=${text.length}), sentMillis=$sentMillis, uniqueID=$uniqueID)"
}
