package dev.bugobliterator.itermbuddy.core.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.SelectClause1
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Received relay frames waiting for their reader, unbounded in count and bounded in bytes.
 *
 * Bytes count when [offer] queues a frame and are released when the reader receives it, by
 * `receive`, `receiveCatching`, `tryReceive` or a `for` loop over [incoming]. A frame that would
 * take the unread bytes past [maxQueuedBytes] is refused: [offer] returns false and [incoming]
 * is closed with [RelayClosedException] 1006 whose reason starts with [OVER_BUDGET], after every
 * frame queued before it. A frame is never dropped while the queue stays open.
 *
 * [offer] never blocks, so OkHttp's single reader thread never stalls behind a slow consumer.
 * [incoming]'s select clauses are not supported: they could not release the budget.
 *
 * While [stamping] is on, each frame is stamped with [nanoTime] when it is offered;
 * [lastReceivedAtNanos] is the stamp of the frame the reader received last (the live diagnostics'
 * receive-to-decoder time), [NO_ARRIVAL] for a frame offered while stamping was off. Off, an offer
 * costs one volatile read and a counter.
 */
internal class FrameQueue(
    private val maxQueuedBytes: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val stamping: ArrivalStamping = ArrivalStamping.PROCESS,
) {
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val queuedBytes = AtomicLong()

    /** A stamped frame: the [sequence]th offered, at [atNanos]. */
    private class Stamp(val sequence: Long, val atNanos: Long)

    /** Stamps of queued frames, in queue order: pushed before their frame, matched by sequence as frames are received. */
    private val arrivals = ConcurrentLinkedQueue<Stamp>()

    /** Frames offered so far; the writer's only (offers are serialised). */
    private var offered = 0L

    /** Frames received so far; the reader's only. */
    private var received = 0L

    /**
     * When the frame the reader received last was offered ([nanoTime]); [NO_ARRIVAL] before the
     * first, and for a frame offered while [stamping] was off. Read it right after a receive, on
     * the reader's side.
     */
    @Volatile
    var lastReceivedAtNanos: Long = NO_ARRIVAL
        private set

    /** The reader's side. */
    val incoming: ReceiveChannel<ByteArray> = Counted()

    /** Bytes queued and not yet received. */
    val unreadBytes: Long get() = queuedBytes.get()

    /**
     * Queues [frame]; false (and the queue closed) if it would pass the budget. Callers serialise
     * their offers (one reader thread); receiving may run concurrently.
     */
    fun offer(frame: ByteArray): Boolean {
        val queued = queuedBytes.get()
        if (queued + frame.size > maxQueuedBytes) {
            val reason = "$OVER_BUDGET: $queued bytes unread, ${frame.size} more, limit $maxQueuedBytes"
            frames.close(RelayClosedException(CLOSE_ABNORMAL, reason))
            return false
        }
        queuedBytes.addAndGet(frame.size.toLong())
        val sequence = offered++
        if (stamping.on) arrivals.add(Stamp(sequence, nanoTime()))
        return frames.trySend(frame).isSuccess
    }

    /** Closes [incoming]: normally, or with [cause] once the queued frames are read. */
    fun close(cause: Throwable? = null) {
        frames.close(cause)
    }

    private fun release(frame: ByteArray) {
        queuedBytes.addAndGet(-frame.size.toLong())
        val sequence = received++
        // Stamps of earlier frames are gone already; a later frame's stamp stays for it.
        var stamp = arrivals.peek()
        while (stamp != null && stamp.sequence < sequence) {
            arrivals.poll()
            stamp = arrivals.peek()
        }
        lastReceivedAtNanos = if (stamp != null && stamp.sequence == sequence) {
            arrivals.poll()
            stamp.atNanos
        } else {
            NO_ARRIVAL
        }
    }

    private inner class Counted : ReceiveChannel<ByteArray> by frames {
        override suspend fun receive(): ByteArray = frames.receive().also(::release)

        override suspend fun receiveCatching(): ChannelResult<ByteArray> =
            frames.receiveCatching().also { result -> result.getOrNull()?.let(::release) }

        override fun tryReceive(): ChannelResult<ByteArray> =
            frames.tryReceive().also { result -> result.getOrNull()?.let(::release) }

        override fun iterator(): ChannelIterator<ByteArray> = object : ChannelIterator<ByteArray> {
            private val inner = frames.iterator()

            override suspend fun hasNext(): Boolean = inner.hasNext()

            override fun next(): ByteArray = inner.next().also(::release)
        }

        override val onReceive: SelectClause1<ByteArray>
            get() = throw UnsupportedOperationException("select on incoming would not release the budget")

        override val onReceiveCatching: SelectClause1<ChannelResult<ByteArray>>
            get() = throw UnsupportedOperationException("select on incoming would not release the budget")
    }

    companion object {
        /** Start of the reason of an over-budget close; RelayConnection also sends it as its WebSocket close reason. */
        const val OVER_BUDGET = "receive queue over budget"

        /** [lastReceivedAtNanos] before any frame was received, and for sockets that do not stamp frames. */
        const val NO_ARRIVAL = Long.MIN_VALUE
        private const val CLOSE_ABNORMAL = 1006
    }
}
