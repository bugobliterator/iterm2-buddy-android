package dev.bugobliterator.itermbuddy.ui.chats

import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Keeps the chat list's snippets resolved, as iOS notes them on each list (`AM:2234`, `AM:4354`):
 * [note]s the non-empty snippets of [rows] whenever they change (a new state or unread mark alone
 * notes nothing), and on each of [reopenings] (a link up, AI back on) calls [reopen] (the
 * resolver's retry gate) and notes the current snippets again (`AM:2349-2351`). Runs until
 * cancelled. [note] is `MentionResolver.note`, which asks only for what it has not resolved.
 */
suspend fun noteSnippetMentions(
    rows: Flow<List<ChatRow>>,
    reopenings: Flow<Unit>,
    reopen: () -> Unit,
    note: (List<String>) -> Unit,
) {
    val snippets = rows.map { list -> list.mapNotNull { row -> row.entry.snippet?.takeIf(String::isNotEmpty) } }.distinctUntilChanged()
    var current: List<String> = emptyList()
    merge(snippets.map { SnippetTrigger.Snippets(it) }, reopenings.map { SnippetTrigger.Reopen }).collect { trigger ->
        when (trigger) {
            SnippetTrigger.Reopen -> reopen()
            is SnippetTrigger.Snippets -> current = trigger.texts
        }
        note(current)
    }
}

private sealed interface SnippetTrigger {
    data object Reopen : SnippetTrigger

    class Snippets(val texts: List<String>) : SnippetTrigger {
        override fun toString(): String = "Snippets(count=${texts.size})"
    }
}
