package dev.bugobliterator.itermbuddy.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.chat.MentionDraft
import dev.bugobliterator.itermbuddy.core.chat.NotSendableReason
import dev.bugobliterator.itermbuddy.core.chat.SendOutcome
import dev.bugobliterator.itermbuddy.core.chat.applyEdit
import dev.bugobliterator.itermbuddy.core.chat.editSpan
import dev.bugobliterator.itermbuddy.core.chat.insertMention
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree

/** The composer's warning while the agent waits on an answer a new message would cancel (`CS:192-237`). */
const val PENDING_REQUEST_WARNING =
    "The agent is waiting for an answer on your Mac. Sending a message now cancels that request."

/** Why the composer is disabled, or why a send did not go, as the owner reads it. */
fun notSendableText(reason: NotSendableReason): String = when (reason) {
    NotSendableReason.NOT_CONNECTED -> "Not connected to your Mac."
    NotSendableReason.AI_UNAVAILABLE -> "AI is turned off on your Mac."
    NotSendableReason.DELETED -> "This chat was deleted on your Mac."
    NotSendableReason.NOT_LOADED -> "Loading this chat…"
    NotSendableReason.TOO_LARGE -> "This chat is too large to open on this phone."
    NotSendableReason.LOAD_FAILED -> "This chat couldn't be loaded."
    NotSendableReason.SEND_FAILED -> "Couldn't send. Your message is kept."
    NotSendableReason.NOT_RETRYABLE -> "That message can't be sent again."
}

/**
 * The draft as the composer shows it: plain text as typed, and each token as its name linked to
 * its session (the output transformation's model; [MentionOutput] is its thin adapter).
 */
fun MentionDraft.displayRuns(): List<InlineRun> {
    val runs = ArrayList<InlineRun>(tokens.size * 2 + 1)
    var at = 0
    for (token in tokens) {
        if (token.start > at) runs += InlineRun(text.substring(at, token.start))
        runs += InlineRun(token.displayName, link = LinkTarget.Session(token.guid, token.displayName))
        at = token.end
    }
    if (at < text.length) runs += InlineRun(text.substring(at))
    return runs
}

/** What a tap on Send does. */
enum class ComposerSendStep {
    /** Nothing: the draft is blank, or the chat takes no message now. */
    NOTHING,

    /** Ask first: sending cancels the request the agent waits on ([PENDING_REQUEST_WARNING]). */
    CONFIRM,

    /** Send the draft. */
    SEND,
}

/**
 * The step for a Send tap: [enabled] is whether the chat takes a message now and no send is out;
 * [warns] is [dev.bugobliterator.itermbuddy.core.chat.warnsOfPendingRequest]; [confirmed] is
 * whether the owner already answered the confirmation.
 */
fun composerSendStep(draft: MentionDraft, enabled: Boolean, warns: Boolean, confirmed: Boolean): ComposerSendStep = when {
    !enabled || draft.isBlank -> ComposerSendStep.NOTHING
    warns && !confirmed -> ComposerSendStep.CONFIRM
    else -> ComposerSendStep.SEND
}

/** The draft the field shows, kept beside its [TextFieldState]. Main thread only. */
private class DraftHolder(initial: MentionDraft) {
    var draft by mutableStateOf(initial)
}

/**
 * Keeps [holder]'s tokens in step with the field's raw text: each edit goes through
 * [applyEdit], so deleting any character of a token deletes it whole; when that widens the edit,
 * the field's text is set to the draft's and the cursor placed after the edit.
 */
@OptIn(ExperimentalFoundationApi::class)
private class MentionInput(private val holder: DraftHolder, private val onChange: (MentionDraft) -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val changes = changes
        if (changes.changeCount == 0) return
        val original = originalText.toString()
        var draft = holder.draft.takeIf { it.text == original } ?: MentionDraft(original, emptyList())
        var cursor: Int? = null
        // Last first, so each original range is still where it was.
        val order = (0 until changes.changeCount).sortedByDescending { changes.getOriginalRange(it).min }
        for (index in order) {
            val from = changes.getOriginalRange(index)
            val to = changes.getRange(index)
            val replacement = asCharSequence().subSequence(to.min, to.max).toString()
            cursor = draft.editSpan(from.min, from.max).first + replacement.length
            draft = draft.applyEdit(from.min, from.max, replacement)
        }
        if (draft.text != asCharSequence().toString()) {
            replace(0, length, draft.text)
            selection = TextRange((cursor ?: draft.text.length).coerceIn(0, draft.text.length))
        }
        holder.draft = draft
        onChange(draft)
    }
}

