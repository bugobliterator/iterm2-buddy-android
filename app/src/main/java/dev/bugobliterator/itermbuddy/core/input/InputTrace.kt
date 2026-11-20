package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The opt-in input trace, for debugging what the keyboard sends (owner request): one line per
 * InputConnection call, key event, translator emit, sender decision and sent `sendKey`/`pasteText`
 * envelope. Off by default in every build; the owner turns it on with Settings' "Include typed-key
 * trace" switch (in memory only: off again when the process restarts), or with
 * `adb shell setprop log.tag.IB.Input VERBOSE` (off with `... SUPPRESS`); either turns it on.
 * Its lines go to logcat and to the app's log that Settings › Share log sends.
 *
 * Typed text is never written as text: every character is written as its code point
 * ([codePoints]: `U+002F[/]`, the character in brackets only for printable ASCII). Everything
 * else the app logs, and every `toString`, stays redacted as before.
 *
 * With the gate off, [log] builds no string: callers pass a lambda, which is not called. The gate
 * is re-read at most once a second ([GATE_RECHECK_MILLIS]), or at the next line after [recheck].
 * Lines are capped at [MAX_LINE] chars.
 *
 * @param gate whether tracing is on (production: the switch or `Log.isLoggable(TAG, Log.VERBOSE)`,
 *   [dev.bugobliterator.itermbuddy.applog.keyTraceGate]).
 * @param nowMillis a monotonic clock in milliseconds.
 * @param sink writes one line (production: `AppLog.v(TAG, line)`, logcat and the app's log).
 */
class InputTrace(
    private val gate: () -> Boolean,
    private val nowMillis: () -> Long,
    private val sink: (String) -> Unit,
) {
    @Volatile private var checkedAt = 0L

    @Volatile private var checked = false

    @Volatile private var on = false

    /** Whether tracing is on now (the gate, re-read at most once a second). */
    val enabled: Boolean
        get() {
            val now = nowMillis()
            if (!checked || now - checkedAt >= GATE_RECHECK_MILLIS || now < checkedAt) {
                on = try {
                    gate()
                } catch (_: RuntimeException) {
                    false
                }
                checkedAt = now
                checked = true
            }
            return on
        }

    /** Re-reads the gate at the next line instead of within a second (the in-app switch changed). */
    fun recheck() {
        checked = false
    }

    /** Writes [line]'s result when tracing is on; otherwise does not call it. */
    fun log(line: () -> String) {
        if (!enabled) return
        val text = line()
        sink(if (text.length <= MAX_LINE) text else text.take(MAX_LINE - TRUNCATED.length) + TRUNCATED)
    }

    companion object {
        /** The log tag (Android tags must be at most 23 characters). */
        const val TAG = "IB.Input"

        const val GATE_RECHECK_MILLIS = 1_000L

        const val MAX_LINE = 512

        const val TRUNCATED = "…[truncated]"

        /** Never on: the default until the app installs the real one, and in JVM tests. */
        val OFF = InputTrace({ false }, { 0L }, {})

        /** The trace the input path writes to; `CompanionApp` installs the switch- and adb-gated one. */
        @Volatile
        var current: InputTrace = OFF
    }
}

/** [text] as code points, space-separated: `U+0061[a] U+002F[/] U+1F600`; `""` for empty text. */
fun codePoints(text: CharSequence?): String {
    if (text == null) return "null"
    if (text.isEmpty()) return "\"\""
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        if (out.isNotEmpty()) out.append(' ')
        out.append(codePoint(codePoint))
        i += Character.charCount(codePoint)
    }
    return out.toString()
}

/** One code point: `U+002F[/]` for printable ASCII, else `U+000A`, `U+1F600`. */
fun codePoint(codePoint: Int): String {
    val hex = "U+" + Integer.toHexString(codePoint).uppercase().padStart(4, '0')
    return if (codePoint in 0x20..0x7E) "$hex[${codePoint.toChar()}]" else hex
}

/** A key event for the trace: its key (text as code points) and modifiers. */
fun traceKeyEvent(event: KeyEvent): String {
    val key = when (val k = event.key) {
        is Key.Text -> "text(${codePoints(k.text)})"
        is Key.Special -> "special(${k.key.raw})"
    }
    val m = event.modifiers
    return "$key control=${m.control} shift=${m.shift} leftOption=${m.leftOption} rightOption=${m.rightOption}"
}

/** Whether [message] is one whose envelope the trace shows (`sendKey`, `pasteText`). */
fun isTracedInput(message: PhoneMessage): Boolean = message is PhoneMessage.SendKey || message is PhoneMessage.PasteText

/**
 * The envelope JSON as sent, with each typed string (`text._0` of a key, `pasteText.text`) written
 * as its code points ([codePoints]) instead of its text; everything else as is.
 */
fun traceEnvelope(bytes: ByteArray): String = try {
    codePointStrings(Json.parseToJsonElement(bytes.decodeToString()), parentKey = null).toString()
} catch (_: IllegalArgumentException) {
    "<${bytes.size} bytes, not JSON>"
}

private fun codePointStrings(element: JsonElement, parentKey: String?): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            val typed = (parentKey == "text" && key == "_0") || (parentKey == "pasteText" && key == "text")
            if (typed && value is JsonPrimitive && value.isString) JsonPrimitive(codePoints(value.content)) else codePointStrings(value, key)
        },
    )
    is JsonArray -> JsonArray(element.map { codePointStrings(it, parentKey) })
    else -> element
}
