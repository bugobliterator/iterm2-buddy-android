package dev.bugobliterator.itermbuddy.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.bugobliterator.itermbuddy.core.chat.AttachmentKind
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ClientAction
import dev.bugobliterator.itermbuddy.core.chat.CommandContent
import dev.bugobliterator.itermbuddy.core.chat.EchoState
import dev.bugobliterator.itermbuddy.core.chat.MentionParser
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.StreamingState
import dev.bugobliterator.itermbuddy.core.chat.Subpart
import dev.bugobliterator.itermbuddy.core.chat.argumentWasCut
import dev.bugobliterator.itermbuddy.core.chat.markdownDescription
import dev.bugobliterator.itermbuddy.core.chat.permissionCategory
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconCache
import dev.bugobliterator.itermbuddy.ui.chats.ChatIconRef
import dev.bugobliterator.itermbuddy.ui.chats.chatIconRef

/** The read-only request card's last line: answering is a later phase. */
const val ANSWER_ON_MAC_TEXT = "Answer this on your Mac. Answering from the phone comes in a later update."

/** iOS's warning on a command the Mac's safety check flagged (`IOS/Views/MessageBubbleView.swift:238-243`). */
const val DANGEROUS_COMMAND_WARNING = "The AI safety check flagged this command as potentially dangerous. Review it with care."

/** iOS's text for content this build cannot decode (`IOS/Views/MessageBubbleView.swift:284-291`). */
const val UNSUPPORTED_MESSAGE_TEXT = "You need a newer version of iTerm2 Buddy to view this message."

/** A known message whose body did not decode (an Android leniency, *Global Constraints*). */
const val UNREADABLE_MESSAGE_TEXT = "This message couldn't be read."

/** How a text block reads: the body colour, or secondary (status updates, placeholders). */
enum class TextTone { BODY, SECONDARY }

/** The small glyph before a block, as iOS's `Label` icons. */
enum class BlockGlyph(val text: String) {
    /** `play.circle`: a tool call or a command being run. */
    PLAY("▶"),

    /** `arrow.up.circle`: update the app. */
    UPGRADE("⬆"),
}

/** One piece of a bubble or notice, as iOS stacks them (`IOS/Views/MessageBubbleView.swift:201-419`). */
sealed interface BubbleBlock {
    data class Text(val runs: List<InlineRun>, val tone: TextTone = TextTone.BODY, val glyph: BlockGlyph? = null) : BubbleBlock

    /** Monospace: a code attachment, or a terminal command and ([secondary]) its output. */
    data class Code(val text: String, val secondary: Boolean = false) : BubbleBlock {
        override fun toString(): String = "Code(text(len=${text.length}), secondary=$secondary)"
    }

    /** An image file whose bytes were kept, shown inline within the bitmap budget; [name] while it decodes. */
    data class Image(val ref: ChatIconRef, val name: String) : BubbleBlock {
        override fun toString(): String = "Image(ref=$ref, name(len=${name.length}))"
    }

    /** A file shown by name after a paperclip: not an image, its bytes not kept, or a provider file. */
    data class Paperclip(val name: String) : BubbleBlock {
        override fun toString(): String = "Paperclip(name(len=${name.length}))"
    }
}

/** One line of a read-only request card. Every [toString] shows lengths only. */
sealed interface CardLine {
    /** Orange, with a warning glyph. */
    data class Warning(val text: String) : CardLine {
        override fun toString(): String = "Warning(text(len=${text.length}))"
    }

    /** Semibold. */
    data class Title(val runs: List<InlineRun>) : CardLine

    data class Body(val runs: List<InlineRun>) : CardLine

    /** A command's whole argument, selectable monospace: shown when the description cut it (owner decision). */
    data class FullArgument(val text: String) : CardLine {
        override fun toString(): String = "FullArgument(text(len=${text.length}))"
    }

    /** Secondary: [ANSWER_ON_MAC_TEXT]. */
    data class Footer(val text: String) : CardLine
}

/** A request card's lines; there are no buttons in this phase. */
data class RequestCardContent(val lines: List<CardLine>)

/**
 * The blocks of a bubble or notice for [message], as iOS renders its content
 * (`IOS/Views/MessageBubbleView.swift:201-419`): markdown read as inline markdown and plain text
 * as written, both with mentions named ([resolve], the "@" optional in the agent's text); code
 * attachments monospace; status updates secondary; an image file inline when its bytes were
 * kept, else (and any other file) a paperclip and its name; context subparts not shown. A request
 * gets its card's lines (see [requestCard]). Pure; runs off the main thread.
 */
