package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.TurnEvent
import dev.bugobliterator.itermbuddy.core.chat.UserTextMessage
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupInfo
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A message the phone sends to the Mac: the pairing, live-session, history, selection, browser
 * and agent chat subset of upstream `CompanionClientMessage`
 * (`sources/Companion/Shared/CompanionMessages.swift`). The live-session cases' encoders are in
 * `StreamMessages.kt`, the history and selection ones in `HistorySelectionMessages.kt`, the
 * browser ones in `BrowserMessages.kt`, the chat ones in `ChatMessages.kt`.
 *
 * No case here encodes an approval, a push or a notification message (`remoteCommandDecision`,
 * `selectSessionResponse`, `linkSession`, a `userCommand` publish, `pushStatus`, `syncSince`,
 * `messagesSince`, `notificationPermissionResponse`): those belong to later phases.
 *
 * Payloads follow Swift's synthesized enum coding: `{"<case>": {...}}`, labelled
 * values by name, an unlabelled value under `_0`, `{}` for a case with no values,
 * and `Data` as standard padded base64.
 */
sealed interface PhoneMessage {
    /** The payload object, `{"<case>": {...}}`. */
    fun payload(): JsonObject

    /** `hello(revision:minimumPeer:)`. The defaults are what this app advertises. */
    data class Hello(
        val revision: Int = ProtocolRevision.REVISION,
        val minimumPeer: Int = ProtocolRevision.MINIMUM_PEER,
    ) : PhoneMessage {
        override fun payload(): JsonObject = case(
            "hello",
            JsonObject(mapOf("revision" to JsonPrimitive(revision), "minimumPeer" to JsonPrimitive(minimumPeer))),
        )
    }

    /** `relayRoomSecret(Data)`. Holds its own copy of [secret]. */
    class RelayRoomSecret(secret: ByteArray) : PhoneMessage {
        private val bytes = secret.copyOf()

        val secret: ByteArray get() = bytes.copyOf()

        override fun payload(): JsonObject = case(
            "relayRoomSecret",
            JsonObject(mapOf(UNLABELLED to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))),
        )

