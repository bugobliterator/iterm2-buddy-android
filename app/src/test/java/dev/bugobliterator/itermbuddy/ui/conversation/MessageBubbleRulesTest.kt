package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.AgentStatusUpdate
import dev.bugobliterator.itermbuddy.core.chat.Attachment
import dev.bugobliterator.itermbuddy.core.chat.AttachmentKind
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatMessage
import dev.bugobliterator.itermbuddy.core.chat.ChatRepository
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.chat.ClientAction
import dev.bugobliterator.itermbuddy.core.chat.CommandContent
import dev.bugobliterator.itermbuddy.core.chat.ConversationState
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import dev.bugobliterator.itermbuddy.core.chat.MessageBody
import dev.bugobliterator.itermbuddy.core.chat.Participant
import dev.bugobliterator.itermbuddy.core.chat.Subpart
import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import dev.bugobliterator.itermbuddy.ui.HomeDestination
import dev.bugobliterator.itermbuddy.ui.SessionNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleRulesTest {
    private val chatID = "6A4F2C1E-9B3D-4E8A-B7C5-2D1F0E9A8B7C"
    private val stableID = "ptys_7K2M9Q4R8T1VH"
    private val rawGuid = "C3D4E5F6-0718-4293-A4B5-C6D7E8F90A1B"
    private var serial = 0

    private fun message(body: MessageBody, author: Participant = Participant.AGENT) =
        ChatMessage(chatID, author, body, 1795000000000.0, "11111111-2222-4333-8444-%012d".format(++serial))

    private val noResolutions: (String) -> MentionResolution? = { null }

    private fun card(body: MessageBody) = requestCard(message(body), noResolutions)

    private fun List<InlineRun>.text() = joinToString("") { it.text }

    private fun CardLine.text(): String = when (this) {
        is CardLine.Warning -> text
        is CardLine.Title -> runs.text()
        is CardLine.Body -> runs.text()
        is CardLine.FullArgument -> text
        is CardLine.Footer -> text
    }

    private val interactiveRequests = listOf(
        MessageBody.ClassicCommand(CommandContent.ExecuteCommand("ls"), safe = true),
        MessageBody.SelectSessionRequest(terminal = true),
        MessageBody.SelectSessionRequest(terminal = false),
        MessageBody.ClientNotice(ClientAction.WorkgroupPermissionRequest("R1", "wg-1", "Team", "Run the tests")),
        MessageBody.ClientNotice(ClientAction.WorkgroupPermissionRequest("R1", "session:$stableID", "zsh", "")),
        MessageBody.ClientNotice(ClientAction.EnableOrchestrationRequest("R2")),
        MessageBody.ClientNotice(ClientAction.OfferLink(terminal = true, guid = stableID, name = null)),
        MessageBody.ClientNotice(ClientAction.OrchestrationPermissionGranted("wg-1", "Team")),
    )

    @Test
    fun requestCardsHaveNoActions() {
        for (body in interactiveRequests) {
            val message = message(body)
            assertTrue(body.toString(), message.isInteractiveRequest)
            val card = requestCard(message, noResolutions)
            // Read-only: the card ends with the answer-on-your-Mac line, and says something before it.
            assertEquals(body.toString(), CardLine.Footer(ANSWER_ON_MAC_TEXT), card.lines.last())
            assertTrue(body.toString(), card.lines.size >= 2)
        }
        assertEquals(
            listOf(
                "The AI agent needs to run commands in a live terminal session, but none is attached to this chat.",
                ANSWER_ON_MAC_TEXT,
            ),
            card(MessageBody.SelectSessionRequest(terminal = true)).lines.map { it.text() },
        )
        assertEquals(
            listOf("Allow agent to control workgroup “Team”?", "Run the tests", ANSWER_ON_MAC_TEXT),
            card(interactiveRequests[3]).lines.map { it.text() },
        )
        assertEquals(
            listOf("Allow agent to control session “zsh”?", ANSWER_ON_MAC_TEXT),
            card(interactiveRequests[4]).lines.map { it.text() },
        )
        assertEquals(
            listOf(
                "Link this chat to terminal session “$stableID”?",
                "Linking gives the AI access to this terminal session subject to your per-call permission.",
                ANSWER_ON_MAC_TEXT,
            ),
            card(interactiveRequests[6]).lines.map { it.text() },
        )
        assertEquals("Enable orchestration?", card(interactiveRequests[5]).lines.first().text())
        assertEquals("Granted this chat permission to control “Team”.", card(interactiveRequests[7]).lines.first().text())
    }

    @Test
    fun dangerousCommandShowsWarning() {
        val flagged = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("rm -rf build"), safe = false))
        assertEquals(CardLine.Warning(DANGEROUS_COMMAND_WARNING), flagged.lines.first())
        assertEquals("The AI safety check flagged this command as potentially dangerous. Review it with care.", DANGEROUS_COMMAND_WARNING)
        for (safe in listOf(true, null)) {
            val card = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("rm -rf build"), safe = safe))
            assertFalse(card.lines.any { it is CardLine.Warning })
        }
    }

    @Test
    fun commandCardShowsDescriptionAndCategory() {
        val lines = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("ls -la"), safe = true)).lines
        assertEquals(
            CardLine.Body(listOf(InlineRun("Executing "), InlineRun("ls -la", code = true))),
            lines[0],
        )
        assertEquals(
            CardLine.Body(
                listOf(InlineRun("Would you like to grant AI "), InlineRun("Run Commands", bold = true), InlineRun(" permission?")),
            ),
            lines[1],
        )
        assertEquals(CardLine.Footer(ANSWER_ON_MAC_TEXT), lines[2])
        val clipboard = card(MessageBody.ClassicCommand(CommandContent.SetClipboard, safe = null)).lines.map { it.text() }
        assertEquals(
            listOf("Pasting to the clipboard", "Would you like to grant AI Write to the Clipboard permission?", ANSWER_ON_MAC_TEXT),
            clipboard,
        )
    }

    @Test
    fun cutCommandArgumentIsShownInFull() {
        val long = "find . -name '*.kt' -exec grep -l `whoami` {} +"
        val lines = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand(long), safe = true)).lines
        assertTrue(lines[0].text().endsWith("…"))
        assertEquals(CardLine.FullArgument(long), lines[1])
        for (content in listOf(CommandContent.InsertTextAtCursor(long), CommandContent.GetManPage(long))) {
            assertTrue(card(MessageBody.ClassicCommand(content, safe = true)).lines.contains(CardLine.FullArgument(long)))
        }
        val short = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand("ls"), safe = true)).lines
        assertFalse(short.any { it is CardLine.FullArgument })
    }

    @Test
    fun bubbleBlocksFollowIos() {
        val image = AttachmentKind.File("shot.png", "image/png", 3, byteArrayOf(1, 2, 3))
        val imageWithoutBytes = AttachmentKind.File("big.png", "image/png", 9_000_000, null)
        val pdf = AttachmentKind.File("notes.pdf", "application/pdf", 10, null)
        val blocks = bubbleBlocks(
            message(
                MessageBody.Multipart(
                    listOf(
                        Subpart.Markdown("Done **now**"),
                        Subpart.AttachmentPart(Attachment(true, "a", AttachmentKind.Code("val x = 1"))),
                        Subpart.AttachmentPart(Attachment(true, "b", AttachmentKind.StatusUpdate(AgentStatusUpdate.WebSearchStarted))),
                        Subpart.AttachmentPart(Attachment(true, "c", image)),
                        Subpart.AttachmentPart(Attachment(true, "d", imageWithoutBytes)),
                        Subpart.AttachmentPart(Attachment(true, "e", pdf)),
                        Subpart.AttachmentPart(Attachment(false, "f", AttachmentKind.FileId("file-1", "data.csv"))),
                        Subpart.Context("hidden context"),
                    ),
                ),
            ),
            noResolutions,
        )
        assertEquals(7, blocks.size)
        assertEquals(BubbleBlock.Text(listOf(InlineRun("Done "), InlineRun("now", bold = true))), blocks[0])
        assertEquals(BubbleBlock.Code("val x = 1"), blocks[1])
        assertEquals(BubbleBlock.Text(listOf(InlineRun("Searching the web…")), TextTone.SECONDARY), blocks[2])
        assertTrue(blocks[3] is BubbleBlock.Image)
        assertEquals(BubbleBlock.Paperclip("big.png"), blocks[4])
        assertEquals(BubbleBlock.Paperclip("notes.pdf"), blocks[5])
        assertEquals(BubbleBlock.Paperclip("data.csv"), blocks[6])

        assertEquals(
            listOf(BubbleBlock.Text(listOf(InlineRun(UNSUPPORTED_MESSAGE_TEXT)), TextTone.SECONDARY, BlockGlyph.UPGRADE)),
            bubbleBlocks(message(MessageBody.Unsupported), noResolutions),
        )
        assertEquals("You need a newer version of iTerm2 Buddy to view this message.", UNSUPPORTED_MESSAGE_TEXT)
        assertEquals(
            listOf(BubbleBlock.Text(listOf(InlineRun(UNREADABLE_MESSAGE_TEXT)), TextTone.SECONDARY)),
            bubbleBlocks(message(MessageBody.Unreadable("markdown")), noResolutions),
        )
        assertEquals("This message couldn't be read.", UNREADABLE_MESSAGE_TEXT)
        // Plain text is not markdown, but its mentions are still names.
        val hit = MentionResolution(stableID, "Build shell", stableID, null)
        assertEquals(
            listOf(BubbleBlock.Text(listOf(InlineRun("**ask** "), InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell"))))),
            bubbleBlocks(message(MessageBody.PlainText("**ask** @$stableID"), Participant.USER)) { if (it == stableID) hit else null },
        )
        assertEquals(
            listOf(BubbleBlock.Code("make test"), BubbleBlock.Code("ok", secondary = true)),
            bubbleBlocks(message(MessageBody.TerminalCommand("make test", "ok")), noResolutions),
        )
        assertEquals(
            listOf(BubbleBlock.Text(listOf(InlineRun("Listing sessions")), glyph = BlockGlyph.PLAY)),
            bubbleBlocks(message(MessageBody.ExternalCommand("list_sessions", "Listing sessions", safe = null)), noResolutions),
        )
        assertEquals(
            listOf(BubbleBlock.Text(listOf(InlineRun("Sending commands to AI automatically")))),
            bubbleBlocks(message(MessageBody.ClientNotice(ClientAction.StreamingChanged(dev.bugobliterator.itermbuddy.core.chat.StreamingState.ACTIVE))), noResolutions),
        )
    }

    private fun summary(guid: String, title: String = "Fix flaky widget test", scopes: List<String> = emptyList()) =
        ChatSummary(chatID, title, 1794960060000.0, 1795000300000.0, guid.isEmpty(), guid.ifEmpty { null }, null, scopes, null)

    private val planner = SessionSummary(stableID, "claude", "planning", isBrowser = false)
    private val tree = SessionTree(
        listOf(
            WindowEntry(
                "Window",
                listOf(
                    TabEntry(
                        "Tab",
                        listOf(
                            PaneEntry(
                                SessionSummary("ptys_9PJ4C8EGA6S0J", "tail", "", isBrowser = false),
                                listOf(PeerEntry("Planner", planner), PeerEntry("Coder", SessionSummary("ptys_6V0T2B8NW4HEQ", "claude", "", false))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun goToSessionPrefersTreeMatch() {
        for (live in listOf(null, false, true)) {
            assertEquals(GoToSession(planner, "Planner: claude"), goToSession(summary(stableID), tree, live))
        }
        // The tree holds stable IDs in canonical form; a chat may hold one in another case.
        assertEquals(GoToSession(planner, "Planner: claude"), goToSession(summary(stableID.lowercase()), tree, null))
    }

    @Test
    fun goToSessionResolvesRawGuidThroughMac() {
        val chat = summary(rawGuid)
        assertNull(goToSession(chat, tree, live = null))
        val target = goToSession(chat, tree, live = true) ?: throw AssertionError("no button once confirmed")
        assertEquals(rawGuid, target.session.guid)
        assertEquals("Fix flaky widget test", target.title)

        val opened = mutableListOf<SessionSummary>()
        val navigator = SessionNavigator(open = { opened += it }, close = {}, macRevision = { 15 })
        navigator.openSession(target.session, target.title)
        assertEquals(listOf(rawGuid), opened.map { it.guid })
        val destination = navigator.destination.value as HomeDestination.LiveSession
        assertEquals(rawGuid, destination.session.guid)
        assertEquals("Fix flaky widget test", destination.title)
    }

    @Test
    fun goToSessionHiddenWhileUnconfirmed() {
        assertNull(goToSession(summary(rawGuid), tree, live = null))
        assertNull(goToSession(summary(rawGuid), tree, live = false))
        assertNull(goToSession(summary(rawGuid), null, live = null))
        // An orchestrator (unbound) chat never shows it, and nor does an unknown chat.
        assertNull(goToSession(summary(""), tree, live = true))
        assertNull(goToSession(null, tree, live = true))
    }

    @Test
    fun topBarAndBodyFollowTheState() {
        val entry = ChatListEntry(summary("", title = "Orchestrator", scopes = listOf("wg-1", "session:$stableID")), null, muted = true)
        val presenter = ConversationPresenter()
        fun present(state: ConversationState, revision: Int? = 12, chat: ChatListEntry? = entry) =
            presenter.present(chatID, state, chat, AgentState.WORKING, revision, tree, null, emptyMap())

        val shown = present(ConversationState.Shown(listOf(message(MessageBody.Markdown("hi"))), 1, stale = true, deleted = true, sendable = false))
        assertEquals("Orchestrator", shown.title)
        assertEquals(true, shown.muted)
        assertTrue(shown.showsMute)
        assertNull(shown.goToSession)
        assertEquals(listOf(WorkgroupLink("wg-1", "wg-1")), shown.workgroups)
        val body = shown.body as ConversationBody.Shown
        assertEquals(listOf(STALE_CONVERSATION_TEXT, ChatRepository.DELETED_CHAT_NOTICE), body.banners)
        assertEquals("Couldn’t refresh. Showing the last loaded messages.", STALE_CONVERSATION_TEXT)
        assertTrue(body.items.last() === TranscriptItem.Typing)

        assertFalse(present(ConversationState.Loading, revision = 4).showsMute)
        assertEquals("Chat", present(ConversationState.Loading, chat = null).title)
        assertEquals(ConversationBody.Loading, present(ConversationState.Loading).body)
        assertEquals(ConversationBody.TooLarge, present(ConversationState.TooLarge).body)
        assertEquals(ConversationBody.Failed(retryable = true), present(ConversationState.Failed(retryable = true)).body)
        assertEquals("This chat is too large to open on this phone.", TOO_LARGE_CONVERSATION_TEXT)
        assertEquals("Couldn't load this chat.", FAILED_CONVERSATION_TEXT)
    }

    @Test
    fun cardsAndBlocksHoldNoContentInToString() {
        val secret = "Sentinel deploy shell"
        val card = card(MessageBody.ClassicCommand(CommandContent.ExecuteCommand(secret + secret), safe = false))
        assertFalse(card.toString().contains(secret))
        for (line in card.lines) assertFalse(line.toString().contains(secret))
        assertFalse(BubbleBlock.Code(secret).toString().contains(secret))
        assertFalse(BubbleBlock.Paperclip(secret).toString().contains(secret))
        assertFalse(GoToSession(planner.copy(name = secret), secret).toString().contains(secret))
        assertFalse(WorkgroupLink("wg-1", secret).toString().contains(secret))
    }
}