fun bubbleBlocks(message: ChatMessage, resolve: (String) -> MentionResolution?): List<BubbleBlock> {
    val text = MessageText(message.author == Participant.AGENT, resolve)
    return when (val body = message.body) {
        is MessageBody.PlainText -> listOf(BubbleBlock.Text(text.plain(body.text)))
        is MessageBody.Markdown -> listOf(BubbleBlock.Text(text.markdown(body.text)))
        is MessageBody.Multipart -> body.parts.mapNotNull { part ->
            when (part) {
                is Subpart.PlainText -> BubbleBlock.Text(text.plain(part.text))
                is Subpart.Markdown -> BubbleBlock.Text(text.markdown(part.text))
                is Subpart.AttachmentPart -> attachmentBlock(part.attachment.kind)
                is Subpart.Context -> null
            }
        }
        is MessageBody.ExplanationRequest -> listOf(BubbleBlock.Text(listOf(InlineRun(body.snippet))))
        is MessageBody.ExplanationResponse -> listOf(BubbleBlock.Text(text.markdown(body.markdown)))
        is MessageBody.ExternalCommand -> listOf(BubbleBlock.Text(text.markdown(body.markdownDescription), glyph = BlockGlyph.PLAY))
        is MessageBody.TerminalCommand -> listOfNotNull(
            BubbleBlock.Code(body.command),
            body.output.takeIf { it.isNotEmpty() }?.let { BubbleBlock.Code(it, secondary = true) },
        )
        is MessageBody.WatcherEvent -> listOf(BubbleBlock.Text(listOf(InlineRun(body.detail))))
        is MessageBody.ClientNotice -> noticeBlocks(body.action, text) ?: cardBlocks(message, resolve)
        is MessageBody.ClassicCommand, is MessageBody.SelectSessionRequest -> cardBlocks(message, resolve)
        MessageBody.Unsupported ->
            listOf(BubbleBlock.Text(listOf(InlineRun(UNSUPPORTED_MESSAGE_TEXT)), TextTone.SECONDARY, BlockGlyph.UPGRADE))
        is MessageBody.Unreadable -> listOf(BubbleBlock.Text(listOf(InlineRun(UNREADABLE_MESSAGE_TEXT)), TextTone.SECONDARY))
        // Deltas are folded into their message, the rest hidden: defensive only, as on iOS (`:292-297`).
        is MessageBody.Append, is MessageBody.AppendAttachment, is MessageBody.Commit, is MessageBody.Hidden -> emptyList()
    }
}

/** A non-interactive client-local notice (`IOS/Views/MessageBubbleView.swift:301-366`); null for a request. */
private fun noticeBlocks(action: ClientAction, text: MessageText): List<BubbleBlock>? {
    fun line(value: String) = listOf(BubbleBlock.Text(listOf(InlineRun(value))))
    return when (action) {
        is ClientAction.Notice -> line(action.text)
        ClientAction.PickingSession -> line("Selecting session…")
        is ClientAction.ExecutingCommand ->
            listOf(BubbleBlock.Text(text.markdown(action.content.markdownDescription()), glyph = BlockGlyph.PLAY))
        is ClientAction.StreamingChanged -> line(
            if (action.state == StreamingState.ACTIVE) "Sending commands to AI automatically" else "Stopped sending commands to AI",
        )
        is ClientAction.Permissions -> line("Permissions updated")
        ClientAction.OfferOrchestration -> line("Orchestration is available for this chat. Enable it on your Mac.")
        is ClientAction.OfferLink, is ClientAction.WorkgroupPermissionRequest, is ClientAction.EnableOrchestrationRequest,
        is ClientAction.OrchestrationPermissionGranted,
        -> null
    }
}

private fun cardBlocks(message: ChatMessage, resolve: (String) -> MentionResolution?): List<BubbleBlock> =
    requestCard(message, resolve).lines.map { line ->
        when (line) {
            is CardLine.Warning -> BubbleBlock.Text(listOf(InlineRun(line.text)))
            is CardLine.Title -> BubbleBlock.Text(line.runs)
            is CardLine.Body -> BubbleBlock.Text(line.runs)
            is CardLine.FullArgument -> BubbleBlock.Code(line.text)
            is CardLine.Footer -> BubbleBlock.Text(listOf(InlineRun(line.text)), TextTone.SECONDARY)
        }
    }

