package dev.bugobliterator.itermbuddy.core.relay

import java.util.Locale
import kotlin.random.Random

/** A WebSocket close the relay sent: its close code and reason. */
data class RelayClose(val code: Int, val reason: String)

/** The relay's 1000 close when a newer phone took this room's phone slot (room.js:530). */
const val CLOSE_REASON_DISPLACED = "displaced"

/** The relay's 1008 close when the room relayed more than its daily byte quota (room.js:239). */
const val CLOSE_REASON_DAILY_QUOTA = "daily quota exceeded"

/**
 * The relay's 1000 close when an unestablished room saw more than 8 phone handshakes in one Mac
 * park (room.js:40-48, 491-497). The relay has wiped the room and the Mac must mint a new pairing
 * ID, so the scanned QR is dead: show "Scan again" instead of backing off. It can arrive instead
 * of an admission result, or on an admitted socket when another phone blows the cap. See
 * [requiresRescan].
 */
const val CLOSE_REASON_PAIRING_CYCLE_CAP = "pairing cycle cap"

private const val CLOSE_NORMAL = 1000
private const val CLOSE_POLICY = 1008
private const val DISPLACED_DELAY_MILLIS = 60_000L
private const val DAILY_QUOTA_DELAY_MILLIS = 30 * 60_000L
private const val FIRST_ATTEMPT_CAP_SECONDS = 3L
private const val MAX_CAP_SECONDS = 30L

/**
 * Whether [close] means the relay tore the pairing room down (1000 "pairing cycle cap"), so the
 * phone must scan a new QR rather than reconnect. Matched by code and case-insensitive reason, as
 * upstream `RelaySignal` matches its long-backoff closes.
 */
fun requiresRescan(close: RelayClose): Boolean =
    close.code == CLOSE_NORMAL && close.reason.lowercase(Locale.ROOT).contains(CLOSE_REASON_PAIRING_CYCLE_CAP)

/**
 * How long to wait before reconnect [attempt] (1-based), as upstream `RelayReconnectBackoff`:
 * full jitter, uniform in `[0, cap]` seconds with `cap = 3` for the first attempt and
 * `min(30, 2^attempt)` after it. Pass a seeded [random] in tests.
 *
 * Two closes wait longer whatever the attempt, matched like upstream `RelaySignal.swift:54-60`:
 * by code plus a case-insensitive substring of the reason, never by the bare code, since the relay
 * overloads 1000 and 1008. 1000 with "displaced" waits 60 s; 1008 with "daily quota" waits 30 min.
 *
 * [lastClose] must be the close frame the relay sent on the connection that just ended, and only
 * that: null after a local close, a drop (1006), a timeout, an HTTP failure or a rejection, and
 * never carried over from an earlier connection. Check [requiresRescan] before calling this.
 */
fun nextDelayMillis(attempt: Int, lastClose: RelayClose?, random: Random): Long {
    require(attempt >= 1) { "attempt is 1-based, got $attempt" }
    if (lastClose != null) {
        val reason = lastClose.reason.lowercase(Locale.ROOT)
        if (lastClose.code == CLOSE_NORMAL && reason.contains(CLOSE_REASON_DISPLACED)) return DISPLACED_DELAY_MILLIS
        if (lastClose.code == CLOSE_POLICY && reason.contains("daily quota")) return DAILY_QUOTA_DELAY_MILLIS
    }
    // 2^5 already exceeds the cap; checking first avoids overflow for large attempts.
    val capSeconds = when {
        attempt == 1 -> FIRST_ATTEMPT_CAP_SECONDS
        attempt >= 5 -> MAX_CAP_SECONDS
        else -> minOf(MAX_CAP_SECONDS, 1L shl attempt)
    }
    return random.nextLong(0, capSeconds * 1_000 + 1)
}

/**
 * Whether an HTTP status on the relay's WebSocket upgrade ends the attempt for good rather than
 * retrying with backoff (upstream `RelaySignal`): 4xx is fatal (403 Origin or bad room, 413 and
 * the rest) except 429, which the relay sends for per-IP limits; 5xx and anything else retries.
 */
fun isFatalHttp(status: Int): Boolean = status in 400..499 && status != 429
