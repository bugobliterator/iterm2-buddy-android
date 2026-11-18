package dev.bugobliterator.itermbuddy.core.chat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat feature's memory bounds, all injectable.
 *
 * The inbox: [inboxItems] inputs **and** [inboxBytes] bytes, whichever fills first. An input's
 * bytes are its frame's size: [ChatInput.Payload.size] for a raw frame,
 * [ChatInput.Message.frameBytes] for a decoded one, none for markers and
 * [ChatInput.PayloadTooLarge] (whose bytes are gone). Default 4096 items and 16 MiB.
 *
 * The repository: transcripts are kept for open conversations and the [closedTranscripts] most
 * recently closed (default 3); a transcript's attachment bytes are under [attachments].
 */
data class ChatBudgets(
    val inboxItems: Int = 4096,
    val inboxBytes: Long = DEFAULT_INBOX_BYTES,
    val closedTranscripts: Int = 3,
    val attachments: AttachmentBudgets = AttachmentBudgets(),
) {
    init {
        require(closedTranscripts >= 0) { "closedTranscripts must not be negative" }
    }
}

/**
 * The [ChatSink] the session's receive loop delivers to: [accept] only enqueues and returns, and
 * one consumer coroutine in [scope] calls [consume] for each input, in order.
 *
 * **Bound.** An input holds one of [ChatBudgets.inboxItems] slots and its frame's bytes (see
 * [ChatBudgets]) from when [accept] admits it until [consume] has returned for it. [accept]
 * suspends while the inbox is full and never drops. An input is always admitted into an empty
 * inbox, so one over [ChatBudgets.inboxBytes] waits only for the inbox to drain; an input with no
 * bytes needs only a free slot. [post]ed work bypasses both bounds.
 *
 * **Consumer.** [consume] must never await a reply from the Mac (the receive loop that would read
 * it may be waiting on this inbox) and should not block: a request runs in its own coroutine and
 * [post]s its outcome back. An exception from [consume], a `CancellationException` of its own
 * included (a `withTimeout` inside it, say), is logged by its class and the input's kind, and the
 * consumer goes on with the next input; only the cancellation of [scope] stops it. Once it has
 * stopped, [accept] throws [ChatInboxStoppedException] at once (also to an accept already waiting
 * for room), which the session treats as a sink failure that did not take the input, rather than
 * waiting out its stall bound on every frame: a chat reply's request fails at once. Link markers
 * are different: the client counts a marker the sink threw on as taken (`sinkMarker`), so a link
 * still comes up, and goes down, with the chat consumer dead. That is deliberate: a dead chat
 * feature must never keep live video from connecting.
 *
 * @param log diagnostics: input kinds and exception classes only, never content.
 */
