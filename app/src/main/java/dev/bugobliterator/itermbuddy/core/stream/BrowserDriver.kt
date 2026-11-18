package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Mac revision that takes browser taps, scrolls and controls (upstream `browserInputRevision`). */
const val BROWSER_INPUT_REVISION = 15

/**
 * Taps, scrolls and the browser controls for a browser stream, as iOS sends them
 * (`IOS/App/AppModel.swift:4661-4715`; the Mac handles them in
 * `MC/CompanionHostBridge.swift:587-603, 724-743`).
 *
 * - **Active only** while the active stream's config says it is a browser and the Mac's hello
 *   revision ([macRevision]) is at least [BROWSER_INPUT_REVISION] (iOS `browserInputActive`,
 *   4663-4672); otherwise every call is a no-op. Below 15 a browser stream is view-only.
 * - **Tap** at an encoded-image pixel, origin top-left (`browserTap`); it also stops a fling, as a
 *   tap does in a browser (`IOS/Screens/SessionView.swift:2192-2193`).
 * - **Scroll** feeds a [BrowserScrollAccumulator]; a ticker in [scope] flushes it every
 *   [flushIntervalMillis] (40 ms: 25 Hz, inside iOS's 20-30 Hz display link, 2316-2325) and
 *   sends each whole-pixel step as a `browserScroll`, while there is travel or a fling, then
 *   stops. Travel gathered for one stream is dropped, never sent to another.
 * - **Controls**: [back], [forward], [reload], [stop]; [load] trims the typed text (whitespace and
 *   newlines) and skips it when nothing is left (4706-4710), as `BrowserLoad` itself does not trim.
 * - **Ordered.** Every message goes through one queue and one consumer in [scope], bound to the
 *   link and stream ID active at call time (`onLink`) and to [sendBound]; at dequeue an item whose
 *   stream is no longer the active one is discarded, and a failure is logged and skipped. The
 *   messages carry no request ID; the Mac answers none of them (a control's result comes back as
 *   a `browserNavigationChanged`).
 *
 * [editing] and [navigation] are the controller's [StreamController.browserEditing] and
 * [StreamController.browserNavigation], for the keyboard and the browser controls.
 *
 * Thread-safe. Logs carry stream and link IDs, counts and lengths, never a URL, a title or typed
 * text.
 *
 * @param nowMillis a monotonic clock for fling decay; tests pass the scheduler's.
 */
