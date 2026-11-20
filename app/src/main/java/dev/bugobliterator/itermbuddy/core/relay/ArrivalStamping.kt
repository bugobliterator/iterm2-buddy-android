package dev.bugobliterator.itermbuddy.core.relay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether relay queues stamp frame arrivals (`FrameQueue`): on while at least one user holds it.
 * The live diagnostics' meter holds [PROCESS] while it measures, so the receive path pays nothing
 * for stamps otherwise.
 */
class ArrivalStamping {
    private val users = AtomicInteger()

    /** Whether any user holds it. */
    val on: Boolean get() = users.get() > 0

    /** Turns stamping on until the matching [release]. */
    fun acquire() {
        users.incrementAndGet()
    }

    fun release() {
        users.decrementAndGet()
    }

    companion object {
        /** The process's: every relay connection's queue reads it. */
        val PROCESS = ArrivalStamping()
    }
}
