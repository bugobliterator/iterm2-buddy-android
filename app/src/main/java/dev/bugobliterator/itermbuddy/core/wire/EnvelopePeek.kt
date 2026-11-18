package dev.bugobliterator.itermbuddy.core.wire

/**
 * A Mac envelope's request ID and its payload's one case, read without decoding the body. The
 * receive loop routes by it: a [RAW_CHAT_CASES] frame crosses to the chat sink as raw bytes, and
 * every other frame is decoded with [decodeEnvelope] as before.
 */
data class EnvelopePeek(val requestID: Long?, val case: String)

/**
 * The chat cases whose bodies can be large (a whole chat history, the whole chat list): the
 * receive loop never parses them; the chat feature's consumer decodes them off the loop.
 */
val RAW_CHAT_CASES: Set<String> = setOf("history", "chatsAndSessions", "chatListChanged")

/**
 * Reads [bytes]' request ID and case with one forward scan over the UTF-8 bytes: string- and
 * escape-aware, skipping values by bracket depth, building no tree and no `String` of the body (only
 * the case name and short keys are decoded), so its cost does not grow with the body's size.
 *
 * Agrees with [decodeEnvelope] on every frame that decodes: the same request ID and, for the case,
 * the payload's one key. A repeated key keeps its last value, as the parsed tree does, and an
 * escaped key reads as what it spells. It throws [WireDecodeException] for every structural reason
 * [decodeEnvelope] does: a media frame, a frame that is not one JSON object (unterminated, trailing
 * bytes, a missing `:` or `,`, a trailing comma, mismatched brackets, a bad escape), no `payload`
 * object, a payload without exactly one key, or a request ID that is not `null` or a plain
 * non-negative integer literal that fits in a [Long].
 *
 * Inside a skipped value it checks only bracket matching and string termination: it does not
 * validate UTF-8, number syntax, the punctuation of nested containers or nesting depth. Such a
 * frame passes the peek and is rejected later by [decodeEnvelope], which every frame still goes
 * through. Exception messages name keys and positions only, never content.
 */
fun peekEnvelope(bytes: ByteArray): EnvelopePeek = EnvelopeScanner(bytes).scan()

/**
 * The top-level `chatID` string of [bytes]' payload body (`{"payload":{"<case>":{"chatID":...}}}`),
 * read by another forward scan like [peekEnvelope]'s, building no tree; only that one string is
 * decoded. Null when there is none, when it is not a string or is longer than 256 bytes raw, or
 * when the frame does not scan. Never throws. For a frame too large to decode, to say which chat
 * it was about.
 */
fun peekChatID(bytes: ByteArray): String? = try {
    EnvelopeScanner(bytes).chatID()
} catch (_: WireDecodeException) {
    null
} catch (_: RuntimeException) {
    // Defensive: the scanner bounds-checks every read, but this must never fail a frame.
    null
}

private const val QUOTE = '"'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
private const val OPEN_OBJECT = '{'.code.toByte()
private const val CLOSE_OBJECT = '}'.code.toByte()
private const val OPEN_ARRAY = '['.code.toByte()
private const val CLOSE_ARRAY = ']'.code.toByte()
private const val COLON = ':'.code.toByte()
private const val COMMA = ','.code.toByte()
private const val MEDIA_FRAME_BYTE: Byte = 0x01

/** Keys longer than this, raw, cannot be `requestID` or `payload`, so they are never decoded. */
private const val LONGEST_ENVELOPE_KEY = 64

private val REQUEST_ID_KEY = "requestID".toByteArray(Charsets.US_ASCII)
private val PAYLOAD_KEY = "payload".toByteArray(Charsets.US_ASCII)
private val CHAT_ID_KEY = "chatID".toByteArray(Charsets.US_ASCII)

/** A `chatID` longer than this, raw, is not one the Mac sends (a UUID string): not decoded. */
private const val LONGEST_CHAT_ID = 256
private val NULL_LITERAL = "null".toByteArray(Charsets.US_ASCII)

