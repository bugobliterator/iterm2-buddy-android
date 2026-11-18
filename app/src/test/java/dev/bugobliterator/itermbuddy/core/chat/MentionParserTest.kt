package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionParserTest {
    private val vectors: JsonObject by lazy { Json.parseToJsonElement(Fixtures.read("chat/mention-parser.json")).jsonObject }

    private fun section(name: String): List<JsonObject> = vectors.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.nullableString(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun parserMatchesUpstreamVectors() {
        val cases = section("cases")
        assertEquals(27, cases.size)
        for (case in cases) {
            val name = case.string("name")
            val text = case.string("text")
            val expected = case.getValue("mentions").jsonArray.map { it.jsonObject }.map {
                val location = it.getValue("location").jsonPrimitive.int
                Mention(
                    start = location,
                    end = location + it.getValue("length").jsonPrimitive.int,
                    prefix = it.nullableString("prefix"),
                    token = it.string("token"),
                )
            }
            val found = MentionParser.mentions(text, case.getValue("atSignOptional").jsonPrimitive.boolean)
            assertEquals(name, expected, found)
            assertEquals(name, case.getValue("mentions").jsonArray.map { it.jsonObject.string("identifier") }, found.map { it.identifier })
        }
    }

    @Test
    fun splitMatchesUpstreamVectors() {
        val cases = section("split")
        assertEquals(14, cases.size)
        for (case in cases) {
            val name = case.string("name")
            val split = MentionParser.split(case.string("identifier"))
            if (case["invalid"]?.jsonPrimitive?.boolean == true) {
                assertNull(name, split)
            } else {
                assertEquals(name, case.nullableString("prefix") to case.string("token"), split)
            }
        }
    }

    @Test
    fun canonicalMatchesUpstreamVectors() {
        val cases = section("canonical")
        assertEquals(16, cases.size)
        for (case in cases) {
            assertEquals(case.string("name"), case.nullableString("canonical"), StableSessionId.canonical(case.string("input")))
        }
    }

    @Test
    fun crockfordConfusablesAreFoldedOnlyByCanonical() {
        val confusable = "ptys_7K2M9Q4R8TlVH"
        assertEquals("ptys_7K2M9Q4R8T1VH", StableSessionId.canonical(confusable))
        assertEquals(emptyList<Mention>(), MentionParser.mentions("Ask @$confusable now."))
        assertEquals(emptyList<Mention>(), MentionParser.mentions("Ask $confusable now.", atSignOptional = true))
        assertNull(MentionParser.split(confusable))
    }

    @Test
    fun bareUuidIsNeverAMention() {
        val uuid = "1D2C3B4A-5968-4776-A5B4-C3D2E1F00F1E"
        for (text in listOf("Session $uuid finished.", uuid, "($uuid)", "session:$uuid", "wg-$uuid")) {
            assertEquals(text, emptyList<Mention>(), MentionParser.mentions(text, atSignOptional = true))
            assertEquals(text, emptyList<Mention>(), MentionParser.mentions(text, atSignOptional = false))
        }
        assertEquals(listOf("session:$uuid"), MentionParser.mentions("@session:$uuid", atSignOptional = true).map { it.identifier })
    }

    @Test
    fun codeSpansAreProtected() {
        val cases = section("protect")
        assertEquals(16, cases.size)
        for (case in cases) {
            val name = case.string("name")
            val text = case.string("text")
            val indented = case.getValue("indentedCodeBlocks").jsonPrimitive.boolean
            val spans = MentionParser.protectedSpans(text, indentedCodeBlocks = indented)
            // `protect` scans with the "@" optional and leaves alone every mention a span touches.
            val found = MentionParser.mentions(text, atSignOptional = true)
            val expected = case.getValue("mentions").jsonArray.map { it.jsonObject }
            assertEquals(name, expected.map { it.getValue("location").jsonPrimitive.int }, found.map { it.start })
            assertEquals(name, expected.map { it.getValue("length").jsonPrimitive.int }, found.map { it.end - it.start })
            assertEquals(name, expected.map { it.string("identifier") }, found.map { it.identifier })
            val replaced = found.map { mention -> spans.none { it.first < mention.end && mention.start <= it.last } }
            assertEquals(name, expected.map { it.getValue("protected").jsonPrimitive.boolean }, replaced)
            assertEquals(name, found.filterIndexed { index, _ -> replaced[index] }, MentionParser.protectedMentions(text, indented))
            assertEquals(name, case.string("protectedText"), placeholderText(text, found.filterIndexed { index, _ -> replaced[index] }))
        }
    }

    /** Upstream's `protect` output with its random nonce written as "NONCE", as the vectors store it. */
    private fun placeholderText(text: String, mentions: List<Mention>): String {
        val result = StringBuilder(text)
        mentions.withIndex().reversed().forEach { (index, mention) ->
            result.replace(mention.start, mention.end, "iTermMentionNONCEX${index}Z")
        }
        return result.toString()
    }

    @Test
    fun spansCoverUpstreamKinds() {
        val text = "a `code` b [x](dest) c <https://e.example/p> d https://f.example/q e"
        val covered = MentionParser.protectedSpans(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("`code`", "](dest)", "<https://e.example/p>", "https://f.example/q"), covered)
    }

    /**
     * ICU's `\s` is Unicode White_Space, so a no-break space, an em space, a vertical tab or a
     * next-line ends a bare URL (checked against upstream's `protect` with Swift 6.2), and a
     * fenced block's lines may end in a lone carriage return.
     */
    @Test
    fun spansFollowIcuWhitespaceAndLineEnds() {
        for (space in listOf('\u00A0', '\u2003', '\u000B', '\u0085')) {
            val text = "https://e.example/a${space}ptys_7K2M9Q4R8T1VH"
            assertEquals(space.code.toString(16), listOf(0 until 19), MentionParser.protectedSpans(text))
        }
        val crText = "x\r```\r@ptys_7K2M9Q4R8T1VH\r```\r@ptys_3HX6W0ZD5N2BT"
        val spans = MentionParser.protectedSpans(crText)
        val unprotected = MentionParser.mentions(crText).filter { mention -> spans.none { it.first < mention.end && mention.start <= it.last } }
        assertEquals(listOf("ptys_3HX6W0ZD5N2BT"), unprotected.map { it.identifier })
    }

    /**
     * ICU's line starts include VT, FF, NEL and LS as well as a lone CR, for fences and indented
     * lines alike; Java's `^` leaves out VT and FF. Checked against upstream's `protect` with
     * Swift 6.2: the count is how many mentions it replaced.
     */
    @Test
    fun linesStartAsIcuStartsThem() {
        val id = "@ptys_7K2M9Q4R8T1VH"
        val cases = mapOf(
            "x\u000B```\n$id\n```" to 0,
            "x\u000C```\n$id\n```" to 0,
            "x\u0085```\n$id\n```" to 0,
            "x\u2028```\n$id\n```" to 0,
            "x\r```\n$id\n```" to 0,
            "```\r\n$id\r\n```\r\n$id" to 1,
            "```\n$id\u000B```\n$id" to 1,
            "x\u000B    $id" to 0,
            "x\u000C\t$id" to 0,
        )
        for ((text, replaced) in cases) {
            assertEquals(text, replaced, MentionParser.protectedMentions(text, indentedCodeBlocks = true).size)
        }
    }

    /**
     * Unicode case folding, checked against upstream's own `MentionParser` and `StableSessionID`
     * compiled with Swift 6.2 (Linux, ICU): a Kelvin sign matches the alphabet's K case-insensitively
     * and `canonical` folds it (Swift `Character` equality is canonical equivalence); a long s in
     * "ptys_" matches but fails `canonical`'s prefix check, so the token stays as written; a long s
     * in "session:" stays in the lowercased prefix; a Kelvin sign right after an id is a letter to
     * the trailing lookahead; a combining mark after an id does not stop the match.
     */
    @Test
    fun unicodeCaseFoldingMatchesSwift() {
        assertEquals("ptys_7K2M9Q4R8T1VH", StableSessionId.canonical("ptys_7\u212A2M9Q4R8T1VH"))
        assertEquals(
            listOf(Mention(4, 23, null, "ptys_7K2M9Q4R8T1VH")),
            MentionParser.mentions("Ask @ptys_7\u212A2M9Q4R8T1VH now"),
        )
        assertNull(StableSessionId.canonical("pty\u017F_7K2M9Q4R8T1VH"))
        assertEquals(
            listOf(Mention(4, 23, null, "pty\u017F_7K2M9Q4R8T1VH")),
            MentionParser.mentions("Ask @pty\u017F_7K2M9Q4R8T1VH now"),
        )
        assertEquals(
            listOf(Mention(4, 31, "\u017Fession:", "ptys_7K2M9Q4R8T1VH")),
            MentionParser.mentions("Ask @\u017Fession:ptys_7K2M9Q4R8T1VH now"),
        )
        assertEquals(emptyList<Mention>(), MentionParser.mentions("Ask @ptys_7K2M9Q4R8T1VH\u212A now"))
        assertEquals(listOf(Mention(4, 23, null, "ptys_7K2M9Q4R8T1VH")), MentionParser.mentions("Ask @ptys_7K2M9Q4R8T1VH\u00E9 now"))
        assertEquals(listOf(Mention(0, 19, null, "ptys_7K2M9Q4R8T1VH")), MentionParser.mentions("@ptys_7K2M9Q4R8T1VH\u0301 x"))
        assertEquals(
            emptyList<Mention>(),
            MentionParser.mentions("\u00E9ptys_7K2M9Q4R8T1VH \u0301ptys_7K2M9Q4R8T1VH", atSignOptional = true),
        )
        assertNull(MentionParser.split("ptys_7K2M9Q4R8T1VH\u0301"))
        assertNull(StableSessionId.canonical("ptys_7K2M9Q4R8T1V\u0301H"))
        assertNull(StableSessionId.canonical("ptys_7K2M9Q4R8T1V\uFF28"))
    }

    @Test
    fun identifierJoinsPrefixAndToken() {
        assertEquals("wg-x", Mention(0, 1, "wg-", "x").identifier)
        assertEquals("ptys_7K2M9Q4R8T1VH", Mention(0, 1, null, "ptys_7K2M9Q4R8T1VH").identifier)
        assertTrue(StableSessionId.PREFIX == "ptys_")
    }
}
