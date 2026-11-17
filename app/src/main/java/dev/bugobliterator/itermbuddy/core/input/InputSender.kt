package dev.bugobliterator.itermbuddy.core.input

import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where typed input goes: the session [sessionGuid] as watched under [watchEpoch] on link
 * [linkId], taken from the adopted [ActiveStream] when the input was made.
 */
data class InputTarget(val linkId: Long, val watchEpoch: Long, val sessionGuid: String)

/** Whether [target] is still where input goes: [active] has its link, watch epoch and session. */
fun isInputTarget(active: ActiveStream?, target: InputTarget): Boolean =
    active != null && active.linkId == target.linkId && active.watchEpoch == target.watchEpoch &&
        active.sessionGuid == target.sessionGuid

/**
 * The one ordered path for keys and pastes to the Mac: one unbounded queue, one consumer in
 * [scope], so input is sent in the order [key], [paste] and [pasteThenReturn] were called (per
 * calling thread; the calls are thread-safe).
 *
 * - **Stale input.** At dequeue, an item whose target is no longer current ([isCurrent]: another
 *   link, another watch, another session) is discarded, and the running count is logged.
 * - **Bounded.** Each send is bound to its target's link (`onLink`) and to [sendBound]; a send that
 *   times out or fails is logged and skipped, and the queue goes on in order.
 * - **Paste then Return** is one item: its `text "\n"` is sent only after the paste was sent
 *   **and** its target is still current at that moment, so a failed or overtaken paste never
 *   submits.
 *
 * Logs carry link ids, watch epochs, GUIDs, lengths and exception class names: never typed or
 * pasted text, and never an exception's message (it may quote what it failed to send).
 *
 * @param sendBound the bound on each send, 2 s by default.
 */
class InputSender(
    private val link: StreamLink,
    scope: CoroutineScope,
    private val isCurrent: (InputTarget) -> Boolean,
    private val log: (String) -> Unit,
    private val sendBound: Duration = 2.seconds,
) {
    private sealed interface Item {
        val target: InputTarget
        val label: String
    }

    private class KeyItem(override val target: InputTarget, val event: KeyEvent) : Item {
        override val label: String get() = "key $event"
    }

    private class PasteItem(override val target: InputTarget, val text: String, val thenReturn: Boolean) : Item {
        override val label: String get() = "paste (len=${text.length}${if (thenReturn) ", then Return" else ""})"
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)

    /** Items discarded as stale; touched by the consumer only. */
    private var discarded = 0L

    init {
        scope.launch {
            for (item in queue) process(item)
        }
    }

    fun key(target: InputTarget, event: KeyEvent) = enqueue(KeyItem(target, event))

    fun paste(target: InputTarget, text: String) = enqueue(PasteItem(target, text, thenReturn = false))

    fun pasteThenReturn(target: InputTarget, text: String) = enqueue(PasteItem(target, text, thenReturn = true))

    private fun enqueue(item: Item) {
        // Unlimited: this fails only once the consumer's scope has ended.
        if (queue.trySend(item).isFailure) log("input sender stopped; dropped ${item.label}")
    }

    private suspend fun process(item: Item) {
        val target = item.target
        if (!isCurrent(target)) {
            discarded++
            log("discarded stale ${item.label} for ${target.sessionGuid} on link ${target.linkId} watch ${target.watchEpoch}; $discarded discarded in all")
            return
        }
        when (item) {
            is KeyItem -> send(PhoneMessage.SendKey(target.sessionGuid, item.event), target, item.label)
            is PasteItem -> {
                val pasted = send(PhoneMessage.PasteText(target.sessionGuid, item.text), target, item.label)
                if (!item.thenReturn) return
                if (pasted && isCurrent(target)) {
                    send(PhoneMessage.SendKey(target.sessionGuid, RETURN), target, "Return after paste")
                } else {
                    log("Return after paste for ${target.sessionGuid} not sent: ${if (pasted) "the target changed" else "the paste was not sent"}")
                }
            }
        }
    }

    /** Sends [message] on [target]'s link within [sendBound]; false (logged) on a timeout or failure. */
    private suspend fun send(message: PhoneMessage, target: InputTarget, label: String): Boolean = try {
        withTimeout(sendBound) { link.send(message, onLink = target.linkId) }
        true
    } catch (_: TimeoutCancellationException) {
        log("$label for ${target.sessionGuid} on link ${target.linkId} timed out after $sendBound; skipped")
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$label for ${target.sessionGuid} on link ${target.linkId} failed (${e::class.simpleName}); skipped")
        false
    }

    private companion object {
        val RETURN = KeyEvent(Key.Text("\n"))
    }
}
