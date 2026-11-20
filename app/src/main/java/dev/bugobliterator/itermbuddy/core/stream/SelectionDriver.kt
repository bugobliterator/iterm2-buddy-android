package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The live view's terminal selection on the Mac, as iOS drives it (`IOS/App/AppModel.swift:4752-4810,
 * 5070-5110`): gestures, Select All, Clear and Copy, all sent in call order through one queue and
 * one consumer in [scope] (iOS chains them, `sendOrderedSelection`, 4789-4797), so a clear never
 * overtakes a drag's end and a copy follows the selection it copies.
 *
 * - **Gestures.** [begin] captures the active stream (its link and stream ID) and [mode] for the
 *   whole gesture; [move] and [end] carry that mode (the Mac reads it on every phase,
 *   `CompanionHostBridge.swift:818`) and are dropped when there was no [begin] or the active stream
 *   is no longer the one captured. A [move] to the point last sent is dropped (iOS,
 *   `AppModel.swift:4772-4775`); [end] always goes and finishes the gesture.
 * - **Select All and Clear** use the active stream at call time; nothing is sent without one.
 * - **Stale items.** At dequeue an item whose stream is no longer the active one (another link or
 *   stream ID) is discarded, and each send is bound to its link (`onLink`) and to [sendBound]; a
 *   failure is logged and the queue goes on.
 * - **Failed begin.** When a gesture's begin fails or times out, its moves and end are dropped too.
 * - **Copy** asks the Mac for the selection of a session by GUID, on the active link, and answers
 *   its text (`""` when nothing is selected), or null on an error reply, a failure, no active
 *   stream, or no reply within [copyTimeout] or the call's own bound (the request's own bound, so the
 *   queue waits no longer). [copyToClipboard] does it in
 *   [scope], so it outlives the screen (a rotation), hands non-empty text to a clipboard writer and
 *   reports the [CopyOutcome] in [copyNotices] for the screen to show when it is there.
 *
 * [range] is the selection as the Mac reports it ([StreamController.selection]); the tiles it
 * invalidates are the history store's job. The calls are thread-safe. Logs carry counts, stream
 * and link IDs and exception class names, never selection text or points.
 */
class SelectionDriver(
    private val link: StreamLink,
    private val active: StateFlow<ActiveStream?>,
    selection: StateFlow<SelectionSpan?>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val sendBound: Duration = 2.seconds,
    private val copyTimeout: Duration = 15.seconds,
) {
    /** The active stream's selection as the Mac last reported it; null when none. */
    val range: StateFlow<SelectionSpan?> = selection

    /** The stream an item belongs to. */
    private data class StreamKey(val linkId: Long, val streamID: Long)

    private sealed interface Item

    /** [gesture] is the gesture the message belongs to, or null for Select All and Clear. */
    private class Send(val key: StreamKey, val message: PhoneMessage, val gesture: Long? = null) : Item

    private class Copy(val linkId: Long, val sessionGuid: String, val timeout: Duration, val result: CompletableDeferred<String?>) : Item

    /** The gesture in progress: its stream, mode and the last point sent. */
    private class Gesture(val id: Long, val key: StreamKey, val mode: SelectionMode, var lastSent: SelectionPoint)

    private val lock = Any()
    private var gesture: Gesture? = null
    private var nextGestureId = 1L
    private val queue = Channel<Item>(Channel.UNLIMITED)

    /** The gesture whose begin failed; touched by the consumer only. */
    private var failedGesture: Long? = null

    private val notices = MutableStateFlow<CopyNotice?>(null)
    private var nextNoticeId = 1L

    /** The latest [copyToClipboard] outcome not yet [noticeShown]; it carries no text. */
    val copyNotices: StateFlow<CopyNotice?> = notices.asStateFlow()

    init {
        scope.launch {
            for (item in queue) process(item)
        }
    }

    /** Starts a gesture at [point] on the active stream; false (nothing sent) without one. */
    fun begin(point: SelectionPoint, mode: SelectionMode = SelectionMode.CHARACTER): Boolean {
        synchronized(lock) {
            val key = activeKey()
            if (key == null) {
                gesture = null
                return false
            }
            val started = Gesture(nextGestureId++, key, mode, point)
            gesture = started
            enqueue(Send(key, PhoneMessage.SelectionGesture(key.streamID, SelectionPhase.BEGIN, mode, point), started.id))
            return true
        }
    }

    /** Extends the gesture to [point]; dropped for a repeat of the last point or a changed stream. */
    fun move(point: SelectionPoint) {
        synchronized(lock) {
            val current = currentGesture() ?: return
            if (point == current.lastSent) return
            current.lastSent = point
            enqueue(Send(current.key, PhoneMessage.SelectionGesture(current.key.streamID, SelectionPhase.MOVE, current.mode, point), current.id))
        }
    }

    /** Finishes the gesture at [point]; dropped when the stream changed since [begin]. */
    fun end(point: SelectionPoint) {
        synchronized(lock) {
            val current = currentGesture() ?: return
            gesture = null
            enqueue(Send(current.key, PhoneMessage.SelectionGesture(current.key.streamID, SelectionPhase.END, current.mode, point), current.id))
        }
    }

    /** Selects the whole session on the active stream. */
    fun selectAll() = sendForActive { PhoneMessage.SelectAllInStream(it) }

    /** Clears the selection on the active stream. */
    fun clear() = sendForActive { PhoneMessage.ClearSelection(it) }

    /**
     * The selection of session [sessionGuid] as text, after everything queued before it: `""` when
     * nothing is selected, null on an error, a failure, no active stream or no reply within
     * [timeout] (the request's own bound, so a late reply holds the queue no longer than that).
     */
    suspend fun copy(sessionGuid: String, timeout: Duration = copyTimeout): String? = enqueueCopy(sessionGuid, timeout)?.await()

    /**
     * How many gestures have begun so far: a caller that selects and later clears can tell
     * whether someone else began a selection meanwhile (a long press), and leave that one be.
     */
    fun gesturesBegun(): Long = synchronized(lock) { nextGestureId - 1 }

    /** Queues a copy now, in call order with the sends; null (nothing queued) without an active stream. */
    private fun enqueueCopy(sessionGuid: String, timeout: Duration = copyTimeout): CompletableDeferred<String?>? {
        val linkId = active.value?.linkId ?: return null
        val result = CompletableDeferred<String?>()
        enqueue(Copy(linkId, sessionGuid, timeout, result))
        return result
    }

    /**
     * [copy] in [scope]: non-empty text goes to [clipboard] (never logged), and the outcome to
     * [copyNotices].
     */
    fun copyToClipboard(sessionGuid: String, clipboard: (String) -> Unit) {
        // Queued at call time, so a Clear or Select All called after it cannot overtake it.
        val pending = enqueueCopy(sessionGuid)
        scope.launch {
            val text = pending?.await()
            val outcome = copyOutcome(text)
            if (outcome == CopyOutcome.COPIED && text != null) {
                try {
                    clipboard(text)
                } catch (e: Exception) {
                    log("clipboard write failed (${e::class.simpleName})")
                    notices.value = CopyNotice(nextId(), sessionGuid, CopyOutcome.FAILED)
                    return@launch
                }
            }
            notices.value = CopyNotice(nextId(), sessionGuid, outcome)
        }
    }

    /** The screen has shown [notice]. */
    fun noticeShown(notice: CopyNotice) {
        notices.compareAndSet(notice, null)
    }

    private fun nextId(): Long = synchronized(lock) { nextNoticeId++ }

    private fun sendForActive(message: (streamID: Long) -> PhoneMessage) {
        synchronized(lock) {
            val key = activeKey() ?: return
            enqueue(Send(key, message(key.streamID)))
        }
    }

    /** The gesture in progress, or null (forgotten) when the active stream is not its own. */
    private fun currentGesture(): Gesture? {
        val current = gesture ?: return null
        if (activeKey() != current.key) {
            gesture = null
            log("selection gesture on stream ${current.key.streamID} link ${current.key.linkId} dropped: the stream changed")
            return null
        }
        return current
    }

    private fun activeKey(): StreamKey? = active.value?.let { StreamKey(it.linkId, it.streamID) }

    private fun enqueue(item: Item) {
        if (queue.trySend(item).isFailure) {
            log("selection driver stopped; dropped an item")
            if (item is Copy) item.result.complete(null)
        }
    }

    private suspend fun process(item: Item) {
        when (item) {
            is Send -> {
                if (item.gesture != null && item.gesture == failedGesture) return
                if (activeKey() != item.key) {
                    log("discarded selection ${item.message::class.simpleName} for stream ${item.key.streamID} on link ${item.key.linkId}: not active")
                    return
                }
                if (!send(item) && item.message is PhoneMessage.SelectionGesture && item.message.phase == SelectionPhase.BEGIN) {
                    failedGesture = item.gesture
                }
            }
            is Copy -> item.result.complete(copy(item))
        }
    }

    /** Sends [item] within [sendBound]; false (logged) on a timeout or failure. */
    private suspend fun send(item: Send): Boolean {
        val name = item.message::class.simpleName
        return try {
            withTimeout(sendBound) { link.send(item.message, onLink = item.key.linkId) }
            true
        } catch (_: TimeoutCancellationException) {
            log("selection $name for stream ${item.key.streamID} timed out after $sendBound; skipped")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("selection $name for stream ${item.key.streamID} on link ${item.key.linkId} failed (${e::class.simpleName}); skipped")
            false
        }
    }

    private suspend fun copy(item: Copy): String? = try {
        val reply = link.request(PhoneMessage.CopySelection(item.sessionGuid), onLink = item.linkId, timeout = item.timeout)
        when (reply) {
            is MacMessage.SelectionText -> {
                log("copy for ${item.sessionGuid}: ${reply.text.length} chars")
                reply.text
            }
            else -> {
                log("copy for ${item.sessionGuid} answered ${reply::class.simpleName}")
                null
            }
        }
    } catch (e: CancellationException) {
        item.result.complete(null)
        throw e
    } catch (e: Exception) {
        log("copy for ${item.sessionGuid} failed (${e::class.simpleName})")
        null
    }
}

/** What a Copy came to. */
enum class CopyOutcome {
    /** On the clipboard; Android 13+ confirms it itself, so nothing more is shown. */
    COPIED,

    /** The Mac had no selection (`""`: iOS skips the clipboard for it, `AppModel.swift:5096-5103`). */
    NOTHING_SELECTED,

    /** An error, a failure or no reply. */
    FAILED,
}

/** [SelectionDriver.copy]'s answer as a [CopyOutcome]. */
fun copyOutcome(text: String?): CopyOutcome = when {
    text == null -> CopyOutcome.FAILED
    text.isEmpty() -> CopyOutcome.NOTHING_SELECTED
    else -> CopyOutcome.COPIED
}

/** One [SelectionDriver.copyToClipboard] outcome for session [sessionGuid], [id] telling repeats apart. Holds no text. */
data class CopyNotice(val id: Long, val sessionGuid: String, val outcome: CopyOutcome)