private class EnvelopeScanner(private val bytes: ByteArray) {
    private var pos = 0

    /** One bit per open container, set for an object; grows only past 256 levels. */
    private var containers = LongArray(4)

    fun scan(): EnvelopePeek {
        if (bytes.isNotEmpty() && bytes[0] == MEDIA_FRAME_BYTE) throw WireDecodeException("media frame")
        skipWhitespace()
        if (at() != OPEN_OBJECT) {
            throw WireDecodeException("envelope is not a JSON object")
        }
        pos++
        var requestID: RequestIdValue = RequestIdValue.Absent
        var payload: PayloadValue = PayloadValue.Absent
        if (!closesEmpty(CLOSE_OBJECT)) {
            while (true) {
                val keyStart = pos
                skipString()
                val isRequestID = keyIs(keyStart, REQUEST_ID_KEY)
                val isPayload = !isRequestID && keyIs(keyStart, PAYLOAD_KEY)
                expectColon()
                when {
                    isRequestID -> requestID = readRequestID()
                    isPayload -> payload = readPayload()
                    else -> skipValue()
                }
                if (nextMemberOrClose(CLOSE_OBJECT)) break
            }
        }
        skipWhitespace()
        if (pos != bytes.size) throw WireDecodeException("frame is not valid JSON: bytes after the envelope")
        val id = when (requestID) {
            RequestIdValue.Absent -> null
            is RequestIdValue.Present -> requestID.value
            is RequestIdValue.Invalid -> throw WireDecodeException("requestID is not a non-negative integer (${requestID.reason})")
        }
        return when (payload) {
            PayloadValue.Absent, PayloadValue.NotAnObject -> throw WireDecodeException("payload is missing or not an object")
            is PayloadValue.Cases -> EnvelopePeek(
                id,
                payload.single ?: throw WireDecodeException("payload must have exactly one key, has ${payload.count}"),
            )
        }
    }

    /** The payload body's top-level `chatID` string (the last one, as the tree keeps), or null. */
    fun chatID(): String? {
        if (bytes.isNotEmpty() && bytes[0] == MEDIA_FRAME_BYTE) return null
        skipWhitespace()
        if (at() != OPEN_OBJECT) return null
        pos++
        var found: String? = null
        if (closesEmpty(CLOSE_OBJECT)) return null
        while (true) {
            val keyStart = pos
            skipString()
            val isPayload = keyIs(keyStart, PAYLOAD_KEY)
            expectColon()
            if (isPayload && at() == OPEN_OBJECT) {
                pos++
                if (!closesEmpty(CLOSE_OBJECT)) {
                    while (true) {
                        skipString()
                        expectColon()
                        if (at() == OPEN_OBJECT) found = bodyChatID() ?: found else skipValue()
                        if (nextMemberOrClose(CLOSE_OBJECT)) break
                    }
                }
            } else {
                skipValue()
            }
            if (nextMemberOrClose(CLOSE_OBJECT)) break
        }
        return found
    }

    /** At a case body's `{`: scans its members, returning its `chatID` string, leaving [pos] after its `}`. */
    private fun bodyChatID(): String? {
        pos++
        var found: String? = null
        if (closesEmpty(CLOSE_OBJECT)) return null
        while (true) {
            val keyStart = pos
            skipString()
            val isChatID = keyIs(keyStart, CHAT_ID_KEY)
            expectColon()
            if (isChatID && at() == QUOTE) {
                val valueStart = pos
                skipString()
                found = if (pos - valueStart - 2 <= LONGEST_CHAT_ID) decodeString(valueStart) else null
            } else {
                skipValue()
            }
            if (nextMemberOrClose(CLOSE_OBJECT)) break
        }
        return found
    }

    private sealed interface RequestIdValue {
        data object Absent : RequestIdValue

        class Present(val value: Long?) : RequestIdValue

        class Invalid(val reason: String) : RequestIdValue
    }

