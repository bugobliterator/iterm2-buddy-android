package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.core.chat.AttachmentBudgets
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.SessionState
import dev.bugobliterator.itermbuddy.core.chat.TurnEvent
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupInfo
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupMember
import dev.bugobliterator.itermbuddy.core.chat.decodeChatMessage
import dev.bugobliterator.itermbuddy.core.chat.decodeChatMessages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Codecs for the agent chat messages (upstream `CompanionMessages.swift:695-761, 1003-1141`).
 * Encoding writes what Swift's synthesized `Codable` writes: labelled values by name, an
 * unlabelled one under `_0`, a `UUID` as its uppercase string, a `Date` as whole milliseconds since
 * 1970 written as an integer literal (`WireCoding.swift:15-25`). Decoding follows `Envelope.kt`'s
 * rules (unknown keys ignored, optionals as `decodeIfPresent`, a known case with a bad body throws
 * WireDecodeException naming keys and cases, never values), with the content model's leniencies
 * for message bodies (`MessageDecoding.kt`) and one more here: an unknown workgroup member state is
 * `SessionState.UNKNOWN` where upstream rejects the whole `workgroupInfo` (`WW:170-175`).
 */

/**
 * Upstream `CompanionSessionScreenInfo` (`MSG:140-153`): a session's content geometry, fetched to
 * learn whether the session exists. [name] is the session's name: [toString] shows its length only.
 */
data class SessionScreenInfo(
    val guid: String,
    val name: String,
    val lineCount: Int,
    val columns: Int,
    val width: Double,
    val lineHeight: Double,
    val scale: Double,
) {
    override fun toString(): String =
        "SessionScreenInfo(guid=$guid, name(len=${name.length}), lineCount=$lineCount, columns=$columns, " +
            "width=$width, lineHeight=$lineHeight, scale=$scale)"
}

/**
 * Upstream `CompanionSessionContent` (`MSG:156-163`): the session lines [firstLine] ..< [firstLine] +
 * [lineCount] the Mac actually rendered, as one PNG. [pngData] is rendered terminal content: the
 * value keeps its own copy, hands out a fresh copy on every read, and [toString] shows only its size.
 */
class SessionContent(val guid: String, val firstLine: Int, val lineCount: Int, pngData: ByteArray) {
    private val png = pngData.copyOf()

    /** A copy of the PNG bytes. */
    val pngData: ByteArray get() = png.copyOf()

    /** The PNG's size in bytes, without a copy. */
    val pngSize: Int get() = png.size

    override fun equals(other: Any?): Boolean =
        other is SessionContent && other.guid == guid && other.firstLine == firstLine &&
            other.lineCount == lineCount && other.png.contentEquals(png)

    override fun hashCode(): Int = ((guid.hashCode() * 31 + firstLine) * 31 + lineCount) * 31 + png.contentHashCode()

    override fun toString(): String =
        "SessionContent(guid=$guid, firstLine=$firstLine, lineCount=$lineCount, pngData=${png.size} bytes)"
}

private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))

internal fun encodeChatIdCase(name: String, chatID: String): JsonObject = case(name, obj("chatID" to JsonPrimitive(chatID)))

internal fun encodeCreateChat(message: PhoneMessage.CreateChat): JsonObject =
    case("createChat", obj("title" to JsonPrimitive(message.title), "mode" to message.mode.encode()))

internal fun encodeDeleteMessages(message: PhoneMessage.DeleteMessages): JsonObject = case(
    "deleteMessages",
    obj(
        "chatID" to JsonPrimitive(message.chatID),
        "messageIDs" to JsonArray(message.messageIDs.map { JsonPrimitive(it) }),
    ),
)

internal fun encodeSetChatMuted(message: PhoneMessage.SetChatMuted): JsonObject =
    case("setChatMuted", obj("chatID" to JsonPrimitive(message.chatID), "muted" to JsonPrimitive(message.muted)))

/**
 * `publish(message:toChatID:partial:)` with exactly the five keys of the user message iOS builds
 * (`AM:3100-3118`): `context`, `inResponseTo`, `responseID`, `agentReasoning`, `configuration` and
 * `firstBlobRef` are nil and Swift omits them.
 */
internal fun encodePublish(message: PhoneMessage.Publish): JsonObject {
    val user = message.message
    val content = obj("plainText" to obj(UNLABELLED to JsonPrimitive(user.text)))
    return case(
        "publish",
        obj(
            "message" to obj(
                "chatID" to JsonPrimitive(user.chatID),
                "author" to JsonPrimitive(Participant.USER.raw),
                "content" to content,
                "sentDate" to JsonPrimitive(user.sentMillis),
                "uniqueID" to JsonPrimitive(user.uniqueID),
            ),
            "toChatID" to JsonPrimitive(message.toChatID),
            "partial" to JsonPrimitive(false),
        ),
    )
}

