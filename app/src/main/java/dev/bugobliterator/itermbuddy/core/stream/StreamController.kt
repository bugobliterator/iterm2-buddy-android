package dev.bugobliterator.itermbuddy.core.stream

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every bound and interval the [StreamController] uses, injectable for virtual-time tests.
 *
 * @property start bound on one `startSessionStream` request (the client may extend it to at most
 *   twice this while frames are queued behind a busy sink).
 * @property stallAfter a frame accepted by the decoder and not output for this long labels the
 *   picture stalled.
 * @property keyframeRequestGap at most one `requestKeyframe` per this interval (Android-only).
 * @property stallPoll (addition) how often a stalled picture is checked for recovery: the
 *   [VideoOutput] has no output callback, so clearing the label is polled while it shows.
 * @property keyframeGiveUp (addition) how long keyframe requests may follow one another (each
 *   within four [keyframeRequestGap]s of the last) with no frame accepted by the output before
 *   the stream is given up: stopped, and [LiveProblem.StartFailed] (retryable) shown. Bounds the
 *   loops a stream the output can never take would cause: a config whose parameter sets never
 *   complete, or keyframes over the decoder's largest input, each answered by another request.
 */
data class StreamTimeouts(
    val start: Duration = 15.seconds,
    val stallAfter: Duration = 1.seconds,
    val keyframeRequestGap: Duration = 500.milliseconds,
    val stallPoll: Duration = 250.milliseconds,
    val keyframeGiveUp: Duration = 10.seconds,
)

/**
 * The controller's inbox bound: [inboxItems] inputs **and** [inboxMediaBytes] bytes of media,
 * whichever fills first. Default 256 items and 32 MiB.
 */
data class StreamBudgets(val inboxItems: Int = 256, val inboxMediaBytes: Long = DEFAULT_INBOX_MEDIA_BYTES)

/**
 * The adopted stream: [streamID] on link [linkId] for the session [sessionGuid], adopted under the
 * watch intent [watchEpoch]. [config] is the newest `streamConfig` (kept whatever the decoder made
 * of it, so Fit has it on TooLarge); [liveTop] is the absolute line at the top of the live screen.
 * Holds no terminal content.
 */
data class ActiveStream(
    val linkId: Long,
    val streamID: Long,
    val sessionGuid: String,
    val watchEpoch: Long,
    val config: StreamConfig?,
    val liveTop: Long?,
)

/**
 * The live session's stream controller: the watch intent, start serialisation, link scoping, acks,
 * keyframe requests and the stalled label, mirroring the iOS client
 * (`IOS/App/AppModel.swift:4360-4660`, `IOS/Screens/SessionView.swift:955-1000`).
 *
 * **Threading.** [accept] only enqueues into a bounded inbox (see [StreamBudgets]) and returns; it
 * suspends while the inbox is full and never drops. One consumer, on [scope]'s dispatcher limited
 * to one coroutine at a time, applies every input and every call ([open], [close], [pause],
 * [resume], [retry], keyframe requests from the [VideoOutput], start outcomes, stall checks) in
 * arrival order; all state is touched only there. The consumer never awaits a Mac reply: the
 * `startSessionStream` request runs in a child coroutine that posts its outcome back to the
 * inbox, and stops and keyframe requests go through one ordered sender coroutine. Acks go through
 * an [AckSender].
 *
 * **Inbox.** An input takes one of [StreamBudgets.inboxItems] slots and, for media, its size in
 * bytes, from when [accept] admits it until the consumer has finished with it. An input is always
 * admitted into an empty inbox, so a frame over [StreamBudgets.inboxMediaBytes] waits only for the
 * inbox to drain; an input with no media bytes needs only a free slot. Calls and internal events
 * take no slot.
 *
 * **Link scoping.** State is keyed by link id: media and control are applied only for the current
 * link (the newest [StreamInput.LinkUp]); a newer LinkUp supersedes everything older even when a
 * LinkDown was missed; a LinkDown for any other link is ignored. Nothing is sent on a link that
 * has ended. A cancelled [accept] may or may not have been applied; the link is ending then.
 *
 * **Adoption.** At most one start is in flight, bound to its link and request ID. A
 * `streamStarted` is adopted only when its link and request ID match that start, the watched
 * session is still the one started, the app is foreground and no stream is active (the iOS
 * guard, `AppModel.swift:4598-4603`); every other `streamStarted` on the current link is stopped,
 * as is an adopted one whose codec is not HEVC.
 *
 * **Recovery.** A frame that does not parse is acked when its header gives this stream's pts,
 * and a keyframe is requested. A keyframe request held back by the rate limit is deferred to the
 * end of the gap and re-checked then, never dropped. Every [VideoOutput] call is guarded.
 *
 * **Stalled (Android-only).** While Live, the picture is labelled stalled once a frame accepted by
 * the output has not been output for `stallAfter`, or once the output has refused every frame for
 * `stallAfter` while Ready (MediaCodec can do either; iOS VideoToolbox outputs or fails).
 *
 * @param nowMillis a monotonic clock in milliseconds; the [VideoOutput] must use the same one for
 *   [VideoOutput.outputOverdueSinceMillis].
 * @param log diagnostics; given GUIDs, stream and link IDs, sizes and counts, never terminal
 *   content or session names.
 * @param ackContext where the [AckSender] runs: a background dispatcher by default, so acks never
 *   depend on [scope]'s dispatcher (which may be the main thread); tests pass their own.
 */
