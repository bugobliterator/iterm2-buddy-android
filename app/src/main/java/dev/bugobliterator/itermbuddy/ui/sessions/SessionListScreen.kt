package dev.bugobliterator.itermbuddy.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.TreeState
import java.util.Locale

internal const val EMPTY_TREE = "No sessions are open in iTerm2."
internal const val TREE_FAILED = "Couldn't load sessions."

/**
 * The Sessions list for [state]: a spinner while loading, "Couldn't load sessions." with Try
 * again on a failure, the empty text, or the rows of [sessionRows], all under pull to refresh.
 * A row tap is [onOpen] with the row's title (the navigator's `openSession`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: TreeState,
    onOpen: (SessionSummary, String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state is TreeState.Loaded && state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        when (state) {
            TreeState.Loading -> Centered { CircularProgressIndicator() }
            is TreeState.Failed -> Centered {
                Text(TREE_FAILED, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (state.retryable) Button(onClick = onRefresh) { Text("Try again") }
            }
            is TreeState.Loaded -> {
                val rows = remember(state.tree) { sessionRows(state.tree) }
                if (rows.isEmpty()) {
                    Centered { Text(EMPTY_TREE, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows) { _, row -> RowFor(row, onOpen) }
                    }
                }
            }
        }
    }
}

/** A scrollable centred column, so pull to refresh works on the empty and failure states too. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun RowFor(row: SessionRow, onOpen: (SessionSummary, String) -> Unit) {
    when (row) {
        is SessionRow.Window -> Text(
            text = row.title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
        is SessionRow.Tab -> Line(icon = "▭", title = row.title, subtitle = null, indent = 0.dp)
        SessionRow.SplitPane -> Line(icon = "▤", title = "Split Pane", subtitle = null, indent = INDENT)
        is SessionRow.Session -> Line(
            icon = if (row.summary.isBrowser) "◍" else "▣",
            title = row.title,
            subtitle = rowSubtitle(row.summary),
            indent = if (row.nested) INDENT * 2 else INDENT,
            onClick = { onOpen(row.summary, row.title) },
        )
    }
}

@Composable
private fun Line(icon: String, title: String, subtitle: String?, indent: Dp, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(start = 16.dp + indent, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp)) { Text(icon, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val INDENT = 24.dp
