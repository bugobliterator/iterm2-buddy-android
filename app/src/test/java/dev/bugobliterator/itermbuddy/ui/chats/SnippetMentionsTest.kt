package dev.bugobliterator.itermbuddy.ui.chats

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
