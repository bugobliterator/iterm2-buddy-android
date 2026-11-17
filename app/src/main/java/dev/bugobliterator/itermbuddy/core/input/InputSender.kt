package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.BreakIterator
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where typed input goes: the session [sessionGuid] as watched under [watchEpoch] on link
 * [linkId], taken from the adopted [ActiveStream] when the input was made.
 */
data class InputTarget(val linkId: Long, val watchEpoch: Long, val sessionGuid: String)

/** Whether [target] is still where input goes: [active] has its link, watch epoch and session. */
fun isInputTarget(active: ActiveStream?, target: InputTarget): Boolean =
    active != null && active.linkId == target.linkId && active.watchEpoch == target.watchEpoch &&
        active.sessionGuid == target.sessionGuid

/**
 * The one ordered path for keys and pastes to the Mac: one unbounded queue, one consumer in
 * [scope], so input is sent in the order [key], [paste] and [typeThenReturn] were called (per
 * calling thread; the calls are thread-safe).
 *
 * - **Stale input.** At dequeue, an item whose target is no longer current ([isCurrent]: another
 *   link, another watch, another session) is discarded, and the running count is logged.
 * - **Bounded.** Each send is bound to its target's link (`onLink`) and to [sendBound]; a send that
 *   times out or fails is logged and skipped, and the queue goes on in order.
 * - **Paste** (the composer's) is one `pasteText`: iTerm2's paste path, bracketed when the app
 *   enabled it.
 * - **Type then Return** (the composer's Paste ⏎). Owner decision 2026-11-18: single-line drafts
 *   are typed then Returned; multi-line drafts are pasted with no Return. A draft with a newline
 *   (`\n` or `\r`, [isMultiLineDraft]) is one `pasteText`, exactly as **Paste**, and the user
 *   presses Return: typed, each of its lines would be entered as it went. A single-line draft is
 *   one item that types it as `text` key events with no modifiers, as the iOS composer does
 *   (`SessionView.swift:1846`, `sendLiteral`), then sends `text "\n"`: typed, because
 *   `pasteText` is chunked on the Mac and may ask for confirmation there, so a Return sent after
 *   it could arrive before the paste lands (the plan had `pasteText` then Return). A large draft
 *   is typed in several `text` events, in order, split at grapheme boundaries into pieces of at
 *   most [typeChunkBytes] UTF-8 bytes ([splitForTyping]); before each piece the target is checked
 *   again and the item stops at the first piece that is stale, fails or times out. The Return is
 *   sent only after every piece was sent **and** the target is still current at that moment, so
 *   failed or overtaken typing never submits.
 *
 * Logs carry the item's kind (key, paste, typed draft), counts, link ids, watch epochs, GUIDs and
 * exception class names: never typed or pasted text, never which key (a special key's name is a
 * keystroke pattern), and never an exception's message (it may quote what it failed to send).
 *
 * @param sendBound the bound on each send, 2 s by default.
 * @param typeChunkBytes the largest piece of a typed draft, in UTF-8 bytes ([TYPE_CHUNK_BYTES]).
 */