class StreamController(
    private val link: StreamLink,
    private val video: VideoOutput,
    private val scope: CoroutineScope,
    private val timeouts: StreamTimeouts = StreamTimeouts(),
    private val budgets: StreamBudgets = StreamBudgets(),
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit,
    ackContext: CoroutineContext = Dispatchers.Default,
    tileTimeouts: TileTimeouts = TileTimeouts(),
    tileEncodedBudgetBytes: Long = DEFAULT_ENCODED_TILE_BYTES,
) : StreamSink {
    private val stateFlow = MutableStateFlow<LiveViewState>(LiveViewState.Closed)

    /** What the live view shows. */
    val state: StateFlow<LiveViewState> = stateFlow.asStateFlow()

    private val activeFlow = MutableStateFlow<ActiveStream?>(null)

    /** The adopted stream, or null. */
    val activeStream: StateFlow<ActiveStream?> = activeFlow.asStateFlow()

    /**
     * The active stream's history tiles. The controller forwards its configs, extents, selection
     * ranges and live tops, and clears it whenever the active stream ends or is replaced, before
     * any input of the next stream (iOS `neutralizeDeadStream`, `AppModel.swift:4445-4447, 4534-4553`).
     */
    val historyTiles = HistoryTileStore(link, scope, tileTimeouts, tileEncodedBudgetBytes, log)

    /** The active stream's selection as the Mac reports it; null when none or no stream. */
    val selection: StateFlow<SelectionSpan?> = historyTiles.selection

    // ---- Inbox ----

    private sealed interface Item

    /** A sink input holding one slot and [bytes] of media budget until the consumer is done with it. */
    private class Incoming(val input: StreamInput, val bytes: Long) : Item

    private sealed interface Event : Item {
        class Open(val session: SessionSummary) : Event

        data object Close : Event

        data object Pause : Event

        data object Resume : Event

        data object Retry : Event

        data object KeyframeNeeded : Event

        data object StallCheck : Event

        class StartDone(val attempt: Attempt, val outcome: StartOutcome) : Event
    }

    private data class Usage(val items: Int, val bytes: Long)

    private val usage = MutableStateFlow(Usage(0, 0))

    private val items = Channel<Item>(Channel.UNLIMITED)

    /** The most inputs the inbox has held at once, for tests that need its bound reached. */
    internal val inboxHighWater = AtomicInteger()

    // ---- Outbox: stops and keyframe requests in order, and start requests launched in turn ----

    private sealed interface Out {
        class Send(val message: PhoneMessage, val linkId: Long) : Out

        class Start(val attempt: Attempt) : Out
    }

    private val outbox = Channel<Out>(Channel.UNLIMITED)

    /** The consumer's dispatcher: [scope]'s own, one coroutine at a time. */
    private val serial: CoroutineDispatcher =
        (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default).limitedParallelism(1)

    /**
     * On [ackContext] (a background dispatcher by default), never [serial]: acks keep going out
     * while the consumer is busy in a submit, whatever dispatcher [scope] has.
     */
    private val acks = AckSender(link, CoroutineScope(scope.coroutineContext + ackContext))

    // ---- State, touched only by the consumer ----

    /** The watch intent: [session] is watched; [epoch] is bumped by every open and close. */
    private class Intent(val session: SessionSummary, val epoch: Long)

    /** The one start in flight: on [linkId] for [guid]; [requestID] is set before the envelope is written. */
    private class Attempt(val linkId: Long, val guid: String) {
        @Volatile var requestID: Long? = null

        /** Whether its `streamStarted` has been seen and dealt with (adopted or stopped). */
        var resolved = false
    }

    private sealed interface StartOutcome {
        class Replied(val message: MacMessage) : StartOutcome

        class Threw(val error: Exception) : StartOutcome
    }

    private class Active(
        val linkId: Long,
        val streamID: Long,
        val session: SessionSummary,
        val epoch: Long,
        var config: StreamConfig? = null,
        var liveTop: Long? = null,
    )

    private var intent: Intent? = null
    private var epoch = 0L
    private var foreground = true
    private var currentLink: Long? = null
    private var newestLink = 0L
    private var attempt: Attempt? = null
    private var active: Active? = null
    private var lastKeyframeRequestAt: Long? = null
    private var deferredKeyframe: Job? = null

    /** When the output began refusing every frame while Ready (Android-only stall cause), or null. */
    private var rejectingSince: Long? = null

    /** When the current run of keyframe requests with no frame accepted in between began, or null. */
    private var requestRunSince: Long? = null
    private var stallTimer: Job? = null
    private var stallTimerAt = Long.MAX_VALUE

    init {
        video.setKeyframeRequester { post(Event.KeyframeNeeded) }
        scope.launch(serial) {
            for (item in items) {
                try {
                    handle(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("stream controller failed on ${item.kind()}: ${e.javaClass.simpleName}")
                } finally {
                    if (item is Incoming) usage.update { Usage(it.items - 1, it.bytes - item.bytes) }
                }
            }
        }
        scope.launch(serial) {
            for (out in outbox) {
                when (out) {
                    is Out.Send -> try {
                        link.send(out.message, onLink = out.linkId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("${out.message} was not sent on link ${out.linkId}: ${e.javaClass.simpleName}")
                    }
                    is Out.Start -> scope.launch(serial) { runStart(out.attempt) }
                }
            }
        }
    }

    /**
     * Enqueues [input] and returns; suspends while the inbox is full (never drops). Never waits
     * for the Mac and never throws a cancellation of its own.
     */
    override suspend fun accept(input: StreamInput) {
        val bytes = if (input is StreamInput.Media) input.size.toLong() else 0L
        reserve(bytes)
        // No suspension between the reservation and the send: an UNLIMITED channel never refuses.
        items.trySend(Incoming(input, bytes))
    }

    private suspend fun reserve(bytes: Long) {
        while (true) {
            val seen = usage.value.takeIf { fits(it, bytes) } ?: usage.first { fits(it, bytes) }
            if (usage.compareAndSet(seen, Usage(seen.items + 1, seen.bytes + bytes))) {
                inboxHighWater.accumulateAndGet(seen.items + 1, ::maxOf)
                return
            }
        }
    }

    private fun fits(now: Usage, bytes: Long): Boolean =
        now.items == 0 || (now.items < budgets.inboxItems && (bytes == 0L || now.bytes + bytes <= budgets.inboxMediaBytes))

    /** Watches [session]; a no-op when it is already watched and not ended or failed. */
    fun open(session: SessionSummary) = post(Event.Open(session))

    /** Stops the stream and drops the intent (Back). */
    fun close() = post(Event.Close)

    /** Backgrounded: stops the stream and keeps the intent. */
    fun pause() = post(Event.Pause)

    /** Foregrounded: starts the watched session when a link is up. */
    fun resume() = post(Event.Resume)

    /** Stops any active stream, resets the video and starts a fresh stream for the watched (or last shown) session. */
    fun retry() = post(Event.Retry)

    private fun post(event: Event) {
        items.trySend(event)
    }

    private fun Item.kind(): String = when (this) {
        is Incoming -> input.javaClass.simpleName
        is Event -> javaClass.simpleName
    }

    private fun handle(item: Item) {
        when (item) {
            is Incoming -> when (val input = item.input) {
                is StreamInput.LinkUp -> onLinkUp(input)
                is StreamInput.LinkDown -> onLinkDown(input)
                is StreamInput.Media -> onMedia(input)
                is StreamInput.Control -> onControl(input)
            }
            is Event.Open -> onOpen(item.session)
            Event.Close -> onClose()
            Event.Pause -> onPause()
            Event.Resume -> onResume()
            Event.Retry -> onRetry()
            Event.KeyframeNeeded -> requestKeyframe()
            Event.StallCheck -> checkStall()
            is Event.StartDone -> onStartDone(item.attempt, item.outcome)
        }
    }

    // ---- Calls ----

    private fun onOpen(session: SessionSummary) {
        val watched = intent
        val shown = stateFlow.value
        if (watched != null && watched.session.guid == session.guid &&
            shown !is LiveViewState.Ended && shown !is LiveViewState.Failed
        ) {
            return
        }
        epoch++
        stopActive()
        resetVideo()
        intent = Intent(session, epoch)
        showWaiting(session)
        maybeStart()
    }

    private fun onClose() {
        epoch++
        intent = null
        stopActive()
        resetVideo()
        setState(LiveViewState.Closed)
    }

    private fun onPause() {
        if (!foreground) return
        foreground = false
        stopActive()
        resetVideo()
        intent?.let { setState(LiveViewState.Paused(it.session)) }
    }

    private fun onResume() {
        val fromBackground = !foreground
        foreground = true
        if (fromBackground) resetVideo()
        val watched = intent ?: return
        if (active == null && (fromBackground || stateFlow.value is LiveViewState.Failed)) showWaiting(watched.session)
        maybeStart()
    }

    private fun onRetry() {
        val watched = intent
        if (watched == null) {
            // Ended or failed for good: Retry watches the shown session again.
            val shown = when (val s = stateFlow.value) {
                is LiveViewState.Failed -> s.session
                is LiveViewState.Ended -> s.session
                else -> return
            }
            onOpen(shown)
            return
        }
        stopActive()
        resetVideo()
        showWaiting(watched.session)
        maybeStart()
    }

    // ---- Links ----

    private fun onLinkUp(up: StreamInput.LinkUp) {
        if (up.linkId <= newestLink) {
            log("ignored LinkUp for link ${up.linkId}: link $newestLink is newer")
            return
        }
        newestLink = up.linkId
        // A newer link supersedes the old one even if its LinkDown was missed: nothing to stop on it.
        forgetLink()
        currentLink = up.linkId
        resetVideo()
        val watched = intent ?: return
        showWaiting(watched.session)
        maybeStart()
    }

    private fun onLinkDown(down: StreamInput.LinkDown) {
        if (down.linkId != currentLink) {
            log("ignored LinkDown for link ${down.linkId}: not the current link")
            return
        }
        val had = active
        currentLink = null
        forgetLink()
        resetVideo()
        val watched = intent ?: return
        if (down.messageTooLarge && had != null) {
            // An oversized message on the active stream's link: restarting would loop, so end.
            log("stream ${had.streamID} on link ${down.linkId} ended: a message was over the size cap")
            intent = null
            setState(LiveViewState.Ended(had.session, StreamEndReason.ERROR))
            return
        }
        setState(if (foreground) LiveViewState.Reconnecting(watched.session) else LiveViewState.Paused(watched.session))
    }

    /** Drops everything bound to the link that has ended: the active stream and the start in flight. */
    private fun forgetLink() {
        attempt = null
        setActive(null)
        lastKeyframeRequestAt = null
    }

    // ---- Media and control ----

    private fun onMedia(media: StreamInput.Media) {
        val stream = active ?: return
        if (media.linkId != currentLink || media.linkId != stream.linkId) return
        val frame = try {
            media.parse()
        } catch (e: MediaFrameException) {
            onUnparseable(stream, media, e)
            return
        }
        if (frame.streamID != stream.streamID) return
        // Ack first, every frame, depth 0 (SessionView.swift:976-984), whatever happens to it next.
        acks.ack(stream.linkId, stream.streamID, frame.ptsMilliseconds)
        val configGeneration = stream.config?.generationId
        val frameGeneration = frame.generationId
        if (configGeneration != null && frameGeneration != null && frameGeneration < configGeneration) return
        frame.liveTop?.let {
            stream.liveTop = it
            publishActive()
            historyTiles.onLiveTop(it)
        }
        val submitted = try {
            video.submit(frame)
        } catch (e: RuntimeException) {
            log("the video output failed on a frame of stream ${stream.streamID}: ${e.javaClass.simpleName}")
            false
        }
        if (submitted) {
            rejectingSince = null
            requestRunSince = null
        } else {
            val readiness = readinessOrNull()
            if (readiness == VideoReadiness.Ready) {
                if (rejectingSince == null) rejectingSince = nowMillis()
            } else {
                rejectingSince = null
            }
            if (readiness == VideoReadiness.NoDecoder && stateFlow.value is LiveViewState.Live) {
                // The output ran out of decoders after configure said Ready (every candidate failed).
                log("no decoder left for stream ${stream.streamID}")
                setState(LiveViewState.Failed(stream.session, LiveProblem.NoDecoder))
            }
            requestKeyframe()
        }
        checkStall()
    }

    /**
     * A frame on the active link that does not parse. It may have been a keyframe the Mac's
     * limiter now waits on, so: ack its pts when the header is readable and is this stream's, and
     * ask for a keyframe (the Mac answers with config and a keyframe that bypasses its limiter).
     * A frame readably for another stream is ignored, as any other stream's media is.
     */
    private fun onUnparseable(stream: Active, media: StreamInput.Media, error: MediaFrameException) {
        val peek = media.peekHeader()
        log("dropped a media frame of ${media.size} bytes on stream ${peek.streamID ?: "?"}: ${error.message}")
        if (peek.streamID != null && peek.streamID != stream.streamID) return
        if (peek.streamID != null && peek.ptsMilliseconds != null) {
            acks.ack(stream.linkId, stream.streamID, peek.ptsMilliseconds)
        }
        requestKeyframe()
    }

    private fun onControl(control: StreamInput.Control) {
        if (control.linkId != currentLink) return
        when (val message = control.message) {
            is MacMessage.StreamStarted -> onStarted(control.linkId, control.requestID, message)
            is MacMessage.StreamConfigMessage -> onConfig(control.linkId, message.config)
            is MacMessage.StreamEnded -> onEnded(control.linkId, message)
            is MacMessage.StreamExtent -> onExtent(control.linkId, message)
            is MacMessage.SelectionRange -> onSelectionRange(control.linkId, message)
            else -> Unit
        }
    }

    private fun onStarted(linkId: Long, requestID: Long?, started: MacMessage.StreamStarted) {
        val pending = attempt
        val ours = pending != null && !pending.resolved && pending.linkId == linkId &&
            requestID != null && pending.requestID == requestID
        if (ours) pending.resolved = true
        val watched = intent
        if (ours && watched != null && watched.session.guid == pending.guid && foreground && active == null) {
            if (started.codec != StreamCodec.HEVC) {
                log("stopping stream ${started.streamID} on link $linkId: codec ${started.codec} is not HEVC")
                sendStop(linkId, started.streamID)
                intent = null
                setState(LiveViewState.Failed(watched.session, LiveProblem.StartFailed(retryable = false)))
                return
            }
            log("adopted stream ${started.streamID} on link $linkId for ${watched.session.guid}")
            lastKeyframeRequestAt = null
            setActive(Active(linkId, started.streamID, watched.session, watched.epoch))
            setState(LiveViewState.Starting(watched.session))
            return
        }
        log("stopping stream ${started.streamID} on link $linkId: not adopted")
        sendStop(linkId, started.streamID)
    }

    private fun onConfig(linkId: Long, config: StreamConfig) {
        val stream = active ?: return
        if (stream.linkId != linkId || stream.streamID != config.streamID) return
        val previous = stream.config
        stream.config = config
        historyTiles.onConfig(linkId, stream.streamID, config)
        if (previous == null || previous.generationId != config.generationId) {
            // AppModel.swift:4403: snap the live top to the new extent until a frame says otherwise.
            val snapped = config.firstAbsLine + maxOf(0, config.totalLines - config.rows)
            stream.liveTop = snapped
            historyTiles.onLiveTop(snapped)
        }
        publishActive()
        val wasStalled = (stateFlow.value as? LiveViewState.Live)?.stalled ?: false
        val readiness = try {
            video.configure(config)
        } catch (e: RuntimeException) {
            log("the video output failed to configure stream ${stream.streamID}: ${e.javaClass.simpleName}")
            VideoReadiness.NoDecoder
        }
        setState(
            when (readiness) {
                // The stalled flag carries over until the check below says otherwise: no flicker on a resend.
                VideoReadiness.Ready, VideoReadiness.WaitingForSurface -> LiveViewState.Live(stream.session, config, wasStalled)
                // Incomplete parameter sets: still starting; the request below asks the Mac for a fresh config.
                VideoReadiness.WaitingForConfig -> LiveViewState.Starting(stream.session)
                VideoReadiness.NoDecoder -> LiveViewState.Failed(stream.session, LiveProblem.NoDecoder)
                is VideoReadiness.TooLarge -> LiveViewState.Failed(
                    stream.session,
                    LiveProblem.TooLarge(readiness.width, readiness.height, readiness.maxWidth, readiness.maxHeight),
                )
            },
        )
        if (readiness == VideoReadiness.WaitingForConfig) requestKeyframe()
        checkStall()
    }

    private fun onEnded(linkId: Long, ended: MacMessage.StreamEnded) {
        val stream = active ?: return
        // A stream we stopped ourselves is no longer active, so its stoppedByClient is silent.
        if (stream.linkId != linkId || stream.streamID != ended.streamID) return
        log("stream ${ended.streamID} on link $linkId ended: ${ended.reason}")
        setActive(null)
        resetVideo()
        intent = null
        setState(LiveViewState.Ended(stream.session, ended.reason))
    }

    /** iOS `AppModel.swift:4403-4420`: a clear snaps the live top in the controller and the store. */
    private fun onExtent(linkId: Long, extent: MacMessage.StreamExtent) {
        val stream = active ?: return
        if (stream.linkId != linkId || stream.streamID != extent.streamID) return
        val snapped = historyTiles.onExtent(extent.firstAbsLine, extent.totalLines) ?: return
        stream.liveTop = snapped
        publishActive()
    }

    /** iOS applies it only for the active stream (`AppModel.swift:4421-4424`). */
    private fun onSelectionRange(linkId: Long, selection: MacMessage.SelectionRange) {
        val stream = active ?: return
        if (stream.linkId != linkId || stream.streamID != selection.streamID) return
        historyTiles.onSelectionRange(selection.range)
    }

    // ---- Starts ----

    /** iOS `startLiveStreamIfPossible` (`AppModel.swift:4579-4590`). */
    private fun maybeStart() {
        val watched = intent ?: return
        val linkId = currentLink ?: return
        if (!foreground || active != null || attempt != null) return
        val started = Attempt(linkId, watched.session.guid)
        attempt = started
        setState(LiveViewState.Starting(watched.session))
        outbox.trySend(Out.Start(started))
    }

    /** Runs in a child coroutine, never in the consumer: it waits for the reply and posts the outcome. */
    private suspend fun runStart(started: Attempt) {
        val outcome = try {
            StartOutcome.Replied(
                link.request(
                    PhoneMessage.StartSessionStream(started.guid),
                    onLink = started.linkId,
                    timeout = timeouts.start,
                    onSent = { started.requestID = it },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StartOutcome.Threw(e)
        }
        post(Event.StartDone(started, outcome))
    }

    /** iOS `AppModel.swift:4591-4627`, after the attempt's own `streamStarted` (if any) was handled in order. */
    private fun onStartDone(started: Attempt, outcome: StartOutcome) {
        if (attempt !== started) return // its link ended: forgotten
        var failed = false
        when (outcome) {
            is StartOutcome.Replied -> when (val reply = outcome.message) {
                is MacMessage.StreamStarted -> if (!started.resolved && started.linkId == currentLink) {
                    // Defensive: the reply came back without passing through the sink first.
                    onStarted(started.linkId, started.requestID, reply)
                }
                is MacMessage.Error -> {
                    failed = true
                    val code = runCatching { reply.code }.getOrNull()
                    if (code == UNKNOWN_SESSION) sessionGone(started) else startFailed(started, "error $code")
                }
                else -> {
                    failed = true
                    startFailed(started, "unexpected reply ${reply.javaClass.simpleName}")
                }
            }
            is StartOutcome.Threw -> {
                failed = true
                val error = outcome.error
                // A request failure's message names only the link and the bound; anything else, its class.
                startFailed(started, if (error is CompanionRequestException) "${error.message}" else error.javaClass.simpleName)
            }
        }
        attempt = null
        // Drive the intent if it moved to another session meanwhile, or if this attempt was
        // superseded rather than failed; a failed start of the same session waits for the next
        // link, resume or Retry instead of looping.
        val watched = intent ?: return
        if (watched.session.guid != started.guid || !failed) maybeStart()
    }

    private fun sessionGone(started: Attempt) {
        log("start for ${started.guid} failed: unknownSession")
        val watched = intent ?: return
        if (watched.session.guid != started.guid) return
        intent = null
        resetVideo()
        setState(LiveViewState.Ended(watched.session, StreamEndReason.SESSION_CLOSED))
    }

    private fun startFailed(started: Attempt, why: String) {
        log("start for ${started.guid} on link ${started.linkId} failed: $why")
        val watched = intent ?: return
        if (watched.session.guid != started.guid || active != null || !foreground) return
        setState(LiveViewState.Failed(watched.session, LiveProblem.StartFailed(retryable = true)))
    }

    // ---- Sending ----

    private fun stopActive() {
        val stream = active ?: return
        setActive(null)
        if (stream.linkId == currentLink) sendStop(stream.linkId, stream.streamID)
    }

    private fun sendStop(linkId: Long, streamID: Long) {
        outbox.trySend(Out.Send(PhoneMessage.StopSessionStream(streamID), linkId))
    }

    /**
     * iOS `onNeedsKeyframe`, skipped while the output cannot use one and rate-limited
     * (Android-only). An output waiting for a complete config can use one: the Mac sends a config
     * before every requested keyframe.
     */
    private fun requestKeyframe() {
        val stream = active ?: return
        if (stream.linkId != currentLink) return
        val readiness = readinessOrNull()
        if (readiness != VideoReadiness.Ready && readiness != VideoReadiness.WaitingForConfig) return
        val now = nowMillis()
        val last = lastKeyframeRequestAt
        val gap = timeouts.keyframeRequestGap.inWholeMilliseconds
        if (last != null && now - last < gap) {
            // Deferred, not dropped: the need is re-checked (gate and limit) when the gap ends, so a
            // request suppressed here (say on a surface reattach) is not lost on an idle screen.
            if (deferredKeyframe?.isActive != true) {
                deferredKeyframe = scope.launch(serial) {
                    delay(last + gap - now)
                    post(Event.KeyframeNeeded)
                }
            }
            return
        }
        // A run of requests: each within four gaps of the last, with no frame accepted between.
        val runSince = requestRunSince
        if (runSince == null || last == null || now - last > gap * KEYFRAME_RUN_GAPS) {
            requestRunSince = now
        } else if (now - runSince >= timeouts.keyframeGiveUp.inWholeMilliseconds) {
            giveUpOnKeyframes(stream, now - runSince)
            return
        }
        lastKeyframeRequestAt = now
        outbox.trySend(Out.Send(PhoneMessage.RequestKeyframe(stream.streamID), stream.linkId))
    }

    /**
     * Keyframe requests have gone on for [StreamTimeouts.keyframeGiveUp] and the output has taken
     * no frame: the stream cannot be shown (parameter sets that never complete, keyframes over
     * the decoder's largest input). Stop it rather than loop; Retry, a resume or a new link
     * starts a fresh one.
     */
    private fun giveUpOnKeyframes(stream: Active, forMillis: Long) {
        log("giving up on stream ${stream.streamID}: keyframe requests for $forMillis ms and no frame accepted")
        stopActive()
        resetVideo()
        setState(LiveViewState.Failed(stream.session, LiveProblem.StartFailed(retryable = true)))
    }

    private fun readinessOrNull(): VideoReadiness? = try {
        video.readiness()
    } catch (e: RuntimeException) {
        log("the video output failed on readiness: ${e.javaClass.simpleName}")
        null
    }

    /** iOS `CompanionVideoView.reset`; a throw is logged and skipped so the transition still completes. */
    private fun resetVideo() {
        try {
            video.reset()
        } catch (e: RuntimeException) {
            log("the video output failed on reset: ${e.javaClass.simpleName}")
        }
    }

    // ---- Stalled label (Android-only) ----

    private fun checkStall() {
        val live = stateFlow.value as? LiveViewState.Live
        if (live == null || active == null) {
            cancelStallTimer()
            return
        }
        val overdue = try {
            video.outputOverdueSinceMillis()
        } catch (e: RuntimeException) {
            log("the video output failed on outputOverdueSinceMillis: ${e.javaClass.simpleName}")
            null
        }
        // Either cause counts: a frame accepted and not output, or every frame refused while Ready.
        val overdueSince = listOfNotNull(overdue, rejectingSince).minOrNull()
        val now = nowMillis()
        val stallMillis = timeouts.stallAfter.inWholeMilliseconds
        // Without a surface nothing can be output, so nothing is stalled; re-checked after a poll.
        val noSurface = readinessOrNull() == VideoReadiness.WaitingForSurface
        val stalled = !noSurface && overdueSince != null && now - overdueSince >= stallMillis
        if (stalled != live.stalled) {
            log("picture ${if (stalled) "stalled" else "recovered"} on stream ${activeFlow.value?.streamID}")
            setState(live.copy(stalled = stalled))
        }
        when {
            overdueSince == null -> cancelStallTimer()
            stalled || noSurface -> armStallTimer(now + timeouts.stallPoll.inWholeMilliseconds)
            else -> armStallTimer(overdueSince + stallMillis)
        }
    }

    private fun armStallTimer(at: Long) {
        if (stallTimer?.isActive == true && stallTimerAt <= at) return
        stallTimer?.cancel()
        stallTimerAt = at
        stallTimer = scope.launch(serial) {
            delay((at - nowMillis()).coerceAtLeast(0))
            post(Event.StallCheck)
        }
    }

    private fun cancelStallTimer() {
        stallTimer?.cancel()
        stallTimer = null
        stallTimerAt = Long.MAX_VALUE
    }

    // ---- Publishing ----

    private fun setActive(stream: Active?) {
        active = stream
        // Every end or replacement of the active stream: no tile or selection outlives it.
        historyTiles.clear()
        rejectingSince = null
        requestRunSince = null
        deferredKeyframe?.cancel()
        deferredKeyframe = null
        if (stream == null) cancelStallTimer()
        publishActive()
    }

    private fun publishActive() {
        activeFlow.value = active?.let { ActiveStream(it.linkId, it.streamID, it.session.guid, it.epoch, it.config, it.liveTop) }
    }

    /** The state while [session] is watched with no stream yet. */
    private fun showWaiting(session: SessionSummary) {
        setState(
            when {
                !foreground -> LiveViewState.Paused(session)
                currentLink == null -> LiveViewState.Reconnecting(session)
                else -> LiveViewState.Starting(session)
            },
        )
    }

    private fun setState(next: LiveViewState) {
        stateFlow.value = next
        if (next !is LiveViewState.Live) cancelStallTimer()
    }

    private companion object {
        const val UNKNOWN_SESSION = "unknownSession"

        /** Requests further apart than this many gaps start a new run (see [StreamTimeouts.keyframeGiveUp]). */
        const val KEYFRAME_RUN_GAPS = 4
    }
}

/** Default for [StreamBudgets.inboxMediaBytes]: 32 MiB. */
const val DEFAULT_INBOX_MEDIA_BYTES: Long = 32L * 1024 * 1024