        override fun equals(other: Any?): Boolean = other is RelayRoomSecret && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "RelayRoomSecret(${bytes.size} bytes)"
    }

    data object Ping : PhoneMessage {
        override fun payload(): JsonObject = case("ping", EMPTY)
    }

    /** Fire-and-forget: sent without a request ID. */
    data object Unpairing : PhoneMessage {
        override fun payload(): JsonObject = case("unpairing", EMPTY)
    }

    /** `fetchSessionTree`, a request: the Mac replies with [MacMessage.SessionTreeMessage]. */
    data object FetchSessionTree : PhoneMessage {
        override fun payload(): JsonObject = case("fetchSessionTree", EMPTY)
    }

    /**
     * `startSessionStream(sessionGuid:params:)`, a request: the Mac replies with
     * [MacMessage.StreamStarted] or an error.
     */
    data class StartSessionStream(val sessionGuid: String, val params: StreamParams = StreamParams()) : PhoneMessage {
        override fun payload(): JsonObject = encodeStartSessionStream(this)
    }

    /** `stopSessionStream(streamID:)`, fire-and-forget; the Mac answers with `streamEnded`. */
    data class StopSessionStream(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("stopSessionStream", streamID)
    }

    /**
     * `streamAck(streamID:lastPTSMilliseconds:queueDepth:)`, fire-and-forget.
     * [lastPTSMilliseconds] is upstream `UInt64`. The app only ever sends [queueDepth] 0.
     */
    data class StreamAck(val streamID: Long, val lastPTSMilliseconds: Long, val queueDepth: Int = 0) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamAck(this)
    }

    /** `requestKeyframe(streamID:)`, fire-and-forget. */
    data class RequestKeyframe(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("requestKeyframe", streamID)
    }

    /** `sendKey(sessionGuid:event:)`, fire-and-forget. */
    data class SendKey(val sessionGuid: String, val event: KeyEvent) : PhoneMessage {
        override fun payload(): JsonObject = encodeSendKey(this)

        override fun toString(): String = "SendKey(sessionGuid=$sessionGuid, event=$event)"
    }

    /** `pasteText(sessionGuid:text:)`, fire-and-forget. [toString] shows only the text's length. */
    data class PasteText(val sessionGuid: String, val text: String) : PhoneMessage {
        override fun payload(): JsonObject = encodePasteText(this)

        override fun toString(): String = "PasteText(sessionGuid=$sessionGuid, text(len=${text.length}))"
    }

    /** `resizeSession(sessionGuid:columns:rows:)`, fire-and-forget; never sent automatically. */
    data class ResizeSession(val sessionGuid: String, val columns: Int, val rows: Int) : PhoneMessage {
        override fun payload(): JsonObject = encodeResizeSession(this)
    }

    /**
     * `fetchHistoryTile(streamID:firstAbsLine:lineCount:generationId:)`, a request: the Mac
     * replies with [MacMessage.HistoryTileMessage] or an error. [streamID] and [generationId]
     * are upstream `UInt32`, [firstAbsLine] `Int64`. [lineCount] must be in 1..200
     * ([MAX_HISTORY_TILE_LINES], the Mac's cap); the encoder refuses anything else.
     */
    data class FetchHistoryTile(val streamID: Long, val firstAbsLine: Long, val lineCount: Int, val generationId: Long) :
        PhoneMessage {
        override fun payload(): JsonObject = encodeFetchHistoryTile(this)
    }

    /**
     * `reportScrollWheel(streamID:up:lines:)`, fire-and-forget: only while the config has both
     * `altScreen` and `scrollWheelReporting`. [up] reveals older content; [lines] is the notch
     * count and must be positive.
     */
    data class ReportScrollWheel(val streamID: Long, val up: Boolean, val lines: Int) : PhoneMessage {
        override fun payload(): JsonObject = encodeReportScrollWheel(this)
    }

    /**
     * `selectionGesture(streamID:phase:mode:point:)`, fire-and-forget. Send the begin's [mode] on
     * move and end too: the Mac picks the character or snapping path from [mode] on every phase
     * (`CompanionHostBridge.swift:818`).
     */
    data class SelectionGesture(
        val streamID: Long,
        val phase: SelectionPhase,
        val mode: SelectionMode,
        val point: SelectionPoint,
    ) : PhoneMessage {
        override fun payload(): JsonObject = encodeSelectionGesture(this)
    }

    /** `clearSelection(streamID:)`, fire-and-forget; a [MacMessage.SelectionRange] follows. */
    data class ClearSelection(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamOnlyCase("clearSelection", streamID)
    }

    /** `selectAllInStream(streamID:)`, fire-and-forget; a [MacMessage.SelectionRange] follows. */
    data class SelectAllInStream(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamOnlyCase("selectAllInStream", streamID)
    }

    /** `copySelection(sessionGuid:)`, a request: the Mac replies with [MacMessage.SelectionText]. */
    data class CopySelection(val sessionGuid: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeCopySelection(this)
    }

    /**
     * `browserTap(streamID:x:y:)`, fire-and-forget (revision 15): a click on a browser stream's
     * page at ([x], [y]) in encoded-image pixels, origin top left. Integral values are written as
     * integers (`300`), as Swift writes them; NaN and infinities are refused.
     */
    data class BrowserTap(val streamID: Long, val x: Double, val y: Double) : PhoneMessage {
        override fun payload(): JsonObject = encodeBrowserTap(this)
    }

    /**
     * `browserScroll(streamID:x:y:deltaX:deltaY:)`, fire-and-forget (revision 15): scrolls what is
     * under ([x], [y]) (encoded-image pixels, origin top left) so the content moves by
     * ([deltaX], [deltaY]) encoded-image pixels, positive right and down, following the finger.
     */
    data class BrowserScroll(val streamID: Long, val x: Double, val y: Double, val deltaX: Double, val deltaY: Double) :
        PhoneMessage {
        override fun payload(): JsonObject = encodeBrowserScroll(this)
    }

    /** `browserGoBack(streamID:)`, fire-and-forget (revision 15); the result is a [MacMessage.BrowserNavigationChanged]. */
    data class BrowserGoBack(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("browserGoBack", streamID)
    }

    /** `browserGoForward(streamID:)`, fire-and-forget (revision 15). */
    data class BrowserGoForward(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("browserGoForward", streamID)
    }

    /** `browserReload(streamID:)`, fire-and-forget (revision 15). */
    data class BrowserReload(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("browserReload", streamID)
    }

    /** `browserStopLoading(streamID:)`, fire-and-forget (revision 15). */
    data class BrowserStopLoading(val streamID: Long) : PhoneMessage {
        override fun payload(): JsonObject = encodeStreamIdCase("browserStopLoading", streamID)
    }

    /**
     * `browserLoad(streamID:text:)`, fire-and-forget (revision 15): the address field's text,
     * which the Mac opens as a URL or searches for. The caller trims it and sends nothing when it
     * is empty, as iOS does (`AppModel.swift:4706-4710`). [toString] shows only its length.
     */
    data class BrowserLoad(val streamID: Long, val text: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeBrowserLoad(this)

        override fun toString(): String = "BrowserLoad(streamID=$streamID, text(len=${text.length}))"
    }

    // Agent chat (`MSG:695-761`). Requests, as iOS `CompanionClient` sends them (`CC:95-236`):
    // ListChatsAndSessions, CreateChat, Subscribe, ResolveMentions, FetchWorkgroupInfo and
    // FetchSessionScreenInfo. The rest are fire-and-forget sends with no request ID.

    /** `listChatsAndSessions`, a request: the Mac replies with [MacMessage.ChatsAndSessions]. */
    data object ListChatsAndSessions : PhoneMessage {
        override fun payload(): JsonObject = case("listChatsAndSessions", EMPTY)
    }

    /** `createChat(title:mode:)`, a request: the Mac replies with [MacMessage.ChatCreated]. [toString] hides the title. */
    data class CreateChat(val title: String, val mode: NewChatMode) : PhoneMessage {
        override fun payload(): JsonObject = encodeCreateChat(this)

        override fun toString(): String = "CreateChat(title(len=${title.length}), mode=$mode)"
    }

    /** `deleteChat(chatID:)`, fire-and-forget; a refusal comes back as an unsolicited error. */
    data class DeleteChat(val chatID: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeChatIdCase("deleteChat", chatID)
    }

    /**
     * `deleteMessages(chatID:messageIDs:)`, fire-and-forget (Mac revision 12): truncates the chat from
     * the first of [messageIDs], a suffix. Each ID must be a canonical uppercase UUID, the form the
     * phone holds and Swift's `UUID` writes.
     */
    data class DeleteMessages(val chatID: String, val messageIDs: List<String>) : PhoneMessage {
        init {
            require(messageIDs.all(::isCanonicalUppercaseUuid)) { "messageIDs must be uppercase canonical UUIDs" }
        }

        override fun payload(): JsonObject = encodeDeleteMessages(this)
    }

    /** `setChatMuted(chatID:muted:)`, fire-and-forget (Mac revision 5). */
    data class SetChatMuted(val chatID: String, val muted: Boolean) : PhoneMessage {
        override fun payload(): JsonObject = encodeSetChatMuted(this)
    }

    /**
     * `subscribe(chatID:)`, a request: the Mac installs the subscription, then replies with
     * [MacMessage.History] (empty for an unknown chat), then sends live events.
     */
    data class Subscribe(val chatID: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeChatIdCase("subscribe", chatID)
    }

    /** `unsubscribe(chatID:)`, fire-and-forget. */
    data class Unsubscribe(val chatID: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeChatIdCase("unsubscribe", chatID)
    }

    /**
     * `publish(message:toChatID:partial:)`, fire-and-forget, always `partial:false` (`CC:176-178`).
     * The message is the owner's plain text and nothing else; [toChatID] is the message's own chat,
     * as iOS always sends it (`AM:3100-3118`).
     */
    data class Publish(val message: UserTextMessage, val toChatID: String = message.chatID) : PhoneMessage {
        init {
            require(toChatID == message.chatID) { "a message is published to its own chat" }
        }

        override fun payload(): JsonObject = encodePublish(this)

        override fun toString(): String = "Publish(message=$message, toChatID=$toChatID)"
    }

    /**
     * `resolveMentions(identifiers:)`, a request: the Mac replies with [MacMessage.MentionsResolved],
     * one entry per identifier, in order. [toString] shows only the count.
     */
    data class ResolveMentions(val identifiers: List<String>) : PhoneMessage {
        override fun payload(): JsonObject = encodeResolveMentions(this)

        override fun toString(): String = "ResolveMentions(identifiers=${identifiers.size})"
    }

    /** `fetchWorkgroupInfo(workgroupID:)`, a request: the Mac replies with [MacMessage.WorkgroupInfoMessage]. */
    data class FetchWorkgroupInfo(val workgroupID: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeFetchWorkgroupInfo(this)
    }

    /** `fetchSessionScreenInfo(sessionGuid:)`, a request: the Mac replies with [MacMessage.SessionScreenInfoMessage]. */
    data class FetchSessionScreenInfo(val sessionGuid: String) : PhoneMessage {
        override fun payload(): JsonObject = encodeFetchSessionScreenInfo(this)
    }
}

