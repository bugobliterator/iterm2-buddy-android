package dev.bugobliterator.itermbuddy.core.chat

/**
 * One @-mention token in a [MentionDraft]: the draft's raw text holds `@<guid>` over
 * [start] until [end] (exclusive), shown as [displayName]. [toString] shows no name.
 */
data class MentionToken(val start: Int, val end: Int, val guid: String, val displayName: String) {
    override fun toString(): String = "MentionToken(start=$start, end=$end, guid=$guid, displayName(len=${displayName.length}))"
}

/**
 * What the owner is typing to a chat: [text] is the raw text as the wire sends it, each picked
 * mention already written `@<guid>`, and [tokens] (in order, never overlapping) mark the picked
 * mentions in it. iOS holds a token as one text attachment that selects and deletes as one
 * character and serialises to `@<guid>` (`IOS/Views/MentionComposerView.swift:18-35, 105-150`);
 * here the composer shows each token's span as its name ([MentionToken.displayName]) and
 * [applyEdit] keeps a token whole. Text that merely looks like a mention (a pasted `@ptys_…`) is
 * plain text: only a picked session is a token. [toString] shows lengths only.
 */
data class MentionDraft(val text: String, val tokens: List<MentionToken>) {
    init {
        var previousEnd = 0
        for (token in tokens) {
            require(token.start >= previousEnd && token.end <= text.length) { "tokens must be in order, apart and inside the text" }
            require(text.regionMatches(token.start, "@${token.guid}", 0, token.end - token.start) &&
                token.end - token.start == token.guid.length + 1) { "a token must span @<guid>" }
            previousEnd = token.end
        }
    }

    /** Nothing but whitespace and newlines: nothing to send (`AM:3087-3091`). */
    val isBlank: Boolean get() = text.isBlank()

    override fun toString(): String = "MentionDraft(text(len=${text.length}), tokens=${tokens.size})"

    companion object {
        val EMPTY = MentionDraft("", emptyList())
    }
}

/** The draft as the wire sends it: tokens as `@<guid>` (`IOS/Views/MentionComposerView.swift:105-120`). */
fun MentionDraft.serialised(): String = text

/**
 * The span an edit of [start] until [end] really replaces: widened to cover every token it
 * touches, so a token is deleted (or replaced) whole when any of its characters is; an insertion
 * strictly inside a token moves to the token's end. Typing right before or right after a token
 * leaves it alone.
 */
fun MentionDraft.editSpan(start: Int, end: Int): Pair<Int, Int> {
    require(start in 0..end && end <= text.length) { "the edit must be inside the text" }
    var from = start
    var to = end
    for (token in tokens) {
        if (start == end) {
            if (start > token.start && start < token.end) return token.end to token.end
        } else if (start < token.end && end > token.start) {
            from = minOf(from, token.start)
            to = maxOf(to, token.end)
        }
    }
    return from to to
}

/**
 * The draft with [start] until [end] replaced by [replacement] (plain text, never a token), the
 * span widened by [editSpan]. Tokens after it shift by the change in length; tokens it covers go.
 *
 * The keyboard sees a token as its name, so a backspace into it can come back as the whole
 * token replaced by what is left of the name (Gboard re-composing "Code Revie" for "Code
 * Review"). An edit whose span is exactly one token and whose replacement is a remnant of the
 * token's name (a part of it, or the name with one character changed, added or removed) deletes
 * the token instead of leaving that remnant as text.
 */
fun MentionDraft.applyEdit(start: Int, end: Int, replacement: String): MentionDraft {
    val (from, to) = editSpan(start, end)
    val covered = tokens.firstOrNull { it.start == from && it.end == to }
    if (covered != null && replacement.isNotEmpty() && isRemnantOf(replacement, covered.displayName)) {
        return applyEdit(from, to, "")
    }
    val delta = replacement.length - (to - from)
    val kept = ArrayList<MentionToken>(tokens.size)
    for (token in tokens) {
        when {
            token.end <= from -> kept += token
            token.start >= to -> kept += token.copy(start = token.start + delta, end = token.end + delta)
        }
    }
    return MentionDraft(text.substring(0, from) + replacement + text.substring(to), kept)
}

/**
 * The draft with a token for session [guid], shown as [displayName] (the guid when that is
 * blank), and a trailing space inserted at [at] (moved to the end of a token it falls inside),
 * as iOS inserts one at the cursor (`IOS/Views/MentionComposerView.swift:126-150`).
 */
fun MentionDraft.insertMention(at: Int, guid: String, displayName: String): MentionDraft {
    val (position, _) = editSpan(at, at)
    val raw = "@$guid"
    val spaced = applyEdit(position, position, "$raw ")
    val token = MentionToken(position, position + raw.length, guid, displayName.ifBlank { guid })
    return MentionDraft(spaced.text, (spaced.tokens + token).sortedBy { it.start })
}

/**
 * Whether [text] is what a keyboard leaves of [name] after editing it as a word: a part of it at
 * least two characters long (one for a two-character name), or [name] with one character changed,
 * added or removed. A single typed character over a selected token is not a remnant.
 */
private fun isRemnantOf(text: String, name: String): Boolean {
    val shortest = minOf(2, name.length - 1).coerceAtLeast(1)
    if (text.length >= shortest && name.contains(text)) return true
    return text.length >= 2 && withinOneEdit(text, name)
}

/** Whether [a] and [b] differ by at most one character changed, added or removed. */
private fun withinOneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    var j = 0
    while (j < a.length - i && j < b.length - i && a[a.length - 1 - j] == b[b.length - 1 - j]) j++
    return a.length - i - j <= 1 && b.length - i - j <= 1
}
