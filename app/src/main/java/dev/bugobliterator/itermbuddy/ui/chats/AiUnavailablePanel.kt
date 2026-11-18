package dev.bugobliterator.itermbuddy.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal const val AI_OFF_TITLE = "AI Chat Is Off"
internal const val AI_OFF_BODY = "AI features are turned off on your paired Mac, so chats aren’t available here. To use chats, " +
    "open iTerm2 on your Mac and turn on AI in Settings (an administrator may also have disabled it)."
internal const val AI_OFF_SESSIONS = "You can still browse sessions, watch live output, and control your terminal from the Sessions tab."

/**
 * Shown in place of the chat list (and, later, the new-chat screen) while the Mac has AI off:
 * iOS's panel and texts (`IOS/Views/AIUnavailablePanel.swift:14-33`). The remedy is on the Mac, so
 * there is no button.
 */
@Composable
fun AiUnavailablePanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✨", style = MaterialTheme.typography.displayMedium, modifier = Modifier.clearAndSetSemantics { })
        Text(
            AI_OFF_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            AI_OFF_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            AI_OFF_SESSIONS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