/**
 * A message the Mac sends to the phone: the pairing, live-session, history, selection, browser,
 * Mac status and agent chat subset of upstream `CompanionHostMessage`. Every other host case
 * decodes to [Unsupported] (`modalAlertAnswerRejected` among them: this app never answers an
 * alert). The decoders are in `StreamMessages.kt`, `HistorySelectionMessages.kt`,
 * `BrowserMessages.kt`, `MacStatus.kt` and `ChatMessages.kt`.
 */
sealed interface MacMessage {
    /**
     * `hello(revision:minimumPeer:wantsNotificationPermission:aiAvailable:macStatus:)`.
     * [raw] is the whole case body, so later plans can read the optional fields
     * (`macStatus` arrives only for a phone at revision 14 or later; read it with
     * [macStatus]). The revisions are [Long] to match Swift's 64-bit `Int`.
     */
    data class HelloReply(val revision: Long, val minimumPeer: Long, val raw: JsonObject) : MacMessage {
        /**
         * Whether the Mac has AI available now (revision 13). Absent or `null`, from a Mac before
         * 13, reads as true, as iOS reads it (`MSG:986-991`): such a Mac only paired with AI on.
         */
        val aiAvailable: Boolean get() = (raw["aiAvailable"] as? JsonPrimitive)?.content != "false"

