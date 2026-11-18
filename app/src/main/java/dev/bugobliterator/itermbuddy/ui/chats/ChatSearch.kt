package dev.bugobliterator.itermbuddy.ui.chats

import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The chat list's search query, app-scoped like [SessionSearch][dev.bugobliterator.itermbuddy.ui.sessions.SessionSearch]
 * so it survives opening a conversation and coming back, and rotation. Cleared on unpair.
 * Thread-safe. The query is shown to the owner, never logged, and [toString] shows its length only.
 */
class ChatSearch {
    private val flow = MutableStateFlow("")

    val query: StateFlow<String> = flow.asStateFlow()

    fun set(query: String) {
        flow.value = query
    }

    fun clear() = set("")

    override fun toString(): String = "ChatSearch(query(len=${flow.value.length}))"
}

/**
 * The [rows] that match the search [query], in the Mac's order. The query is split on
 * whitespace and every term must appear (case-insensitive, `Locale.ROOT`) in the row's title or
 * in its snippet as shown ([renderedSnippet] with [resolutions]: a resolved mention matches by
 * its name, not its ID). A blank query keeps every row. Nothing here is logged.
 */
fun filterChatRows(rows: List<ChatRow>, query: String, resolutions: Map<String, MentionResolution> = emptyMap()): List<ChatRow> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return rows
    return rows.filter { row -> matches(terms, row.entry.chat.title, renderedSnippet(row.entry.snippet, resolutions)) }
}

internal fun searchTerms(query: String): List<String> = query.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }

internal fun matches(terms: List<String>, title: String, snippet: String?): Boolean {
    val folded = listOfNotNull(title, snippet).map { it.lowercase(Locale.ROOT) }
    return terms.all { term -> folded.any { term in it } }
}

private val WHITESPACE = Regex("\\s+")
