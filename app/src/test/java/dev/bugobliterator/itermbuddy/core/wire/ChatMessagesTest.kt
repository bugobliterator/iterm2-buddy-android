package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.chat.AttachmentBudgets
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.SessionState
import dev.bugobliterator.itermbuddy.core.chat.TurnEvent
import dev.bugobliterator.itermbuddy.core.chat.UserTextMessage
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupInfo
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupMember
import dev.bugobliterator.itermbuddy.core.chat.decodeChatMessage
import dev.bugobliterator.itermbuddy.core.chat.decodeChatMessages
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagesTest {
    /** [bytes] is the sample's `json` text exactly as the fixture file holds it, escapes included. */
    private class Sample(val path: String, val direction: String, val json: JsonObject, val bytes: ByteArray) {
        val name: String get() = path.substringAfterLast('/').removeSuffix(".json")

        /** The payload's one case body. */
        val body: JsonObject get() = json.getValue("payload").jsonObject.values.single().jsonObject
    }

    /** Slices the raw `json` value, which the generator writes last, out of a fixture file. */
    private fun rawJson(path: String, text: String): String {
        val marker = "\"json\" : "
        check(text.indexOf(marker) == text.lastIndexOf(marker) && marker in text) { "$path: no single json key" }
        val raw = text.substringAfter(marker).trimEnd().dropLast(1).trimEnd()
        check(raw.endsWith("}")) { "$path: json is not the last value" }
        return raw
    }

    private val samples: List<Sample> by lazy {
        val paths = Fixtures.list("messages/agent-chat")
        assertTrue("messages/agent-chat is empty", paths.isNotEmpty())
        paths.map { path ->
            val text = Fixtures.read(path)
            val file = Json.parseToJsonElement(text).jsonObject
            val json = file.getValue("json").jsonObject
            val raw = rawJson(path, text)
            assertEquals("$path: sliced json", json, Json.parseToJsonElement(raw))
            Sample(path, file.getValue("direction").jsonPrimitive.content, json, raw.toByteArray(Charsets.UTF_8))
        }
    }

    private fun samples(direction: String): List<Sample> =
        samples.filter { it.direction == direction }.also { assertTrue("no $direction samples", it.isNotEmpty()) }

    private fun sample(name: String): Sample = samples.single { it.name == name }

    /** Runs [check] on each sample; any failure becomes an AssertionError naming the sample. */
    private fun forEachSample(samples: List<Sample>, check: (Sample) -> Unit) {
        for (sample in samples) {
            try {
                check(sample)
            } catch (e: Throwable) {
                throw AssertionError("${sample.path}: $e", e)
            }
        }
    }

    private fun tree(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    /**
     * [element] in the samples' canonical form: keys sorted, no whitespace, `/` written `\/`,
     * non-ASCII as raw UTF-8, number literals as they are (the encoder already writes Swift's).
     */
    private fun canonical(element: JsonElement): String = StringBuilder().also { it.writeCanonical(element) }.toString()

    private fun StringBuilder.writeCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    writeQuoted(key)
                    append(':')
                    writeCanonical(value)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    writeCanonical(value)
                }
                append(']')
            }
            JsonNull -> append("null")
            is JsonPrimitive -> if (element.isString) writeQuoted(element.content) else append(element.content)
        }
    }

    private fun StringBuilder.writeQuoted(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private val orchestratorChat = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
    private val sessionChat = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
    private val newChat = "D4E5F6A7-B8C9-4DAE-8F01-23456789ABCD"
    private val workgroup = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
    private val legacySession = "C3D4E5F6-0718-4293-A4B5-C6D7E8F90A1B"
    private fun messageId(suffix: String) = "11111111-2222-4333-8444-000000000$suffix"

    private val publishedText =
        "Ask @ptys_3HX6W0ZD5N2BT to review src/widget.c\nthen ship it 🚀 — naïve café"

    private val published = UserTextMessage(orchestratorChat, publishedText, 1795000500000, messageId("500"))

    /** Every phoneToMac sample, with the message and request ID iOS sends it with (`CC:95-225`). */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        "list-chats-and-sessions" to (PhoneMessage.ListChatsAndSessions to 9L),
        "create-chat-orchestrator" to (PhoneMessage.CreateChat("Orchestrator", NewChatMode.Orchestrator) to 10L),
        "create-chat-session" to (PhoneMessage.CreateChat("New Chat", NewChatMode.Session("ptys_7K2M9Q4R8T1VH")) to 11L),
        "subscribe" to (PhoneMessage.Subscribe(orchestratorChat) to 12L),
        "resolve-mentions" to (
            PhoneMessage.ResolveMentions(
                listOf("ptys_8M3B5X1QJ7TW1", "session:1D2C3B4A-5968-4776-A5B4-C3D2E1F00F1E", workgroup, "F0E1D2C3-B4A5-4968-8776-A5B4C3D2E1F0"),
            ) to 13L
            ),
        "fetch-workgroup-info" to (PhoneMessage.FetchWorkgroupInfo(workgroup) to 14L),
        "fetch-session-screen-info" to (PhoneMessage.FetchSessionScreenInfo(legacySession) to 15L),
        "unsubscribe" to (PhoneMessage.Unsubscribe(orchestratorChat) to null),
        "publish-plain-text" to (PhoneMessage.Publish(published, orchestratorChat) to null),
        "delete-chat" to (PhoneMessage.DeleteChat(sessionChat) to null),
        "delete-messages" to (PhoneMessage.DeleteMessages(orchestratorChat, listOf(messageId("300"), messageId("400"))) to null),
        "set-chat-muted-on" to (PhoneMessage.SetChatMuted(sessionChat, muted = true) to null),
        "set-chat-muted-off" to (PhoneMessage.SetChatMuted(sessionChat, muted = false) to null),
    )

    private val icon = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAADUlEQVR4AQECAP3/AAAAAgABfgUN0gAAAABJRU5ErkJggg==",
    )

    private val widgetEntry = ChatListEntry(
        ChatSummary(
            orchestratorChat, "Widget release checklist", 1794960000000.0, 1795000400000.0, true, null, null,
            listOf(workgroup, "session:ptys_8M3B5X1QJ7TW1"), icon,
        ),
        "All three tests pass now.",
        false,
    )

    private fun flakyEntry(snippet: String) = ChatListEntry(
        ChatSummary(sessionChat, "Fix flaky widget test", 1794960060000.0, 1795000300000.0, false, legacySession, null, emptyList(), null),
        snippet,
        true,
    )

    private val newChatEntry = ChatListEntry(
        ChatSummary(newChat, "New Chat", 1794960120000.0, 1794960120000.0, false, "ptys_1F5Y7R3MQ9KD4", null, emptyList(), null),
        null,
        false,
    )

    private val sessions = listOf(
        SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget", isBrowser = false),
        SessionSummary("ptys_3HX6W0ZD5N2BT", "Code Review", "reviewing widget.c", isBrowser = false),
        SessionSummary("ptys_9PJ4C8EGA6S0J", "tail", "tail -f build.log", isBrowser = false),
        SessionSummary("ptys_1F5Y7R3MQ9KD4", "claude", "planning", isBrowser = false),
        SessionSummary("ptys_6V0T2B8NW4HEQ", "claude", "", isBrowser = false),
        SessionSummary("ptys_8M3B5X1QJ7TW1", "vim", "notes.md", isBrowser = false),
        SessionSummary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", isBrowser = true),
    )

    private val workgroupInfo = WorkgroupInfo(
        workgroup,
        "Widget Review",
        listOf(
            WorkgroupMember("Implementer", "ptys_7K2M9Q4R8T1VH", "zsh", null, null, SessionState.IDLE),
            WorkgroupMember("Code Review", "ptys_3HX6W0ZD5N2BT", "Code Review", "Reviewing", "widget.c, 3 of 7 hunks", SessionState.WORKING),
            WorkgroupMember("Planner", "ptys_1F5Y7R3MQ9KD4", "claude", "Needs input", null, SessionState.WAITING),
            WorkgroupMember("Tester", "ptys_6V0T2B8NW4HEQ", "claude", null, null, SessionState.UNKNOWN),
            WorkgroupMember("docs-writer", null, null, null, null, SessionState.UNKNOWN),
        ),
    )

    private fun error(sample: Sample): MacMessage.Error = MacMessage.Error(sample.body.getValue("_0").jsonObject)

    /** A delivery's message decoded by the content model, which ChatMessageDecodingTest pins in detail. */
    private fun delivery(sample: Sample, partial: Boolean): MacMessage.Delivery {
        val message = decodeChatMessage(sample.body.getValue("message").jsonObject, AttachmentBudgets())
        return MacMessage.Delivery(message, message.chatID, partial)
    }

    /** Every macToPhone sample by name, with the request ID and message it decodes to. */
    private val macMessages: Map<String, (Sample) -> Pair<Long?, MacMessage>> = mapOf(
        "chats-and-sessions" to { sample ->
            val snippet = sample.body.getValue("chats").jsonArray[1].jsonObject.getValue("snippet").jsonPrimitive.content
            assertEquals(200, snippet.length)
            9L to MacMessage.ChatsAndSessions(listOf(widgetEntry, flakyEntry(snippet), newChatEntry), sessions)
        },
        "chats-and-sessions-ai-off" to { _ -> 9L to MacMessage.ChatsAndSessions(emptyList(), sessions) },
        "chat-created" to { _ ->
            10L to MacMessage.ChatCreated(
                ChatListEntry(
                    ChatSummary(
                        "2F3E4D5C-6B7A-4980-A1B2-C3D4E5F60718", "Orchestrator", 1795000600000.0, 1795000600003.0, true,
                        null, null, emptyList(), null,
                    ),
                    null,
                    false,
                ),
            )
        },
        "chat-created-session" to { _ ->
            11L to MacMessage.ChatCreated(
                ChatListEntry(
                    ChatSummary(
                        "7E6D5C4B-3A29-4817-B6F5-E4D3C2B1A090", "New Chat", 1795000610000.0, 1795000610002.0, false,
                        "ptys_7K2M9Q4R8T1VH", null, emptyList(), null,
                    ),
                    null,
                    false,
                ),
            )
        },
        "chat-list-changed" to { _ -> null to MacMessage.ChatListChanged(listOf(widgetEntry, newChatEntry)) },
        "history" to { sample ->
            val messages = decodeChatMessages(sample.body.getValue("messages").jsonArray, AttachmentBudgets())
            assertEquals(24, messages.size)
            assertEquals(1795000400000.25, messages.last().sentMillis, 0.0)
            12L to MacMessage.History(orchestratorChat, messages, 57)
        },
        "history-empty" to { _ -> 12L to MacMessage.History("0A1B2C3D-4E5F-4061-8293-A4B5C6D7E8F9", emptyList(), 0) },
        "error-ai-unavailable" to { sample -> 12L to error(sample) },
        "mentions-resolved" to { _ ->
            13L to MacMessage.MentionsResolved(
                listOf(
                    MentionResolution("ptys_8M3B5X1QJ7TW1", "vim", "ptys_8M3B5X1QJ7TW1", null),
                    MentionResolution("session:1D2C3B4A-5968-4776-A5B4-C3D2E1F00F1E", "vim", "ptys_8M3B5X1QJ7TW1", null),
                    MentionResolution(workgroup, "Widget Review", "ptys_7K2M9Q4R8T1VH", workgroup),
                    MentionResolution("F0E1D2C3-B4A5-4968-8776-A5B4C3D2E1F0", null, null, null),
                ),
            )
        },
        "workgroup-info" to { _ -> 14L to MacMessage.WorkgroupInfoMessage(workgroupInfo) },
        "error-unknown-workgroup" to { sample -> 14L to error(sample) },
        "session-screen-info" to { _ ->
            15L to MacMessage.SessionScreenInfoMessage(SessionScreenInfo(legacySession, "zsh", 1025, 81, 647.875, 16.25, 1.5))
        },
        "error-unknown-session" to { sample -> 15L to error(sample) },
        "error-delete-refused" to { sample -> null to error(sample) },
        "delivery-user-echo" to { sample ->
            val expected = MacMessage.Delivery(
                ChatMessage(orchestratorChat, Participant.USER, MessageBody.PlainText(publishedText), 1795000500000.0, messageId("500")),
                orchestratorChat,
                partial = false,
            )
            assertEquals(expected, delivery(sample, partial = false))
            null to expected
        },
        "delivery-agent-opening" to { sample -> null to delivery(sample, partial = false) },
        "delivery-append" to { sample ->
            val expected = delivery(sample, partial = true)
            assertEquals(MessageBody.Append(" in **zsh** now.", messageId("501")), expected.message.body)
            null to expected
        },
        "delivery-append-attachment-code" to { sample -> null to delivery(sample, partial = true) },
        "delivery-append-attachment-status" to { sample -> null to delivery(sample, partial = true) },
        "delivery-enable-orchestration-request" to { sample -> null to delivery(sample, partial = false) },
        "delivery-notice" to { sample -> null to delivery(sample, partial = false) },
        "delivery-remote-command-classic" to { sample -> null to delivery(sample, partial = false) },
        "delivery-remote-command-external" to { sample -> null to delivery(sample, partial = false) },
        "delivery-select-session-request" to { sample -> null to delivery(sample, partial = false) },
        "delivery-watcher-event" to { sample -> null to delivery(sample, partial = false) },
        "delivery-workgroup-permission-request" to { sample -> null to delivery(sample, partial = false) },
        "messages-removed" to { _ ->
            null to MacMessage.MessagesRemoved(orchestratorChat, listOf(messageId("300"), messageId("350"), messageId("400")))
        },
        "typing-status-on" to { _ -> null to MacMessage.TypingStatus(true, Participant.AGENT, orchestratorChat) },
        "typing-status-off" to { _ -> null to MacMessage.TypingStatus(false, Participant.AGENT, orchestratorChat) },
        "turn-lifecycle-started" to { _ -> null to MacMessage.TurnLifecycle(TurnEvent.STARTED, orchestratorChat) },
        "turn-lifecycle-ended" to { _ -> null to MacMessage.TurnLifecycle(TurnEvent.ENDED, orchestratorChat) },
        "ai-availability-changed-on" to { _ -> null to MacMessage.AiAvailabilityChanged(true) },
    )

    @Test
    fun agentChatPhoneSamplesEncodeExactly() {
        val phoneSamples = samples("phoneToMac")
        forEachSample(phoneSamples) { sample ->
            val (message, requestID) = phoneMessages[sample.name] ?: throw AssertionError("no table entry")
            val encoded = encodeEnvelope(message, requestID)
            assertEquals("tree", sample.json, tree(encoded))
            val bytes = canonical(tree(encoded)).toByteArray(Charsets.UTF_8)
            assertEquals("canonical bytes", sample.bytes.decodeToString(), bytes.decodeToString())
            assertArrayEquals("canonical bytes", sample.bytes, bytes)
        }
        assertEquals("table entries without a sample", phoneMessages.keys, phoneSamples.map { it.name }.toSet())
    }

    @Test
    fun agentChatMacSamplesDecode() {
        val macSamples = samples("macToPhone")
        forEachSample(macSamples) { sample ->
            val expected = macMessages[sample.name] ?: throw AssertionError("no table entry")
            assertEquals(expected(sample), decodeEnvelope(sample.bytes))
        }
        assertEquals("table entries without a sample", macMessages.keys, macSamples.map { it.name }.toSet())
    }

    @Test
    fun decodeToleranceMatchesTheRecordedVerdicts() {
        val cases = Json.parseToJsonElement(Fixtures.read("chat/decode-tolerance.json")).jsonArray.map { it.jsonObject }
        assertTrue("no tolerance cases", cases.size >= 9)
        val checked = mutableSetOf<String>()
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            try {
                val decoded = decodeEnvelope(case.getValue("json").jsonPrimitive.content.toByteArray())
                when (case.getValue("upstream").jsonPrimitive.content) {
                    "decodes" -> {
                        // Upstream's value, re-encoded, means the same once decoded here.
                        val upstream = decodeEnvelope(case.getValue("upstreamValue").toString().toByteArray())
                        assertEquals(upstream, decoded)
                        checkDecoded(name, decoded.second)
                    }
                    "rejects" -> {
                        assertTrue("an Android leniency must say so", "android" in case)
                        checkLeniency(name, decoded.second)
                    }
                    else -> throw AssertionError("unknown verdict")
                }
                checked += name
            } catch (e: Throwable) {
                throw AssertionError("decode-tolerance $name: $e", e)
            }
        }
        assertEquals(cases.size, checked.size)
    }

    /** What each tolerated case decodes to, beyond equality with upstream's value. */
    private fun checkDecoded(name: String, message: MacMessage) {
        when (name) {
            "delivery-future-content-case", "delivery-future-client-local-action" ->
                assertEquals(MessageBody.Unsupported, (message as MacMessage.Delivery).message.body)
            "turn-lifecycle-future-event" -> assertEquals(TurnEvent.UNKNOWN_FUTURE, (message as MacMessage.TurnLifecycle).event)
            "chats-and-sessions-without-muted" ->
                assertTrue((message as MacMessage.ChatsAndSessions).chats.all { it.muted == null })
            "hello-without-ai-available" -> assertTrue((message as MacMessage.HelloReply).aiAvailable)
            "history-with-future-content-case" -> {
                val history = message as MacMessage.History
                assertEquals(listOf(MessageBody.Markdown("First reply"), MessageBody.Unsupported), history.messages.map { it.body })
            }
            else -> throw AssertionError("no check for this case")
        }
    }

    /** Upstream rejects these frames; Android keeps them, as each case's `android` field says. */
    private fun checkLeniency(name: String, message: MacMessage) {
        when (name) {
            "workgroup-info-future-state" -> {
                val members = (message as MacMessage.WorkgroupInfoMessage).info.members
                assertEquals(listOf(SessionState.UNKNOWN) + workgroupInfo.members.drop(1).map { it.state }, members.map { it.state })
            }
            "history-with-corrupt-markdown-body" -> {
                val history = message as MacMessage.History
                assertEquals(listOf(MessageBody.Markdown("First reply"), MessageBody.Unreadable("markdown")), history.messages.map { it.body })
            }
            "delivery-remote-command-future-tool" ->
                assertEquals(MessageBody.Unreadable("remoteCommandRequest"), (message as MacMessage.Delivery).message.body)
            else -> throw AssertionError("no check for this case")
        }
    }

    private fun publishBody(message: PhoneMessage.Publish): JsonObject =
        tree(encodeEnvelope(message, null)).jsonObject.getValue("payload").jsonObject.getValue("publish").jsonObject

    @Test
    fun publishCarriesExactlyFiveMessageKeys() {
        val body = publishBody(PhoneMessage.Publish(published))
        assertEquals(setOf("message", "partial", "toChatID"), body.keys)
        assertEquals(JsonPrimitive(false), body["partial"])
        assertEquals(JsonPrimitive(orchestratorChat), body["toChatID"])
        val message = body.getValue("message").jsonObject
        assertEquals(setOf("chatID", "author", "content", "sentDate", "uniqueID"), message.keys)
        assertEquals(JsonPrimitive("user"), message["author"])
        assertEquals(Json.parseToJsonElement("""{"plainText":{"_0":${JsonPrimitive(publishedText)}}}"""), message["content"])
        // iOS always publishes to the message's own chat (`AM:3100-3118`).
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.Publish(published, sessionChat) }
    }

    @Test
    fun publishedUuidIsUppercase() {
        val id = newMessageId()
        val lettered = "ABCDEF01-2222-4333-8444-0000000005AB"
        val message = publishBody(PhoneMessage.Publish(UserTextMessage(orchestratorChat, "hi", 1, id))).getValue("message").jsonObject
        val written = message.getValue("uniqueID").jsonPrimitive.content
        assertEquals(id, written)
        assertTrue(written, isCanonicalUppercaseUuid(written))
        // The phone holds UUIDs uppercase, so a lowercase one is a caller bug, never sent.
        assertThrows(IllegalArgumentException::class.java) {
            UserTextMessage(orchestratorChat, "hi", 1, lettered.lowercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneMessage.DeleteMessages(orchestratorChat, listOf(lettered.lowercase()))
        }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.DeleteMessages(orchestratorChat, listOf("not-a-uuid")) }
        // Mac UUIDs are read case-insensitively and held uppercase.
        val removed = decodeEnvelope(
            """{"payload":{"messagesRemoved":{"chatID":"c","messageIDs":["${messageId("300").lowercase().replace('1', 'a')}"]}}}""".toByteArray(),
        ).second as MacMessage.MessagesRemoved
        assertEquals(listOf("AAAAAAAA-2222-4333-8444-000000000300"), removed.messageIDs)
        for (bad in listOf("\"x\"", "1", "null")) {
            val frame = """{"payload":{"messagesRemoved":{"chatID":"c","messageIDs":[$bad]}}}"""
            assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
        }
    }

    @Test
    fun publishedSentDateIsAnIntegerLiteral() {
        for (millis in listOf(0L, 1L, 1795000500000L, 253402300799999L)) {
            val message = publishBody(PhoneMessage.Publish(UserTextMessage(orchestratorChat, "hi", millis, newMessageId())))
                .getValue("message").jsonObject
            val literal = message.getValue("sentDate").jsonPrimitive
            assertFalse(literal.isString)
            assertEquals(millis.toString(), literal.content)
        }
        // A Mac date may be fractional; it decodes into a Double.
        val history = decodeEnvelope(sample("history").bytes).second as MacMessage.History
        assertEquals(1795000400000.25, history.messages.last().sentMillis, 0.0)
    }

    @Test
    fun helloWithoutAiAvailableMeansAvailable() {
        fun hello(extra: String): MacMessage.HelloReply =
            decodeEnvelope("""{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11$extra}}}""".toByteArray())
                .second as MacMessage.HelloReply
        assertTrue(hello("").aiAvailable)
        assertTrue(hello(""","aiAvailable":null""").aiAvailable)
        assertTrue(hello(""","aiAvailable":true""").aiAvailable)
        assertFalse(hello(""","aiAvailable":false""").aiAvailable)
        val wrongType = """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"aiAvailable":"no"}}}"""
        assertThrows(WireDecodeException::class.java) { decodeEnvelope(wrongType.toByteArray()) }
        // The Mac's real reply to an earlier phone carries no aiAvailable.
        val session = Fixtures.read("messages/session/hello-mac.json")
        val reply = decodeEnvelope(rawJson("hello-mac", session).toByteArray()).second as MacMessage.HelloReply
        assertTrue(reply.aiAvailable)
    }

    @Test
    fun advertisedRevisionCoversAgentChat() {
        // turnLifecycle (8), messagesRemoved/deleteMessages (12), aiAvailable and the aiUnavailable error (13).
        assertTrue(ProtocolRevision.REVISION >= 13)
        assertEquals(11, ProtocolRevision.MINIMUM_PEER)
    }

    @Test
    fun noChatPathEncodesApprovalOrPushMessages() {
        val forbidden = setOf(
            "remoteCommandDecision", "selectSessionResponse", "linkSession", "pushStatus", "syncSince",
            "messagesSince", "notificationPermissionResponse", "userCommand",
        )
        fun keys(element: JsonElement): Set<String> = when (element) {
            is JsonObject -> element.keys + element.values.flatMap { keys(it) }
            is JsonArray -> element.flatMap { keys(it) }.toSet()
            else -> emptySet()
        }
        for ((name, entry) in phoneMessages) {
            val payload = entry.first.payload()
            assertEquals(name, 1, payload.size)
            val found = keys(payload) intersect forbidden
            assertTrue("$name encodes $found", found.isEmpty())
        }
        // A publish can carry nothing but plainText.
        val content = publishBody(PhoneMessage.Publish(published)).getValue("message").jsonObject.getValue("content").jsonObject
        assertEquals(setOf("plainText"), content.keys)
        assertEquals(setOf("_0"), content.getValue("plainText").jsonObject.keys)
    }

    @Test
    fun chatContentIsRedactedInToString() {
        val secret = "kittens-secret"
        val chat = ChatSummary(orchestratorChat, secret, 1.0, 2.0, true, null, null, listOf(workgroup), secret.toByteArray())
        val entry = ChatListEntry(chat, secret, false)
        val message = ChatMessage(orchestratorChat, Participant.AGENT, MessageBody.Markdown(secret), 1.0, messageId("500"))
        val values = listOf<Any>(
            PhoneMessage.Publish(UserTextMessage(orchestratorChat, secret, 1, messageId("500"))),
            PhoneMessage.CreateChat(secret, NewChatMode.Orchestrator),
            PhoneMessage.ResolveMentions(listOf(secret)),
            MacMessage.History(orchestratorChat, listOf(message), 1),
            MacMessage.Delivery(message, orchestratorChat, partial = false),
            MacMessage.ChatsAndSessions(listOf(entry), listOf(SessionSummary("ptys_X", secret, secret, isBrowser = false))),
            MacMessage.ChatListChanged(listOf(entry)),
            MacMessage.ChatCreated(entry),
            MacMessage.MentionsResolved(listOf(MentionResolution(secret, secret, "ptys_X", null))),
            MacMessage.WorkgroupInfoMessage(
                WorkgroupInfo(workgroup, secret, listOf(WorkgroupMember(secret, "ptys_X", secret, secret, secret, SessionState.IDLE))),
            ),
            SessionScreenInfo("ptys_X", secret, 1, 1, 1.0, 1.0, 1.0),
            MacMessage.SessionScreenInfoMessage(SessionScreenInfo("ptys_X", secret, 1, 1, 1.0, 1.0, 1.0)),
            MacMessage.Error(Json.parseToJsonElement("""{"code":"internalError","message":"$secret"}""").jsonObject),
            MacMessage.ChatPayloadTaken("history", 12),
            decodeEnvelope(
                """{"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":{"alerts":[{"title":"$secret"}]}}}}""".toByteArray(),
            ).second,
        )
        for (value in values) {
            val printed = value.toString()
            assertFalse("${value.javaClass.simpleName}: $printed", "kittens" in printed)
        }
        assertEquals(
            "Error(code=internalError)",
            MacMessage.Error(Json.parseToJsonElement("""{"code":"internalError","message":"$secret"}""").jsonObject).toString(),
        )
    }

    @Test
    fun chatDecodeErrorsNameNoContent() {
        val secret = "kittens-secret"
        val frames = listOf(
            // Not JSON: kotlinx's own message quotes the input, so it must not reach the exception.
            """{"payload":{"history":{"chatID":"$secret","messages":[$secret""",
            """{"payload":{"delivery":{"chatID":"c","partial":false,"message":$secret}}}x""",
            """{"payload":{"history":{"chatID":"c","maxSeq":"$secret","messages":[]}}}""",
            """{"payload":{"typingStatus":{"chatID":"c","isTyping":true,"participant":"$secret"}}}""",
            """{"payload":{"turnLifecycle":{"chatID":"c","event":["$secret"]}}}""",
            """{"payload":{"workgroupInfo":{"_0":{"workgroupID":"w","name":"$secret","members":[{"roleName":1,"state":"idle"}]}}}}""",
            """{"payload":{"messagesRemoved":{"chatID":"c","messageIDs":["$secret"]}}}""",
            """{"payload":{"delivery":{"chatID":"c","partial":false,"message":{"author":"$secret"}}}}""",
            """{"payload":{"chatListChanged":{"chats":[{"chat":{"id":"x","title":"$secret"}}]}}}""",
            """{"payload":{"sessionScreenInfo":{"_0":{"guid":"g","name":"$secret","lineCount":1.5}}}}""",
            // An icon that is not base64: the JDK decoder's message can quote the bad character.
            """{"payload":{"chatCreated":{"entry":{"chat":{"id":"x","title":"t","creationDate":1,"lastModifiedDate":2,""" +
                """"orchestrationEnabled":false,"claimedScopes":[],"icon":"${secret}!!"}}}}}""",
        )
        for (frame in frames) {
            val error = assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
            var current: Throwable? = error
            while (current != null) {
                assertFalse("$frame: ${current.message}", current.message.orEmpty().contains("kittens"))
                current = current.cause
            }
        }
    }

    @Test
    fun chatCasesRejectMalformedBodies() {
        val bad = listOf(
            """{"payload":{"history":{"chatID":"c","maxSeq":1.5,"messages":[]}}}""",
            """{"payload":{"history":{"chatID":"c","maxSeq":1,"messages":[{"chatID":"c"}]}}}""",
            """{"payload":{"delivery":{"chatID":"c","message":{},"partial":false}}}""",
            """{"payload":{"delivery":{"chatID":"c","partial":"no"}}}""",
            """{"payload":{"typingStatus":{"chatID":"c","isTyping":true,"participant":"system"}}}""",
            """{"payload":{"turnLifecycle":{"chatID":"c","event":3}}}""",
            """{"payload":{"aiAvailabilityChanged":{"available":"yes"}}}""",
            """{"payload":{"chatCreated":{"entry":{"chat":{}}}}}""",
            """{"payload":{"chatsAndSessions":{"chats":[],"sessions":[{"guid":"g"}]}}}""",
            """{"payload":{"mentionsResolved":{"_0":[{"displayName":"x"}]}}}""",
            """{"payload":{"workgroupInfo":{"_0":{"workgroupID":"w","name":"n","members":[{"roleName":"r","state":1}]}}}}""",
            """{"payload":{"sessionScreenInfo":{"_0":{"guid":"g","name":"n","lineCount":1,"columns":1,"width":"w","lineHeight":1,"scale":1}}}}""",
        )
        for (frame in bad) {
            assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
        }
        // A chat without the optional fields, from an older Mac, still decodes.
        val minimal = """{"payload":{"chatListChanged":{"chats":[{"chat":{"id":"x","title":"t","creationDate":1,""" +
            """"lastModifiedDate":2.5,"orchestrationEnabled":false,"permissions":"","claimedScopes":[],"watchers":[]}}]}}}"""
        val entry = (decodeEnvelope(minimal.toByteArray()).second as MacMessage.ChatListChanged).chats.single()
        assertEquals(ChatListEntry(ChatSummary("x", "t", 1.0, 2.5, false, null, null, emptyList(), null), null, null), entry)
        assertNull(entry.chat.copyIcon())
    }

    @Test
    fun chatWithoutPermissionsOrWatchersDecodes() {
        // An Android leniency: upstream's synthesized Chat decode requires both; the phone reads neither.
        val frame = """{"payload":{"chatCreated":{"entry":{"chat":{"id":"x","title":"t","creationDate":1,""" +
            """"lastModifiedDate":2,"orchestrationEnabled":true,"claimedScopes":["wg-1"]},"muted":true}}},"requestID":10}"""
        assertEquals(
            10L to MacMessage.ChatCreated(
                ChatListEntry(ChatSummary("x", "t", 1.0, 2.0, true, null, null, listOf("wg-1"), null), null, true),
            ),
            decodeEnvelope(frame.toByteArray()),
        )
    }
}
