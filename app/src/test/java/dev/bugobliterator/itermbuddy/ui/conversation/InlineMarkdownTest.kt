package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.DEFUNCT_SESSION_TEXT
import dev.bugobliterator.itermbuddy.core.chat.MentionParser
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {
    private val stableID = "ptys_7K2M9Q4R8T1VH"
    private val otherStableID = "ptys_3HX6W0ZD5N2BT"
    private val workgroup = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
    private val workgroupID = "5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"

    private val resolutions = mapOf(
        stableID to MentionResolution(stableID, "Build shell", stableID, null),
        otherStableID to MentionResolution(otherStableID, "Review shell", otherStableID, null),
        workgroup to MentionResolution(workgroup, "Release team", null, workgroupID),
    )

    private fun agent(text: String, resolve: (String) -> MentionResolution? = resolutions::get): List<InlineRun> =
        inlineMarkdown(text, MentionParser.protectedMentions(text, atSignOptional = true), resolve)

    private fun user(text: String, resolve: (String) -> MentionResolution? = resolutions::get): List<InlineRun> =
        inlineMarkdown(text, MentionParser.protectedMentions(text, atSignOptional = false), resolve)

    private fun plain(text: String) = InlineRun(text)

    @Test
    fun inlineStylesAndWhitespace() {
        val text = "Hi **bold** and *it* _also_ `co de`  ~~gone~~ [site](https://x.example/a) see https://y.example/b.\n  next"
        assertEquals(
            listOf(
                plain("Hi "),
                InlineRun("bold", bold = true),
                plain(" and "),
                InlineRun("it", italic = true),
                plain(" "),
                InlineRun("also", italic = true),
                plain(" "),
                InlineRun("co de", code = true),
                plain("  "),
                InlineRun("gone", strike = true),
                plain(" "),
                InlineRun("site", link = LinkTarget.Url("https://x.example/a")),
                plain(" see "),
                InlineRun("https://y.example/b", link = LinkTarget.Url("https://y.example/b")),
                plain(".\n  next"),
            ),
            agent(text),
        )
        // Nesting, an autolink, an escape, and a fenced block shown as code without its fences.
        assertEquals(
            listOf(
                InlineRun("both", bold = true, italic = true),
                plain(" "),
                InlineRun("a ", bold = true),
                InlineRun("b", bold = true, italic = true),
                plain(" "),
                InlineRun("https://z.example", link = LinkTarget.Url("https://z.example")),
                plain(" *not* \n"),
                InlineRun("val x = 1\nprint(x)", code = true),
                plain("\ndone"),
            ),
            agent("***both*** **a _b_** <https://z.example> \\*not\\* \n```kotlin\nval x = 1\nprint(x)\n```\ndone"),
        )
        assertEquals(
            listOf(plain("visit "), InlineRun("www.example.org", link = LinkTarget.Url("http://www.example.org")), plain(", then")),
            agent("visit www.example.org, then"),
        )
        // Intraword underscores are not emphasis; intraword stars are.
        assertEquals(listOf(plain("snake_case_name and a"), InlineRun("b", italic = true), plain("c")), agent("snake_case_name and a*b*c"))
    }

    @Test
    fun stableIdUnderscoresSurviveEmphasis() {
        val text = "Ask @$stableID and @$otherStableID to compare notes."
        // Unresolved, both stay as written: their underscores never pair up as emphasis.
        val raw = agent(text) { null }
        assertEquals(listOf(plain(text)), raw)
        // Resolved, both become links named by the Mac.
        assertEquals(
            listOf(
                plain("Ask "),
                InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell")),
                plain(" and "),
                InlineRun("Review shell", link = LinkTarget.Session(otherStableID, "Review shell")),
                plain(" to compare notes."),
            ),
            agent(text),
        )
        // A mention inside emphasis keeps the emphasis, as iOS merges the attributes.
        assertEquals(
            listOf(plain("Tell "), InlineRun("Release team", bold = true, link = LinkTarget.Workgroup(workgroupID, "Release team"))),
            agent("Tell **@$workgroup**"),
        )
    }

    @Test
    fun bareStableIdLinksOnlyInAgentText() {
        val text = "ask $stableID now"
        assertEquals(
            listOf(plain("ask "), InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell")), plain(" now")),
            agent(text),
        )
        assertEquals(listOf(plain(text)), user(text))
        assertEquals(
            listOf(plain("ask "), InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell")), plain(" now")),
            plainRuns("ask @$stableID now", MentionParser.mentions("ask @$stableID now"), resolutions::get),
        )
    }

    @Test
    fun mentionInCodeSpanIsNotLinked() {
        val runs = agent("Run `@$stableID` then @$stableID, see [@$otherStableID](https://x.example)")
        assertEquals(
            listOf(
                plain("Run "),
                InlineRun("@$stableID", code = true),
                plain(" then "),
                InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell")),
                plain(", see "),
                // A mention in a link's text still opens the session, as on iOS.
                InlineRun("Review shell", link = LinkTarget.Session(otherStableID, "Review shell")),
            ),
            runs,
        )
    }

    @Test
    fun unresolvedMentionStaysRaw() {
        val runs = agent("Ask @$workgroup and @$stableID") { null }
        assertEquals(listOf(plain("Ask @$workgroup and @$stableID")), runs)
        assertTrue(runs.all { it.link == null })
    }

    @Test
    fun missRendersDefunct() {
        val miss = MentionResolution(stableID, null, null, null)
        // Named with nothing to open is defunct too, as iOS renders it.
        val nothingToOpen = MentionResolution(otherStableID, "Gone", null, null)
        val runs = agent("Ask @$stableID or @$otherStableID") { mapOf(stableID to miss, otherStableID to nothingToOpen)[it] }
        assertEquals(listOf(plain("Ask $DEFUNCT_SESSION_TEXT or $DEFUNCT_SESSION_TEXT")), runs)
    }

    @Test
    fun brokenMarkdownFallsBackToText() {
        // A partial streaming chunk: every unmatched construct shows as written.
        for (text in listOf("**unclosed and `tick [link](https://", "a ** b * c", "[]", "``", "~~one~", "<not a link>", "ends with \\")) {
            val runs = agent(text)
            assertEquals(text, listOf(plain(text)), runs)
        }
        assertFalse(agent("**a").any { it.bold })
    }

    @Test
    fun hostileInputsStayText() {
        // Shapes that make a naive inline parser quadratic: each is read in one linear pass.
        // An unclosed double backtick before many single ones: its closer is looked for once.
        val backticks = agent("``" + "a`b".repeat(20_000))
        assertEquals("``", backticks.first().text.take(2))
        val hostile = listOf(
            "[".repeat(20_000) + "[a](https://x.example)".repeat(200),
            "(http://.".repeat(10_000),
            "*a _b ".repeat(10_000),
            "https://x.example/" + ")".repeat(20_000),
        )
        for (text in hostile) {
            val runs = agent(text)
            assertEquals(text.length.toString(), text.replace("[a](https://x.example)", "a"), runs.joinToString("") { it.text })
        }
    }

    @Test
    fun runsHoldNoContentInToString() {
        val secret = "Sentinel deploy shell"
        val run = InlineRun(secret, link = LinkTarget.Session(stableID, secret))
        assertFalse(run.toString().contains(secret))
        assertFalse(LinkTarget.Url("https://$secret.example").toString().contains(secret))
        assertFalse(LinkTarget.Workgroup(workgroupID, secret).toString().contains(secret))
    }
}
