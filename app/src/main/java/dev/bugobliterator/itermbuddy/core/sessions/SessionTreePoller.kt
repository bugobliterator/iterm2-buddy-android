package dev.bugobliterator.itermbuddy.core.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches the session tree again every [interval] while the Sessions list is shown (owner
 * decision). iTerm2 pushes a tree only on a terminate, a session-count change or a
 * presentation-name change (`CompanionHostBridge.swift:112, 191-201, 235-272`), never when a
 * subtitle changes, so Claude Code's `\(status)` on the rows would otherwise go stale.
 *
 * It polls only while all three gates are open: [setVisible] (the Sessions list is the
 * destination shown), [setForeground] (the app is in the foreground) and [setConnected]. The
 * moment one closes, polling stops: its wait or its wait for [poll] is cancelled (the fetch itself
 * belongs to the repository and still lands). When the list appears or the app comes to the
 * foreground on a live link, it polls at once and then every [interval], counted from the end of
 * the previous poll; when the gates open because the link came up, it waits an interval first,
 * since the repository fetches on every connect by itself.
 *
 * [poll] is [SessionTreeRepository.poll]: it sends nothing while a fetch is out and returns when
 * that fetch ends, so at most one request is ever out. Thread-safe. Logs nothing: there is
 * nothing here but flags.
 */
class SessionTreePoller(
    private val scope: CoroutineScope,
    private val poll: suspend () -> Unit,
    private val interval: Duration = 4.seconds,
) {
    private val lock = Any()

    // Guarded by lock.
    private var visible = false
    private var foreground = true
    private var connected = false
    private var job: Job? = null

    /** Whether polling runs now (all gates open). */
    val polling: Boolean get() = synchronized(lock) { job != null }

    /** Whether the Sessions list is the destination shown. */
    fun setVisible(visible: Boolean) = update(pollAtOnce = true) { this.visible = visible }

    /** Whether the app is in the foreground. */
    fun setForeground(foreground: Boolean) = update(pollAtOnce = true) { this.foreground = foreground }

    /** Whether the client is connected. */
    fun setConnected(connected: Boolean) = update(pollAtOnce = false) { this.connected = connected }

    private fun update(pollAtOnce: Boolean, change: () -> Unit) {
        synchronized(lock) {
            change()
            val open = visible && foreground && connected
            if (!open) {
                job?.cancel()
                job = null
            } else if (job == null) {
                job = scope.launch { run(pollFirst = pollAtOnce) }
            }
        }
    }

    private suspend fun run(pollFirst: Boolean) {
        if (!pollFirst) delay(interval)
        while (true) {
            poll()
            delay(interval)
        }
    }

    override fun toString(): String = synchronized(lock) {
        "SessionTreePoller(visible=$visible, foreground=$foreground, connected=$connected, polling=${job != null})"
    }
}
