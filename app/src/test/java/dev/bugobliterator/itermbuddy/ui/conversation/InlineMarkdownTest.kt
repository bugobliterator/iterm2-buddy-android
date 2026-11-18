package dev.bugobliterator.itermbuddy.ui.conversation

import dev.bugobliterator.itermbuddy.core.chat.DEFUNCT_SESSION_TEXT
import dev.bugobliterator.itermbuddy.core.chat.MentionParser
import dev.bugobliterator.itermbuddy.core.chat.MentionResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {
    private companion object {
        /** The bound on [inlineMarkdownSteps] per character of input. */
        const val STEPS_PER_CHARACTER = 12L
    }

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

    /** The owner's text: protected as the agent's, but only "@"-signed mentions link. */
    private fun user(text: String, resolve: (String) -> MentionResolution? = resolutions::get): List<InlineRun> =
        inlineMarkdown(text, MentionParser.protectedMentions(text, atSignOptional = true), resolve, atSignOptional = false)

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
        // The owner's bare stable IDs are still kept whole through emphasis; "@"-signed ones link.
        val two = "compare $stableID and $otherStableID, ask @$stableID"
        assertEquals(
            listOf(plain("compare $stableID and $otherStableID, ask "), InlineRun("Build shell", link = LinkTarget.Session(stableID, "Build shell"))),
            user(two),
        )
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
    fun hostileInputsStayLinear() {
        // cmark's pathological shapes, and the link-destination ones, at about 1 MB each: the
        // parser's steps stay within a small constant per character (counted, never timed).
        val size = 1_000_000
        fun fill(unit: String) = unit.repeat(size / unit.length)
        val nested = size / 2
        val shapes = mapOf(
            "empty links unclosed" to fill("[]("),
            "angle destinations unclosed" to fill("[](<"),
            "paren titles unclosed" to fill("[](x ("),
            "quote titles unclosed" to fill("[](x \""),
            "destinations over the bound" to "[](" + "a".repeat(size),
            "bracket and paren pattern" to fill("[ (]("),
            "nested brackets" to "[".repeat(nested) + "a" + "]".repeat(nested),
            "nested parens in a destination" to "[a](" + "(".repeat(nested) + ")".repeat(nested),
            "link openers without closers" to fill("[a "),
            "link closers without openers" to fill("a] "),
            "nested strong and emphasis" to fill("*a **a "),
            "emphasis closers without openers" to fill("a_ "),
            "emphasis openers without closers" to fill("_a "),
            "mismatched openers and closers" to fill("*a_ "),
            "openers and closers multiple of three" to fill("a**b c* "),
            "link openers and emphasis closers" to fill("[ a_"),
            "unclosed backticks of every length" to buildString { var run = 1; while (length < size) { append("e"); append("`".repeat(run++)) } },
            "unclosed double backtick" to "``" + fill("a`b"),
            "autolink openers" to fill("<http:a"),
            "bare urls after parens" to fill("(http://."),
            "bare urls with unbalanced parens" to "https://x.example/" + ")".repeat(size),
        )
        for ((name, text) in shapes) {
            val steps = inlineMarkdownSteps(text, emptyList())
            assertTrue("$name: ${steps / text.length} steps per character", steps <= STEPS_PER_CHARACTER * text.length)
        }
        // And they keep their text: a failed construct is shown as written.
        for (text in listOf(fill("[]("), fill("[](<"), fill("[](x ("), "[".repeat(1000) + "a" + "]".repeat(1000), fill("(http://."))) {
            assertEquals(text, agent(text).joinToString("") { it.text })
        }
    }

    @Test
    fun linksAndAutolinksFollowGfm() {
        // `www.foo` links; a URL runs past a comma to whitespace, then sheds trailing punctuation.
        assertEquals(listOf(InlineRun("www.foo", link = LinkTarget.Url("http://www.foo"))), agent("www.foo"))
        assertEquals(
            listOf(plain("see "), InlineRun("https://x.example/a,b", link = LinkTarget.Url("https://x.example/a,b")), plain(", ok")),
            agent("see https://x.example/a,b, ok"),
        )
        assertEquals(listOf(InlineRun("www.x.example,more", link = LinkTarget.Url("http://www.x.example,more"))), agent("www.x.example,more"))
        // A bare URL in a link's text takes the link's target, as on iOS.
        assertEquals(
            listOf(InlineRun("https://shown.example", link = LinkTarget.Url("https://target.example"))),
            agent("[https://shown.example](https://target.example)"),
        )
        // A long or multi-line destination is text.
        val long = "[a](https://x.example/" + "p".repeat(MAX_LINK_DESTINATION) + ")"
        assertTrue(agent(long).none { it.link != null && it.text == "a" })
        assertEquals(listOf(plain("[a](<https://x\n.example>)")), agent("[a](<https://x\n.example>)"))
        // Nested parentheses in a destination, and an escaped one.
        assertEquals(listOf(InlineRun("a", link = LinkTarget.Url("f(x)"))), agent("[a](f(x))"))
        assertEquals(listOf(InlineRun("a", link = LinkTarget.Url("f\\(x"))), agent("[a](f\\(x)"))
        assertEquals(listOf(InlineRun("a", link = LinkTarget.Url("u"))), agent("[a](u \"title\")"))
    }

    @Test
    fun openableUrls() {
        for (url in listOf("http://x.example", "https://x.example/a", "HTTPS://X.EXAMPLE", "mailto:a@x.example")) {
            assertTrue(url, isOpenableUrl(url))
        }
        for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/hosts", "content://x", "tel:123", "src/main.kt", "")) {
            assertFalse(url, isOpenableUrl(url))
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