class InputSender(
    private val link: StreamLink,
    scope: CoroutineScope,
    private val isCurrent: (InputTarget) -> Boolean,
    private val log: (String) -> Unit,
    private val sendBound: Duration = 2.seconds,
    private val typeChunkBytes: Int = TYPE_CHUNK_BYTES,
) {
    private sealed interface Item {
        val target: InputTarget
        val label: String
    }

    private class KeyItem(override val target: InputTarget, val event: KeyEvent) : Item {
        override val label: String get() = "key"
    }

    private class PasteItem(override val target: InputTarget, val text: String) : Item {
        override val label: String get() = "paste"
    }

    private class TypeItem(override val target: InputTarget, val text: String) : Item {
        override val label: String get() = "typed draft then Return"
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)

    /** Items discarded as stale; touched by the consumer only. */
    private var discarded = 0L

    init {
        scope.launch {
            for (item in queue) process(item)
        }
    }

    fun key(target: InputTarget, event: KeyEvent) = enqueue(KeyItem(target, event))

    fun paste(target: InputTarget, text: String) = enqueue(PasteItem(target, text))

    /** Paste ⏎: a single-line [text] typed then Returned; a multi-line one pasted with no Return. */
    fun typeThenReturn(target: InputTarget, text: String) =
        enqueue(if (isMultiLineDraft(text)) PasteItem(target, text) else TypeItem(target, text))

    private fun enqueue(item: Item) {
        // Unlimited: this fails only once the consumer's scope has ended.
        if (queue.trySend(item).isFailure) log("input sender stopped; dropped ${item.label}")
    }

    private suspend fun process(item: Item) {
        val target = item.target
        if (!isCurrent(target)) {
            discarded++
            log("discarded stale ${item.label} for ${target.sessionGuid} on link ${target.linkId} watch ${target.watchEpoch}; $discarded discarded in all")
            return
        }
        when (item) {
            is KeyItem -> send(PhoneMessage.SendKey(target.sessionGuid, item.event), target, item.label)
            is PasteItem -> send(PhoneMessage.PasteText(target.sessionGuid, item.text), target, item.label)
            is TypeItem -> typeThenReturn(item)
        }
    }

    private suspend fun typeThenReturn(item: TypeItem) {
        val target = item.target
        val pieces = splitForTyping(item.text, typeChunkBytes)
        for ((index, piece) in pieces.withIndex()) {
            // The first piece was checked at dequeue; the rest are checked again before each send.
            val label = "typed piece ${index + 1}/${pieces.size}"
            val sent = (index == 0 || isCurrent(target)) &&
                send(PhoneMessage.SendKey(target.sessionGuid, KeyEvent(Key.Text(piece))), target, label)
            if (!sent) {
                log("${item.label} for ${target.sessionGuid} stopped at piece ${index + 1}/${pieces.size}; Return not sent")
                return
            }
        }
        if (isCurrent(target)) {
            send(PhoneMessage.SendKey(target.sessionGuid, RETURN), target, "Return after typed draft")
        } else {
            log("Return after typed draft for ${target.sessionGuid} not sent: the target changed")
        }
    }

    /** Sends [message] on [target]'s link within [sendBound]; false (logged) on a timeout or failure. */
    private suspend fun send(message: PhoneMessage, target: InputTarget, label: String): Boolean = try {
        withTimeout(sendBound) { link.send(message, onLink = target.linkId) }
        true
    } catch (_: TimeoutCancellationException) {
        log("$label for ${target.sessionGuid} on link ${target.linkId} timed out after $sendBound; skipped")
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$label for ${target.sessionGuid} on link ${target.linkId} failed (${e::class.simpleName}); skipped")
        false
    }

    companion object {
        /**
         * The largest typed piece: 16 KiB of UTF-8. JSON escaping at worst multiplies it by six
         * (`\u00XX`), so an envelope stays well under the relay's 256 KiB frame.
         */
        const val TYPE_CHUNK_BYTES = 16 * 1024
        private val RETURN = KeyEvent(Key.Text("\n"))
    }
}

/** Whether a composer draft has a line break (`\n` or `\r`): Paste ⏎ then pastes it with no Return. */
fun isMultiLineDraft(text: String): Boolean = text.any { it == '\n' || it == '\r' }

/**
 * [text] in order as pieces of at most [maxBytes] UTF-8 bytes each, split only at grapheme
 * boundaries; a single grapheme larger than [maxBytes] is a piece of its own. Empty for empty
 * text. A boundary is one `BreakIterator.getCharacterInstance` reports that [joinsPrevious] does
 * not veto: older `BreakIterator`s (the JDK's before 20) split emoji modifier and ZWJ sequences,
 * which the veto keeps whole (`\r\n`, combining marks, variation selectors, emoji modifiers,
 * ZWJ sequences, tags and flag pairs).
 */
fun splitForTyping(text: String, maxBytes: Int = InputSender.TYPE_CHUNK_BYTES): List<String> {
    if (text.isEmpty()) return emptyList()
    if (text.length * 3 <= maxBytes) return listOf(text)
    val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val pieces = mutableListOf<String>()
    var pieceStart = 0
    var pieceBytes = 0
    var start = graphemes.first()
    var end = graphemes.next()
    while (end != BreakIterator.DONE) {
        if (end < text.length && joinsPrevious(text, end)) {
            end = graphemes.next()
            continue
        }
        val bytes = utf8Length(text, start, end)
        if (pieceBytes > 0 && pieceBytes + bytes > maxBytes) {
            pieces += text.substring(pieceStart, start)
            pieceStart = start
            pieceBytes = 0
        }
        pieceBytes += bytes
        start = end
        end = graphemes.next()
    }
    pieces += text.substring(pieceStart)
    return pieces
}

/** Whether the code point at [index] belongs to the grapheme before it (see [splitForTyping]). */
private fun joinsPrevious(text: String, index: Int): Boolean {
    val before = text.codePointBefore(index)
    val at = text.codePointAt(index)
    if (before == '\r'.code && at == '\n'.code) return true
    if (before == ZWJ) return true
    when (Character.getType(at).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> return true
    }
    if (at == ZWJ || at in 0xFE00..0xFE0F || at in 0x1F3FB..0x1F3FF || at in 0xE0020..0xE007F) return true
    if (isRegionalIndicator(before) && isRegionalIndicator(at)) {
        // Flags pair up: join when an odd number of indicators run up to this one.
        var count = 0
        var i = index
        while (i > 0 && isRegionalIndicator(text.codePointBefore(i))) {
            count++
            i -= Character.charCount(text.codePointBefore(i))
        }
        return count % 2 == 1
    }
    return false
}

private const val ZWJ = 0x200D

private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

private fun utf8Length(text: String, start: Int, end: Int): Int {
    var bytes = 0
    var i = start
    while (i < end) {
        val codePoint = text.codePointAt(i)
        bytes += when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        i += Character.charCount(codePoint)
    }
    return bytes
}