private fun attachmentBlock(kind: AttachmentKind): BubbleBlock = when (kind) {
    is AttachmentKind.Code -> BubbleBlock.Code(kind.text)
    is AttachmentKind.StatusUpdate -> BubbleBlock.Text(listOf(InlineRun(kind.display)), TextTone.SECONDARY)
    is AttachmentKind.File -> {
        val ref = if (kind.isImage) chatIconRef(kind.bytesView()) else null
        if (ref != null) BubbleBlock.Image(ref, kind.name) else BubbleBlock.Paperclip(kind.name)
    }
    is AttachmentKind.FileId -> BubbleBlock.Paperclip(kind.name)
}

/**
 * The read-only card for an interactive request, with iOS's texts
 * (`IOS/Views/MessageBubbleView.swift:216-379`) and no buttons: a classic command shows the safety
 * warning when the Mac flagged it, its `markdownDescription`, the whole argument when the
 * description cut it (owner decision, beyond iOS), and the permission it asks for; the others
 * their question. Every card ends with [ANSWER_ON_MAC_TEXT].
 */
fun requestCard(message: ChatMessage, resolve: (String) -> MentionResolution?): RequestCardContent {
    val text = MessageText(message.author == Participant.AGENT, resolve)
    fun plain(value: String) = listOf(InlineRun(value))
    fun title(value: String) = CardLine.Title(listOf(InlineRun(value, bold = true)))
    val lines = ArrayList<CardLine>()
    when (val body = message.body) {
        is MessageBody.ClassicCommand -> {
            if (body.safe == false) lines += CardLine.Warning(DANGEROUS_COMMAND_WARNING)
            lines += CardLine.Body(text.markdown(body.content.markdownDescription()))
            fullArgument(body.content)?.let { lines += CardLine.FullArgument(it) }
            val category = body.content.permissionCategory.label
            lines += CardLine.Body(inlineMarkdown("Would you like to grant AI **$category** permission?", emptyList(), resolve))
        }
        is MessageBody.SelectSessionRequest -> {
            val kind = if (body.terminal) "terminal" else "web browser"
            lines += CardLine.Body(plain("The AI agent needs to run commands in a live $kind session, but none is attached to this chat."))
        }
        is MessageBody.ClientNotice -> when (val action = body.action) {
            is ClientAction.OfferLink -> {
                val kind = if (action.terminal) "terminal" else "browser"
                lines += title("Link this chat to $kind session “${action.name ?: action.guid}”?")
                lines += CardLine.Body(plain("Linking gives the AI access to this $kind session subject to your per-call permission."))
            }
            is ClientAction.WorkgroupPermissionRequest -> {
                val kind = if (action.workgroupID.startsWith("session:")) "session" else "workgroup"
                lines += title("Allow agent to control $kind “${action.workgroupName}”?")
                if (action.summary.isNotEmpty()) lines += CardLine.Body(plain(action.summary))
            }
            is ClientAction.EnableOrchestrationRequest -> {
                lines += title("Enable orchestration?")
                lines += CardLine.Body(plain(ENABLE_ORCHESTRATION_TEXT))
            }
            is ClientAction.OrchestrationPermissionGranted -> {
                lines += title("Granted this chat permission to control “${action.name}”.")
                lines += CardLine.Body(
                    plain("You @-mentioned it, so the agent can act there without asking. Revoke to require approval again."),
                )
            }
            else -> noticeBlocks(action, text)?.forEach { if (it is BubbleBlock.Text) lines += CardLine.Body(it.runs) }
        }
        else -> bubbleBlocks(message, resolve).forEach { if (it is BubbleBlock.Text) lines += CardLine.Body(it.runs) }
    }
    lines += CardLine.Footer(ANSWER_ON_MAC_TEXT)
    return RequestCardContent(lines)
}

private const val ENABLE_ORCHESTRATION_TEXT =
    "Orchestration mode lets the agent read screen contents from any session. To type into a session still requires " +
        "your permission. Enabling will detach any linked terminal or browser session and switch the chat to Orchestration mode."

/** The argument [markdownDescription] cut at 32 characters, in full; null when nothing was cut. */
private fun fullArgument(content: CommandContent): String? {
    if (!content.argumentWasCut) return null
    return when (content) {
        is CommandContent.ExecuteCommand -> content.command
        is CommandContent.InsertTextAtCursor -> content.text
        is CommandContent.GetManPage -> content.cmd
        else -> null
    }
}

/**
 * Renders one message's texts. Every author's mentions are found with the "@" optional, so a bare
 * stable ID is always kept whole through markdown (upstream's `protect`); only the agent's bare
 * ones are linked, as the Mac links them, while the owner's show as written (iOS links only
 * "@"-signed mentions, `MessageBubbleView.swift:119-121`).
 */
