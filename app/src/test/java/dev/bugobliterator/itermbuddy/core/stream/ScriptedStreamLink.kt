package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * A [StreamLink] the test drives by hand: sends are recorded (optionally held on [sendGate]);
 * each request is handed to [requests] and returns when the test completes its reply.
 */
class ScriptedStreamLink : StreamLink {
    class Sent(val message: PhoneMessage, val onLink: Long?)

    class Pending(val message: PhoneMessage, val onLink: Long?, val requestID: Long, val timeout: Duration? = null) {
        val reply = CompletableDeferred<MacMessage>()
    }

    val sent = CopyOnWriteArrayList<Sent>()
    val requests = Channel<Pending>(Channel.UNLIMITED)

    /** When set, every send waits for it first. */
    @Volatile var sendGate: CompletableDeferred<Unit>? = null

    /** The current link: sends and requests for another one throw, as `CompanionClient` does. */
    @Volatile var currentLink: Long = 1

    /**
     * When true, a request waits for its reply even if its caller is cancelled: a reply the
     * session already handed to its waiter as the caller was being cancelled.
     */
    @Volatile var repliesOutliveCancellation = false

    private val nextRequestID = AtomicLong(1)

    override suspend fun send(message: PhoneMessage, onLink: Long?) {
        sendGate?.await()
        if (onLink != null && onLink != currentLink) throw CompanionRequestException("link $onLink has ended")
        sent += Sent(message, onLink)
    }

    override suspend fun request(
        message: PhoneMessage,
        onLink: Long?,
        timeout: Duration?,
        onSent: (requestID: Long) -> Unit,
    ): MacMessage {
        if (onLink != null && onLink != currentLink) throw CompanionRequestException("link $onLink has ended")
        val pending = Pending(message, onLink, nextRequestID.getAndIncrement(), timeout)
        onSent(pending.requestID)
        requests.trySend(pending)
        return if (repliesOutliveCancellation) withContext(NonCancellable) { pending.reply.await() } else pending.reply.await()
    }
}