        /** [raw] can carry alert headings, bodies and session GUIDs: only its keys are shown. */
        override fun toString(): String = "HelloReply(revision=$revision, minimumPeer=$minimumPeer, keys=${raw.keys.sorted()})"
    }

    data object RelayRoomSecretStored : MacMessage

    data object Pong : MacMessage

    /** Unsolicited: the Mac has dropped this pairing. */
    data object Unpaired : MacMessage

    /**
     * `error(CompanionError)`: [raw] is the `CompanionError` object, `{"code", "message"}`.
     * [message] is Mac text that can interpolate a Swift error or chat content (`HB:468`,
     * `HB:1406`), so [toString] shows the [code] only.
     */
    data class Error(val raw: JsonObject) : MacMessage {
        val code: String get() = (raw.getValue("code") as JsonPrimitive).content
        val message: String get() = (raw.getValue("message") as JsonPrimitive).content

        override fun toString(): String = "Error(code=$code)"
    }

    /**
     * `sessionTree(CompanionSessionTree)`: the reply to [PhoneMessage.FetchSessionTree], or,
     * with no request ID, the Mac's unsolicited push (phone revision 13 or later).
     *
     * [arrival] orders trees that reach the app by different paths (a push through
     * `CompanionClient.events`, a reply through `request`): `CompanionSession.handle` stamps
     * every tree with the next number of one counter the client keeps across all its links, in
     * the order the frames were received, so a larger [arrival] is always the newer tree. A tree
     * decoded outside a session has 0.
     */
    data class SessionTreeMessage(val tree: SessionTree, val arrival: Long = 0) : MacMessage