private class MessageText(private val agent: Boolean, private val resolve: (String) -> MentionResolution?) {
    fun plain(value: String): List<InlineRun> =
        plainRuns(value, MentionParser.mentions(value, atSignOptional = true), resolve, atSignOptional = agent)

    fun markdown(value: String): List<InlineRun> =
        inlineMarkdown(value, MentionParser.protectedMentions(value, atSignOptional = true), resolve, atSignOptional = agent)
}

/** Colours a bubble's text is drawn in. */
private class BubbleColors(val text: Color, val secondary: Color, val link: Color, val codeBackground: Color)

/**
 * One transcript item: the owner's bubbles right in the accent colour, the agent's left, notices
 * centred and outlined, request cards left and outlined (`IOS/Views/MessageBubbleView.swift:19-38,
 * 181-197`). Text is selectable; a mention opens through [onMention]. Images decode through
 * [images] off the main thread. A message sent from this phone shows "Sending…" under it until its
 * echo comes back, or "Not delivered" with Retry and Delete ([onUnsent]). With [onDeleteFromHere],
 * a bubble shows a ⋯ menu beside it offering "Delete From Here", which calls it with the message's
 * `uniqueID` (the caller confirms).
 */
@Composable
fun MessageBubble(
    item: TranscriptItem,
    images: ChatIconCache<ImageBitmap>,
    onMention: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
    onUnsent: (UnsentAction, String) -> Unit = { _, _ -> },
    onDeleteFromHere: ((String) -> Unit)? = null,
) {
    when (item) {
        is TranscriptItem.Bubble -> ChatBubble(item, images, onMention, modifier, onUnsent, onDeleteFromHere)
        is TranscriptItem.Notice -> NoticeBubble(item, images, onMention, modifier)
        is TranscriptItem.RequestCard -> RequestCardView(item, onMention, modifier)
        TranscriptItem.Typing -> TypingIndicator(modifier)
    }
}

/** What the owner does with a message of theirs that was not delivered. */
enum class UnsentAction { RETRY, DELETE }

@Composable
private fun ChatBubble(
    item: TranscriptItem.Bubble,
    images: ChatIconCache<ImageBitmap>,
    onMention: (LinkTarget) -> Unit,
    modifier: Modifier,
    onUnsent: (UnsentAction, String) -> Unit,
    onDeleteFromHere: ((String) -> Unit)?,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = if (item.fromUser) Alignment.End else Alignment.Start) {
        BubbleBody(item, images, onMention, onDeleteFromHere)
        when (item.echo) {
            EchoState.SENDING -> Text(
                "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
            EchoState.NOT_DELIVERED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Not delivered",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = { onUnsent(UnsentAction.RETRY, item.message.uniqueID) }) { Text("Retry") }
                TextButton(onClick = { onUnsent(UnsentAction.DELETE, item.message.uniqueID) }) { Text("Delete") }
            }
            EchoState.DELIVERED, null -> Unit
        }
    }
}

