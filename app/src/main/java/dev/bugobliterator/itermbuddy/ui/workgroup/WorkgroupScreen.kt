package dev.bugobliterator.itermbuddy.ui.workgroup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.SessionState
import dev.bugobliterator.itermbuddy.core.chat.StableSessionId
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupFailure
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupMember
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupState
import dev.bugobliterator.itermbuddy.core.chat.WorkgroupWatch
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.ui.sessions.SessionRow
import dev.bugobliterator.itermbuddy.ui.sessions.sessionRows

/** Under the members when the latest request failed and the list is from an earlier answer. */
const val STALE_WORKGROUP_TEXT = "Couldn’t refresh. Showing the last loaded members."

/** The footer of a workgroup with no members (`IOS/Screens/WorkgroupView.swift:53-56`). */
const val EMPTY_WORKGROUP_TEXT = "This workgroup has no members."

/** The canned text for [failure]: never the Mac's own message. */
fun workgroupFailureText(failure: WorkgroupFailure): String = when (failure) {
    WorkgroupFailure.GONE -> "That workgroup no longer exists."
    WorkgroupFailure.UNREACHABLE -> "Couldn’t load this workgroup."
}

/** The bar title: the workgroup's name once read, else [fallback] (the mention or menu label, `:40`). */
fun workgroupTitle(state: WorkgroupState, fallback: String): String =
    (state as? WorkgroupState.Shown)?.info?.name ?: fallback

/**
 * The line under a member (`IOS/Screens/WorkgroupView.swift:97-110`): "Hasn’t started yet" without
 * a session; else its reported status and detail joined by ": ", either alone, or null.
 */
fun memberStatusLine(member: WorkgroupMember): String? {
    if (member.sessionGuid == null) return "Hasn’t started yet"
    val status = member.statusText ?: return member.detailText
    val detail = member.detailText ?: return status
    return "$status: $detail"
}

/** The member's state glyph (`IOS/Screens/WorkgroupView.swift:112-133`). */
enum class MemberGlyph {
    /** No session yet: a dashed circle, whatever the state. */
    NOT_STARTED,
    WORKING,
    WAITING,
    IDLE,

    /** A session whose state the Mac does not know (or a future one): a terminal glyph. */
    UNKNOWN,
}

fun memberGlyph(member: WorkgroupMember): MemberGlyph = when {
    member.sessionGuid == null -> MemberGlyph.NOT_STARTED
    else -> when (member.state) {
        SessionState.WORKING -> MemberGlyph.WORKING
        SessionState.WAITING -> MemberGlyph.WAITING
        SessionState.IDLE -> MemberGlyph.IDLE
        SessionState.UNKNOWN -> MemberGlyph.UNKNOWN
    }
}

/** The glyph's spoken label: Working, Waiting, Idle, Unknown, or Not started. */
fun memberStateLabel(member: WorkgroupMember): String = when (memberGlyph(member)) {
    MemberGlyph.NOT_STARTED -> "Not started"
    MemberGlyph.WORKING -> "Working"
    MemberGlyph.WAITING -> "Waiting"
    MemberGlyph.IDLE -> "Idle"
    MemberGlyph.UNKNOWN -> "Unknown"
}

/** The session's name under the role, only when it says more than the role (`:82-86`). */
fun memberSessionName(member: WorkgroupMember): String? = member.sessionName?.takeIf { it != member.roleName }

/** A member's session view and its title (the role name, `:64-72`). [toString] shows no names. */
data class MemberSession(val session: SessionSummary, val title: String) {
    override fun toString(): String = "MemberSession(session=$session, title(len=${title.length}))"
}

/**
 * What tapping [member] opens, or null when it has no session yet (not tappable). Its `sessionGuid`
 * is the session's stable ID (`HB:2183`), as the tree's are (compared in canonical form): a member
 * found in [tree] opens that session, its browser flag included; one not found (or no tree yet)
 * opens a terminal view by its own ID, which the Mac resolves (`HB:2007`).
 */
fun memberSession(member: WorkgroupMember, tree: SessionTree?): MemberSession? {
    val guid = member.sessionGuid ?: return null
    val wanted = sessionKey(guid)
    if (tree != null) {
        for (row in sessionRows(tree)) {
            if (row is SessionRow.Session && sessionKey(row.summary.guid) == wanted) return MemberSession(row.summary, member.roleName)
        }
    }
    return MemberSession(SessionSummary(guid, member.sessionName.orEmpty(), "", isBrowser = false), member.roleName)
}

/** Stable IDs compare in canonical form, anything else without case. */
private fun sessionKey(guid: String): String = StableSessionId.canonical(guid) ?: guid.uppercase()

/** The workgroup view's callbacks. Navigation goes to app-scoped owners, never composition state. */
interface WorkgroupActions {
    fun back()

    /** Pull to refresh, or Try again ([WorkgroupWatch.refresh]). */
    fun refresh()

    /** A member with a session was tapped: open [target] (from [memberSession]). */
    fun openMember(target: MemberSession)
}