/** Shows each token of [holder]'s draft as its name in [linkColor], underlined. Not unit-tested: [displayRuns] is its model. */
private class MentionOutput(private val holder: DraftHolder, private val linkColor: Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val draft = holder.draft
        if (draft.tokens.isEmpty() || draft.text != asCharSequence().toString()) return
        for (token in draft.tokens.asReversed()) {
            replace(token.start, token.end, token.displayName)
            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), token.start, token.start + token.displayName.length)
        }
    }
}

/**
 * The conversation's message row (`IOS/Views/AgentComposerBar.swift:134-176`): **@** opens the
 * [MentionPicker] over [tree], whose pick is inserted at the cursor as a token; the field holds
 * [chatID]'s draft from [drafts]; Send starts [send] (app-scoped, [ChatDrafts.sendInBackground]),
 * which clears the draft only when it was sent; the composer follows that send through
 * [ChatDrafts.sendState], so one that comes back after a rotation still clears its field once and
 * offers no second Send while it is out. Disabled, with [whyNotSendable]'s text, when the chat takes no message now
 * (`IOS/Screens/ConversationView.swift:47-55`). While [warnsOfPendingRequest], the warning shows
 * and the first Send asks to confirm. Voice dictation is a later phase.
 */
@Composable
fun ChatComposer(
    chatID: String,
    drafts: ChatDrafts,
    whyNotSendable: NotSendableReason?,
    warnsOfPendingRequest: Boolean,
    tree: SessionTree?,
    send: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val holder = remember(chatID) { DraftHolder(drafts.get(chatID)) }
    val field = remember(chatID) { TextFieldState(holder.draft.text, TextRange(holder.draft.text.length)) }
    val input = remember(holder) { MentionInput(holder) { drafts.set(chatID, it) } }
    val linkColor = MaterialTheme.colorScheme.primary
    val output = remember(holder, linkColor) { MentionOutput(holder, linkColor) }
    val sendFlow = remember(chatID) { drafts.sendState(chatID) }
    val sendState by sendFlow.collectAsState()
    // Sends that ended before this composer came are already in the field it started from.
    val finishedAtStart = remember(chatID) { sendFlow.value.finished }
    val sending = sendState.sending
    var confirming by remember(chatID) { mutableStateOf(false) }
    var picking by remember(chatID) { mutableStateOf(false) }
    var problem by remember(chatID) { mutableStateOf<String?>(null) }
    val enabled = whyNotSendable == null

    // A send that ended while this composer is up (started here, or before a rotation).
    LaunchedEffect(chatID, sendState.finished) {
        if (sendState.finished <= finishedAtStart) return@LaunchedEffect
        when (val outcome = sendState.outcome) {
            SendOutcome.Sent -> if (drafts.get(chatID).text.isEmpty() && holder.draft.text.isNotEmpty()) {
                holder.draft = MentionDraft.EMPTY
                field.edit { replace(0, length, "") }
            }
            is SendOutcome.NotSendable -> problem = notSendableText(outcome.reason)
            SendOutcome.Empty, null -> Unit
        }
    }

    fun deliver() {
        problem = null
        send()
    }

    fun onSend(confirmed: Boolean) {
        when (composerSendStep(holder.draft, enabled && !sending, warnsOfPendingRequest, confirmed)) {
            ComposerSendStep.NOTHING -> Unit
            ComposerSendStep.CONFIRM -> confirming = true
            ComposerSendStep.SEND -> deliver()
        }
    }

    fun insert(guid: String, name: String) {
        picking = false
        val picked = field.selection
        val cleared = if (picked.collapsed) holder.draft else holder.draft.applyEdit(picked.min, picked.max, "")
        val at = cleared.editSpan(picked.min, picked.min).first
        val inserted = cleared.insertMention(at, guid, name)
        val cursor = at + guid.length + 2
        holder.draft = inserted
        drafts.set(chatID, inserted)
        field.edit {
            replace(0, length, inserted.text)
            selection = TextRange(cursor.coerceAtMost(inserted.text.length))
        }
    }

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val notice = when {
                whyNotSendable != null -> notSendableText(whyNotSendable)
                warnsOfPendingRequest -> PENDING_REQUEST_WARNING
                else -> problem
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { picking = true },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Mention a session" },
                ) { Text("@", style = MaterialTheme.typography.titleLarge) }
                OutlinedTextField(
                    state = field,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text("Message") },
                    inputTransformation = input,
                    outputTransformation = output,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                )
                FilledIconButton(
                    onClick = { onSend(confirmed = false) },
                    enabled = enabled && !sending && !holder.draft.isBlank,
                    modifier = Modifier.semantics { contentDescription = "Send" },
                ) { Text("↑", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send now?") },
            text = { Text(PENDING_REQUEST_WARNING) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSend(confirmed = true)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
    if (picking) MentionPicker(tree = tree, onPick = ::insert, onDismiss = { picking = false })
}