internal fun encodeResolveMentions(message: PhoneMessage.ResolveMentions): JsonObject =
    case("resolveMentions", obj("identifiers" to JsonArray(message.identifiers.map { JsonPrimitive(it) })))

internal fun encodeFetchWorkgroupInfo(message: PhoneMessage.FetchWorkgroupInfo): JsonObject =
    case("fetchWorkgroupInfo", obj("workgroupID" to JsonPrimitive(message.workgroupID)))

internal fun encodeFetchSessionScreenInfo(message: PhoneMessage.FetchSessionScreenInfo): JsonObject =
    case("fetchSessionScreenInfo", obj("sessionGuid" to JsonPrimitive(message.sessionGuid)))

internal fun encodeFetchSessionContent(message: PhoneMessage.FetchSessionContent): JsonObject = case(
    "fetchSessionContent",
    obj(
        "sessionGuid" to JsonPrimitive(message.sessionGuid),
        "firstLine" to JsonPrimitive(message.firstLine),
        "lineCount" to JsonPrimitive(message.lineCount),
    ),
)

// Decoders. [budgets] bound the file attachment bytes a history or delivery keeps.

private fun JsonArray.objects(path: String): List<JsonObject> =
    mapIndexed { index, element -> element as? JsonObject ?: throw WireDecodeException("$path[$index] is not an object") }

private fun JsonObject.optionalString(path: String, key: String): String? = ifPresent(key) { string(path, it) }

internal fun decodeChatsAndSessions(body: JsonElement): MacMessage {
    val case = "chatsAndSessions"
    val reply = body.asObject(case)
    val chats = decodeChatList(reply.array(case, "chats"), "$case.chats")
    val sessions = reply.array(case, "sessions").objects("$case.sessions").map(::decodeSessionSummary)
    return MacMessage.ChatsAndSessions(chats, sessions)
}

internal fun decodeChatCreated(body: JsonElement): MacMessage {
    val case = "chatCreated"
    return MacMessage.ChatCreated(decodeChatListEntry(body.asObject(case).obj(case, "entry"), "$case.entry"))
}

internal fun decodeChatListChanged(body: JsonElement): MacMessage {
    val case = "chatListChanged"
    return MacMessage.ChatListChanged(decodeChatList(body.asObject(case).array(case, "chats"), "$case.chats"))
}

internal fun decodeHistory(body: JsonElement, budgets: AttachmentBudgets): MacMessage {
    val case = "history"
    val history = body.asObject(case)
    val chatID = history.string(case, "chatID")
    val messages = decodeChatMessages(history.array(case, "messages"), budgets)
    return MacMessage.History(chatID, messages, history.long(case, "maxSeq"))
}

internal fun decodeDelivery(body: JsonElement, budgets: AttachmentBudgets): MacMessage {
    val case = "delivery"
    val delivery = body.asObject(case)
    val message = decodeChatMessage(delivery.obj(case, "message"), budgets)
    return MacMessage.Delivery(message, delivery.string(case, "chatID"), delivery.boolean(case, "partial"))
}

internal fun decodeMessagesRemoved(body: JsonElement): MacMessage {
    val case = "messagesRemoved"
    val removed = body.asObject(case)
    val ids = removed.array(case, "messageIDs").mapIndexed { index, element ->
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        canonicalUuidOrNull(text) ?: throw WireDecodeException("$case.messageIDs[$index] is not a UUID")
    }
    return MacMessage.MessagesRemoved(removed.string(case, "chatID"), ids)
}

internal fun decodeTypingStatus(body: JsonElement): MacMessage {
    val case = "typingStatus"
    val typing = body.asObject(case)
    val participant = Participant.fromRaw(typing.string(case, "participant"))
        ?: throw WireDecodeException("$case.participant is not a known participant")
    return MacMessage.TypingStatus(typing.boolean(case, "isTyping"), participant, typing.string(case, "chatID"))
}

/** An unknown event is [TurnEvent.UNKNOWN_FUTURE], as upstream's custom decoder reads it (`MS:68-81`). */
internal fun decodeTurnLifecycle(body: JsonElement): MacMessage {
    val case = "turnLifecycle"
    val turn = body.asObject(case)
    return MacMessage.TurnLifecycle(TurnEvent.fromRaw(turn.string(case, "event")), turn.string(case, "chatID"))
}

