package dev.bugobliterator.itermbuddy.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.ui.sessions.SessionRowLine
import dev.bugobliterator.itermbuddy.ui.sessions.filterSessionRows

/**
 * The name a picked session's token shows: the row's title (a peer's "Code Review: zsh", so the
 * token keeps that context), or the session's name when the title is empty
 * (`IOS/Views/AgentComposerBar.swift:233-245`).
 */
fun mentionName(session: SessionSummary, title: String): String = title.ifEmpty { session.name }

/**
 * "Mention a Session": a sheet listing the session tree with the Sessions list's rows and search
 * (`IOS/Views/AgentComposerBar.swift:397-470`); tapping a session calls [onPick] with its guid
 * and [mentionName]. iOS's preview button is left out: the live view is a tab away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionPicker(tree: SessionTree?, onPick: (guid: String, name: String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Mention a Session", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search sessions") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val rows = remember(tree, query) { tree?.let { filterSessionRows(it, query) }.orEmpty() }
            if (rows.isEmpty()) {
                Text(
                    if (tree == null) "Sessions are loading." else "No sessions match.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(rows) { _, row ->
                        SessionRowLine(row) { session, title -> onPick(session.guid, mentionName(session, title)) }
                    }
                }
            }
        }
    }
}
