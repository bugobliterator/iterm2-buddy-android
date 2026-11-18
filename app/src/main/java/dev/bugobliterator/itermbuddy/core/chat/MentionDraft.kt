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
 */
fun MentionDraft.applyEdit(start: Int, end: Int, replacement: String): MentionDraft {
    val (from, to) = editSpan(start, end)
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
