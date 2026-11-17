package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Sends `streamAck`s for the stream controller, always with `queueDepth` 0 (iOS
 * `IOS/Screens/SessionView.swift:983`: a real depth can hold the Mac back with no later event to
 * release it).
 *
 * [ack] never suspends: it records the ack and wakes one sender coroutine in [scope], which sends
 * pending acks in the order their streams first became pending, each with `onLink` set to its
 * link. While an ack waits to be sent, a newer one for the same link and stream replaces it: the
 * Mac's flow control needs only the newest acknowledged PTS. An ack for a link that has ended
 * (or any send failure) is dropped quietly.
 */
class AckSender(private val link: StreamLink, scope: CoroutineScope) {
    private data class Key(val linkId: Long, val streamID: Long)

    private val lock = Any()

    /** Pending acks, newest PTS per stream, in first-pending order. */
    private val pending = LinkedHashMap<Key, Long>()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val next = synchronized(lock) {
                        val first = pending.entries.firstOrNull() ?: return@synchronized null
                        pending.remove(first.key)
                        first.key to first.value
                    } ?: break
                    val (key, pts) = next
                    try {
                        link.send(PhoneMessage.StreamAck(key.streamID, pts, queueDepth = 0), onLink = key.linkId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The link has ended or the send failed: a later ack (or none) supersedes it.
                    }
                }
            }
        }
    }

    /** Acknowledges [ptsMilliseconds] on stream [streamID] of link [linkId]; returns at once. */
    fun ack(linkId: Long, streamID: Long, ptsMilliseconds: Long) {
        synchronized(lock) { pending[Key(linkId, streamID)] = ptsMilliseconds }
        wake.trySend(Unit)
    }
}
