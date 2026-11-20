package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/** At most one scrolling key this often, so WebKit does not fold a burst of them into one step. */
const val DEFAULT_KEY_INTERVAL_MILLIS = 120L

/**
 * How far one arrow key scrolls a page at the [DEFAULT_KEY_INTERVAL_MILLIS] cadence, CSS px:
 * WebKit's line step is 40 px (`Scrollbar::pixelsPerLineStep`), but its animated key scroll is
 * cut short by the next key. Real-Mac run 21: keys 120 ms apart moved 36 px each, 250 ms apart
 * 39.8 px, and keys back to back coalesce.
 */
const val DEFAULT_KEY_LINE_CSS_PX = 36.0

/** How much of the old view a Page Down keeps, CSS px (real-Mac run 19: 571 of a 611 px view, kept in run 21). */
const val DEFAULT_KEY_PAGE_OVERLAP_CSS_PX = 40.0

/**
 * After a tap, drags scroll with `browserScroll` rather than keys for this long, or until the
 * page's editing state changes, so a swipe straight after tapping a text field never sends arrows
 * into it (about one round trip for the Mac's `browserEditingChanged`).
 */
const val DEFAULT_TAP_KEY_HOLD_MILLIS = 300L

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
 * - **Scroll with keys** ([scrollKeys], Settings' "Scroll web pages with keys", read at each
 *   flush): while it holds and the page is not editing text ([editing]: arrows would move the
 *   caret), each flush's vertical travel goes to a [KeyScrollAccumulator] instead, and at most one
 *   scrolling key every [keyIntervalMillis] goes out as a `sendKey` through the same ordered
 *   input ([SpecialKey.UP]/[SpecialKey.DOWN] for a line, [SpecialKey.PAGE_UP]/[SpecialKey.PAGE_DOWN]
 *   for a viewport), so a drag or fling scrolls whatever has focus on the page wherever iTerm2's
 *   window is (its wheel events land off by the window's origin on screen,
 *   `docs/reports/iterm2-companion-browser-scroll-offset.md`). A line is [keyLineCssPx] CSS px and a
 *   page the stream's height less [keyPageOverlapCssPx], at the config's scale. A key is queued as
 *   stream input and withdrawn at dequeue if the page is editing by then. Horizontal travel still
 *   goes as `browserScroll` (no sideways keys), but each gesture locks to one axis, kept for its
 *   fling: set from the travel that took it past the touch slop ([beginDrag]; else its first
 *   travel), biased to vertical (sideways only when more than twice as wide as tall), so a
 *   vertical swipe's sideways wobble sends no misplaced wheel event. A tap or pinch drops the key backlog; so does a touch-down
 *   ([touchDown]) while a fling or keys are still owed, and travel reversing owed keys.
 * - **After a tap** drags use `browserScroll` for [tapKeyHoldMillis] or until [editing] changes,
 *   whichever is first, so a swipe right after tapping a field does not send arrows into it.
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
    private val scrollKeys: () -> Boolean = { false },
    private val keyIntervalMillis: Long = DEFAULT_KEY_INTERVAL_MILLIS,
    private val keyLineCssPx: Double = DEFAULT_KEY_LINE_CSS_PX,
    private val keyPageOverlapCssPx: Double = DEFAULT_KEY_PAGE_OVERLAP_CSS_PX,
    private val tapKeyHoldMillis: Long = DEFAULT_TAP_KEY_HOLD_MILLIS,
    private val log: (String) -> Unit = {},
) {
    /** The stream input belongs to: its link, watch and session ([target]) and its stream ID. */
    private data class StreamKey(val target: InputTarget, val streamID: Long)

    private val lock = Any()
    private val accumulator = BrowserScrollAccumulator()
    private val keyScroll = KeyScrollAccumulator()

    /** When the last scrolling key went out; touched under [lock]. */
    private var lastKeyMillis = Long.MIN_VALUE / 2

    /** Keys wait until then after a tap; touched under [lock]. */
    private var keysHeldUntil = Long.MIN_VALUE

    /** The gesture's axis while keys are on (null: not decided yet); touched under [lock]. */
    private var axis: Axis? = null

    private enum class Axis { X, Y }

    private companion object {
        /** A gesture locks sideways only when its travel is more than this many times as wide as tall. */
        const val AXIS_X_RATIO = 2f
    }

    /** The stream [accumulator]'s travel belongs to; touched under [lock]. */
    private var scrollKey: StreamKey? = null
    private var ticker: Job? = null

    /**
     * Whether taps, scrolls and controls are sent: a browser stream on a revision 15 Mac. Each
     * call checks the same condition itself at call time, so it never waits for this to update.
     */
    val inputActive: StateFlow<Boolean> = combine(active, macRevision) { stream, revision -> keyOf(stream, revision) != null }
        .stateIn(scope, SharingStarted.Eagerly, activeKey() != null)

    init {
        // The page answered the tap (a field focused or left): keys need wait no longer.
        scope.launch { editing.drop(1).collect { synchronized(lock) { keysHeldUntil = Long.MIN_VALUE } } }
    }

    /** Clicks the page at encoded-image pixel ([imageX], [imageY]); stops a fling and holds key scrolling briefly. */
    fun tap(imageX: Double, imageY: Double) {
        synchronized(lock) {
            accumulator.stopFling()
            keyScroll.cancel()
            axis = null
            keysHeldUntil = nowMillis() + tapKeyHoldMillis
        }
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
            val (dx, dy) = locked(dxViewPx, dyViewPx)
            accumulator.drag(dx, dy, imagePxPerViewPx, zoom, atX, atY)
            startTicker()
        }
    }

    /**
     * A finger went down on the page: a fling stops (as touching a coasting list does), and keys
     * still owed are dropped when it was coasting or a whole key is owed, so a reversal never
     * keeps paging the old way; the next drag decides its own axis.
     */
    fun touchDown() {
        synchronized(lock) {
            val wasFlinging = accumulator.isFlinging
            accumulator.stopFling()
            val line = keySizes()?.first
            if (wasFlinging || line == null || keyScroll.hasWork(line)) keyScroll.cancel()
            axis = null
        }
    }

    /**
     * A release at ([velocityXViewPxPerSec], [velocityYViewPxPerSec]) view px/s over
     * encoded-image pixel ([atX], [atY]) coasts, with [imagePxPerViewPx] and [zoom] as in [scroll];
     * see [BrowserScrollAccumulator.fling], on a screen of [densityPxPerDp] px per dp. The caller
     * flings only when it should coast (iOS: at zoom 1 and not after a pinch).
     */
    fun fling(
        velocityXViewPxPerSec: Float,
        velocityYViewPxPerSec: Float,
        imagePxPerViewPx: Float,
        zoom: Float,
        atX: Float,
        atY: Float,
        densityPxPerDp: Float = 1f,
    ) {
        synchronized(lock) {
            if (!claimScroll()) return
            val (vx, vy) = locked(velocityXViewPxPerSec, velocityYViewPxPerSec)
            accumulator.at(atX, atY)
            accumulator.fling(vx, vy, imagePxPerViewPx, zoom, nowMillis(), densityPxPerDp)
            startTicker()
        }
    }

    /**
     * A drag began, having travelled ([slopDx], [slopDy]) view px to pass the touch slop: while
     * keys may scroll, the gesture locks to its axis from that travel ([axisOf]).
     */
    fun beginDrag(slopDx: Float, slopDy: Float) {
        synchronized(lock) {
            if (!scrollKeys() || editing.value) return
            axis = axisOf(slopDx, slopDy)
        }
    }

    /** The axis of travel ([dx], [dy]): vertical unless more than twice as wide as tall; null for none. */
    private fun axisOf(dx: Float, dy: Float): Axis? = when {
        !dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f) -> null
        abs(dx) > AXIS_X_RATIO * abs(dy) -> Axis.X
        else -> Axis.Y
    }

    /**
     * ([dx], [dy]) with the gesture's minor axis dropped while keys may scroll (the switch on and
     * the page not editing): the first travel of a gesture locks it to its dominant axis. Under [lock].
     */
    private fun locked(dx: Float, dy: Float): Pair<Float, Float> {
        if (!scrollKeys() || editing.value) return dx to dy
        val decided = axis ?: axisOf(dx, dy).also { axis = it }
        return when (decided) {
            Axis.Y -> 0f to dy
            Axis.X -> dx to 0f
            null -> dx to dy
        }
    }

    /** Stops a fling and sends nothing (a tap off the page: it clicks nothing but stops the coast, as iOS's tap). */
    fun stopFling() {
        synchronized(lock) {
            accumulator.stopFling()
            keyScroll.cancel()
        }
    }

    /** Drops the travel not yet sent and any fling (a pinch began). */
    fun cancelScroll() {
        synchronized(lock) {
            accumulator.cancel()
            keyScroll.cancel()
        }
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
            keyScroll.cancel()
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
            keyScroll.cancel()
            scrollKey = key
            ticker = null
            return false
        }
        val now = nowMillis()
        val step = accumulator.flush(now)
        val sizes = keySizes()
        if (sizes == null) {
            keyScroll.cancel()
            if (step != null) enqueue(key, PhoneMessage.BrowserScroll(key.streamID, step.x, step.y, step.deltaX.toDouble(), step.deltaY.toDouble()))
        } else {
            val (line, page) = sizes
            if (step != null) {
                keyScroll.add(step.deltaY.toDouble(), line, page)
                if (step.deltaX != 0) enqueue(key, PhoneMessage.BrowserScroll(key.streamID, step.x, step.y, step.deltaX.toDouble(), 0.0))
            }
            if (now - lastKeyMillis >= keyIntervalMillis) {
                keyScroll.next(line, page)?.let { next ->
                    lastKeyMillis = now
                    enqueueKey(key, next)
                }
            }
            if (keyScroll.hasWork(line)) return true
        }
        if (step != null || accumulator.isFlinging) return true
        ticker = null
        false
    }

    /**
     * A key line and page in encoded px while drags scroll with keys ([scrollKeys], the page not
     * editing text, no tap waiting for its answer, a config with a height); null for
     * `browserScroll`. Under [lock].
     */
    private fun keySizes(): Pair<Double, Double>? {
        if (!scrollKeys() || editing.value || nowMillis() < keysHeldUntil) return null
        val config = active.value?.config ?: return null
        if (config.pixelHeight <= 0) return null
        val scale = config.scale.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val line = keyLineCssPx * scale
        return line to maxOf(line, config.pixelHeight - keyPageOverlapCssPx * scale)
    }

    private fun ScrollKey.special(): SpecialKey = when (this) {
        ScrollKey.LINE_UP -> SpecialKey.UP
        ScrollKey.LINE_DOWN -> SpecialKey.DOWN
        ScrollKey.PAGE_UP -> SpecialKey.PAGE_UP
        ScrollKey.PAGE_DOWN -> SpecialKey.PAGE_DOWN
    }

    private fun sendForActive(message: (streamID: Long) -> PhoneMessage) {
        val key = activeKey() ?: return
        enqueue(key, message(key.streamID))
    }

    /**
     * A scrolling key for the page, in order with everything else; withdrawn at dequeue when the
     * stream is no longer active or the page is editing text by then (a tap focused a field).
     */
    private fun enqueueKey(key: StreamKey, scrollKey: ScrollKey) {
        val message = PhoneMessage.SendKey(key.target.sessionGuid, KeyEvent(Key.Special(scrollKey.special())))
        input.streamInput(key.target, message) {
            val current = activeKey() == key
            val editingNow = editing.value
            if (!current || editingNow) log("withdrew a scrolling key for stream ${key.streamID}: ${if (current) "the page is editing" else "not active"}")
            current && !editingNow
        }
    }

    private fun enqueue(key: StreamKey, message: PhoneMessage) {
        input.streamInput(key.target, message) {
            val current = activeKey() == key
            if (!current) log("discarded browser ${message::class.simpleName} for stream ${key.streamID} on link ${key.target.linkId}: not active")
            current
        }
    }
}
