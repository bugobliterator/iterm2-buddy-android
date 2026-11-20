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
 * The one ordered path for keys, pastes, scroll-wheel notches and browser input to the Mac: one unbounded queue,
 * one consumer in [scope], so input is sent in the order [key], [paste], [typeThenReturn], [scrollWheel] and
 * [streamInput] were called (per calling thread; the calls are thread-safe). iOS sends a browser tap and the key
 * typed after it from the main actor in call order (`IOS/App/AppModel.swift:4674-4715`), so a key typed straight
 * after a tap on another field lands in that field.
 *
 * - **Stale input.** At dequeue, an item whose target is no longer current ([isCurrent]: another
 *   link, another watch, another session) is discarded, and the running count is logged.
 * - **Stream-scoped input** ([streamInput], the browser driver's) is also discarded at dequeue when
 *   its own check (the stream it was made for is still the active browser stream) fails. A
 *   `browserScroll` queued right behind another for the same target, stream and point, while that
 *   one is still queued, is merged into it (deltas summed), so a slow link gets fewer, larger steps.
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
 * - **Pasted keys** (owner decision 2026-11-20, the Settings switch "Send / * + = as paste", on by
 *   default). While [pasteKeys] holds, each of [PASTED_KEYS] (`/ * + =`) in a `text` key event
 *   with **no** modifiers, and in each typed piece of **Type then Return**, is sent as its own
 *   one-character `pasteText` instead of a key: the text is split into runs ([splitPastedKeys]),
 *   sent in order, so `a/b` is `text "a"`, `pasteText "/"`, `text "b"`. Why: iTerm2 3.7.7 turns
 *   these four into the wrong key when the program enabled the kitty keyboard protocol or
 *   modifyOtherKeys (Claude Code does): `/` arrives as `CSI 30:47;2u` (key U+001E with Shift), so
 *   Claude Code draws nothing (`NSEvent.keyDown(forCharacter:)`'s reverse map,
 *   `sources/Keyboard/VimKeyParser.swift:243-275`; see
 *   `docs/reports/iterm2-companion-key-injection-bug.md`). A paste is written as text whatever
 *   the keyboard mode (bracketed when the program asked). A one-character paste is written at
 *   once on the Mac when no other paste is in progress
 *   (`iTermPasteHelper.pasteLiteralEventUnconditionallyImmediately`: no indicator, no timer).
 *   While a long paste is still being written on the Mac, though, `iTermPasteHelper` queues
 *   further pastes behind it, and injected keys skip that queue: the pasted key can then be
 *   overtaken by the keys sent after it. A key event with any modifier
 *   (Ctrl+/) is sent as a key, unchanged, as is a key for a browser session's page ([key] with
 *   `pasteKeys` false); the composer's **Paste** is unchanged. As with typed
 *   pieces, each run after the first is sent only while the target is still current, and the item
 *   stops at the first run that is stale, fails or times out. [pasteKeys] is read when the item
 *   is sent.
 *
 * Logs carry the item's kind (key, paste, typed draft), counts, link ids, watch epochs, GUIDs and
 * exception class names: never typed or pasted text, never which key (a special key's name is a
 * keystroke pattern), and never an exception's message (it may quote what it failed to send).
 * Only the opt-in [InputTrace] shows items: each at dequeue (kept, discarded, timed out, failed),
 * its text as code points.
 *
 * @param sendBound the bound on each send, 2 s by default.
 * @param typeChunkBytes the largest piece of a typed draft, in UTF-8 bytes ([TYPE_CHUNK_BYTES]).
 * @param pasteKeys whether [PASTED_KEYS] are sent as pastes (see above); off unless given. The
 *   app passes its Settings switch.
 */
