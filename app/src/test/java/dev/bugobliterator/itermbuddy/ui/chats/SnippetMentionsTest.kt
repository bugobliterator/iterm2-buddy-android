package dev.bugobliterator.itermbuddy.ui.chats

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig
import dev.bugobliterator.itermbuddy.fakemac.FakeChatMac
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list's snippets are noted for mention resolution, as iOS does on each list (`AM:2234`, `AM:4354`). */
@OptIn(ExperimentalCoroutinesApi::class)
class SnippetMentionsTest {
    private fun row(id: String, snippet: String?, state: AgentState = AgentState.IDLE) =
        ChatRow(ChatListEntry(ChatSummary(id, "t", 1.0, 2.0, false, null, null, emptyList(), null), snippet, false), state, false)

    @Test
    fun snippetsAreNotedOnChangeAndAfterTheGateReopens() = runTest {
        val rows = MutableStateFlow(listOf(row("a", "one"), row("b", null), row("c", "")))
        val reopenings = MutableSharedFlow<Unit>()
        val calls = mutableListOf<String>()
        backgroundScope.launch {
            noteSnippetMentions(rows, reopenings, reopen = { calls += "reopen" }, note = { calls += "note $it" })
        }
        runCurrent()
        assertEquals(listOf("note [one]"), calls)

        // A new state or unread mark with the same snippets notes nothing.
        rows.value = listOf(row("a", "one", AgentState.WORKING), row("b", null), row("c", ""))
        runCurrent()
        assertEquals(listOf("note [one]"), calls)

        rows.value = listOf(row("a", "two"), row("b", "three"))
        runCurrent()
        assertEquals(listOf("note [one]", "note [two, three]"), calls)

        // A link up or AI back on: the gate reopens, then the current snippets are noted again.
        reopenings.emit(Unit)
        runCurrent()
        assertEquals(listOf("note [one]", "note [two, three]", "reopen", "note [two, three]"), calls)
    }

    @Test
    fun missIsAskedAgainAfterReconnect() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        // The list's first snippet mentions a session the Mac answers as a miss (mentions-resolved.json).
        rig.mac.onRequestScripted("listChatsAndSessions") { envelope ->
            val id = (envelope["requestID"] as? JsonPrimitive)?.long
            rig.mac.sendMessage(listWithSnippet("Ask @$MISSED to look"), id)
        }
        rig.connect()
        advanceTimeBy(5.seconds)
        val first = rig.chatMac.sent("resolveMentions")
        assertEquals(1, first.size)
        assertTrue(first.single().toString(), MISSED in first.single().toString())
        val answered = rig.graph.mentions.resolutions.value.getValue(MISSED)
        assertNull("a miss", answered.displayName)
        // Noting the same snippets again asks nothing: the miss is behind the retry gate.
        advanceTimeBy(5.seconds)
        assertEquals(1, rig.chatMac.sent("resolveMentions").size)

        // A reconnect reopens the gate: the miss is asked again on the new link.
        rig.mac.dropSocket()
        rig.awaitConnected(2)
        advanceTimeBy(5.seconds)
        val second = rig.chatMac.sent("resolveMentions")
        assertEquals(2, second.size)
        assertTrue(MISSED in second.last().toString())
    }

    private fun listWithSnippet(snippet: String): JsonObject {
        val payload = FakeChatMac.samplePayload("chats-and-sessions.json")
        val body = payload.getValue("chatsAndSessions").jsonObject
        val chats = body.getValue("chats").jsonArray
        val firstChat = JsonObject(chats[0].jsonObject + ("snippet" to JsonPrimitive(snippet)))
        val list = JsonArray(listOf(firstChat) + chats.drop(1))
        return JsonObject(mapOf("chatsAndSessions" to JsonObject(body + ("chats" to list))))
    }

    private companion object {
        const val MISSED = "F0E1D2C3-B4A5-4968-8776-A5B4C3D2E1F0"
    }
}