class ChatInbox(
    scope: CoroutineScope,
    private val budgets: ChatBudgets = ChatBudgets(),
    private val log: (String) -> Unit = {},
    private val consume: suspend (ChatInput) -> Unit,
) : ChatSink {
    private sealed interface Item

    /** A sink input holding one slot and [bytes] of the byte budget until it is consumed. */
    private class Incoming(val input: ChatInput, val bytes: Long) : Item

    /** Work posted by the chat feature itself (a request's outcome): no slot, in order with the inputs. */
    private class Posted(val block: suspend () -> Unit, onDropped: () -> Unit) : Item {
        private val dropped = AtomicBoolean()
        private val onDroppedOnce = onDropped

        /** Tells the poster its block will never run; at most once, however it was found dropped. */
        fun dropped() {
            if (dropped.compareAndSet(false, true)) onDroppedOnce()
        }
    }

    private data class Usage(val items: Int, val bytes: Long, val stopped: Boolean = false)

    private val usage = MutableStateFlow(Usage(0, 0))

    private val stoppedLogged = AtomicBoolean()

    /**
     * Posted work the consumer never runs is told so: still queued when the channel is
     * cancelled, posted after, or taken by a receive that was cancelled before it resumed.
     */
    private val items = Channel<Item>(Channel.UNLIMITED, onUndeliveredElement = { (it as? Posted)?.dropped() })

    /** The most inputs the inbox has held at once, for tests that need its bound reached. */
    internal val highWater = AtomicInteger()

    /** Inputs admitted and not yet consumed. */
    internal val queuedItems: Int get() = usage.value.items

    /** Payload bytes admitted and not yet consumed. */
    internal val queuedBytes: Long get() = usage.value.bytes

    init {
        val consumer = scope.launch {
            for (item in items) {
                try {
                    when (item) {
                        is Incoming -> consume(item.input)
                        is Posted -> item.block()
                    }
                } catch (e: CancellationException) {
                    // The scope is being cancelled: stop. Otherwise consume threw a cancellation of
                    // its own (a timeout inside it): one failed input, not a dead consumer.
                    if (!currentCoroutineContext().isActive) throw e
                    log("chat consumer failed on ${item.kind()}: ${e.javaClass.simpleName}")
                } catch (e: Exception) {
                    log("chat consumer failed on ${item.kind()}: ${e.javaClass.simpleName}")
                } finally {
                    if (item is Incoming) usage.update { it.copy(items = it.items - 1, bytes = it.bytes - item.bytes) }
                }
            }
        }
        consumer.invokeOnCompletion {
            usage.update { it.copy(stopped = true) }
            // Nothing more will run: posted work still queued is dropped and told so.
            items.cancel()
        }
    }

    /** Whether the consumer has stopped (its scope was cancelled); [accept] then fails at once. */
    val stopped: Boolean get() = usage.value.stopped

    /**
     * Enqueues [input] and returns; suspends while the inbox is full, never drops, never waits for
     * the Mac and never throws a cancellation of its own. Cancelled while suspended, nothing was
     * enqueued.
     *
     * Not safe for concurrent callers: the order between two accepts in flight at once is not
     * defined. The session calls it only from its receive loop and its link markers, serialised
     * under its ordering lock.
     *
     * @throws ChatInboxStoppedException when the consumer has stopped; nothing was enqueued.
     */
    override suspend fun accept(input: ChatInput) {
        val bytes = when (input) {
            is ChatInput.Payload -> input.size.toLong()
            is ChatInput.Message -> input.frameBytes.toLong()
            else -> 0L
        }
        reserve(bytes, input)
        // No suspension between the reservation and the send: an UNLIMITED channel never refuses.
        items.trySend(Incoming(input, bytes))
    }

    /**
     * Runs [block] on the consumer after everything enqueued so far. It bypasses the item and byte
     * bounds (it takes no slot) and never suspends the caller; dropped once the consumer has stopped,
     * when [onDropped] runs instead (on the caller's thread, or on the thread that stopped the
     * consumer).
     */
    fun post(onDropped: () -> Unit = {}, block: suspend () -> Unit) {
        val posted = Posted(block, onDropped)
        if (items.trySend(posted).isFailure) posted.dropped()
    }

    private suspend fun reserve(bytes: Long, input: ChatInput) {
        while (true) {
            val seen = usage.value.takeIf { it.stopped || fits(it, bytes) } ?: usage.first { it.stopped || fits(it, bytes) }
            if (seen.stopped) {
                if (stoppedLogged.compareAndSet(false, true)) log("the chat consumer has stopped; refusing chat inputs")
                throw ChatInboxStoppedException(input.javaClass.simpleName)
            }
            if (usage.compareAndSet(seen, seen.copy(items = seen.items + 1, bytes = seen.bytes + bytes))) {
                highWater.accumulateAndGet(seen.items + 1, ::maxOf)
                return
            }
        }
    }

    private fun fits(now: Usage, bytes: Long): Boolean =
        now.items == 0 || (now.items < budgets.inboxItems && (bytes == 0L || now.bytes + bytes <= budgets.inboxBytes))

    private fun Item.kind(): String = when (this) {
        is Incoming -> when (val input = input) {
            is ChatInput.Payload -> "Payload(${input.case})"
            is ChatInput.Message -> "Message(${input.message.javaClass.simpleName})"
            else -> input.javaClass.simpleName
        }
        is Posted -> "posted work"
    }
}

/** [ChatInbox.accept] after the consumer stopped: the input, named by [kind] only, was not taken. */
class ChatInboxStoppedException(kind: String) : IllegalStateException("the chat consumer has stopped; $kind not taken")

/** Default for [ChatBudgets.inboxBytes]: 16 MiB. */
const val DEFAULT_INBOX_BYTES: Long = 16L shl 20