class InputSender(
    private val link: StreamLink,
    scope: CoroutineScope,
    private val isCurrent: (InputTarget) -> Boolean,
    private val log: (String) -> Unit,
    private val sendBound: Duration = 2.seconds,
    private val typeChunkBytes: Int = TYPE_CHUNK_BYTES,
    private val pasteKeys: () -> Boolean = { false },
) {
    private sealed interface Item {
        val target: InputTarget
        val label: String
    }

    private class KeyItem(override val target: InputTarget, val event: KeyEvent, val pasteKeys: Boolean) : Item {
        override val label: String get() = "key"
    }

    private class PasteItem(override val target: InputTarget, val text: String) : Item {
        override val label: String get() = "paste"
    }

    private class TypeItem(override val target: InputTarget, val text: String) : Item {
        override val label: String get() = "typed draft then Return"
    }

    private class WheelItem(override val target: InputTarget, val message: PhoneMessage.ReportScrollWheel) : Item {
        override val label: String get() = "scroll wheel"
    }

    /**
     * A stream-scoped [message] (browser input) sent when [stillActive] at dequeue; [taken] and
     * [message] are touched under [lock], so a scroll can be merged into it until the consumer takes it.
     */
    private class StreamItem(override val target: InputTarget, var message: PhoneMessage, val stillActive: () -> Boolean) : Item {
        var taken = false
        override val label: String get() = "browser ${message::class.simpleName}"
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)

    private val lock = Any()

    /** The item enqueued last, for merging scrolls; under [lock]. */
    private var lastEnqueued: Item? = null

    /** Items discarded as stale; touched by the consumer only. */
    private var discarded = 0L

    init {
        scope.launch {
            for (item in queue) process(item)
        }
    }

    /**
     * A key for [target]. With [pasteKeys] false (a browser session's page: iTerm2 types its keys
     * into the page, not through the garbling key mapper) it is always sent as a key.
     */
    fun key(target: InputTarget, event: KeyEvent, pasteKeys: Boolean = true) = enqueue(KeyItem(target, event, pasteKeys))

    fun paste(target: InputTarget, text: String) = enqueue(PasteItem(target, text))

    /** Paste ⏎: a single-line [text] typed then Returned; a multi-line one pasted with no Return. */
    fun typeThenReturn(target: InputTarget, text: String) =
        enqueue(if (isMultiLineDraft(text)) PasteItem(target, text) else TypeItem(target, text))

    /**
     * Mouse-wheel notches for the alternate screen (`reportScrollWheel`), in order with keys: [up]
     * reveals older content, [lines] (at least 1) is the notch count of one drag step, as iOS sends
     * them (`IOS/Screens/SessionView.swift:2445-2448`, `IOS/App/AppModel.swift:4657-4660`).
     */
    fun scrollWheel(target: InputTarget, streamID: Long, up: Boolean, lines: Int) {
        if (lines <= 0) return
        enqueue(WheelItem(target, PhoneMessage.ReportScrollWheel(streamID, up, lines)))
    }

    /**
     * Stream-scoped input for [target] (a browser stream's tap, scroll or control), in order with
     * keys; dropped at dequeue unless [target] is current and [stillActive] holds then.
     */
    fun streamInput(target: InputTarget, message: PhoneMessage, stillActive: () -> Boolean) {
        synchronized(lock) {
            val last = lastEnqueued
            if (message is PhoneMessage.BrowserScroll && last is StreamItem && !last.taken && last.target == target) {
                val queued = last.message
                if (queued is PhoneMessage.BrowserScroll && queued.streamID == message.streamID && queued.x == message.x && queued.y == message.y) {
                    last.message = queued.copy(deltaX = queued.deltaX + message.deltaX, deltaY = queued.deltaY + message.deltaY)
                    return
                }
            }
            enqueue(StreamItem(target, message, stillActive))
        }
    }

    private fun enqueue(item: Item) {
        synchronized(lock) {
            lastEnqueued = item
            // Unlimited: this fails only once the consumer's scope has ended.
            if (queue.trySend(item).isFailure) log("input sender stopped; dropped ${item.label}")
        }
    }

    private suspend fun process(item: Item) {
        val target = item.target
        val message = if (item is StreamItem) synchronized(lock) { item.taken = true; item.message } else null
        if (!isCurrent(target) || (item is StreamItem && !item.stillActive())) {
            InputTrace.current.log { "sender: discarded stale ${traceItem(item)} for ${target.sessionGuid} link ${target.linkId} watch ${target.watchEpoch}" }
            discarded++
            log("discarded stale ${item.label} for ${target.sessionGuid} on link ${target.linkId} watch ${target.watchEpoch}; $discarded discarded in all")
            return
        }
        InputTrace.current.log { "sender: kept ${traceItem(item)} for ${target.sessionGuid} link ${target.linkId} watch ${target.watchEpoch}" }
        when (item) {
            is KeyItem -> sendKey(item.event, target, item.label, item.pasteKeys)
            is PasteItem -> send(PhoneMessage.PasteText(target.sessionGuid, item.text), target, item.label)
            is TypeItem -> typeThenReturn(item)
            is WheelItem -> send(item.message, target, item.label)
            is StreamItem -> send(checkNotNull(message), target, item.label)
        }
    }

    private suspend fun typeThenReturn(item: TypeItem) {
        val target = item.target
        val pieces = splitForTyping(item.text, typeChunkBytes)
        for ((index, piece) in pieces.withIndex()) {
            // The first piece was checked at dequeue; the rest are checked again before each send.
            val label = "typed piece ${index + 1}/${pieces.size}"
            val sent = (index == 0 || isCurrent(target)) && sendKey(KeyEvent(Key.Text(piece)), target, label)
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

    /**
     * Sends [event] as one `sendKey`, or, when it is [eligible], [pasteKeys] holds and it is text with no modifiers
     * that has any of [PASTED_KEYS], as its runs in order ([splitPastedKeys]); each run after the
     * first only while [target] is current. False at the first run not sent.
     */
    private suspend fun sendKey(event: KeyEvent, target: InputTarget, label: String, eligible: Boolean = true): Boolean {
        val key = event.key
        if (!eligible || key !is Key.Text || event.modifiers != KeyModifiers() || key.text.none { it in PASTED_KEYS } || !pasteKeys()) {
            return send(PhoneMessage.SendKey(target.sessionGuid, event), target, label)
        }
        val runs = splitPastedKeys(key.text)
        for ((index, run) in runs.withIndex()) {
            val runLabel = "$label, run ${index + 1}/${runs.size}"
            if (index > 0 && !isCurrent(target)) {
                InputTrace.current.log { "sender: stopped before $runLabel: the target changed" }
                log("$label for ${target.sessionGuid} stopped at run ${index + 1}/${runs.size}: the target changed")
                return false
            }
            val message = if (run.pasted) {
                InputTrace.current.log { "sender: pasted key ${codePoints(run.text)} as pasteText ($runLabel)" }
                PhoneMessage.PasteText(target.sessionGuid, run.text)
            } else {
                InputTrace.current.log { "sender: typed run ${codePoints(run.text)} as sendKey ($runLabel)" }
                PhoneMessage.SendKey(target.sessionGuid, KeyEvent(Key.Text(run.text)))
            }
            if (!send(message, target, runLabel)) return false
        }
        return true
    }

    /** An item for the opt-in [InputTrace]: its kind and its text as code points. */
    private fun traceItem(item: Item): String = when (item) {
        is KeyItem -> "key ${traceKeyEvent(item.event)}"
        is PasteItem -> "paste ${codePoints(item.text)}"
        is TypeItem -> "type-then-Return ${codePoints(item.text)}"
        is WheelItem -> "scroll wheel ${if (item.message.up) "up" else "down"} ${item.message.lines}"
        is StreamItem -> item.label
    }

    /** Sends [message] on [target]'s link within [sendBound]; false (logged) on a timeout or failure. */
    private suspend fun send(message: PhoneMessage, target: InputTarget, label: String): Boolean = try {
        withTimeout(sendBound) { link.send(message, onLink = target.linkId) }
        true
    } catch (_: TimeoutCancellationException) {
        InputTrace.current.log { "sender: timed out after $sendBound: $label" }
        log("$label for ${target.sessionGuid} on link ${target.linkId} timed out after $sendBound; skipped")
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        InputTrace.current.log { "sender: failed (${e::class.simpleName}): $label" }
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

/**
 * The characters sent as pastes when the Settings switch is on (see [InputSender]): exactly the
 * four iTerm2 3.7.7 sends as the wrong key under Claude Code's kitty flags 5 with
 * modifyOtherKeys 2, measured on the real-Mac CI (all 95 printable ASCII characters arrived and
 * were decoded; the other 91 were right). Claude Code 2.1.327 draws none of the four sent as keys,
 * and draws each of them pasted.
 */
const val PASTED_KEYS = "/*+="

/** A run of typed text: one of [PASTED_KEYS] alone ([pasted]), or text with none of them. */
data class TextRun(val text: String, val pasted: Boolean) {
    override fun toString(): String = "TextRun(len=${text.length}, pasted=$pasted)"
}

/**
 * [text] split into runs, in order: each of [PASTED_KEYS] a run of its own ([TextRun.pasted]),
 * the text between them typed runs. Empty for empty text. The pasted characters are ASCII, so a
 * split never falls inside a surrogate pair.
 */
fun splitPastedKeys(text: String): List<TextRun> {
    val runs = mutableListOf<TextRun>()
    var start = 0
    for ((index, char) in text.withIndex()) {
        if (char !in PASTED_KEYS) continue
        if (index > start) runs += TextRun(text.substring(start, index), pasted = false)
        runs += TextRun(char.toString(), pasted = true)
        start = index + 1
    }
    if (start < text.length) runs += TextRun(text.substring(start), pasted = false)
    return runs
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