@Composable
private fun BubbleBody(
    item: TranscriptItem.Bubble,
    images: ChatIconCache<ImageBitmap>,
    onMention: (LinkTarget) -> Unit,
    onDeleteFromHere: ((String) -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (item.fromUser) {
        BubbleColors(scheme.onPrimary, scheme.onPrimary.copy(alpha = 0.75f), scheme.onPrimary, scheme.onPrimary.copy(alpha = 0.15f))
    } else {
        BubbleColors(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.onSurface.copy(alpha = 0.08f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start) {
        if (item.fromUser) {
            if (onDeleteFromHere == null) {
                Spacer(Modifier.width(48.dp))
            } else {
                MessageMenu(item.message.uniqueID, onDeleteFromHere)
            }
        }
        Surface(
            color = if (item.fromUser) scheme.primary else scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            SelectionContainer {
                Blocks(item.blocks, colors, images, onMention, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (!item.fromUser) Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun NoticeBubble(item: TranscriptItem.Notice, images: ChatIconCache<ImageBitmap>, onMention: (LinkTarget) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = BubbleColors(scheme.onSurfaceVariant, scheme.onSurfaceVariant, scheme.primary, scheme.onSurface.copy(alpha = 0.08f))
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)),
        ) {
            SelectionContainer {
                Blocks(item.blocks, colors, images, onMention, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), small = true, centred = true)
            }
        }
    }
}

@Composable
private fun RequestCardView(item: TranscriptItem.RequestCard, onMention: (LinkTarget) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = BubbleColors(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.onSurface.copy(alpha = 0.08f))
    Row(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f, fill = false).border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (line in item.card.lines) CardLineView(line, colors, onMention)
                }
            }
        }
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
private fun CardLineView(line: CardLine, colors: BubbleColors, onMention: (LinkTarget) -> Unit) {
    val typography = MaterialTheme.typography
    when (line) {
        is CardLine.Warning -> Text(
            "⚠ ${line.text}",
            style = typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = warningOrange(MaterialTheme.colorScheme.surface.luminance()),
        )
        is CardLine.Title -> RunsText(line.runs, colors, onMention, typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        is CardLine.Body -> RunsText(line.runs, colors, onMention, typography.bodyLarge)
        is CardLine.FullArgument -> Surface(color = colors.codeBackground, shape = RoundedCornerShape(6.dp)) {
            Text(
                line.text,
                style = typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.text,
                modifier = Modifier.padding(8.dp),
            )
        }
        is CardLine.Footer -> Text(line.text, style = typography.bodySmall, color = colors.secondary)
    }
}

@Composable
private fun Blocks(
    blocks: List<BubbleBlock>,
    colors: BubbleColors,
    images: ChatIconCache<ImageBitmap>,
    onMention: (LinkTarget) -> Unit,
    modifier: Modifier,
    small: Boolean = false,
    centred: Boolean = false,
) {
    val typography = MaterialTheme.typography
    val base = if (small) typography.bodySmall else typography.bodyLarge
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        for (block in blocks) {
            when (block) {
                is BubbleBlock.Text -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.glyph?.let { Text(it.text, style = base, color = colors.secondary, modifier = Modifier.clearAndSetSemantics { }) }
                    val tone = if (block.tone == TextTone.SECONDARY) colors.secondary else colors.text
                    val style = if (block.tone == TextTone.SECONDARY && !small) typography.bodyMedium else base
                    RunsText(block.runs, colors, onMention, style.copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start), tone)
                }
                is BubbleBlock.Code -> Text(
                    block.text,
                    style = typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (block.secondary) colors.secondary else colors.text,
                )
                is BubbleBlock.Image -> InlineImage(block, images, colors)
                is BubbleBlock.Paperclip -> Text("📎 ${block.name}", style = base, color = colors.text)
            }
        }
    }
}

@Composable
private fun InlineImage(block: BubbleBlock.Image, images: ChatIconCache<ImageBitmap>, colors: BubbleColors) {
    val image by produceState(images.cached(block.ref.key), block.ref) { value = images.load(block.ref) }
    val bitmap = image
    if (bitmap == null) {
        Text("📎 ${block.name}", style = MaterialTheme.typography.bodyLarge, color = colors.text)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = block.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun RunsText(
    runs: List<InlineRun>,
    colors: BubbleColors,
    onMention: (LinkTarget) -> Unit,
    style: androidx.compose.ui.text.TextStyle,
    color: Color = colors.text,
) {
    val text = remember(runs, colors.link, colors.codeBackground, onMention) {
        runs.toAnnotatedString(colors.link, colors.codeBackground, onMention)
    }
    val glyphColor = colors.link
    val inline = remember(glyphColor) {
        mapOf(
            MENTION_GLYPH_ID to InlineTextContent(Placeholder(1.4.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.border(1.dp, glyphColor, RoundedCornerShape(3.dp)).padding(horizontal = 1.dp),
                ) {
                    Text(">_", color = glyphColor, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            },
        )
    }
    Text(text, style = style, color = color, inlineContent = inline)
}

/** iOS's `.orange` for the safety warning: its dark-mode value. */
internal val WARNING_ORANGE_DARK = Color(0xFFFF9F0A)

/** iOS's `.orange` in light mode. */
internal val WARNING_ORANGE_LIGHT = Color(0xFFFF9500)

/** iOS's system orange for a background of [backgroundLuminance] (dark below one half). */
internal fun warningOrange(backgroundLuminance: Float): Color =
    if (backgroundLuminance < 0.5f) WARNING_ORANGE_DARK else WARNING_ORANGE_LIGHT

/** The ⋯ beside one of the owner's bubbles (48 dp, where the gutter is): "Delete From Here". */
@Composable
private fun MessageMenu(uniqueID: String, onDeleteFromHere: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Message actions" }) {
            Text("⋯", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Delete From Here", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDeleteFromHere(uniqueID)
                },
            )
        }
    }
}
