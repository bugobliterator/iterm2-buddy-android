package dev.bugobliterator.itermbuddy.core.chat

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat inbox's bound: [inboxItems] inputs **and** [inboxBytes] bytes of raw payload
 * ([ChatInput.Payload.size]), whichever fills first. Default 4096 items and 16 MiB.
 */
data class ChatBudgets(val inboxItems: Int = 4096, val inboxBytes: Long = DEFAULT_INBOX_BYTES)

/**
 * The [ChatSink] the session's receive loop delivers to: [accept] only enqueues and returns, and
 * one consumer coroutine in [scope] calls [consume] for each input, in order.
 *
 * **Bound.** An input holds one of [ChatBudgets.inboxItems] slots and, for a [ChatInput.Payload],
 * its size in bytes, from when [accept] admits it until [consume] has returned for it. [accept]
 * suspends while the inbox is full and never drops. An input is always admitted into an empty
 * inbox, so a payload over [ChatBudgets.inboxBytes] waits only for the inbox to drain; an input
 * with no payload bytes needs only a free slot.
 *
 * **Consumer.** [consume] must never await a reply from the Mac (the receive loop that would read
 * it may be waiting on this inbox) and should not block: a request runs in its own coroutine and
 * [post]s its outcome back. An exception from [consume] is logged by its class and the input's
 * kind, and the consumer goes on with the next input.
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
    private class Posted(val block: suspend () -> Unit) : Item

    private data class Usage(val items: Int, val bytes: Long)

    private val usage = MutableStateFlow(Usage(0, 0))

    private val items = Channel<Item>(Channel.UNLIMITED)

    /** The most inputs the inbox has held at once, for tests that need its bound reached. */
    internal val highWater = AtomicInteger()

    /** Inputs admitted and not yet consumed. */
    internal val queuedItems: Int get() = usage.value.items

    /** Payload bytes admitted and not yet consumed. */
    internal val queuedBytes: Long get() = usage.value.bytes

    init {
        scope.launch {
            for (item in items) {
                try {
                    when (item) {
                        is Incoming -> consume(item.input)
                        is Posted -> item.block()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("chat consumer failed on ${item.kind()}: ${e.javaClass.simpleName}")
                } finally {
                    if (item is Incoming) usage.update { Usage(it.items - 1, it.bytes - item.bytes) }
                }
            }
        }
    }

    /**
     * Enqueues [input] and returns; suspends while the inbox is full, never drops, never waits for
     * the Mac and never throws a cancellation of its own. Cancelled while suspended, nothing was
     * enqueued.
     */
    override suspend fun accept(input: ChatInput) {
        val bytes = if (input is ChatInput.Payload) input.size.toLong() else 0L
        reserve(bytes)
        // No suspension between the reservation and the send: an UNLIMITED channel never refuses.
        items.trySend(Incoming(input, bytes))
    }

    /** Runs [block] on the consumer after everything enqueued so far; takes no slot and never suspends the caller. */
    fun post(block: suspend () -> Unit) {
        items.trySend(Posted(block))
    }

    private suspend fun reserve(bytes: Long) {
        while (true) {
            val seen = usage.value.takeIf { fits(it, bytes) } ?: usage.first { fits(it, bytes) }
            if (usage.compareAndSet(seen, Usage(seen.items + 1, seen.bytes + bytes))) {
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

/** Default for [ChatBudgets.inboxBytes]: 16 MiB. */
const val DEFAULT_INBOX_BYTES: Long = 16L shl 20