    /**
     * `streamStarted(CompanionStreamStarted)`: [streamID] is upstream `UInt32`. The stream
     * controller must stop a stream whose [codec] is not [StreamCodec.HEVC].
     */
    data class StreamStarted(val streamID: Long, val codec: StreamCodec) : MacMessage

    /** `streamConfig(CompanionStreamConfig)`. */
    data class StreamConfigMessage(val config: StreamConfig) : MacMessage

    /** `streamEnded(streamID:reason:)`, unsolicited. */
    data class StreamEnded(val streamID: Long, val reason: StreamEndReason) : MacMessage

    /** `historyTile(CompanionHistoryTile)`: the reply to [PhoneMessage.FetchHistoryTile]. */
    data class HistoryTileMessage(val tile: HistoryTile) : MacMessage

    /**
     * `streamExtent(streamID:firstAbsLine:totalLines:)`, unsolicited: history was trimmed
     * ([firstAbsLine] advanced) or cleared ([totalLines] dropped). Latest wins on the Mac's
     * coalescing lane, so it can arrive after newer media frames.
     */
    data class StreamExtent(val streamID: Long, val firstAbsLine: Long, val totalLines: Int) : MacMessage

    /**
     * `selectionRange(streamID:range:)`, unsolicited after any selection change on the Mac or
     * the phone: [range] is null when nothing is selected. Latest wins on the coalescing lane.
     */
    data class SelectionRange(val streamID: Long, val range: SelectionSpan?) : MacMessage

    /**
     * `selectionText(text:)`: the reply to [PhoneMessage.CopySelection], `""` when nothing is
     * selected. [text] is terminal content: [toString] shows only its length.
     */
    data class SelectionText(val text: String) : MacMessage {
        override fun toString(): String = "SelectionText(text(len=${text.length}))"
    }

    /**
     * `macStatusChanged(status:)`, unsolicited (revision 14): the Mac's complete current status,
     * sent on every change after the hello reply. Not stream-scoped: it reaches `events`.
     */
    data class MacStatusChanged(val status: MacStatus) : MacMessage

    /**
     * `browserEditingChanged(streamID:editing:)`, unsolicited (revision 15): whether a text field on
     * a browser stream's page has keyboard focus. Sent on the stream's first tick and on each
     * change (`MC/CompanionHostBridge.swift:745-758`).
     */
    data class BrowserEditingChanged(val streamID: Long, val editing: Boolean) : MacMessage

    /**
     * `browserNavigationChanged(streamID:state:)`, unsolicited (revision 15): a browser stream's
     * address, title, history and loading state, on the stream's first tick and on each change
     * (`MC/CompanionHostBridge.swift:773-788`). [toString] redacts the URL and title.
     */
    data class BrowserNavigationChanged(val streamID: Long, val state: BrowserNavigationState) : MacMessage {
        override fun toString(): String = "BrowserNavigationChanged(streamID=$streamID, state=$state)"
    }

    // Agent chat (`MSG:1003-1141`). [ChatsAndSessions], [ChatCreated], [History],
    // [MentionsResolved], [WorkgroupInfoMessage] and [SessionScreenInfoMessage] are replies; the
    // rest are unsolicited events. Every case that carries chat content shows counts and IDs only.

    /** `chatsAndSessions(chats:sessions:)`: the reply to [PhoneMessage.ListChatsAndSessions]. */
    data class ChatsAndSessions(val chats: List<ChatListEntry>, val sessions: List<SessionSummary>) : MacMessage {
        override fun toString(): String = "ChatsAndSessions(chats=${chats.size}, sessions=${sessions.size})"
    }

    /** `chatCreated(entry:)`: the reply to [PhoneMessage.CreateChat]. */
    data class ChatCreated(val entry: ChatListEntry) : MacMessage {
        override fun toString(): String = "ChatCreated(entry=$entry)"
    }