    private sealed interface PayloadValue {
        data object Absent : PayloadValue

        data object NotAnObject : PayloadValue

        /** [single] is the case when the payload has exactly one distinct key; [count] is a lower bound otherwise. */
        class Cases(val single: String?, val count: Int) : PayloadValue
    }

    private fun at(): Byte = if (pos < bytes.size) bytes[pos] else throw unterminated()

    private fun unterminated() = WireDecodeException("frame is not valid JSON: it ends early")

    private fun syntax(what: String) = WireDecodeException("frame is not valid JSON: $what at byte $pos")

    private fun skipWhitespace() {
        while (pos < bytes.size) {
            when (bytes[pos].toInt()) {
                ' '.code, '\n'.code, '\r'.code, '\t'.code -> pos++
                else -> return
            }
        }
    }

    /** After an opening bracket: true (and consumed) when the container is empty. */
    private fun closesEmpty(close: Byte): Boolean {
        skipWhitespace()
        if (at() == close) {
            pos++
            return true
        }
        return false
    }

    private fun expectColon() {
        skipWhitespace()
        if (at() != COLON) throw syntax("expected ':'")
        pos++
        skipWhitespace()
    }

    /** After a member: true at the closing [close], false after a `,` with another member to come. */
    private fun nextMemberOrClose(close: Byte): Boolean {
        skipWhitespace()
        return when (at()) {
            close -> {
                pos++
                true
            }
            COMMA -> {
                pos++
                skipWhitespace()
                false
            }
            else -> throw syntax("expected ',' or a closing bracket")
        }
    }

    /** Skips a string starting at [pos]; leaves [pos] after its closing quote. */
    private fun skipString() {
        if (at() != QUOTE) throw syntax("expected a string")
        pos++
        while (true) {
            when (at()) {
                QUOTE -> {
                    pos++
                    return
                }
                BACKSLASH -> {
                    pos++
                    if (!isEscape(at())) throw syntax("bad escape")
                    pos++
                }
                else -> pos++
            }
        }
    }

    private fun isEscape(byte: Byte): Boolean = when (byte.toInt().toChar()) {
        '"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u' -> true
        else -> false
    }

    /** Whether the string key starting at [start] (its opening quote) and ending just before [pos] spells [name]. */
    private fun keyIs(start: Int, name: ByteArray): Boolean {
        val rawLength = pos - start - 2
        if (rawLength == name.size && bytes.regionEquals(start + 1, name)) return true
        // Only an escaped spelling can still match; no key longer than this can.
        if (rawLength <= name.size || rawLength > LONGEST_ENVELOPE_KEY) return false
        return decodeString(start) == String(name, Charsets.US_ASCII)
    }

    private fun ByteArray.regionEquals(offset: Int, other: ByteArray): Boolean {
        for (index in other.indices) if (this[offset + index] != other[index]) return false
        return true
    }

    /** Decodes the string whose opening quote is at [start] and whose end [pos] is just past. */
    private fun decodeString(start: Int): String {
        val end = pos - 1
        val text = StringBuilder()
        var index = start + 1
        var run = index
        while (index < end) {
            if (bytes[index] != BACKSLASH) {
                index++
                continue
            }
            text.append(String(bytes, run, index - run, Charsets.UTF_8))
            val escape = bytes[index + 1].toInt().toChar()
            index += 2
            when (escape) {
                'b' -> text.append('\b')
                'f' -> text.append('\u000C')
                'n' -> text.append('\n')
                'r' -> text.append('\r')
                't' -> text.append('\t')
                'u' -> {
                    if (index + 4 > end) throw syntax("bad unicode escape")
                    var code = 0
                    repeat(4) {
                        val digit = Character.digit(bytes[index + it].toInt(), 16)
                        if (digit < 0) throw syntax("bad unicode escape")
                        code = code * 16 + digit
                    }
                    text.append(code.toChar())
                    index += 4
                }
                else -> text.append(escape)
            }
            run = index
        }
        text.append(String(bytes, run, end - run, Charsets.UTF_8))
        return text.toString()
    }

