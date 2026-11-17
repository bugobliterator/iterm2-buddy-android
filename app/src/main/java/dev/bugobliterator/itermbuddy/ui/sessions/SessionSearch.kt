package dev.bugobliterator.itermbuddy.ui.sessions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session list's search query, app-scoped so it survives opening a session and coming back,
 * and rotation. Cleared on unpair. Thread-safe. The query is shown to the owner, never logged,
 * and this holder's [toString] does not show it.
 */
class SessionSearch {
    private val flow = MutableStateFlow("")

    val query: StateFlow<String> = flow.asStateFlow()

    fun set(query: String) {
        flow.value = query
    }

    fun clear() = set("")

    override fun toString(): String = "SessionSearch(query(len=${flow.value.length}))"
}
