package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTextTest {
    private val entries: List<JsonObject> by lazy {
        Json.parseToJsonElement(Fixtures.read("chat/remote-command-description.json")).jsonArray.map { it.jsonObject }
    }

    private fun decode(remoteCommand: JsonObject): CommandContent =
        decodeCommandContent(remoteCommand.getValue("content").jsonObject, "content")

    /** The remote command as a classic `remoteCommandRequest` message, decoded the way a delivery is. */
    private fun requestBody(remoteCommand: String): MessageBody = decodeChatMessage(
        Json.parseToJsonElement(
            """{"author":"agent","chatID":"9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E",
            "content":{"remoteCommandRequest":{"_0":$remoteCommand,"safe":true}},
            "sentDate":1795000505000,"uniqueID":"11111111-2222-4333-8444-000000000505"}""",
        ).jsonObject,
        AttachmentBudgets(),
    ).body

    @Test
    fun commandTextMatchesUpstream() {
        assertEquals(50, entries.size)
        val cases = mutableSetOf<String>()
        for (entry in entries) {
            val name = entry.getValue("name").jsonPrimitive.content
            val remoteCommand = entry.getValue("remoteCommand").jsonObject
            val content = decode(remoteCommand)
            cases += content.wireName
            assertEquals(name, entry.getValue("functionName").jsonPrimitive.content, content.functionName)
            assertEquals(name, entry.getValue("permissionCategory").jsonPrimitive.content, content.permissionCategory.label)
            val expected = entry.getValue("markdownDescription").jsonPrimitive.content
            assertEquals(name, expected, content.markdownDescription())
            assertEquals(name, expected.toByteArray().toList(), content.markdownDescription().toByteArray().toList())
            assertEquals(name, MessageBody.ClassicCommand(content, true), requestBody(remoteCommand.toString()))
            // The upstream case name is the fixture's own name or its prefix.
            assertEquals(name, name.substringBefore('-'), content.wireName)
            assertEquals(name, expected.endsWith("…`") || expected.endsWith("…` into the current session"), content.argumentWasCut)
        }
        assertEquals(26, cases.size)
        assertEquals(PermissionCategory.entries.map { it.label }.toSet(), entries.map {
            it.getValue("permissionCategory").jsonPrimitive.content
        }.toSet())
    }

    @Test
    fun unknownCommandContentIsUnreadable() {
        val llmMessage = """"llmMessage":{"body":{"functionCall":{"_0":{"arguments":"{}","id":"call","name":"x"}}},"role":"assistant"}"""
        val unreadable = listOf(
            """{"content":{"openPullRequest":{"_0":{"command":"make test"}}},$llmMessage}""",
            """{"content":{"executeCommand":{"_0":{}}},$llmMessage}""",
            """{"content":{"executeCommand":{"_0":{"command":7}}},$llmMessage}""",
            """{"content":{"getCommandHistory":{"_0":{"limit":"20"}}},$llmMessage}""",
            """{"content":{"createFile":{"_0":{"filename":"a"}}},$llmMessage}""",
            """{"content":{"readWebPage":{"_0":{"startingLineNumber":1}}},$llmMessage}""",
            """{"content":{"isAtPrompt":{}},$llmMessage}""",
            """{"content":{"isAtPrompt":{"_0":{}},"getURL":{"_0":{}}},$llmMessage}""",
            """{"content":{},$llmMessage}""",
            """{$llmMessage}""",
        )
        for (command in unreadable) {
            assertEquals(command, MessageBody.Unreadable("remoteCommandRequest"), requestBody(command))
        }
        assertThrows(WireDecodeException::class.java) {
            decodeCommandContent(Json.parseToJsonElement("""{"openPullRequest":{"_0":{}}}""").jsonObject, "content")
        }
        // A kind other than the string "external" is the classic shape (RemoteCommand.swift:82-92).
        val classicWithKind = requestBody("""{"kind":"internal","content":{"getURL":{"_0":{}}},$llmMessage}""")
        assertEquals(MessageBody.ClassicCommand(CommandContent.GetURL, true), classicWithKind)
        // An executing-command notice with an unknown tool is unreadable too, not unsupported.
        val notice = decodeChatMessage(
            Json.parseToJsonElement(
                """{"author":"agent","chatID":"c","content":{"clientLocal":{"_0":{"action":{"executingCommand":
                {"_0":{"content":{"openPullRequest":{"_0":{}}},$llmMessage}}}}}},
                "sentDate":1,"uniqueID":"11111111-2222-4333-8444-000000000505"}""",
            ).jsonObject,
            AttachmentBudgets(),
        )
        assertEquals(MessageBody.Unreadable("clientLocal"), notice.body)
    }

    @Test
    fun cutArgumentsCountCharactersNotCodeUnits() {
        val family = "👨‍👩‍👧"
        val flag = "🇺🇸"
        val accent = "é"
        assertEquals(1, graphemeCount(family))
        assertEquals(1, graphemeCount(flag))
        assertEquals(2, graphemeCount(flag + flag))
        assertEquals(1, graphemeCount(accent))
        assertEquals(1, graphemeCount("\r\n"))
        assertEquals(1, graphemeCount("👍🏽")) // thumbs up with a skin tone
        assertEquals(1, graphemeCount("각")) // a conjoining jamo syllable
        assertEquals(1, graphemeCount("한"))
        assertEquals(1, graphemeCount("\u0E17\u0E33")) // Thai "ทำ": SARA AM is a spacing mark
        assertEquals(1, graphemeCount("\u0EA5\u0EB3")) // Lao, likewise
        assertEquals(2, graphemeCount("ทำทำ"))
        val thirtyOne = "a".repeat(31)
        assertEquals(thirtyOne + family, (thirtyOne + family).truncatedWithTrailingEllipsis(32))
        assertEquals("$thirtyOne…", (thirtyOne + family + "!").truncatedWithTrailingEllipsis(32))
        assertEquals("$thirtyOne…", (thirtyOne + accent + accent).truncatedWithTrailingEllipsis(32))
        assertEquals("", "".truncatedWithTrailingEllipsis(32))
        assertFalse(CommandContent.ExecuteCommand(thirtyOne + family).argumentWasCut)
        assertTrue(CommandContent.ExecuteCommand(thirtyOne + "``").argumentWasCut)
    }
}