    /** `null`, or a plain non-negative integer literal that fits in a [Long]; anything else is invalid. */
    private fun readRequestID(): RequestIdValue {
        when (at()) {
            QUOTE -> {
                skipString()
                return RequestIdValue.Invalid("string")
            }
            OPEN_OBJECT, OPEN_ARRAY -> {
                skipValue()
                return RequestIdValue.Invalid("container")
            }
        }
        val start = pos
        skipLiteral()
        val length = pos - start
        if (length == NULL_LITERAL.size && bytes.regionEquals(start, NULL_LITERAL)) return RequestIdValue.Present(null)
        if (bytes[start] == '0'.code.toByte() && length > 1) return RequestIdValue.Invalid("leading zero")
        var value = 0L
        for (index in start until pos) {
            val digit = bytes[index] - '0'.code.toByte()
            if (digit !in 0..9) return RequestIdValue.Invalid("not an integer")
            if (value > (Long.MAX_VALUE - digit) / 10) return RequestIdValue.Invalid("out of range")
            value = value * 10 + digit
        }
        return RequestIdValue.Present(value)
    }

    /** The payload object's distinct keys, each value skipped; a non-object payload is skipped too. */
    private fun readPayload(): PayloadValue {
        if (at() != OPEN_OBJECT) {
            skipValue()
            return PayloadValue.NotAnObject
        }
        pos++
        if (closesEmpty(CLOSE_OBJECT)) return PayloadValue.Cases(null, 0)
        var first: String? = null
        var distinct = 0
        while (true) {
            val keyStart = pos
            skipString()
            val key = decodeString(keyStart)
            if (first == null) {
                first = key
                distinct = 1
            } else if (key != first && distinct == 1) {
                distinct = 2
            }
            expectColon()
            skipValue()
            if (nextMemberOrClose(CLOSE_OBJECT)) break
        }
        return PayloadValue.Cases(if (distinct == 1) first else null, distinct)
    }

    /**
     * Skips one value. A literal (number, `true`, `null`, or the unquoted word kotlinx's parser
     * also takes) runs to the next delimiter; a container is skipped by depth, its strings whole.
     */
    private fun skipValue() {
        when (at()) {
            QUOTE -> skipString()
            OPEN_OBJECT, OPEN_ARRAY -> skipContainer()
            CLOSE_OBJECT, CLOSE_ARRAY, COMMA, COLON -> throw syntax("expected a value")
            else -> skipLiteral()
        }
    }

    private fun skipLiteral() {
        val start = pos
        while (pos < bytes.size) {
            when (bytes[pos]) {
                COMMA, CLOSE_OBJECT, CLOSE_ARRAY, COLON, QUOTE, OPEN_OBJECT, OPEN_ARRAY -> break
                ' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte() -> break
                else -> pos++
            }
        }
        if (pos == start) throw syntax("expected a value")
    }

    private fun skipContainer() {
        var depth = 0
        while (true) {
            when (val byte = at()) {
                QUOTE -> skipString()
                OPEN_OBJECT, OPEN_ARRAY -> {
                    push(depth, byte == OPEN_OBJECT)
                    depth++
                    pos++
                }
                CLOSE_OBJECT, CLOSE_ARRAY -> {
                    if (depth == 0 || isObject(depth - 1) != (byte == CLOSE_OBJECT)) throw syntax("mismatched bracket")
                    depth--
                    pos++
                    if (depth == 0) return
                }
                else -> pos++
            }
        }
    }

    private fun push(depth: Int, isObject: Boolean) {
        val word = depth ushr 6
        if (word >= containers.size) containers = containers.copyOf(containers.size * 2)
        val bit = 1L shl (depth and 63)
        containers[word] = if (isObject) containers[word] or bit else containers[word] and bit.inv()
    }

    private fun isObject(depth: Int): Boolean = containers[depth ushr 6] and (1L shl (depth and 63)) != 0L
}
