package dev.bugobliterator.itermbuddy.ui.live

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The composer: a bottom sheet over the live view with a multi-line text field on the normal
 * keyboard (suggestions and dictation work; no personalised learning), for text longer than typing. **Paste** sends the
 * draft as one `pasteText` (iTerm2's paste path, bracketed when the app enabled it); **Paste ⏎**
 * sends the paste and then Return, the Return only once the paste was sent to the same watch.
 *
 * The draft is owned by the caller: it is cleared when sent and kept when the sheet is dismissed.
 * Neither button does anything with an empty draft or while [canSend] is false.
 *
 * @param title the session's title, for the heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    title: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    onPaste: () -> Unit,
    onPasteThenReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sendable = canSend && draft.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Compose for $title",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close" }) {
                    Text("✕", style = MaterialTheme.typography.titleMedium)
                }
            }
            NoPersonalizedLearning {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                    placeholder = { Text("Text to paste into the session") },
                    minLines = 4,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onPaste, enabled = sendable, modifier = Modifier.weight(1f)) { Text("Paste") }
                Button(
                    onClick = onPasteThenReturn,
                    enabled = sendable,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Paste and Return" },
                ) { Text("Paste ⏎") }
            }
        }
    }
}

/**
 * The composer drafts, one per session GUID, in memory for the process's life: they survive
 * rotation and leaving the view, but are never written to saved state (a draft can be large and
 * may hold anything the owner typed). Main thread only. Cleared on unpair.
 */
class ComposerDrafts {
    private val drafts = mutableMapOf<String, String>()

    fun get(sessionGuid: String): String = drafts[sessionGuid].orEmpty()

    fun set(sessionGuid: String, draft: String) {
        if (draft.isEmpty()) drafts.remove(sessionGuid) else drafts[sessionGuid] = draft
    }

    fun clear() = drafts.clear()
}

/**
 * Adds `IME_FLAG_NO_PERSONALIZED_LEARNING` to every text field in [content]: what goes to a
 * terminal (commands, secrets) must not teach the keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modified = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
            nextHandler.startInputMethod(modified)
        },
        content = content,
    )
}
