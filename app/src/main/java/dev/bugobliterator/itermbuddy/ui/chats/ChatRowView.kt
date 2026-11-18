package dev.bugobliterator.itermbuddy.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.DEFUNCT_SESSION_TEXT
import dev.bugobliterator.itermbuddy.core.chat.MentionParser
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution

/** The Mac revision that persists muted chats (`AM:2670-2672`): below it, Mute is not offered. */
const val CHAT_MUTE_REVISION = 5

/** What a long press on a chat row offers. */
enum class ChatRowAction { MUTE, UNMUTE, DELETE }

/**
 * The actions for [entry] on a Mac at [macRevision] (null when not connected): Mute or Unmute
 * only from revision [CHAT_MUTE_REVISION], as iOS hides it on older Macs (`IOS/Screens/HomeView.swift:34-47`);
 * Delete always. A [ChatListEntry.muted] of null (a Mac that does not say) reads as not muted.
 */
fun chatRowActions(entry: ChatListEntry, macRevision: Int?): List<ChatRowAction> = buildList {
    if (macRevision != null && macRevision >= CHAT_MUTE_REVISION) add(if (entry.muted == true) ChatRowAction.UNMUTE else ChatRowAction.MUTE)
    add(ChatRowAction.DELETE)
}

/** The state chip's text: none while idle or unknown. */
fun agentStateLabel(state: AgentState): String? = when (state) {
    AgentState.WORKING -> "Working"
    AgentState.WAITING -> "Waiting for you"
    AgentState.DONE -> "Done"
    AgentState.IDLE, AgentState.UNKNOWN -> null
}

/**
 * [snippet] as the row shows it, iOS's `renderedSnippet` (`AM:4228-4247`): each @-mention the Mac
 * has answered is replaced by its display name, or [DEFUNCT_SESSION_TEXT] when it named nothing;
 * an unanswered one stays as written. Only "@"-signed mentions count (iOS parses the snippet with
 * the "@" required). Null for no snippet or an empty one. Markdown is shown as written (the
 * inline renderer comes with the conversation view).
 */
fun renderedSnippet(snippet: String?, resolutions: Map<String, MentionResolution>): String? {
    if (snippet.isNullOrEmpty()) return null
    val mentions = MentionParser.mentions(snippet)
    if (mentions.isEmpty() || resolutions.isEmpty()) return snippet
    return buildString(snippet.length) {
        var at = 0
        for (mention in mentions) {
            val resolution = resolutions[mention.identifier] ?: continue
            append(snippet, at, mention.start)
            append(resolution.displayName ?: DEFUNCT_SESSION_TEXT)
            at = mention.end
        }
        append(snippet, at, snippet.length)
    }
}

/**
 * One chat row as shown: the Mac's [summary], the [snippet] already rendered ([renderedSnippet]),
 * whether it is [muted], its agent [state] and [unread] mark ([ChatRow]), and its long-press
 * [actions]. [toString] shows lengths and IDs only.
 */
data class ChatRowModel(
    val summary: ChatSummary,
    val snippet: String?,
    val muted: Boolean,
    val state: AgentState,
    val unread: Boolean,
    val actions: List<ChatRowAction>,
) {
    val chatID: String get() = summary.id
    val title: String get() = summary.title
    val lastModifiedMillis: Double get() = summary.lastModifiedMillis
    val stateLabel: String? get() = agentStateLabel(state)

    override fun toString(): String =
        "ChatRowModel(chatID=$chatID, title(len=${title.length}), snippet(len=${snippet?.length}), muted=$muted, " +
            "state=$state, unread=$unread, actions=$actions)"
}

/** [row] as [ChatRowModel], its snippet rendered with [resolutions]. */
fun chatRowModel(row: ChatRow, macRevision: Int?, resolutions: Map<String, MentionResolution>): ChatRowModel = ChatRowModel(
    summary = row.entry.chat,
    snippet = renderedSnippet(row.entry.snippet, resolutions),
    muted = row.entry.muted == true,
    state = row.state,
    unread = row.unread,
    actions = chatRowActions(row.entry, macRevision),
)

private val WORKING_COLOR = Color(0xFF34C759)
private val WAITING_COLOR = Color(0xFFFF9F0A)
private val DONE_COLOR = Color(0xFF0A84FF)
private val ICON_SIZE = 44.dp

/**
 * One chat row: the unread dot, the icon (or a bubble glyph), the title with a bell-slash when
 * muted and the timestamp, two lines of snippet, and the state chip (green Working with a small
 * spinner, orange Waiting for you, blue Done). Tap opens; a long press offers [ChatRowModel.actions]
 * (also as accessibility actions).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRowView(
    row: ChatRowModel,
    timestamp: String,
    icon: ImageBitmap?,
    onOpen: () -> Unit,
    onAction: (ChatRowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = "Chat actions")
                .semantics { customActions = row.actions.map { action -> CustomAccessibilityAction(actionLabel(action)) { onAction(action); true } } }
                .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnreadDot(row.unread)
            ChatIcon(icon)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (row.unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.muted) {
                        Text(
                            "🔕",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = "Muted" },
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                row.snippet?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                row.stateLabel?.let { StateChip(row.state, it) }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (action in row.actions) {
                DropdownMenuItem(
                    text = {
                        Text(actionLabel(action), color = if (action == ChatRowAction.DELETE) MaterialTheme.colorScheme.error else Color.Unspecified)
                    },
                    onClick = {
                        menuOpen = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

internal fun actionLabel(action: ChatRowAction): String = when (action) {
    ChatRowAction.MUTE -> "Mute"
    ChatRowAction.UNMUTE -> "Unmute"
    ChatRowAction.DELETE -> "Delete"
}

@Composable
private fun UnreadDot(unread: Boolean) {
    Box(modifier = Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (unread) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(DONE_COLOR)
                    .semantics { contentDescription = "Unread" },
            )
        }
    }
    Box(modifier = Modifier.size(6.dp))
}

/** The chat's icon, circle-clipped; a bubble glyph on a tinted circle when it has none (`HomeView.swift:147-165`). */
@Composable
private fun ChatIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ICON_SIZE).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(ICON_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("💬", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun StateChip(state: AgentState, label: String) {
    val color = when (state) {
        AgentState.WORKING -> WORKING_COLOR
        AgentState.WAITING -> WAITING_COLOR
        else -> DONE_COLOR
    }
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state == AgentState.WORKING) CircularProgressIndicator(modifier = Modifier.size(10.dp), color = color, strokeWidth = 1.5.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
