package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.ui.conversation.ChatDrafts
import dev.bugobliterator.itermbuddy.ui.conversation.InlineRun
import dev.bugobliterator.itermbuddy.ui.conversation.LinkTarget
import dev.bugobliterator.itermbuddy.ui.conversation.displayRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's draft: mentions are tokens over `@<guid>` in the raw text, as iOS's attachment
 * tokens serialise (`IOS/Views/MentionComposerView.swift:105-150`).
 */
class MentionDraftTest {
    private val guid = "ptys_3HX6W0ZD5N2BT"
    private val token = "@$guid"

    /** "Ask " + the Code Review token + " " (the token's trailing space) + "to review". */
    private fun askDraft(): MentionDraft =
        MentionDraft.EMPTY.applyEdit(0, 0, "Ask ").insertMention(4, guid, "Code Review").let {
            it.applyEdit(it.text.length, it.text.length, "to review")
        }

    @Test
    fun draftSerialisesTokensAsGuids() {
        val draft = askDraft()
        assertEquals("Ask $token to review", draft.serialised())
        assertEquals(listOf(MentionToken(4, 4 + token.length, guid, "Code Review")), draft.tokens)
        // The publish sample's text, typed with a picked mention.
        val sample = MentionDraft.EMPTY.applyEdit(0, 0, "Ask ").insertMention(4, guid, "Code Review").let {
            it.applyEdit(it.text.length, it.text.length, "to review src/widget.c\nthen ship it 🚀 — naïve café")
        }
        assertEquals("Ask @ptys_3HX6W0ZD5N2BT to review src/widget.c\nthen ship it 🚀 — naïve café", sample.serialised())
        // A token inserted into a selection's place, between two words.
        val between = MentionDraft.EMPTY.applyEdit(0, 0, "ab").insertMention(1, "ptys_7K2M9Q4R8T1VH", "zsh")
        assertEquals("a@ptys_7K2M9Q4R8T1VH b", between.serialised())
        assertEquals(1, between.tokens.single().start)
        // An empty name falls back to the guid, so the token always shows something.
        assertEquals(guid, MentionDraft.EMPTY.insertMention(0, guid, " ").tokens.single().displayName)
        // Inserting inside a token puts the new one after it.
        val inside = draft.insertMention(6, "ptys_7K2M9Q4R8T1VH", "zsh")
        assertEquals("Ask $token@ptys_7K2M9Q4R8T1VH  to review", inside.serialised())
        assertEquals(2, inside.tokens.size)
    }

    @Test
    fun deletingInsideATokenDeletesItWhole() {
        val draft = askDraft()
        val start = draft.tokens.single().start
        val end = draft.tokens.single().end
        // Backspace on the token's last character.
        val backspaced = draft.applyEdit(end - 1, end, "")
        assertEquals("Ask  to review", backspaced.text)
        assertTrue(backspaced.tokens.isEmpty())
        // One character in the middle.
        assertEquals("Ask  to review", draft.applyEdit(start + 3, start + 4, "").text)
        // A selection from the text into the token goes with the whole token.
        val across = draft.applyEdit(1, start + 2, "")
        assertEquals("A to review", across.text)
        assertTrue(across.tokens.isEmpty())
        // A character replaced inside a token (autocorrect) replaces the whole token.
        val replaced = draft.applyEdit(start + 2, start + 3, "x")
        assertEquals("Ask x to review", replaced.text)
        assertTrue(replaced.tokens.isEmpty())
        // The span the adapter places the cursor from.
        assertEquals(start to end, draft.editSpan(end - 1, end))
        assertEquals(2 to 2, draft.editSpan(2, 2))
    }

    @Test
    fun keyboardRecomposingATokenDeletesIt() {
        val draft = askDraft()
        val token = draft.tokens.single()
        // Gboard backspaces into "Code Review" by re-composing the word: the whole token is
        // replaced with what is left of its name.
        for (remnant in listOf("Code Revie", "Revie", "Code", "Code Reviews", "Cxde Review")) {
            val edited = draft.applyEdit(token.start, token.end, remnant)
            assertEquals(remnant, "Ask  to review", edited.text)
            assertTrue(remnant, edited.tokens.isEmpty())
        }
        // Typing over a selected token keeps what was typed.
        assertEquals("Ask x to review", draft.applyEdit(token.start, token.end, "x").text)
        assertEquals("Ask Deploy shell to review", draft.applyEdit(token.start, token.end, "Deploy shell").text)
        // Only an edit over exactly the token: text beside it is the owner's.
        assertEquals("Code Revie to review", draft.applyEdit(0, token.end, "Code Revie").text)
    }

    @Test
    fun editsBeforeATokenShiftIt() {
        val draft = askDraft()
        val original = draft.tokens.single()
        val prefixed = draft.applyEdit(0, 0, "Please ")
        assertEquals(original.copy(start = original.start + 7, end = original.end + 7), prefixed.tokens.single())
        assertEquals("Please Ask $token to review", prefixed.serialised())
        val shortened = prefixed.applyEdit(0, 7, "")
        assertEquals(original, shortened.tokens.single())
        // Typing right before or right after a token leaves it whole.
        val before = draft.applyEdit(original.start, original.start, "x")
        assertEquals(original.start + 1, before.tokens.single().start)
        val after = draft.applyEdit(original.end, original.end, "!")
        assertEquals(original, after.tokens.single())
        assertEquals("Ask $token! to review", after.serialised())
        // Edits after the token leave it where it is.
        val tail = draft.applyEdit(draft.text.length - 6, draft.text.length, "ship")
        assertEquals(original, tail.tokens.single())
        assertEquals("Ask $token to ship", tail.serialised())
    }

    @Test
    fun pasteOfRawGuidStaysText() {
        val pasted = MentionDraft.EMPTY.applyEdit(0, 0, "Ask $token now")
        assertTrue("pasted text is never a token", pasted.tokens.isEmpty())
        assertEquals("Ask $token now", pasted.serialised())
        assertEquals(listOf(InlineRun("Ask $token now")), pasted.displayRuns())
        // Deleting one of its characters deletes only that character.
        assertEquals("Ask @ptys_3HX6W0ZD5N2B now", pasted.applyEdit(22, 23, "").text)
    }

    @Test
    fun displayShowsNames() {
        val draft = askDraft()
        assertEquals(
            listOf(
                InlineRun("Ask "),
                InlineRun("Code Review", link = LinkTarget.Session(guid, "Code Review")),
                InlineRun(" to review"),
            ),
            draft.displayRuns(),
        )
        assertEquals(emptyList<InlineRun>(), MentionDraft.EMPTY.displayRuns())
        assertTrue(MentionDraft.EMPTY.isBlank)
        assertTrue(MentionDraft.EMPTY.applyEdit(0, 0, " \n\t").isBlank)
        assertFalse(draft.isBlank)
    }

    @Test
    fun draftsShowNoContent() {
        val secret = "Sentinel deploy shell"
        val draft = MentionDraft.EMPTY.applyEdit(0, 0, secret).insertMention(0, guid, secret)
        for (text in listOf(draft.toString(), draft.tokens.single().toString())) {
            assertFalse(text, text.contains(secret))
        }
        val drafts = ChatDrafts()
        drafts.set("C", draft)
        assertFalse(drafts.toString().contains(secret))
    }
}
