package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 *   [inputActive] publishes it for the UI.
 * - **Tap** at an encoded-image pixel, origin top-left (`browserTap`); it also stops a fling, as a
 *   tap does in a browser (`IOS/Screens/SessionView.swift:2192-2193`).
 * - **Scroll** feeds a [BrowserScrollAccumulator]; a ticker in [scope] flushes it every
 *   [flushIntervalMillis] (40 ms: 25 Hz, inside iOS's 20-30 Hz display link, 2316-2325) and
 *   queues each whole-pixel step as a `browserScroll`, while there is travel or a fling, then
 *   stops. Travel gathered for one stream is dropped, never sent to another.
 * - **Controls**: [back], [forward], [reload], [stop]; [load] trims the typed text (whitespace and
 *   newlines) and skips it when nothing is left (4706-4710), as `BrowserLoad` itself does not trim.
 * - **Ordered with typed keys.** Every message goes through [input], the one ordered path for
 *   keys (iOS sends both from the main actor in call order), so a key typed straight after a tap
 *   on another field lands in that field. Each is bound to the link, session and stream active at
 *   call time: at dequeue it is discarded unless that stream is still the active browser stream;
 *   each send is bound to its link (`onLink`) and to the sender's 2 s bound, and a failure is
 *   logged and skipped there. The messages carry no request ID; the Mac answers none of them (a
 *   control's result comes back as a `browserNavigationChanged`).
 *
 * [editing] and [navigation] are the controller's [StreamController.browserEditing] and
 * [StreamController.browserNavigation], for the keyboard and the browser controls.
 *
 * Thread-safe. Logs carry stream and link IDs and counts, never a URL, a title or typed text.
 *
 * @param nowMillis a monotonic clock for fling decay; tests pass the scheduler's.
 */
class BrowserDriver(
    private val input: InputSender,
    private val active: StateFlow<ActiveStream?>,
    val editing: StateFlow<Boolean>,
    val navigation: StateFlow<BrowserNavigationState?>,
    private val macRevision: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val flushIntervalMillis: Long = 40,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    /** The stream input belongs to: its link, watch and session ([target]) and its stream ID. */
    private data class StreamKey(val target: InputTarget, val streamID: Long)

    private val lock = Any()
    private val accumulator = BrowserScrollAccumulator()

    /** The stream [accumulator]'s travel belongs to; touched under [lock]. */
    private var scrollKey: StreamKey? = null
    private var ticker: Job? = null

    /**
     * Whether taps, scrolls and controls are sent: a browser stream on a revision 15 Mac. Each
     * call checks the same condition itself at call time, so it never waits for this to update.
     */
    val inputActive: StateFlow<Boolean> = combine(active, macRevision) { stream, revision -> keyOf(stream, revision) != null }
        .stateIn(scope, SharingStarted.Eagerly, activeKey() != null)

    /** Clicks the page at encoded-image pixel ([imageX], [imageY]); stops a fling. */
    fun tap(imageX: Double, imageY: Double) {
        synchronized(lock) { accumulator.stopFling() }
        if (!imageX.isFinite() || !imageY.isFinite()) return
        sendForActive { PhoneMessage.BrowserTap(it, imageX, imageY) }
    }

    /**
     * A drag of ([dxViewPx], [dyViewPx]) view pixels over encoded-image pixel ([atX], [atY]), where
     * [imagePxPerViewPx] is the image's density at zoom 1 (Fit) and [zoom] the current zoom, divided
     * separately; see [BrowserScrollAccumulator.drag].
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
     * encoded-image pixel ([atX], [atY]) coasts, with [imagePxPerViewPx] and [zoom] as in [scroll];
     * see [BrowserScrollAccumulator.fling]. The caller flings only when it should coast (iOS: at
     * zoom 1 and not after a pinch).
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

    /** The active browser stream now, or null when browser input is not active. */
    private fun activeKey(): StreamKey? = keyOf(active.value, macRevision.value)

    private fun keyOf(stream: ActiveStream?, revision: Int): StreamKey? {
        if (stream == null || stream.config?.isBrowser != true || revision < BROWSER_INPUT_REVISION) return null
        return StreamKey(InputTarget(stream.linkId, stream.watchEpoch, stream.sessionGuid), stream.streamID)
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

    /** Flushes one step into [input]; false when there is nothing more to send and the ticker should stop. */
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
            enqueue(key, PhoneMessage.BrowserScroll(key.streamID, step.x, step.y, step.deltaX.toDouble(), step.deltaY.toDouble()))
            return true
        }
        if (accumulator.isFlinging) return true
        ticker = null
        false
    }

    private fun sendForActive(message: (streamID: Long) -> PhoneMessage) {
        val key = activeKey() ?: return
        enqueue(key, message(key.streamID))
    }

    private fun enqueue(key: StreamKey, message: PhoneMessage) {
        input.streamInput(key.target, message) {
            val current = activeKey() == key
            if (!current) log("discarded browser ${message::class.simpleName} for stream ${key.streamID} on link ${key.target.linkId}: not active")
            current
        }
    }
}
