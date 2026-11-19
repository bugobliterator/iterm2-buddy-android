package dev.bugobliterator.itermbuddy.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.CreateOutcome
import dev.bugobliterator.itermbuddy.core.chat.NewChatMode
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.ui.sessions.SessionRowLine
import dev.bugobliterator.itermbuddy.ui.sessions.sessionRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** iOS's explanations under the New Chat picker (`IOS/Screens/CreateView.swift:56-58`). */
const val ORCHESTRATION_EXPLANATION = "The orchestrator can see and act across all of your sessions."
const val SESSION_EXPLANATION = "The chat is bound to one terminal session."
const val CREATE_FAILED_TEXT = "Couldn't create the chat."

/** Where a New Chat request stands. */
sealed interface NewChatStatus {
    data object Idle : NewChatStatus

    /** A `createChat` is out: the screen shows a spinner and takes no other pick. */
    data object Creating : NewChatStatus

    /** It failed; [retryable] offers Try again (not while AI is off). */
    data class Failed(val retryable: Boolean) : NewChatStatus
}

/**
 * Creates chats for the New Chat screen, app-scoped (never composition state): one request at a
 * time ([create] while one is out does nothing); on [CreateOutcome.Created] it calls [open] with
 * the new chat (the navigator replaces the stack with its conversation, as iOS does,
 * `AM:2582-2602`), else [status] says whether to try again. [open] runs on [scope]. [clear]
 * (unpair) drops a create still out: it neither opens nor reports.
 *
 * @param create [ChatRepository.create].
 */
class NewChatCreator(
    private val scope: CoroutineScope,
    private val create: suspend (NewChatMode) -> CreateOutcome,
    private val open: (chatID: String) -> Unit,
) {
    private val statusFlow = MutableStateFlow<NewChatStatus>(NewChatStatus.Idle)
    val status: StateFlow<NewChatStatus> = statusFlow.asStateFlow()

    @Volatile private var lastMode: NewChatMode? = null

    private val lock = Any()

    /** The pairing epoch: bumped by [clear], so a create from before it neither opens nor reports. */
    private var epoch = 0L

    /** Asks for a chat in [mode]; returns false when one is already being created. */
    fun create(mode: NewChatMode): Boolean {
        val startedIn = synchronized(lock) {
            if (statusFlow.value == NewChatStatus.Creating) return false
            statusFlow.value = NewChatStatus.Creating
            lastMode = mode
            epoch
        }
        scope.launch {
            val outcome = try {
                create.invoke(mode)
            } catch (e: CancellationException) {
                finish(startedIn) { statusFlow.value = NewChatStatus.Idle }
                throw e
            }
            when (outcome) {
                is CreateOutcome.Created -> if (finish(startedIn) { statusFlow.value = NewChatStatus.Idle }) open(outcome.chatID)
                is CreateOutcome.Failed -> finish(startedIn) { statusFlow.value = NewChatStatus.Failed(outcome.retryable) }
            }
        }
        return true
    }

    /** Runs [update] only if no [clear] came since [startedIn]; says whether it ran. */
    private inline fun finish(startedIn: Long, update: () -> Unit): Boolean = synchronized(lock) {
        if (epoch != startedIn) return false
        update()
        true
    }

    /** Unpair: forgets the failure and the last mode, and a create still out opens nothing when it ends. */
    fun clear() {
        synchronized(lock) {
            epoch++
            lastMode = null
            statusFlow.value = NewChatStatus.Idle
        }
    }

    /** Try again with the mode that failed. */
    fun retry() {
        val mode = lastMode ?: return
        if (statusFlow.value is NewChatStatus.Failed) create(mode)
    }

    /** The owner dismissed the failure (or left the screen). */
    fun dismissFailure() {
        synchronized(lock) {
            if (statusFlow.value is NewChatStatus.Failed) statusFlow.value = NewChatStatus.Idle
        }
    }

    override fun toString(): String = "NewChatCreator(status=${statusFlow.value})"
}

/** The New Chat picker's two kinds (`IOS/Screens/CreateView.swift:45-49`). */
private enum class NewChatKind(val label: String) { SESSION("A session"), ORCHESTRATION("Orchestration") }

/**
 * New Chat (`IOS/Screens/CreateView.swift:40-90`): "A session" or "Orchestration" with iOS's
 * explanation under it; a session is picked from the session [tree] (the Sessions list's rows),
 * the orchestrator with "Create Orchestrator Chat". While AI is off ([aiAvailable] false) it shows
 * the [AiUnavailablePanel] instead. [status] disables picking while a chat is being created and
 * shows a failure with Try again ([onRetry]) when retryable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    aiAvailable: Boolean,
    tree: SessionTree?,
    status: NewChatStatus,
    onBack: () -> Unit,
    onCreate: (NewChatMode) -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("←") }
                },
                title = { Text("New Chat") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!aiAvailable) {
                AiUnavailablePanel()
            } else {
                NewChatPicker(tree, status, onCreate, onRetry, onDismissFailure)
            }
        }
    }
}

@Composable
private fun NewChatPicker(
    tree: SessionTree?,
    status: NewChatStatus,
    onCreate: (NewChatMode) -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(NewChatKind.SESSION) }
    val creating = status == NewChatStatus.Creating
    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            NewChatKind.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = kind == choice,
                    onClick = { kind = choice },
                    shape = SegmentedButtonDefaults.itemShape(index, NewChatKind.entries.size),
                ) { Text(choice.label) }
            }
        }
        Text(
            if (kind == NewChatKind.ORCHESTRATION) ORCHESTRATION_EXPLANATION else SESSION_EXPLANATION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (status is NewChatStatus.Failed) FailureBanner(status.retryable, onRetry, onDismissFailure)
        if (creating) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }
        when (kind) {
            NewChatKind.ORCHESTRATION -> Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = { onCreate(NewChatMode.Orchestrator) }, enabled = !creating, modifier = Modifier.fillMaxWidth()) {
                    Text("Create Orchestrator Chat")
                }
            }
            NewChatKind.SESSION -> SessionPicker(tree, enabled = !creating) { guid -> onCreate(NewChatMode.Session(guid)) }
        }
    }
}

/** The session tree; tapping a session creates a chat bound to it. */
@Composable
private fun SessionPicker(tree: SessionTree?, enabled: Boolean, onPick: (String) -> Unit) {
    val rows = remember(tree) { tree?.let(::sessionRows).orEmpty() }
    if (rows.isEmpty()) {
        Text(
            if (tree == null) "Sessions are loading." else "No sessions are open on your Mac.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows) { row ->
            SessionRowLine(row) { session, _ -> if (enabled) onPick(session.guid) }
        }
    }
}

@Composable
private fun FailureBanner(retryable: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                CREATE_FAILED_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (retryable) TextButton(onClick = onRetry) { Text("Try again") }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
