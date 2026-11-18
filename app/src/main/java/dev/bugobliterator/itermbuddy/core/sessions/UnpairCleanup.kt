package dev.bugobliterator.itermbuddy.core.sessions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs every one of [cleanups], in order, each time [paired] turns false: a local unpair, the
 * Mac's `unpaired`, or a new scan replacing the pairing. A cleanup that throws is logged and the
 * rest still run. `CompanionApp` (through `wireHome`) passes the navigator's `reset` (which
 * also stops any stream), the session tree repository's `clear`, and the chat repository's
 * `clear`. The chat seen store is deliberately not a cleanup of its own: `ChatRepository.clear()`
 * wipes it on the chat consumer, after its barrier against inputs from earlier links, so a mark
 * still queued there cannot land after the wipe (a cleanup here runs on another thread and would
 * race it).
 *
 * A [StateFlow] conflates: a false followed by a true before this collector runs would be missed.
 * The client cannot do that: a new pairing is stored unconfirmed (false) first and is confirmed
 * only after the Mac answers, long after.
 *
 * @return the collecting job, in [scope].
 */
fun clearOnUnpair(
    paired: StateFlow<Boolean?>,
    scope: CoroutineScope,
    cleanups: List<() -> Unit>,
    log: (String) -> Unit = {},
): Job = scope.launch {
    paired.collect { now ->
        if (now != false) return@collect
        for (cleanup in cleanups) {
            try {
                cleanup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("an unpair cleanup failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