    /**
     * `history(chatID:messages:maxSeq:)`: the reply to [PhoneMessage.Subscribe], the chat's whole
     * visible history, oldest first, in one frame. [maxSeq] is upstream `Int64`.
     */
    data class History(val chatID: String, val messages: List<ChatMessage>, val maxSeq: Long) : MacMessage {
        override fun toString(): String = "History(chatID=$chatID, messages=${messages.size}, maxSeq=$maxSeq)"
    }

    /**
     * `delivery(message:chatID:partial:)`, unsolicited on a subscription: a whole message, or with
     * [partial] a streamed `append`/`appendAttachment` delta naming its target.
     */
    data class Delivery(val message: ChatMessage, val chatID: String, val partial: Boolean) : MacMessage {
        override fun toString(): String = "Delivery(message=$message, chatID=$chatID, partial=$partial)"
    }

    /**
     * `messagesRemoved(chatID:messageIDs:)`, unsolicited on a subscription (revision 12): a
     * truncated suffix, held uppercase. It can name messages the phone never saw (hidden rows).
     */
    data class MessagesRemoved(val chatID: String, val messageIDs: List<String>) : MacMessage

    /** `typingStatus(isTyping:participant:chatID:)`, unsolicited: only a spinner hint. */
    data class TypingStatus(val isTyping: Boolean, val participant: Participant, val chatID: String) : MacMessage

    /** `turnLifecycle(event:chatID:)`, unsolicited (revision 8): an agent turn's start or end. */
    data class TurnLifecycle(val event: TurnEvent, val chatID: String) : MacMessage

    /** `mentionsResolved([CompanionMentionResolution])`: the reply to [PhoneMessage.ResolveMentions]. */
    data class MentionsResolved(val resolutions: List<MentionResolution>) : MacMessage {
        override fun toString(): String = "MentionsResolved(resolutions=${resolutions.size})"
    }

    /** `workgroupInfo(CompanionWorkgroupInfo)`: the reply to [PhoneMessage.FetchWorkgroupInfo]. */
    data class WorkgroupInfoMessage(val info: WorkgroupInfo) : MacMessage {
        override fun toString(): String =
            "WorkgroupInfoMessage(workgroupID=${info.workgroupID}, members=${info.members.size})"
    }

    /** `chatListChanged(chats:)`, unsolicited: the Mac's whole chat list, which replaces the phone's. */
    data class ChatListChanged(val chats: List<ChatListEntry>) : MacMessage {
        override fun toString(): String = "ChatListChanged(chats=${chats.size})"
    }

    /** `aiAvailabilityChanged(available:)`, unsolicited (revision 13), at any phone revision. */
    data class AiAvailabilityChanged(val available: Boolean) : MacMessage

    /**
     * `sessionScreenInfo(CompanionSessionScreenInfo)`: the reply to
     * [PhoneMessage.FetchSessionScreenInfo]. A plain reply, not chat-scoped.
     */
    data class SessionScreenInfoMessage(val info: SessionScreenInfo) : MacMessage

    /**
     * What a waiting request receives when its reply crossed to the chat sink as raw bytes
     * ([RAW_CHAT_CASES], see `peekEnvelope`): the reply's [case] and its frame's [sizeBytes]. No content.
     */
    data class ChatPayloadTaken(val case: String, val sizeBytes: Int) : MacMessage

    /**
     * A case this build does not handle: [case] is its discriminator and [raw] the
     * payload object `{"<case>": ...}`. A media frame is never decoded as a message.
     */
    data class Unsupported(val case: String, val raw: JsonObject) : MacMessage {
        /** [raw] may hold terminal content (a chat's messages, say), so only its shape is shown. */
        override fun toString(): String = "Unsupported(case=$case, keys=${(raw[case] as? JsonObject)?.size ?: 0})"
    }
}

/** Key Swift's synthesized `Codable` gives the first unlabelled associated value. */
internal const val UNLABELLED = "_0"

internal val EMPTY = JsonObject(emptyMap())

internal fun case(name: String, body: JsonObject) = JsonObject(mapOf(name to body))
