package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.newMessageId
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDecodingTest {
    private val budgets = AttachmentBudgets()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun sampleJson(name: String): JsonObject =
        json(Fixtures.read("messages/agent-chat/$name.json")).getValue("json").jsonObject

    private val historyArray: JsonArray by lazy {
        sampleJson("history")["payload"]!!.jsonObject["history"]!!.jsonObject["messages"]!!.jsonArray
    }

    private val history: List<ChatMessage> by lazy { decodeChatMessages(historyArray, budgets) }

    private fun historyMessage(idSuffix: String): ChatMessage = history.single { it.uniqueID.endsWith(idSuffix) }

    private val deliveryNames: List<String> by lazy {
        Fixtures.list("messages/agent-chat")
            .map { it.substringAfterLast('/').removeSuffix(".json") }
            .filter { it.startsWith("delivery-") }
            .also { assertTrue("no delivery samples", it.size >= 10) }
    }

    private fun deliveryMessage(name: String): JsonObject =
        sampleJson(name)["payload"]!!.jsonObject["delivery"]!!.jsonObject["message"]!!.jsonObject

    /** The `message` of a decode-tolerance envelope (a delivery's, or a history's messages). */
    private fun toleranceEnvelope(name: String): JsonObject {
        val cases = Json.parseToJsonElement(Fixtures.read("chat/decode-tolerance.json")).jsonArray
        val case = cases.single { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
        return json(case.getValue("json").jsonPrimitive.content).getValue("payload").jsonObject
    }

    /** A message around [content], with the given header fields. */
    private fun message(
        content: String,
        author: String = "\"agent\"",
        chatID: String = "\"6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E\"",
        sentDate: String = "1795000000000",
        uniqueID: String = "\"11111111-2222-4333-8444-000000000999\"",
    ): JsonObject = json(
        """{"author":$author,"chatID":$chatID,"content":$content,"sentDate":$sentDate,"uniqueID":$uniqueID}""",
    )

    @Test
    fun everyVisibleKindDecodes() {
        val bodies = history.map { it.body } + deliveryNames.map { decodeChatMessage(deliveryMessage(it), budgets).body }
        for (body in bodies) {
            assertFalse("$body", body is MessageBody.Unsupported || body is MessageBody.Unreadable)
        }
        assertEquals(historyArray.size, history.size)
        assertTrue(history.none { it.hidden })

        assertEquals(
            MessageBody.PlainText("Ask @ptys_7K2M9Q4R8T1VH to run the tests, then summarise."),
            historyMessage("000000000001").body,
        )
        assertEquals(MessageBody.PlainText("What does this error mean?"), historyMessage("000000000010").body)
        assertEquals(MessageBody.Markdown("Asking **Implementer** to run `make test`."), historyMessage("000000000020").body)
        val notes = historyMessage("000000000030")
        assertEquals(Participant.USER, notes.author)
        assertEquals(
            MessageBody.Multipart(
                listOf(
                    Subpart.PlainText("Here are my notes."),
                    Subpart.Context("Attached from the Mac."),
                    Subpart.AttachmentPart(
                        Attachment(false, "file-notes", AttachmentKind.File("notes.txt", "text/plain", 15, null)),
                    ),
                ),
            ),
            notes.body,
        )
        val run = historyMessage("000000000035").body as MessageBody.Multipart
        assertEquals(Subpart.Markdown("The run produced this:"), run.parts[0])
        assertEquals(
            Subpart.AttachmentPart(Attachment(true, "ci_run_tests", AttachmentKind.Code("print('2 passed, 1 failed')"))),
            run.parts[1],
        )
        val plot = ((run.parts[2] as Subpart.AttachmentPart).attachment.kind as AttachmentKind.File)
        assertEquals("plot.png", plot.name)
        assertEquals(70, plot.size)
        assertEquals(
            Subpart.AttachmentPart(Attachment(false, "file-report", AttachmentKind.FileId("file-abc123", "report.csv"))),
            run.parts[3],
        )
        val status = (run.parts[4] as Subpart.AttachmentPart).attachment.kind as AttachmentKind.StatusUpdate
        assertEquals("Finished searching the web for widget resize flake.", status.display)
        assertEquals(MessageBody.ExplanationRequest("Explain make test"), historyMessage("000000000050").body)
        assertEquals(
            MessageBody.ExplanationResponse("Line 41 of `widget.c` lacks a semicolon."),
            historyMessage("000000000060").body,
        )
        assertEquals(
            MessageBody.ClassicCommand(CommandContent.ExecuteCommand("make test"), safe = false),
            historyMessage("000000000070").body,
        )
        assertEquals(
            MessageBody.ExternalCommand("send_text", "Sending `make test` to Implementer in Widget Review", safe = null),
            historyMessage("000000000080").body,
        )
        assertEquals(
            MessageBody.WatcherEvent("Implementer is idle after the test run."),
            historyMessage("000000000150").body,
        )
        assertEquals(MessageBody.SelectSessionRequest(terminal = true), historyMessage("000000000160").body)
        val actions = listOf(
            ClientAction.PickingSession,
            ClientAction.ExecutingCommand(CommandContent.ExecuteCommand("make test")),
            ClientAction.Notice("Linked this chat to zsh."),
            ClientAction.StreamingChanged(StreamingState.ACTIVE),
            ClientAction.OfferLink(terminal = true, guid = "ptys_7K2M9Q4R8T1VH", name = "zsh"),
            ClientAction.OfferOrchestration,
            ClientAction.Permissions(terminal = true, guid = "ptys_7K2M9Q4R8T1VH"),
            ClientAction.WorkgroupPermissionRequest(
                "permission-widget-review",
                "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F",
                "Widget Review",
                "Send `make test` to Implementer",
            ),
            ClientAction.EnableOrchestrationRequest("enable-orchestration-widget"),
            ClientAction.OrchestrationPermissionGranted("session:ptys_8M3B5X1QJ7TW1", "vim"),
        )
        actions.forEachIndexed { index, action ->
            assertEquals(MessageBody.ClientNotice(action), historyMessage("00000000020$index").body)
        }
        assertEquals(
            MessageBody.TerminalCommand("make test", "2 passed, 1 failed\n"),
            historyMessage("000000000300").body,
        )

        assertEquals(
            MessageBody.Append(" in **zsh** now.", "11111111-2222-4333-8444-000000000501"),
            decodeChatMessage(deliveryMessage("delivery-append"), budgets).body,
        )
        assertEquals(
            MessageBody.AppendAttachment(
                Attachment(true, "ci_run_tests", AttachmentKind.Code("print('ok')")),
                "11111111-2222-4333-8444-000000000501",
            ),
            decodeChatMessage(deliveryMessage("delivery-append-attachment-code"), budgets).body,
        )
    }

    @Test
    fun hiddenKindsAreMarkedHidden() {
        val hidden = mapOf(
            "remoteCommandResponse" to """{"remoteCommandResponse":{"_0":{"success":{"_0":"ok"}},
                "_1":"11111111-2222-4333-8444-000000000070","_2":"execute_command"}}""",
            "renameChat" to """{"renameChat":{"_0":"Widget fixes"}}""",
            "setPermissions" to """{"setPermissions":{"_0":["Run Commands"]}}""",
            "vectorStoreCreated" to """{"vectorStoreCreated":{"id":"vs_widget_notes"}}""",
            "userCommand" to """{"userCommand":{"_0":{"stop":{}}}}""",
        )
        for ((case, content) in hidden) {
            val decoded = decodeChatMessage(message(content), budgets)
            assertEquals(MessageBody.Hidden(case), decoded.body)
            assertTrue(case, decoded.hidden)
        }
        val commitDelta = Json.parseToJsonElement(Fixtures.read("chat/streaming-merge.json")).jsonArray
            .single { it.jsonObject["name"]!!.jsonPrimitive.content == "commit-is-ignored" }
            .jsonObject["deltas"]!!.jsonArray.single().jsonObject
        val commit = decodeChatMessage(commitDelta, budgets)
        assertEquals(MessageBody.Commit("22222222-3333-4444-8555-000000000838"), commit.body)
        assertTrue(commit.hidden)
        // A corrupt hidden body stays hidden: it is never shown, so it is never Unreadable.
        assertTrue(decodeChatMessage(message("""{"commit":{"_0":42}}"""), budgets).hidden)
        assertTrue(decodeChatMessage(message("""{"renameChat":{"_0":42}}"""), budgets).hidden)

        // Every visible kind, streaming deltas, placeholders and unknown cases included, is shown.
        assertTrue(history.none { it.hidden })
        assertTrue(deliveryNames.none { decodeChatMessage(deliveryMessage(it), budgets).hidden })
        assertFalse(decodeChatMessage(message("""{"unsupported":{}}"""), budgets).hidden)
        assertFalse(decodeChatMessage(message("""{"voiceNote":{"_0":"x"}}"""), budgets).hidden)
        assertFalse(decodeChatMessage(message("""{"markdown":{"_0":42}}"""), budgets).hidden)
    }

    @Test
    fun interactiveRequestsAreFlagged() {
        val interactive = history.filter { it.isInteractiveRequest }.map { it.uniqueID.takeLast(3) }
        // classic command, select session, offer link, workgroup permission, enable orchestration
        assertEquals(listOf("070", "160", "204", "207", "208"), interactive)
        for (name in listOf("delivery-remote-command-classic", "delivery-select-session-request",
            "delivery-workgroup-permission-request", "delivery-enable-orchestration-request")) {
            assertTrue(name, decodeChatMessage(deliveryMessage(name), budgets).isInteractiveRequest)
        }
        assertFalse(decodeChatMessage(deliveryMessage("delivery-remote-command-external"), budgets).isInteractiveRequest)
    }

    @Test
    fun blockingAndAbandonableRequestsAreFlagged() {
        val blocking = history.filter { it.isBlockingRequest }.map { it.uniqueID.takeLast(3) }
        assertEquals(listOf("070", "160", "207", "208"), blocking)
        val abandonable = history.filter { it.isAbandonableRequest }.map { it.uniqueID.takeLast(3) }
        assertEquals(listOf("070", "208"), abandonable)

        val external = decodeChatMessage(deliveryMessage("delivery-remote-command-external"), budgets)
        assertFalse(external.isBlockingRequest || external.isAbandonableRequest)
        val selectSession = decodeChatMessage(deliveryMessage("delivery-select-session-request"), budgets)
        assertTrue(selectSession.isBlockingRequest)
        assertFalse(selectSession.isAbandonableRequest)
        val workgroup = decodeChatMessage(deliveryMessage("delivery-workgroup-permission-request"), budgets)
        assertTrue(workgroup.isBlockingRequest)
        assertFalse(workgroup.isAbandonableRequest)
        val enable = decodeChatMessage(deliveryMessage("delivery-enable-orchestration-request"), budgets)
        assertTrue(enable.isBlockingRequest && enable.isAbandonableRequest)
        val classic = decodeChatMessage(deliveryMessage("delivery-remote-command-classic"), budgets)
        assertTrue(classic.isBlockingRequest && classic.isAbandonableRequest)
        // An executing-command notice means the command already runs: nothing to answer.
        assertFalse(historyMessage("000000000201").isBlockingRequest)
    }

    @Test
    fun statusUpdateDisplayMatchesUpstream() {
        val entries = Json.parseToJsonElement(Fixtures.read("chat/status-update-display.json")).jsonArray
        assertTrue(entries.size >= 10)
        for (entry in entries.map { it.jsonObject }) {
            val name = entry.getValue("name").jsonPrimitive.content
            val content = """{"appendAttachment":{"attachment":{"id":"status","inline":true,
                "type":{"statusUpdate":{"_0":${entry.getValue("statusUpdate")}}}},
                "uuid":"11111111-2222-4333-8444-000000000501"}}"""
            val body = decodeChatMessage(message(content), budgets).body as MessageBody.AppendAttachment
            val kind = body.attachment.kind as AttachmentKind.StatusUpdate
            assertEquals(name, entry.getValue("displayString").jsonPrimitive.content, kind.display)
        }
    }

    @Test
    fun nonImageFileBytesAreNotKept() {
        val notes = (historyMessage("000000000030").body as MessageBody.Multipart).parts
            .filterIsInstance<Subpart.AttachmentPart>().single().attachment.kind as AttachmentKind.File
        assertNull(notes.bytes)
        assertEquals("notes.txt", notes.name)
        assertEquals("text/plain", notes.mimeType)
        assertEquals("TODO: tag v1.2\n".length, notes.size)

        val plot = (historyMessage("000000000035").body as MessageBody.Multipart).parts
            .filterIsInstance<Subpart.AttachmentPart>()
            .map { it.attachment.kind }.filterIsInstance<AttachmentKind.File>().single()
        val expected = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAADUlEQVR4AQECAP3/AAAAAgABfgUN0gAAAABJRU5ErkJggg==",
        )
        assertTrue(plot.isImage)
        assertArrayEquals(expected, plot.bytes)
    }

    private fun fileMessage(name: String, mimeType: String, size: Int): JsonObject {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(size) { (it % 251).toByte() })
        return message(
            """{"appendAttachment":{"attachment":{"id":"file-$name","inline":true,"type":{"file":{"_0":
                {"content":"$encoded","mimeType":"$mimeType","name":"$name"}}}},
                "uuid":"11111111-2222-4333-8444-000000000501"}}""",
        )
    }

    private fun ChatMessage.file(): AttachmentKind.File =
        (body as MessageBody.AppendAttachment).attachment.kind as AttachmentKind.File

    @Test
    fun imageOverFourMebibytesKeepsNameOnly() {
        val fourMebibytes = 4 * 1024 * 1024
        val over = decodeChatMessage(fileMessage("big.png", "image/png", fourMebibytes + 1), budgets).file()
        assertNull(over.bytes)
        assertEquals("big.png", over.name)
        assertEquals("image/png", over.mimeType)
        assertEquals(fourMebibytes + 1, over.size)
        val atLimit = decodeChatMessage(fileMessage("edge.png", "image/png", fourMebibytes), budgets).file()
        assertEquals(fourMebibytes, atLimit.bytes?.size)

        // The transcript budget: images are kept in order until the next would overrun it.
        val small = AttachmentBudgets(maxImageBytes = 1000, maxTranscriptImageBytes = 2500)
        val messages = JsonArray(listOf(800, 900, 1200, 700).map { fileMessage("p$it.png", "IMAGE/PNG", it) })
        val kept = decodeChatMessages(messages, small).map { it.file().bytes?.size }
        assertEquals(listOf(800, 900, null, 700), kept)
    }

    @Test
    fun participantOutsideTheEnumIsRejected() {
        for (author in listOf("\"system\"", "\"User\"", "\"\"", "1", "null")) {
            assertThrows(WireDecodeException::class.java) {
                decodeChatMessage(message("""{"markdown":{"_0":"hi"}}""", author = author), budgets)
            }
        }
    }

    @Test
    fun lowercaseUuidDecodesUppercase() {
        val decoded = decodeChatMessage(
            message(
                """{"append":{"string":"x","uuid":"abcdef01-2222-4333-8444-0000000005aa"}}""",
                uniqueID = "\"abcdef01-2222-4333-8444-0000000005bb\"",
            ),
            budgets,
        )
        assertEquals("ABCDEF01-2222-4333-8444-0000000005BB", decoded.uniqueID)
        assertEquals("ABCDEF01-2222-4333-8444-0000000005AA", (decoded.body as MessageBody.Append).target)
        for (bad in listOf("\"ABCDEF012222433384440000000005BB\"", "\"{ABCDEF01-2222-4333-8444-0000000005BB}\"", "7")) {
            assertThrows(WireDecodeException::class.java) {
                decodeChatMessage(message("""{"markdown":{"_0":"hi"}}""", uniqueID = bad), budgets)
            }
        }
        val fresh = newMessageId()
        assertEquals(fresh.uppercase(), fresh)
        assertEquals(36, fresh.length)
    }

    @Test
    fun fractionalDatesDecode() {
        assertEquals(1795000400000.25, historyMessage("000000000400").sentMillis, 0.0)
        assertEquals(1795000000000.0, historyMessage("000000000001").sentMillis, 0.0)
        val exponent = decodeChatMessage(message("""{"markdown":{"_0":"hi"}}""", sentDate = "1.7950004e12"), budgets)
        assertEquals(1.7950004e12, exponent.sentMillis, 0.0)
        assertThrows(WireDecodeException::class.java) {
            decodeChatMessage(message("""{"markdown":{"_0":"hi"}}""", sentDate = "\"1795000000000\""), budgets)
        }
    }

    @Test
    fun forwardAndCorruptContentFollowTheDecodeToleranceVectors() {
        fun deliveryOf(name: String) = toleranceEnvelope(name)["delivery"]!!.jsonObject["message"]!!.jsonObject
        fun historyOf(name: String) = toleranceEnvelope(name)["history"]!!.jsonObject["messages"]!!.jsonArray

        assertEquals(MessageBody.Unsupported, decodeChatMessage(deliveryOf("delivery-future-content-case"), budgets).body)
        assertEquals(
            MessageBody.Unsupported,
            decodeChatMessage(deliveryOf("delivery-future-client-local-action"), budgets).body,
        )
        assertEquals(
            MessageBody.Unreadable("remoteCommandRequest"),
            decodeChatMessage(deliveryOf("delivery-remote-command-future-tool"), budgets).body,
        )
        val corrupt = decodeChatMessages(historyOf("history-with-corrupt-markdown-body"), budgets)
        assertEquals(listOf(MessageBody.Markdown("First reply"), MessageBody.Unreadable("markdown")), corrupt.map { it.body })
        val future = decodeChatMessages(historyOf("history-with-future-content-case"), budgets)
        assertEquals(listOf(MessageBody.Markdown("First reply"), MessageBody.Unsupported), future.map { it.body })
    }

    @Test
    fun decodeErrorsNameNoValues() {
        val secret = "kittens-secret"
        val rejected = listOf(
            message("""{"markdown":{"_0":"$secret"}}""", author = "\"$secret\""),
            message("""{"markdown":{"_0":"$secret"}}""", uniqueID = "\"$secret\""),
            message("""{"markdown":{"_0":"$secret"}}""", sentDate = "\"$secret\""),
            message("""{"markdown":{"_0":"$secret"}}""", chatID = "{\"$secret\":1}"),
            message("""{"$secret":{"_0":"$secret"},"markdown":{"_0":"$secret"}}"""),
            message("\"$secret\""),
        )
        for (obj in rejected) {
            val error = assertThrows(WireDecodeException::class.java) { decodeChatMessage(obj, budgets) }
            assertNoSecret(error, secret)
            val inArray = assertThrows(WireDecodeException::class.java) {
                decodeChatMessages(JsonArray(listOf(message("""{"markdown":{"_0":"ok"}}"""), obj)), budgets)
            }
            assertNoSecret(inArray, secret)
            assertTrue(inArray.message!!.startsWith("messages[1]"))
        }
        val notAnObject = assertThrows(WireDecodeException::class.java) {
            decodeChatMessages(JsonArray(listOf(JsonPrimitive(secret))), budgets)
        }
        assertNoSecret(notAnObject, secret)

        // Corrupt bodies of known cases are kept as Unreadable; nothing of the value leaks there either.
        val corruptBodies = listOf(
            "markdown" to """{"markdown":{"_0":{"$secret":1}}}""",
            "multipart" to """{"multipart":{"_0":[{"attachment":{"_0":{"id":"a","inline":true,
                "type":{"file":{"_0":{"content":"$secret!!","mimeType":"image/png","name":"$secret"}}}}}}]}}""",
            "multipart" to """{"multipart":{"_0":[{"$secret":{"_0":"$secret"}}]}}""",
            "remoteCommandRequest" to """{"remoteCommandRequest":{"_0":{"content":{"$secret":{"_0":{}}}}}}""",
            "remoteCommandRequest" to """{"remoteCommandRequest":{"_0":{"content":{"executeCommand":{"_0":{}}}}}}""",
            "clientLocal" to """{"clientLocal":{"_0":{"action":{"streamingChanged":{"_0":"$secret"}}}}}""",
            "append" to """{"append":{"string":"$secret","uuid":"$secret"}}""",
        )
        for ((case, content) in corruptBodies) {
            val body = decodeChatMessage(message(content), budgets).body
            assertEquals(MessageBody.Unreadable(case), body)
            assertFalse(body.toString().contains(secret))
        }
    }

    private fun assertNoSecret(error: Throwable, secret: String) {
        var current: Throwable? = error
        while (current != null) {
            assertFalse("${current::class.simpleName} leaks the value", (current.message ?: "").contains(secret))
            assertFalse(current.toString().contains(secret))
            current = current.cause
        }
        assertNull("a cause could quote the input", error.cause)
    }

    @Test
    fun chatContentIsRedactedInToString() {
        val s = "kittens-secret"
        val png = byteArrayOf(1, 2, 3)
        val attachments = listOf(
            Attachment(true, "id-$s", AttachmentKind.Code(s)),
            Attachment(true, "id-$s", AttachmentKind.StatusUpdate(AgentStatusUpdate.ReasoningSummaryUpdate(s))),
            Attachment(
                true,
                "id-$s",
                AttachmentKind.StatusUpdate(
                    AgentStatusUpdate.Multipart(listOf(AgentStatusUpdate.WebSearchFinished(s), AgentStatusUpdate.WebSearchStarted)),
                ),
            ),
            Attachment(false, "id-$s", AttachmentKind.File(s, "image/$s", 3, png)),
            Attachment(false, "id-$s", AttachmentKind.FileId(s, s)),
        )
        val subparts = listOf(Subpart.PlainText(s), Subpart.Markdown(s), Subpart.Context(s)) +
            attachments.map { Subpart.AttachmentPart(it) }
        val commands = listOf(
            CommandContent.ExecuteCommand(s), CommandContent.InsertTextAtCursor(s), CommandContent.GetManPage(s),
            CommandContent.CreateFile(s), CommandContent.SearchBrowser(s), CommandContent.LoadURL(s),
            CommandContent.WebSearch(s),
        )
        val actions = listOf(
            ClientAction.PickingSession,
            ClientAction.ExecutingCommand(CommandContent.ExecuteCommand(s)),
            ClientAction.Notice(s),
            ClientAction.StreamingChanged(StreamingState.STOPPED),
            ClientAction.OfferLink(true, "ptys_7K2M9Q4R8T1VH", s),
            ClientAction.OfferOrchestration,
            ClientAction.Permissions(true, "ptys_7K2M9Q4R8T1VH"),
            ClientAction.WorkgroupPermissionRequest(s, "wg-1", s, s),
            ClientAction.EnableOrchestrationRequest(s),
            ClientAction.OrchestrationPermissionGranted("session:ptys_7K2M9Q4R8T1VH", s),
        )
        val target = "11111111-2222-4333-8444-000000000501"
        val bodies: List<MessageBody> = listOf(
            MessageBody.PlainText(s), MessageBody.Markdown(s), MessageBody.Multipart(subparts),
            MessageBody.Append(s, target), MessageBody.AppendAttachment(attachments[0], target),
            MessageBody.Commit(target), MessageBody.ExternalCommand(s, s, true),
            MessageBody.SelectSessionRequest(true), MessageBody.WatcherEvent(s),
            MessageBody.TerminalCommand(s, s), MessageBody.ExplanationRequest(s), MessageBody.ExplanationResponse(s),
            MessageBody.Hidden("renameChat"), MessageBody.Unsupported, MessageBody.Unreadable("markdown"),
        ) + commands.map { MessageBody.ClassicCommand(it, false) } + actions.map { MessageBody.ClientNotice(it) }
        val chat = ChatSummary(
            id = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E", title = s, createdMillis = 1.0, lastModifiedMillis = 2.0,
            orchestrationEnabled = true, terminalSessionGuid = null, browserSessionGuid = null,
            claimedScopes = listOf("wg-1"), icon = s.toByteArray(),
        )
        val member = WorkgroupMember(s, "ptys_7K2M9Q4R8T1VH", s, s, s, SessionState.WORKING)
        val values: List<Any> = bodies + subparts + attachments + attachments.map { it.kind } + commands + actions +
            bodies.map { ChatMessage("6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E", Participant.AGENT, it, 1.0, target) } +
            listOf(
                chat,
                ChatListEntry(chat, s, false),
                MentionResolution("ptys_7K2M9Q4R8T1VH", s, "ptys_7K2M9Q4R8T1VH", null),
                member,
                WorkgroupInfo("wg-1", s, listOf(member)),
                UserTextMessage("6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E", s, 1, target),
            )
        for (value in values) {
            val text = value.toString()
            assertFalse("${value::class.simpleName} shows content: $text", text.contains(s))
            assertFalse("${value::class.simpleName} shows content", text.contains("kittens"))
        }
    }

    @Test
    fun chatSummaryComparesContentAndScopes() {
        fun summary(icon: ByteArray?, scopes: List<String> = listOf("wg-1", "session:ptys_7K2M9Q4R8T1VH")) = ChatSummary(
            "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E", "Title", 1.0, 2.0, true, null, null, scopes, icon,
        )
        assertEquals(summary(byteArrayOf(1, 2)), summary(byteArrayOf(1, 2)))
        assertEquals(summary(byteArrayOf(1, 2)).hashCode(), summary(byteArrayOf(1, 2)).hashCode())
        assertFalse(summary(byteArrayOf(1, 2)) == summary(byteArrayOf(1, 3)))
        assertFalse(summary(null) == summary(byteArrayOf()))
        assertEquals(listOf("wg-1"), summary(null).workgroupScopes)
        val icon = byteArrayOf(9)
        val held = summary(icon)
        icon[0] = 0
        assertEquals(9.toByte(), held.icon!![0])
    }

    @Test
    fun enumsReadRawValues() {
        assertEquals(TurnEvent.STARTED, TurnEvent.fromRaw("started"))
        assertEquals(TurnEvent.ENDED, TurnEvent.fromRaw("ended"))
        assertEquals(TurnEvent.UNKNOWN_FUTURE, TurnEvent.fromRaw("paused"))
        assertEquals(SessionState.WAITING, SessionState.fromRaw("waiting"))
        assertEquals(SessionState.UNKNOWN, SessionState.fromRaw("unknown"))
        assertEquals(SessionState.UNKNOWN, SessionState.fromRaw("sleeping"))
        assertNull(Participant.fromRaw("system"))
        assertNotNull(Participant.fromRaw("agent"))
    }

    @Test
    fun newChatModesEncodeAsSwiftEnums() {
        fun mode(name: String): JsonElement = sampleJson(name)["payload"]!!.jsonObject["createChat"]!!.jsonObject["mode"]!!
        assertEquals(mode("create-chat-orchestrator"), NewChatMode.Orchestrator.encode())
        assertEquals(mode("create-chat-session"), NewChatMode.Session("ptys_7K2M9Q4R8T1VH").encode())
    }

    @Test
    fun userTextMessageRequiresAnUppercaseId() {
        assertThrows(IllegalArgumentException::class.java) {
            UserTextMessage("chat", "hi", 1, "11111111-2222-4333-8444-00000000050a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserTextMessage("chat", "hi", -1, "11111111-2222-4333-8444-00000000050A")
        }
        UserTextMessage("chat", "hi", 1, newMessageId())
    }
}
