package dev.bugobliterator.itermbuddy.core.relay

import kotlin.random.Random

/** The relay's close reason when a newer phone took this room's phone slot (room.js:530). */
const val CLOSE_REASON_DISPLACED = "displaced"

/** The relay's close reason when the room relayed more than its daily byte quota (room.js:239). */
const val CLOSE_REASON_DAILY_QUOTA = "daily quota exceeded"

private const val DISPLACED_DELAY_MILLIS = 60_000L
private const val DAILY_QUOTA_DELAY_MILLIS = 30 * 60_000L
private const val FIRST_ATTEMPT_CAP_SECONDS = 3L
private const val MAX_CAP_SECONDS = 30L

/**
 * How long to wait before reconnect [attempt] (1-based), as upstream `RelayReconnectBackoff`:
 * full jitter, uniform in `[0, cap]` seconds with `cap = 3` for the first attempt and
 * `min(30, 2^attempt)` after it. A "displaced" close waits 60 s and a "daily quota exceeded"
 * close 30 min, whatever the attempt. Pass a seeded [random] in tests.
 */
fun nextDelayMillis(attempt: Int, lastCloseReason: String?, random: Random): Long {
    require(attempt >= 1) { "attempt is 1-based, got $attempt" }
    when (lastCloseReason) {
        CLOSE_REASON_DISPLACED -> return DISPLACED_DELAY_MILLIS
        CLOSE_REASON_DAILY_QUOTA -> return DAILY_QUOTA_DELAY_MILLIS
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
