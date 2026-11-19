package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListState
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.ChatTimeouts
import dev.bugobliterator.itermbuddy.core.chat.ClientAction
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import dev.bugobliterator.itermbuddy.core.chat.CreateOutcome
import dev.bugobliterator.itermbuddy.core.chat.EchoState
import dev.bugobliterator.itermbuddy.core.chat.InMemoryChatSeenStore
import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import dev.bugobliterator.itermbuddy.core.chat.Subpart
import dev.bugobliterator.itermbuddy.core.sessions.SessionTreeRepository
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import dev.bugobliterator.itermbuddy.wireHome
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 3 of the real-Mac CI, Agent Chat: the app's own chat stack, wired by [wireHome] as
 * `CompanionApp` wires it ([ChatRepository] fed by the client's chat sink through its inbox),
 * against a real iTerm2 3.7.7 whose AI runs on the local fake model (`ci/real-iterm2/fake-model.mjs`,
 * no model key). In order:
 * - the hello says AI is on and the chat list loads;
 * - [ChatRepository.create] makes an orchestrator chat, which is [ChatRepository.open]ed (never
 *   set visible, so a turn that ends is latched Done, as for an owner not looking at it);
 * - [ChatRepository.send] sends a message: its echo comes back once, delivered; the chat's
 *   activity goes Working and then Done; the fake's streamed reply ([REPLY], synthetic) lands as
 *   one agent message with exactly that text;
 * - a second message carries the fake's trigger ([TRIGGER]) and a terminal session's GUID: the
 *   fake answers with a call of `session_execute_command` on that session, and iTerm2's
 *   orchestrator parks it on the owner's approval to control the session
 *   (`OrchestratorDispatcher.ensureSessionClaim`): the activity goes Waiting and stays there, and
 *   the newest visible message is a workgroup-permission request card that
 *   [ChatMessage.isBlockingRequest]. Nobody approves it, so the command never runs;
 * - the fake's counts confirm it answered one tool call and that iTerm2 offered the tool, and its
 *   schema hash matches `FAKE_MODEL_TOOL_SCHEMA_SHA256` once that is pinned;
 * - [ChatRepository.delete] deletes the chat, and a fresh list from the Mac no longer has it.
 *
 * The phone could answer the approval (iOS publishes a `userCommand(workgroupPermissionResponse)`),
 * but this app does not encode approvals yet (the Approvals phase), and the fake model cannot
 * approve on the Mac's behalf, so the test stops at Waiting and deletes the parked chat.
 *
 * Reads `REAL_ITERM2_FAKE_MODEL_URL` (the fake's base URL) besides what [RealIterm2Rig] reads;
 * every step waits at most `REAL_ITERM2_CHAT_TIMEOUT_SECONDS`. Runs only under `realIterm2Test`
 * with `REAL_ITERM2=1`; skipped otherwise. It prints chat IDs, states, counts and lengths, never
 * message text.
 */
class RealIterm2AgentChatTest {
    private val rig = RealIterm2Rig()
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() {
        rig.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun orchestratorChatStreamsAReplyThenParksOnApproval() = runBlocking {
        val fakeModelUrl = RealIterm2Rig.requiredEnv("REAL_ITERM2_FAKE_MODEL_URL").trimEnd('/')
        val chatTimeout = rig.chatTimeout
        val startStats = fakeStats(fakeModelUrl)
        if ("unreachable" in startStats) failWith("the fake model does not answer at $fakeModelUrl: $startStats (did start-fake-model.sh run?)")
        report("fake model before the chat: $startStats")
        val nowMillis = { System.nanoTime() / NANOS_PER_MILLI }
        val home = wireHome(
            rig.scope,
            newClient = { streamSink, chatSink -> rig.client(streamSink, chatSink) },
            newController = { link ->
                StreamController(link, FakeVideoOutput(nowMillis), rig.scope, nowMillis = nowMillis, log = { report("stream: $it") })
            },
            newRepository = { client -> SessionTreeRepository(client, rig.scope, log = { report("tree: $it") }) },
            newChats = { link ->
                ChatRepository(
                    link,
                    rig.scope,
                    InMemoryChatSeenStore(),
                    timeouts = ChatTimeouts(list = chatTimeout, subscribe = chatTimeout, create = chatTimeout, echo = chatTimeout),
                    nowMillis = nowMillis,
                    log = { report("chat: $it") },
                )
            },
            log = { report("home: $it") },
            chatLog = { report("chat: $it") },
        )
        val client = rig.connect(home.client)
        val chats = home.chats

        // AI on, and the list loaded.
        val availability = withTimeoutOrNull(chatTimeout) { chats.availability.first { it != null } }
            ?: failWith("no chat link within $chatTimeout")
        report("chat availability: $availability")
        if (!availability.aiAvailable) {
            failWith("the Mac reports AI off: the AI plugin is missing or invalid, or EnableAI was not read (see the diagnostics)")
        }
        val listed = withTimeoutOrNull(chatTimeout) {
            chats.listSnapshot.first { it.list is ChatListState.Loaded || it.list is ChatListState.AiUnavailable }
        } ?: failWith("the chat list did not load within $chatTimeout: ${chats.listSnapshot.value}")
        report("chat list: ${listed.list}")
        assertTrue("the chat list: ${listed.list}", listed.list is ChatListState.Loaded)

        val session = rig.firstTerminalSession(client)

        // An orchestrator chat, created and opened as the New Chat screen does.
        val created = chats.create(NewChatMode.Orchestrator)
        val chatID = (created as? CreateOutcome.Created)?.chatID ?: failWith("create(Orchestrator) got $created")
        report("created orchestrator chat $chatID")
        var deleted = false
        try {
            val activity = CopyOnWriteArrayList<AgentState>()
            rig.scope.launch {
                chats.activity.map { it[chatID] ?: AgentState.UNKNOWN }.distinctUntilChanged().collect { state ->
                    activity += state
                    report("activity of $chatID: $state")
                }
            }
            chats.open(chatID)
            val opened = rig.poll(chatTimeout) { (chats.conversation(chatID).value as? ConversationState.Shown)?.takeIf { it.sendable } }
                ?: failWith("the chat did not open sendable within $chatTimeout: ${chats.conversation(chatID).value}")
            report("opened: $opened")

            // A plain message: echo once, Working then Done, the streamed reply as one message.
            val before = activity.size
            val greetingID = sendAndAwaitEcho(chats, chatID, GREETING, expectedEchoes = 1)
            awaitSequence(activity, before, listOf(AgentState.WORKING, AgentState.DONE), "the first turn")
            val shown = chats.conversation(chatID).value as? ConversationState.Shown ?: failWith("the conversation is not shown")
            val echoIndex = shown.messages.indexOfFirst { it.uniqueID == greetingID }
            val replies = rig.poll(chatTimeout) {
                val now = chats.conversation(chatID).value as? ConversationState.Shown
                now?.messages?.drop(echoIndex + 1)?.filter { it.author == Participant.AGENT && !it.hidden && !textOf(it.body).isNullOrBlank() }
                    ?.takeIf { messages -> messages.any { textOf(it.body)?.trim() == REPLY } }
            } ?: failWith("no agent message with the fake's reply (${REPLY.length} chars) within $chatTimeout: " + agentTextLengths(chats, chatID, echoIndex) + "; fake ${fakeStats(fakeModelUrl)}")
            report("agent text messages after the echo: ${replies.size}, lengths ${replies.map { textOf(it.body)?.length }}")
            assertEquals("agent text messages after the echo", 1, replies.size)
            assertEquals("the reply's length", REPLY.length, textOf(replies.single().body)?.trim()?.length)

            // The trigger: the fake calls session_execute_command, and iTerm2 parks it on approval.
            val beforeTrigger = activity.size
            sendAndAwaitEcho(chats, chatID, "$TRIGGER session_guid=${session.guid}", expectedEchoes = 2)
            awaitSequence(activity, beforeTrigger, listOf(AgentState.WORKING, AgentState.WAITING), "the trigger's turn")
            val request = rig.poll(chatTimeout) {
                val now = chats.conversation(chatID).value as? ConversationState.Shown
                now?.messages?.lastOrNull { !it.hidden }?.takeIf { it.author == Participant.AGENT && it.isBlockingRequest }
            } ?: failWith(
                "the newest visible message is not a blocking request within $chatTimeout: " +
                    "${(chats.conversation(chatID).value as? ConversationState.Shown)?.messages?.lastOrNull { !it.hidden }?.body}; fake ${fakeStats(fakeModelUrl)}",
            )
            val action = (request.body as? MessageBody.ClientNotice)?.action
            report("request card: $action, interactive=${request.isInteractiveRequest}, abandonable=${request.isAbandonableRequest}")
            val permission = action as? ClientAction.WorkgroupPermissionRequest
                ?: failWith("the request card is ${request.body}, not a workgroup-permission request")
            assertTrue("the request is for a single session: ${permission.workgroupID}", permission.workgroupID.startsWith(SESSION_SCOPE))
            assertTrue("isBlockingRequest", request.isBlockingRequest)
            assertTrue("isInteractiveRequest", request.isInteractiveRequest)
            // Parked, not passing through: still Waiting a while later.
            delay(PARK_HOLD)
            assertEquals("the activity after ${PARK_HOLD.inWholeSeconds} s parked", AgentState.WAITING, chats.activity.value[chatID])

            val stats = fakeStats(fakeModelUrl)
            report("fake model: $stats")
            assertEquals("the fake's tool calls", 1, stats.int("toolCalls"))
            assertTrue("the fake's text replies", (stats.int("textReplies") ?: 0) >= 1)
            val tool = stats["targetTool"]?.jsonObject ?: failWith("the fake reports no target tool: $stats")
            assertEquals("iTerm2 offers ${tool.string("name")}", true, tool.bool("offered"))
            val schemaSha256 = tool.string("sha256") ?: failWith("the fake has no schema hash for ${tool.string("name")}")
            when (tool.bool("matchesPin")) {
                null -> report("${tool.string("name")} schema sha256 $schemaSha256: not pinned yet (FAKE_MODEL_TOOL_SCHEMA_SHA256 in ci/real-iterm2/common.sh)")
                true -> report("${tool.string("name")} schema sha256 $schemaSha256 matches the pin")
                false -> failWith("iTerm2's ${tool.string("name")} schema sha256 is $schemaSha256, not the pinned ${tool.string("pinnedSha256")}")
            }

            // Delete the parked chat; a fresh list from the Mac no longer has it.
            chats.close(chatID)
            chats.delete(chatID)
            deleted = true
            delay(LIST_SETTLE)
            chats.refresh()
            delay(LIST_SETTLE)
            val afterDelete = rig.poll(chatTimeout) {
                (chats.listSnapshot.value.list as? ChatListState.Loaded)?.takeIf { list -> !list.refreshing && list.entries.none { it.chat.id == chatID } }
            } ?: failWith("the chat is still listed $chatTimeout after the delete: ${chats.listSnapshot.value.list}")
            report("deleted $chatID; the list has ${afterDelete.entries.size} chats")
        } finally {
            if (!deleted) {
                chats.delete(chatID)
                delay(LIST_SETTLE)
            }
        }
    }

    /** Sends [text] and returns its `uniqueID` once its echo is delivered, listed exactly once, with [text] as its body. */
    private suspend fun sendAndAwaitEcho(chats: ChatRepository, chatID: String, text: String, expectedEchoes: Int): String {
        val outcome = chats.send(chatID, MentionDraft(text, emptyList()))
        report("send (${text.length} chars): $outcome")
        assertEquals("send", SendOutcome.Sent, outcome)
        val shown = rig.poll(rig.chatTimeout) {
            (chats.conversation(chatID).value as? ConversationState.Shown)?.takeIf { now ->
                now.echoes.size == expectedEchoes && now.echoes.values.all { it == EchoState.DELIVERED }
            }
        } ?: failWith("the echo was not delivered within ${rig.chatTimeout}: ${chats.conversation(chatID).value}")
        val delivered = shown.messages.filter { it.uniqueID in shown.echoes.keys }
        val uniqueID = delivered.lastOrNull()?.uniqueID ?: failWith("no echo in the transcript: $shown")
        val copies = shown.messages.count { it.uniqueID == uniqueID }
        report("echo delivered: ${shown.echoes.size} sent from here, this one listed $copies times")
        assertEquals("copies of the echo", 1, copies)
        val echo = shown.messages.single { it.uniqueID == uniqueID }
        assertEquals("the echo's author", Participant.USER, echo.author)
        assertTrue("the echo's body is not the text sent (${textOf(echo.body)?.length} chars)", textOf(echo.body)?.trim() == text)
        return uniqueID
    }

    /** Waits until [activity], from index [from], holds [expected] in that order (other states may sit between). */
    private suspend fun awaitSequence(activity: List<AgentState>, from: Int, expected: List<AgentState>, what: String) {
        rig.poll(rig.chatTimeout) {
            var next = 0
            for (state in activity.drop(from)) if (next < expected.size && state == expected[next]) next++
            true.takeIf { next == expected.size }
        } ?: failWith("$what did not go ${expected.joinToString(" then ")} within ${rig.chatTimeout}: ${activity.drop(from)}")
        report("$what went ${activity.drop(from)}")
    }

    private fun agentTextLengths(chats: ChatRepository, chatID: String, after: Int): String {
        val now = chats.conversation(chatID).value as? ConversationState.Shown ?: return "not shown"
        return "agent messages after the echo: " +
            now.messages.drop(after + 1).filter { it.author == Participant.AGENT }.map { "${it.body::class.simpleName}(${textOf(it.body)?.length})" }
    }

    /** The fake model's counts and captured tool facts (`GET /fake/stats`; no content). */
    private fun fakeStats(baseUrl: String): JsonObject = try {
        http.newCall(Request.Builder().url("$baseUrl/fake/stats").build()).execute().use { response ->
            Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    } catch (e: Exception) {
        JsonObject(mapOf("unreachable" to JsonPrimitive(e.javaClass.simpleName)))
    }

    private companion object {
        /** Synthetic test data; [REPLY] and [TRIGGER] mirror `ci/real-iterm2/fake-model.mjs`. */
        const val GREETING = "Hello from the buddy CI: reply with your fixed text."
        const val REPLY = "Fake model reply: the real iTerm2 agent chat check streamed in six parts."
        const val TRIGGER = "buddy-ci-approval-trigger"

        /** A single-session claim's workgroup ID (`WorkgroupIntrospection.syntheticWorkgroupIDPrefix`). */
        const val SESSION_SCOPE = "session:"
        const val NANOS_PER_MILLI = 1_000_000L
        val PARK_HOLD = 5.seconds
        val LIST_SETTLE = 4.seconds

        /** The text of a text-bearing body (plain, Markdown, or their parts); null for any other kind. */
        fun textOf(body: MessageBody): String? = when (body) {
            is MessageBody.PlainText -> body.text
            is MessageBody.Markdown -> body.text
            is MessageBody.Multipart -> body.parts.mapNotNull {
                when (it) {
                    is Subpart.PlainText -> it.text
                    is Subpart.Markdown -> it.text
                    else -> null
                }
            }.takeIf { it.isNotEmpty() }?.joinToString("")
            else -> null
        }

        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