/**
 * The workgroup view for [watch]: started while it is composed and stopped when it leaves (iOS's
 * `.task(id:)` load on appear, `IOS/Screens/WorkgroupView.swift:42-44`). [tree] is the session tree,
 * read when a member is tapped. The watch's foreground and connection gates are the caller's to set:
 * make it with [dev.bugobliterator.itermbuddy.HomeGraph.watchWorkgroup] in a scope that ends with the
 * destination (for example `remember(workgroupID) { home.watchWorkgroup(rememberedScope, workgroupID) }`).
 */
@Composable
fun WorkgroupRoute(
    watch: WorkgroupWatch,
    fallbackTitle: String,
    tree: () -> SessionTree?,
    onBack: () -> Unit,
    onOpenSession: (SessionSummary, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(watch) {
        val shown = showWorkgroupRoute(watch)
        onDispose { shown.close() }
    }
    val state by watch.state.collectAsState()
    val actions = remember(watch, onBack, onOpenSession) { workgroupRouteActions(watch, onBack, onOpenSession) }
    WorkgroupScreen(state = state, fallbackTitle = fallbackTitle, actions = actions, tree = tree, modifier = modifier)
}

/**
 * The route's lifetime on [watch], without Compose: started now, stopped when the returned
 * handle closes (the route leaving composition). [WorkgroupRoute] calls it; tests drive it.
 */
fun showWorkgroupRoute(watch: WorkgroupWatch): AutoCloseable {
    watch.start()
    return AutoCloseable { watch.stop() }
}

/** The route's calls on [watch]: Back, refresh, and a member's session opened with [onOpenSession]. */
fun workgroupRouteActions(
    watch: WorkgroupWatch,
    onBack: () -> Unit,
    onOpenSession: (SessionSummary, String) -> Unit,
): WorkgroupActions = object : WorkgroupActions {
    override fun back() = onBack()

    override fun refresh() = watch.refresh()

    override fun openMember(target: MemberSession) = onOpenSession(target.session, target.title)
}

/**
 * A workgroup destination whose watch is already gone (a frame between its pop and the next
 * destination): the top bar with the title and Back over the loading state, never an empty screen.
 */
@Composable
fun WorkgroupFallback(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    WorkgroupScreen(
        state = WorkgroupState.Loading,
        fallbackTitle = title,
        actions = object : WorkgroupActions {
            override fun back() = onBack()

            override fun refresh() = Unit

            override fun openMember(target: MemberSession) = Unit
        },
        tree = { null },
        modifier = modifier,
    )
}

/**
 * A workgroup's members (`IOS/Screens/WorkgroupView.swift`): a top bar with ← and the workgroup's
 * name; one row per member with its state glyph, role, session name and status line; members not
 * started are dimmed and not tappable. Loading and failures fill the body; pull to refresh asks again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkgroupScreen(
    state: WorkgroupState,
    fallbackTitle: String,
    actions: WorkgroupActions,
    tree: () -> SessionTree?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = actions::back, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("←") }
                },
                title = { Text(workgroupTitle(state, fallbackTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        val refreshing = (state as? WorkgroupState.Shown)?.refreshing == true
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (state) {
                WorkgroupState.Loading -> Centered {
                    CircularProgressIndicator()
                    Text("Loading workgroup…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is WorkgroupState.Failed -> Centered {
                    Text(workgroupFailureText(state.reason), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (state.retryable) Button(onClick = actions::refresh) { Text("Try again") }
                }
                is WorkgroupState.Shown -> Column(modifier = Modifier.fillMaxSize()) {
                    if (state.stale) StaleBanner()
                    MemberList(state.info.members) { member ->
                        memberSession(member, tree())?.let(actions::openMember)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberList(members: List<WorkgroupMember>, onOpen: (WorkgroupMember) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Members have no identity of their own (iOS keys them by offset, `:50`).
        itemsIndexed(members) { _, member ->
            MemberRow(member, onOpen)
            HorizontalDivider()
        }
        if (members.isEmpty()) {
            item {
                Text(
                    EMPTY_WORKGROUP_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MemberRow(member: WorkgroupMember, onOpen: (WorkgroupMember) -> Unit) {
    val tappable = member.sessionGuid != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable { onOpen(member) } else Modifier)
            .alpha(if (tappable) 1f else 0.6f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MemberGlyphView(member)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(member.roleName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            memberSessionName(member)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            memberStatusLine(member)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tappable) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun MemberGlyphView(member: WorkgroupMember) {
    val glyph = memberGlyph(member)
    val (text, color) = when (glyph) {
        MemberGlyph.NOT_STARTED -> "◌" to MaterialTheme.colorScheme.onSurfaceVariant
        MemberGlyph.WORKING -> "▶" to WORKING_COLOR
        MemberGlyph.WAITING -> "!" to WAITING_COLOR
        MemberGlyph.IDLE -> "⏸" to MaterialTheme.colorScheme.onSurfaceVariant
        MemberGlyph.UNKNOWN -> "▣" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = memberStateLabel(member)
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(24.dp).padding(top = 2.dp).clearAndSetSemantics { contentDescription = label },
    )
}

@Composable
private fun StaleBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            STALE_WORKGROUP_TEXT,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A scrollable centred column, so pull to refresh works on the loading and failure states too. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** iOS's system green and orange (`:119-124`), legible on light and dark surfaces. */
private val WORKING_COLOR = Color(0xFF34C759)
private val WAITING_COLOR = Color(0xFFFF9500)