class BrowserDriver(
    private val link: StreamLink,
    private val active: StateFlow<ActiveStream?>,
    val editing: StateFlow<Boolean>,
    val navigation: StateFlow<BrowserNavigationState?>,
    private val macRevision: () -> Int,
    private val scope: CoroutineScope,
    private val flushIntervalMillis: Long = 40,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    private val sendBound: Duration = 2.seconds,
) {
    /** The stream an item belongs to. */
    private data class StreamKey(val linkId: Long, val streamID: Long)

    private class Send(val key: StreamKey, val message: PhoneMessage)

    private val lock = Any()
    private val queue = Channel<Send>(Channel.UNLIMITED)
    private val accumulator = BrowserScrollAccumulator()

    /** The stream [accumulator]'s travel belongs to; touched under [lock]. */
    private var scrollKey: StreamKey? = null
    private var ticker: Job? = null

    init {
        scope.launch {
            for (item in queue) process(item)
        }
    }

    /** Whether taps, scrolls and controls are sent now: a browser stream on a revision 15 Mac. */
    val inputActive: Boolean get() = activeKey() != null

    /** Clicks the page at encoded-image pixel ([imageX], [imageY]); stops a fling. */
    fun tap(imageX: Double, imageY: Double) {
        synchronized(lock) { accumulator.stopFling() }
        if (!imageX.isFinite() || !imageY.isFinite()) return
        sendForActive { PhoneMessage.BrowserTap(it, imageX, imageY) }
    }

    /**
     * A drag of ([dxViewPx], [dyViewPx]) view pixels over encoded-image pixel ([atX], [atY]); see
     * [BrowserScrollAccumulator.drag].
     */
    fun scroll(dxViewPx: Float, dyViewPx: Float, imagePxPerViewPx: Float, zoom: Float, atX: Float, atY: Float) {
        synchronized(lock) {
            if (!claimScroll()) return
            accumulator.drag(dxViewPx, dyViewPx, imagePxPerViewPx, zoom, atX, atY)
            startTicker()
        }
    }

    /**
     * A release at ([velocityXViewPxPerSec], [velocityYViewPxPerSec]) view px/s over
     * encoded-image pixel ([atX], [atY]) coasts; see [BrowserScrollAccumulator.fling]. The caller
     * flings only when it should coast (iOS: at zoom 1 and not after a pinch).
     */
    fun fling(
        velocityXViewPxPerSec: Float,
        velocityYViewPxPerSec: Float,
        imagePxPerViewPx: Float,
        zoom: Float,
        atX: Float,
        atY: Float,
    ) {
        synchronized(lock) {
            if (!claimScroll()) return
            accumulator.at(atX, atY)
            accumulator.fling(velocityXViewPxPerSec, velocityYViewPxPerSec, imagePxPerViewPx, zoom, nowMillis())
            startTicker()
        }
    }

    /** Drops the travel not yet sent and any fling (a pinch began). */
    fun cancelScroll() {
        synchronized(lock) { accumulator.cancel() }
    }

    fun back() = sendForActive { PhoneMessage.BrowserGoBack(it) }

    fun forward() = sendForActive { PhoneMessage.BrowserGoForward(it) }

    fun reload() = sendForActive { PhoneMessage.BrowserReload(it) }

    fun stop() = sendForActive { PhoneMessage.BrowserStopLoading(it) }

    /** Opens what was typed in the address field (the Mac loads a URL or searches); trimmed, skipped when empty. */
    fun load(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendForActive { PhoneMessage.BrowserLoad(it, trimmed) }
    }

    /** The active browser stream, or null when browser input is not active. */
    private fun activeKey(): StreamKey? {
        val stream = active.value ?: return null
        if (stream.config?.isBrowser != true || macRevision() < BROWSER_INPUT_REVISION) return null
        return StreamKey(stream.linkId, stream.streamID)
    }

    /** Binds the accumulator to the active stream, dropping travel for another; false when inactive. Under [lock]. */
    private fun claimScroll(): Boolean {
        val key = activeKey()
        if (key != scrollKey) {
            accumulator.cancel()
            scrollKey = key
        }
        return key != null
    }

    /** Under [lock]. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(flushIntervalMillis)
                if (!tick()) break
            }
        }
    }

    /** Flushes one step; false when there is nothing more to send and the ticker should stop. */
    private fun tick(): Boolean = synchronized(lock) {
        val key = activeKey()
        if (key == null || key != scrollKey) {
            if (accumulator.isFlinging) log("browser scroll for stream ${scrollKey?.streamID} dropped: not active")
            accumulator.cancel()
            scrollKey = key
            ticker = null
            return false
        }
        val step = accumulator.flush(nowMillis())
        if (step != null) {
            enqueue(Send(key, PhoneMessage.BrowserScroll(key.streamID, step.x, step.y, step.deltaX.toDouble(), step.deltaY.toDouble())))
            return true
        }
        if (accumulator.isFlinging) return true
        ticker = null
        false
    }

    private fun sendForActive(message: (streamID: Long) -> PhoneMessage) {
        val key = activeKey() ?: return
        enqueue(Send(key, message(key.streamID)))
    }

    private fun enqueue(item: Send) {
        if (queue.trySend(item).isFailure) log("browser driver stopped; dropped ${item.message::class.simpleName}")
    }

    private suspend fun process(item: Send) {
        val name = item.message::class.simpleName
        if (activeKey() != item.key) {
            log("discarded browser $name for stream ${item.key.streamID} on link ${item.key.linkId}: not active")
            return
        }
        try {
            withTimeout(sendBound) { link.send(item.message, onLink = item.key.linkId) }
        } catch (_: TimeoutCancellationException) {
            log("browser $name for stream ${item.key.streamID} timed out after $sendBound; skipped")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("browser $name for stream ${item.key.streamID} on link ${item.key.linkId} failed (${e::class.simpleName}); skipped")
        }
    }
}