internal fun decodeMentionsResolved(body: JsonElement): MacMessage {
    val case = "mentionsResolved"
    val path = "$case.$UNLABELLED"
    val resolutions = body.asObject(case).array(case, UNLABELLED).objects(path).map { resolution ->
        MentionResolution(
            identifier = resolution.string(path, "identifier"),
            displayName = resolution.optionalString(path, "displayName"),
            sessionGuid = resolution.optionalString(path, "sessionGuid"),
            workgroupID = resolution.optionalString(path, "workgroupID"),
        )
    }
    return MacMessage.MentionsResolved(resolutions)
}

/** A member state this build does not know is [SessionState.UNKNOWN]; it must still be a string. */
internal fun decodeWorkgroupInfo(body: JsonElement): MacMessage {
    val case = "workgroupInfo"
    val info = body.asObject(case).obj(case, UNLABELLED)
    val path = "$case.$UNLABELLED"
    val members = info.array(path, "members").objects("$path.members").map { member ->
        val at = "$path.members"
        WorkgroupMember(
            roleName = member.string(at, "roleName"),
            sessionGuid = member.optionalString(at, "sessionGuid"),
            sessionName = member.optionalString(at, "sessionName"),
            statusText = member.optionalString(at, "statusText"),
            detailText = member.optionalString(at, "detailText"),
            state = SessionState.fromRaw(member.string(at, "state")),
        )
    }
    return MacMessage.WorkgroupInfoMessage(WorkgroupInfo(info.string(path, "workgroupID"), info.string(path, "name"), members))
}

internal fun decodeAiAvailabilityChanged(body: JsonElement): MacMessage {
    val case = "aiAvailabilityChanged"
    return MacMessage.AiAvailabilityChanged(body.asObject(case).boolean(case, "available"))
}

internal fun decodeSessionScreenInfo(body: JsonElement): MacMessage {
    val case = "sessionScreenInfo"
    val info = body.asObject(case).obj(case, UNLABELLED)
    val path = "$case.$UNLABELLED"
    return MacMessage.SessionScreenInfoMessage(
        SessionScreenInfo(
            guid = info.string(path, "guid"),
            name = info.string(path, "name"),
            lineCount = info.int(path, "lineCount"),
            columns = info.int(path, "columns"),
            width = info.double(path, "width"),
            lineHeight = info.double(path, "lineHeight"),
            scale = info.double(path, "scale"),
        ),
    )
}

internal fun decodeSessionContent(body: JsonElement): MacMessage {
    val case = "sessionContent"
    val content = body.asObject(case).obj(case, UNLABELLED)
    val path = "$case.$UNLABELLED"
    return MacMessage.SessionContentMessage(
        SessionContent(
            guid = content.string(path, "guid"),
            firstLine = content.int(path, "firstLine"),
            lineCount = content.int(path, "lineCount"),
            pngData = content.base64(path, "pngData"),
        ),
    )
}

private fun decodeChatList(array: JsonArray, path: String): List<ChatListEntry> =
    array.objects(path).map { decodeChatListEntry(it, path) }

/** `CompanionChatListEntry` (`MSG:64-71`): `muted` is absent from a Mac before revision 5. */
private fun decodeChatListEntry(entry: JsonObject, path: String): ChatListEntry = ChatListEntry(
    chat = decodeChatSummary(entry.obj(path, "chat"), "$path.chat"),
    snippet = entry.optionalString(path, "snippet"),
    muted = entry.ifPresent("muted") { boolean(path, it) },
)

/**
 * Upstream `Chat` (`Chat.swift:14-88`), the fields the phone uses. Those are required as upstream's
 * synthesized decode requires them, except the optional session bindings and `icon` (standard
 * base64, adopted without a copy). `permissions`, `watchers`, `modelName` and the rest are read past.
 */
private fun decodeChatSummary(chat: JsonObject, path: String): ChatSummary = ChatSummary.adopting(
    id = chat.string(path, "id"),
    title = chat.string(path, "title"),
    createdMillis = chat.millis(path, "creationDate"),
    lastModifiedMillis = chat.millis(path, "lastModifiedDate"),
    orchestrationEnabled = chat.boolean(path, "orchestrationEnabled"),
    terminalSessionGuid = chat.optionalString(path, "terminalSessionGuid"),
    browserSessionGuid = chat.optionalString(path, "browserSessionGuid"),
    claimedScopes = chat.array(path, "claimedScopes").mapIndexed { index, scope ->
        (scope as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw WireDecodeException("$path.claimedScopes[$index] is not a string")
    },
    icon = chat.ifPresent("icon") { base64(path, it) },
)
