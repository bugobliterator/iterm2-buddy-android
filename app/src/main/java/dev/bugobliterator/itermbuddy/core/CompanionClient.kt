package dev.bugobliterator.itermbuddy.core

import android.util.Log
import dev.bugobliterator.itermbuddy.core.noise.DEFAULT_MAX_MESSAGE_BYTES
import dev.bugobliterator.itermbuddy.core.noise.NoiseChannel
import dev.bugobliterator.itermbuddy.core.noise.NoiseException
import dev.bugobliterator.itermbuddy.core.noise.NoiseInitiator
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.pairing.sasCode
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.DEFAULT_MAX_QUEUED_BYTES
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayClose
import dev.bugobliterator.itermbuddy.core.relay.RelayClosedException
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.UnreachableKind
import dev.bugobliterator.itermbuddy.core.relay.forOrigin
import dev.bugobliterator.itermbuddy.core.relay.isFatalHttp
import dev.bugobliterator.itermbuddy.core.relay.nextDelayMillis
import dev.bugobliterator.itermbuddy.core.relay.requiresRescan
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.ProtocolRevision
import dev.bugobliterator.itermbuddy.store.PairingRecord
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.PairingStore
import dev.bugobliterator.itermbuddy.store.PairingStoreUnavailableException
import dev.bugobliterator.itermbuddy.store.StalePairingFlowException
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Every timeout and limit the connect flow uses, so tests can run it in virtual time.
 *
 * @property handshakeFresh Noise handshake bound while the pairing is unconfirmed (upstream 15 s).
 * @property handshakeReconnect Noise handshake bound on reconnects (upstream 6 s).
 * @property confirmation wait for the Mac's verdict on the code (upstream 180 s).
 * @property freshMacWait how long an unconfirmed pairing keeps retrying while the Mac is absent:
 *   iTerm2 replaces an idle QR after 120 s (`CompanionPairingController.swift:262`).
 * @property rpc bound on each request/reply in the connect flow and on [CompanionClient.request],
 *   matching upstream's 15 s `hello` race.
 * @property badSignatureLimit "bad signature" rejections in a row before giving up.
 * @property roomSecretErrorLimit `error` replies to `relayRoomSecret` in a row before giving up.
 * @property admission bound on relay admission, connect to result (addition; upstream 30 s).
 * @property register bound on one `/register` call (addition).
 * @property codeShownGrace (addition) a session that showed the code for longer than this proves
 *   the Mac was there, so the Mac-absence clock starts again from zero when it ends. A session
 *   that closes sooner with no frame from the Mac (such as a Mac pinned to another key) does not.
 * @property maxMessageBytes (addition) cap on one reassembled Noise message from the Mac; over it
 *   the link ends with [dev.bugobliterator.itermbuddy.core.stream.StreamInput.LinkDown]
 *   `messageTooLarge` and the client reconnects.
 * @property maxQueuedBytes (addition) bound on received relay frames not yet read; over it the
 *   relay connection is closed and the client reconnects. Never a dropped frame.
 * @property sinkStall (addition) the longest the stream sink may take over one input. Over it a
 *   media or control input ends the link as lost (the client reconnects), and a link marker
 *   is missed and logged, so the connect flow, [CompanionClient.cancel] and
 *   [CompanionClient.unpair] never wait longer than this on the sink.
 */
data class ClientTimeouts(
    val handshakeFresh: Duration = 15.seconds,
    val handshakeReconnect: Duration = 6.seconds,
    val confirmation: Duration = 180.seconds,
    val freshMacWait: Duration = 120.seconds,
    val rpc: Duration = 15.seconds,
    val badSignatureLimit: Int = 3,
    val roomSecretErrorLimit: Int = 3,
    val admission: Duration = 30.seconds,
    val register: Duration = 30.seconds,
    val codeShownGrace: Duration = 2.seconds,
    val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    val sinkStall: Duration = DEFAULT_SINK_STALL,
)

/** Why the client is waiting rather than connected. */
enum class WaitReason {
    /** The relay answered "mac offline" (iTerm2 is closed) or refused this attempt for a passing reason. */
    MAC_OFFLINE,

    /** The relay could not be reached: the Mac it runs on is asleep or offline. */
    RELAY_UNREACHABLE,

    /**
     * Addition: the pairing store could not be read or written right now
     * ([PairingStoreUnavailableException]). Nothing was lost; the client retries with backoff and
     * never re-pairs because of it.
     */
    STORE_UNAVAILABLE,
}

/** What the connect flow is doing, for the UI and the foreground service. */
sealed interface ClientState {
    /** Nothing is stored: show Scan. */
    data object Unpaired : ClientState

    /**
     * Addition: a pairing is stored but the client is not connecting, after [CompanionClient.cancel]
     * of a stored pairing or a failed [CompanionClient.unpair]. [CompanionClient.start] resumes.
     */
    data object Idle : ClientState

    data object Connecting : ClientState

    /** "Waiting for your Mac", with Cancel. Retries with backoff. */
    data class WaitingForMac(val reason: WaitReason) : ClientState

    /** Show [code]; the user types it on the Mac. [toString] leaves the code out, so logs never carry it. */
    data class ShowCode(val code: String) : ClientState {
        override fun toString(): String = "ShowCode(code=<redacted>)"
    }

    /** Paired and talking; [macRevision] is the Mac's protocol revision from its `hello`. */
    data class Connected(val macRevision: Int) : ClientState

    /**
     * The flow stopped. [rescanNeeded] means the UI offers Scan again; otherwise the stored pairing
     * is kept and [CompanionClient.start] tries again. Besides the cases in the plan:
     * - an `Unreachable(TLS)` admission ("The relay's certificate was rejected …"), with no retry;
     * - a relay close 1000 "pairing cycle cap" ([requiresRescan]) on an unconfirmed pairing: it is
     *   wiped and [rescanNeeded] is true. A confirmed pairing just reconnects: the Mac keeps it.
     */
    data class Failed(val message: String, val rescanNeeded: Boolean) : ClientState
}

/**
 * The phone's connect flow to iTerm2 through the relay: admission, Noise XK, the SAS code,
 * `hello`, the room secret and `/register` (*notes* → Relay admission, Noise, After pairing).
 * Owned by the foreground service; it holds at most one connection.
 *
 * [pair], [start], [cancel] and [unpair] are serialised: each one cancels the previous one's
 * work and waits, uncancellably, for it to finish before it starts, so the chain runs in order.
 *
 * @param store the one pairing; every blocking store call runs on Dispatchers.IO.
 * @param scope where the flow runs; cancelling it stops everything, as a process kill would.
 * @param endpointsFor where to connect for a stored relay origin; null refuses the origin.
 * @param random jitter for [nextDelayMillis]; tests pass a seeded one.
 * @param log diagnostics; never given secrets or registration tokens.
 * @param streamSink where media and stream-scoped control go, in wire order, bracketed per link by
 *   [StreamInput.LinkUp] and [StreamInput.LinkDown]; with null they are dropped and logged by kind.
 *
 * Links: each connection that reaches the connected step (7) is a link, numbered from 1. At step
 * 7 the link becomes current (so [send] and [request] with `onLink` reach it), then
 * [StreamInput.LinkUp] is sunk, then the state goes [ClientState.Connected]. When the session ends
 * it is closed first, its receive loop has stopped, and then [StreamInput.LinkDown] is sunk,
 * uncancellably, so [cancel], [unpair] and [pair] never lose it. Each marker waits at most
 * [ClientTimeouts.sinkStall] for the sink and is missed after that (see [StreamInput]); a link
 * cancelled during its LinkUp still gets its LinkDown.
 */
class CompanionClient internal constructor(
    private val store: PairingStore,
    private val transport: RelayTransport,
    private val scope: CoroutineScope,
    private val endpointsFor: (String) -> RelayEndpoints?,
    private val timeouts: ClientTimeouts,
    private val random: Random,
    private val timeSource: TimeSource.WithComparableMarks,
    private val storeContext: CoroutineContext,
    private val log: (String) -> Unit,
    private val streamSink: StreamSink? = null,
) : StreamLink {
    constructor(
        store: PairingStore,
        http: OkHttpClient,
        scope: CoroutineScope,
        endpointsFor: (String) -> RelayEndpoints? = { RelayEndpoints.forOrigin(it) },
        timeouts: ClientTimeouts = ClientTimeouts(),
        random: Random = Random.Default,
        log: (String) -> Unit = { Log.i(TAG, it) },
        streamSink: StreamSink? = null,
    ) : this(
        store,
        OkHttpRelayTransport(
            http,
            timeouts.admission.inWholeMilliseconds,
            timeouts.register.inWholeMilliseconds,
            timeouts.maxQueuedBytes,
        ),
        scope,
        endpointsFor,
        timeouts,
        random,
        TimeSource.Monotonic,
        Dispatchers.IO,
        log,
        streamSink,
    )

    private val stateFlow = MutableStateFlow<ClientState>(ClientState.Unpaired)

    /** Starts as [ClientState.Unpaired] until [start] has looked at the store. */
    val state: StateFlow<ClientState> = stateFlow.asStateFlow()

    private val eventFlow = MutableSharedFlow<MacMessage>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Unsolicited messages from the Mac: envelopes with no request ID or one nobody waits for.
     * Media and stream-scoped messages never come here; they go to the stream sink.
     */
    val events: SharedFlow<MacMessage> = eventFlow.asSharedFlow()

    private val pairedFlow = MutableStateFlow<Boolean?>(null)

    /**
     * Null until the first `store.load()` of [start] finishes (or [pair] writes a fresh record),
     * then true while the stored record is `CONFIRMED` or `REGISTERED`.
     */
    val paired: StateFlow<Boolean?> = pairedFlow.asStateFlow()

    private val jobLock = Any()
    private var job: Job? = null

    /** The connected session and its link id: set at step 7, cleared when the session ends. */
    private class ActiveLink(val id: Long, val session: CompanionSession)

    @Volatile
    private var active: ActiveLink? = null

    private val session: CompanionSession? get() = active?.session

    private val nextLinkId = AtomicLong(1)

    /**
     * The code [pair] is pairing, from before its record is written until the Mac confirms it;
     * [cancel] wipes a PENDING_CONFIRMATION record for it.
     */
    @Volatile
    private var freshCode: PairingCode? = null

    /** Starts a new pairing for a scanned [code], replacing whatever is stored. */
    fun pair(code: PairingCode) {
        launchControl { runFresh(code) }
    }

    /**
     * Resumes the stored pairing. A `PENDING_CONFIRMATION` record is kept and recovered
     * (flow step 3a), because the Mac may already have pinned this phone.
     *
     * The state is [ClientState.Connecting] when this returns, so a UI drawn straight after never
     * shows the initial [ClientState.Unpaired] to a paired user while the store is read.
     */
    fun start() {
        setState(ClientState.Connecting)
        launchControl { runStored() }
    }

    /** Stops the current attempt. A fresh pairing is wiped; a stored one stays and goes [ClientState.Idle]. */
    fun cancel() {
        launchControl {
            val fresh = freshCode
            // Committed: finish the wipe even if a later start() or pair() supersedes this cancel.
            withContext(NonCancellable) {
                freshCode = null
                if (fresh != null) wipeUnconfirmed(fresh)
            }
            setState(if (hasStoredPairing()) ClientState.Idle else ClientState.Unpaired)
        }
    }

    /**
     * Cancels reconnect work, sends `unpairing` without waiting for a reply, clears the store and
     * goes [ClientState.Unpaired], even if the send fails or nothing is connected.
     *
     * @throws IOException ([PairingStoreUnavailableException] or a failed write) if the store still
     *   cannot be cleared after a few tries; the state is then [ClientState.Idle] and the pairing
     *   is still stored.
     * @throws CancellationException if a later [pair] or [start] superseded this unpair.
     */
    suspend fun unpair() {
        val result = CompletableDeferred<Unit>()
        val job = launchControl(cancelPrevious = false) { previous ->
            try {
                val live = session
                if (live != null) {
                    try {
                        live.send(PhoneMessage.Unpairing)
                    } catch (e: IOException) {
                        log("unpairing was not sent: ${e.message}")
                    }
                }
                previous?.cancel()
                withContext(NonCancellable) { previous?.join() }
                freshCode = null
                clearStoreWithRetry()
                setState(ClientState.Unpaired)
                result.complete(Unit)
            } catch (e: IOException) {
                setState(ClientState.Idle)
                result.completeExceptionally(e)
            }
        }
        // Completes the caller's wait if the job ends any other way (superseded, or a bug).
        job.invokeOnCompletion { cause ->
            result.completeExceptionally(cause ?: IllegalStateException("unpair ended without clearing"))
        }
        result.await()
    }

    /**
     * Sends [message] with no request ID on the current link, in the caller's order, and returns
     * once its frames are handed to the socket.
     *
     * @throws CompanionRequestException when not connected, when [onLink] is not the current
     *   link id, or when the send fails.
     */
    override suspend fun send(message: PhoneMessage, onLink: Long?) {
        linkFor(onLink).send(message)
    }

    /**
     * Sends [message] to the Mac and returns its reply, bounded by [timeout] or else
     * [ClientTimeouts.rpc]. Time the receive loop spends waiting on the stream sink does not
     * count against the bound. [onSent] gets the request ID before the envelope is written.
     *
     * @throws CompanionRequestException when not connected, when [onLink] is not the current
     *   link id, on a timeout, or if the connection drops.
     */
    override suspend fun request(
        message: PhoneMessage,
        onLink: Long?,
        timeout: Duration?,
        onSent: (requestID: Long) -> Unit,
    ): MacMessage {
        val live = linkFor(onLink)
        val bound = timeout ?: timeouts.rpc
        return live.request(message, bound, onSent) ?: throw CompanionRequestException("no reply within $bound")
    }

    private fun linkFor(onLink: Long?): CompanionSession {
        val current = active ?: throw CompanionRequestException("not connected")
        if (onLink != null && onLink != current.id) {
            throw CompanionRequestException("link $onLink has ended; the current link is ${current.id}")
        }
        return current.session
    }

    /** Sends [message] with no request ID; dropped if not connected. */
    fun notify(message: PhoneMessage) {
        val live = session ?: return
        scope.launch {
            try {
                live.send(message)
            } catch (e: IOException) {
                log("notify failed: ${e.message}")
            }
        }
    }

    /** Records what the store now holds for [paired]. */
    private fun notePaired(record: PairingRecord?) {
        pairedFlow.value = record != null && (record.stage == PairingStage.CONFIRMED || record.stage == PairingStage.REGISTERED)
    }

    /**
     * Sinks a link marker, waiting at most [ClientTimeouts.sinkStall]: a sink that takes longer
     * misses it (logged), so the control chain never hangs on the sink. A failing sink is logged
     * and never ends the flow.
     */
    private suspend fun sinkLink(input: StreamInput) {
        val target = streamSink ?: return
        try {
            withTimeoutOrNull(timeouts.sinkStall) { target.accept(input) }
                ?: log("the stream sink missed $input: it did not take it within ${timeouts.sinkStall}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("the stream sink failed on ${input.javaClass.simpleName}: ${e.javaClass.simpleName}")
        }
    }

    private fun setState(next: ClientState) {
        if (stateFlow.value != next) log("state: $next")
        stateFlow.value = next
    }

    /**
     * Runs [block] after the previous control operation: with [cancelPrevious] the previous job is
     * cancelled and joined first; otherwise [block] gets it and decides when.
     */
    private fun launchControl(cancelPrevious: Boolean = true, block: suspend (previous: Job?) -> Unit): Job =
        synchronized(jobLock) {
            val previous = job
            val next = scope.launch {
                try {
                    // Uncancellable: a job superseded while it waits must still not finish before
                    // its own predecessor, or two flows would touch the store at once.
                    if (cancelPrevious) {
                        previous?.cancel()
                        withContext(NonCancellable) { previous?.join() }
                    }
                    block(if (cancelPrevious) null else previous)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("connect flow failed: $e")
                    setState(ClientState.Failed("Something went wrong: ${e.message}", rescanNeeded = false))
                } finally {
                    if (!cancelPrevious) {
                        withContext(NonCancellable) {
                            previous?.cancel()
                            previous?.join()
                        }
                    }
                }
            }
            job = next
            next
        }

    private suspend fun <T> onStore(block: () -> T): T = withContext(storeContext) { block() }

    private suspend fun hasStoredPairing(): Boolean = try {
        onStore { store.load() }.also(::notePaired) != null
    } catch (_: PairingStoreUnavailableException) {
        true
    }

    /**
     * Clears the store only if it holds an unconfirmed pairing for [code]. A confirmed pairing is
     * never wiped here: the Mac has pinned it. A store that is unavailable keeps it.
     */
    private suspend fun wipeUnconfirmed(code: PairingCode) {
        try {
            onStore {
                store.withLock {
                    val stored = store.load()
                    if (stored != null && stored.stage == PairingStage.PENDING_CONFIRMATION && stored.code == code) {
                        store.clear()
                        notePaired(null)
                    }
                }
            }
        } catch (e: IOException) {
            log("could not wipe the pairing: ${e.message}")
        }
    }

    /**
     * Clears the store, retrying any [IOException]: [PairingStoreUnavailableException] and a failed
     * write alike. Throws the last one after [CLEAR_ATTEMPTS] tries.
     */
    private suspend fun clearStoreWithRetry() {
        var attempt = 1
        while (true) {
            try {
                onStore { store.clear() }
                notePaired(null)
                return
            } catch (e: IOException) {
                if (attempt >= CLEAR_ATTEMPTS) throw e
                log("store unavailable while unpairing: ${e.message}")
                delay(nextDelayMillis(attempt++, null, random))
            }
        }
    }

    private suspend fun runFresh(code: PairingCode) {
        freshCode = code
        setState(ClientState.Connecting)
        val deadline = timeSource.markNow() + timeouts.freshMacWait
        var attempt = 0
        var record: PairingRecord? = null
        while (record == null) {
            try {
                record = onStore { beginFreshPairing(store, code) }.also(::notePaired)
            } catch (e: IOException) {
                // PairingStoreUnavailableException included: the QR stays valid for a while.
                log("could not start the pairing: ${e.message}")
                if (deadline.hasPassedNow()) {
                    setState(ClientState.Failed(STORE_FAILED_MESSAGE, rescanNeeded = true))
                    return
                }
                setState(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE))
                delay(minOf(nextDelayMillis(++attempt, null, random), remainingMillis(deadline)))
            }
        }
        PairingFlow(record, fromScan = true).run()
    }

    private suspend fun runStored() {
        setState(ClientState.Connecting)
        var attempt = 0
        var loaded = false
        var record: PairingRecord? = null
        while (!loaded) {
            try {
                record = onStore { store.load() }
                loaded = true
                notePaired(record)
            } catch (e: PairingStoreUnavailableException) {
                log("store unavailable: ${e.message}")
                setState(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE))
                delay(nextDelayMillis(++attempt, null, random))
            }
        }
        if (record == null) {
            setState(ClientState.Unpaired)
            return
        }
        PairingFlow(record, fromScan = false).run()
    }

    private fun remainingMillis(deadline: ComparableTimeMark): Long =
        (-deadline.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)

    /** Time that runs only while resumed, for how long the Mac has been absent. */
    private class Stopwatch(private val timeSource: TimeSource.WithComparableMarks) {
        private var accumulated = Duration.ZERO
        private var runningSince: ComparableTimeMark? = null

        val elapsed: Duration get() = accumulated + (runningSince?.elapsedNow() ?: Duration.ZERO)

        fun resume() {
            if (runningSince == null) runningSince = timeSource.markNow()
        }

        fun pause() {
            runningSince?.let { accumulated += it.elapsedNow() }
            runningSince = null
        }

        fun reset() {
            accumulated = Duration.ZERO
            runningSince = null
        }
    }

    /** The outcome of one connection attempt. */
    private sealed interface End {
        /** Not connected; back off and retry. [relayClose] is the relay's close frame from this connection. */
        data class Retry(val reason: WaitReason, val relayClose: RelayClose? = null) : End

        /** Retry at once with the other proof (self-heal). */
        data object SwitchProof : End

        /** Was connected; the connection ended. [relayClose] is the relay's close frame, if any. */
        data class Lost(val relayClose: RelayClose?) : End

        /** The flow is over; the state is already set. */
        data object Stop : End
    }

    /** Either the Mac's reply to `hello`, or how the attempt ended before one. */
    private sealed interface HelloStep {
        class Reply(val message: MacMessage) : HelloStep

        class Ended(val end: End) : HelloStep
    }

    /**
     * One pairing's connect loop. [fromScan] is true when [pair] began it: its first handshake
     * then waits for the bare verdict (step 3). Every later handshake while the pairing is still
     * unconfirmed uses pending recovery (step 3a), since an "accepted" may have been lost in
     * flight and the Mac may already have pinned this phone.
     */
    private inner class PairingFlow(private var record: PairingRecord, fromScan: Boolean) {
        private val roomName = roomName(record.code)
        private var signed = record.stage == PairingStage.REGISTERED
        private var macMayHavePinned = !fromScan
        private var attempt = 0
        private var badSignatures = 0
        private var roomSecretErrors = 0
        private var proofSwitches = 0

        /**
         * While unconfirmed: how long the Mac has been absent, measured against
         * [ClientTimeouts.freshMacWait]. It runs from the end of an attempt that ended with no
         * frame from the Mac (mac offline, unreachable, a failed handshake, a Mac that closed
         * silently), pauses while the code is on screen, and resets whenever a decrypted frame
         * from the Mac arrives or an attempt showed the code for longer than
         * [ClientTimeouts.codeShownGrace] (the Mac was there; a verdict may have been lost). Time spent waiting for the user to type the code never counts,
         * as upstream defers QR regeneration during a confirmation
         * (`CompanionPairingController.swift:805-810`).
         */
        private val absence = Stopwatch(timeSource)

        /** Whether the current attempt decrypted any frame from the Mac. */
        private var heardThisAttempt = false

        /** When the current attempt put the code on screen, if it did. */
        private var codeShownAt: ComparableTimeMark? = null

        /** Whether the current attempt showed the code longer than [ClientTimeouts.codeShownGrace]. */
        private val codeShownLong: Boolean
            get() = codeShownAt?.let { it.elapsedNow() > timeouts.codeShownGrace } == true

        private val unconfirmed get() = record.stage == PairingStage.PENDING_CONFIRMATION

        private val macGoneTooLong get() = unconfirmed && absence.elapsed >= timeouts.freshMacWait

        suspend fun run() {
            val endpoints = endpointsFor(record.code.relayOrigin)
            if (endpoints == null) {
                setState(ClientState.Failed(BAD_ORIGIN_MESSAGE, rescanNeeded = true))
                return
            }
            while (true) {
                // Checked before each admission too, so an admission timeout cannot overshoot it.
                if (macGoneTooLong) {
                    giveUp()
                    return
                }
                heardThisAttempt = false
                codeShownAt = null
                val end = try {
                    connectOnce(endpoints)
                } catch (e: StalePairingFlowException) {
                    // Someone else changed the store under this flow; show what is there now.
                    log("pairing changed under the flow: ${e.message}")
                    setState(if (hasStoredPairing()) ClientState.Idle else ClientState.Unpaired)
                    End.Stop
                }
                val keepGoing = when (end) {
                    End.Stop -> false
                    // One immediate retry with the other proof; a second switch in a row backs off.
                    End.SwitchProof -> ++proofSwitches <= 1 || backOff(WaitReason.MAC_OFFLINE, null)
                    is End.Lost -> {
                        proofSwitches = 0
                        attempt = 0
                        when {
                            end.relayClose != null && requiresRescan(end.relayClose) && unconfirmed -> rescan()
                            else -> {
                                setState(ClientState.Connecting)
                                backOff(null, end.relayClose)
                            }
                        }
                    }
                    is End.Retry -> {
                        proofSwitches = 0
                        when {
                            heardThisAttempt -> absence.reset()
                            end.reason != WaitReason.STORE_UNAVAILABLE -> {
                                // The Mac was there while the code was up: its absence starts now.
                                if (codeShownLong) absence.reset()
                                absence.resume()
                            }
                        }
                        when {
                            // A confirmed pairing outlives a torn-down room: the Mac keeps it and re-parks.
                            end.relayClose != null && requiresRescan(end.relayClose) && unconfirmed -> rescan()
                            else -> backOff(end.reason, end.relayClose)
                        }
                    }
                }
                if (!keepGoing) return
            }
        }

        /**
         * Waits before the next attempt, showing [reason] if given. Returns false when an
         * unconfirmed pairing's wait for the Mac has run out; the state is then set.
         */
        private suspend fun backOff(reason: WaitReason?, relayClose: RelayClose?): Boolean {
            // A store failure says nothing about the Mac: never give up (and wipe) because of it.
            if (reason != WaitReason.STORE_UNAVAILABLE && macGoneTooLong) return giveUp()
            if (reason != null) setState(ClientState.WaitingForMac(reason))
            var wait = nextDelayMillis(++attempt, relayClose, random)
            if (unconfirmed) {
                val remaining = (timeouts.freshMacWait - absence.elapsed).inWholeMilliseconds.coerceAtLeast(0)
                wait = minOf(wait, remaining)
            }
            log("retrying in $wait ms (attempt $attempt)")
            delay(wait)
            return true
        }

        /** The Mac stayed away past [ClientTimeouts.freshMacWait]: iTerm2 has replaced this code. */
        private suspend fun giveUp(): Boolean {
            log("the Mac was absent for ${timeouts.freshMacWait}; giving up on this code")
            forgetUnconfirmed()
            setState(ClientState.Failed(QR_EXPIRED_MESSAGE, rescanNeeded = true))
            return false
        }

        /** The relay tore an unconfirmed pairing's room down (1000 "pairing cycle cap"): the QR is dead. */
        private suspend fun rescan(): Boolean {
            log("the relay tore the pairing room down; the code is dead")
            forgetUnconfirmed()
            setState(ClientState.Failed(CYCLE_CAP_MESSAGE, rescanNeeded = true))
            return false
        }

        /** Wipes this pairing if it is still unconfirmed. */
        private suspend fun forgetUnconfirmed() {
            freshCode = null
            wipeUnconfirmed(record.code)
        }

        private suspend fun connectOnce(endpoints: RelayEndpoints): End {
            val socket = transport.open(endpoints, roomName)
            try {
                val token = when (val admitted = admit(socket)) {
                    is Admission.Token -> admitted.value
                    is Admission.Ended -> return admitted.end
                }
                if (!unconfirmed || stateFlow.value !is ClientState.ShowCode) setState(ClientState.Connecting)
                val handshake = when (val shake = handshake(socket)) {
                    is Shake.Done -> shake
                    is Shake.Failed -> return End.Retry(WaitReason.MAC_OFFLINE, shake.relayClose)
                }
                return coroutineScope {
                    val live = CompanionSession(
                        socket,
                        handshake.channel,
                        confirmationMode(),
                        onEvent = ::emitEvent,
                        log = log,
                        sink = streamSink,
                        sinkStall = timeouts.sinkStall,
                    )
                    live.start(this)
                    try {
                        val end = converse(live, handshake.hash, endpoints, token)
                        // The Mac may unpair at any point once the session is up, not only at step 7.
                        if (end != End.Stop && live.unpaired.isCompleted) macUnpaired(live) else end
                    } finally {
                        heardThisAttempt = heardThisAttempt || live.heardFromMac
                        endLink(live)
                    }
                }
            } finally {
                socket.close()
            }
        }

        /** The link id of this attempt's session once it reached step 7, until its [StreamInput.LinkDown]. */
        private var linkId: Long? = null

        /**
         * Ends [live]: it stops being current and is closed, its receive loop stops, and then, if it
         * was a link, [StreamInput.LinkDown] is sunk. Uncancellable from the close on, so a
         * cancelled flow ([cancel], [unpair], [pair]) still delivers it. Idempotent.
         */
        private suspend fun endLink(live: CompanionSession) {
            if (active?.session === live) active = null
            live.close()
            val ended = linkId ?: return
            linkId = null
            withContext(NonCancellable) {
                live.awaitStopped()
                sinkLink(StreamInput.LinkDown(ended, live.messageTooLarge))
            }
        }

        private fun confirmationMode(): ConfirmationMode = when {
            !unconfirmed -> ConfirmationMode.NONE
            macMayHavePinned -> ConfirmationMode.POSSIBLE
            else -> ConfirmationMode.REQUIRED
        }

        private suspend fun admit(socket: RelaySocket): Admission {
            val signer = if (signed) JoinSigner(record.roomSecret) else null
            val result = socket.admit(if (signer != null) Proof.Signed(signer) else Proof.Fresh)
            log("admission (${if (signed) "signed" else "fresh"}): $result")
            if (result !is AdmissionResult.Rejected || result.error != BAD_SIGNATURE) badSignatures = 0
            return when (result) {
                is AdmissionResult.Admitted -> Admission.Token(result.registrationToken)
                is AdmissionResult.Rejected -> Admission.Ended(rejected(result.error))
                is AdmissionResult.Closed -> {
                    // 1006 is a drop or the local admission timeout; "closed by client" is our own close.
                    val relayClose = result.close.takeIf { it.code != CLOSE_ABNORMAL && it.reason != LOCAL_CLOSE }
                    Admission.Ended(End.Retry(WaitReason.MAC_OFFLINE, relayClose))
                }
                is AdmissionResult.HttpFailure -> Admission.Ended(
                    if (isFatalHttp(result.status)) {
                        fail("The relay refused the connection (HTTP ${result.status}): ${result.body}")
                    } else {
                        End.Retry(WaitReason.MAC_OFFLINE)
                    },
                )
                is AdmissionResult.Unreachable -> Admission.Ended(
                    if (result.kind == UnreachableKind.TLS) fail(TLS_MESSAGE) else End.Retry(WaitReason.RELAY_UNREACHABLE),
                )
                is AdmissionResult.ProtocolError -> Admission.Ended(End.Retry(WaitReason.MAC_OFFLINE))
            }
        }

        private fun rejected(error: String): End = when {
            error == SIGNATURE_REQUIRED && !signed -> {
                // The relay holds our verifier but the register response was lost (AM:1816-1830).
                log("self-heal: the room is registered; retrying signed")
                signed = true
                End.SwitchProof
            }
            error == TICKET_REQUIRED && signed -> {
                // The relay lost the verifier and asks for a ticket (AM:1831-1845).
                log("self-heal: the room is not registered; retrying fresh")
                signed = false
                End.SwitchProof
            }
            error == BAD_SIGNATURE -> {
                badSignatures++
                if (badSignatures >= timeouts.badSignatureLimit) {
                    fail(BAD_SIGNATURE_MESSAGE, rescanNeeded = true)
                } else {
                    End.Retry(WaitReason.MAC_OFFLINE)
                }
            }
            // "mac offline", and anything else ("rate limited", "slot occupied", ...).
            else -> End.Retry(WaitReason.MAC_OFFLINE)
        }

        private fun fail(message: String, rescanNeeded: Boolean = false): End {
            setState(ClientState.Failed(message, rescanNeeded))
            return End.Stop
        }

        private suspend fun handshake(socket: RelaySocket): Shake {
            val initiator = NoiseInitiator(
                record.code.responderStaticPublicKey,
                record.phoneStaticPrivate,
                record.code.prologue,
            )
            val timeout = if (unconfirmed) timeouts.handshakeFresh else timeouts.handshakeReconnect
            return try {
                withTimeoutOrNull(timeout) {
                    socket.send(initiator.writeMessage1())
                    initiator.readMessage2(socket.incoming.receive())
                    socket.send(initiator.writeMessage3())
                    Shake.Done(initiator.handshakeHash, initiator.split(timeouts.maxMessageBytes))
                } ?: Shake.Failed(null).also { log("handshake timed out") }
            } catch (e: RelayClosedException) {
                log("relay closed during the handshake: ${e.code} ${e.reason}")
                Shake.Failed(e.close.takeIf { it.code != CLOSE_ABNORMAL })
            } catch (_: ClosedReceiveChannelException) {
                log("connection closed during the handshake")
                Shake.Failed(null)
            } catch (e: IOException) {
                log("handshake failed: $e")
                Shake.Failed(null)
            } catch (e: NoiseException) {
                log("handshake failed: ${e.message}")
                Shake.Failed(null)
            } finally {
                initiator.close()
            }
        }

        /** Steps 3 to 7 on a live session. */
        private suspend fun converse(
            live: CompanionSession,
            hash: ByteArray,
            endpoints: RelayEndpoints,
            token: String,
        ): End {
            val step = when (confirmationMode()) {
                ConfirmationMode.REQUIRED -> confirmThenHello(live, hash)
                ConfirmationMode.POSSIBLE -> recover(live, hash)
                ConfirmationMode.NONE -> hello(live)
            }
            val helloReply = when (step) {
                is HelloStep.Reply -> step.message
                is HelloStep.Ended -> return step.end
            }
            if (stateFlow.value is ClientState.ShowCode) setState(ClientState.Connecting)

            // Step 4: versions.
            if (helloReply !is MacMessage.HelloReply) {
                log("unexpected reply to hello: $helloReply")
                return End.Retry(WaitReason.MAC_OFFLINE)
            }
            if (ProtocolRevision.REVISION < helloReply.minimumPeer) return fail(UPDATE_APP_MESSAGE)
            if (helloReply.revision < ProtocolRevision.MINIMUM_PEER) return fail(UPDATE_ITERM2_MESSAGE)

            // Step 5: the room secret.
            when (val stored = rpc(live, PhoneMessage.RelayRoomSecret(record.roomSecret))) {
                null -> return lost(live)
                MacMessage.RelayRoomSecretStored -> roomSecretErrors = 0
                is MacMessage.Error -> {
                    // A Mac that is unpairing answers relayRoomSecret with an error, then sends
                    // `unpaired` (CompanionLink.swift:442-453): that is not a failure to count.
                    if (withTimeoutOrNull(UNPAIRED_GRACE) { live.unpaired.await() } != null) return lost(live)
                    roomSecretErrors++
                    log("the Mac could not store the room secret ($roomSecretErrors in a row): ${stored.code}")
                    if (roomSecretErrors >= timeouts.roomSecretErrorLimit) return fail(ROOM_SECRET_MESSAGE)
                    return End.Retry(WaitReason.MAC_OFFLINE)
                }
                else -> {
                    log("unexpected reply to relayRoomSecret: $stored")
                    return End.Retry(WaitReason.MAC_OFFLINE)
                }
            }

            // Step 6: register on every connect.
            register(endpoints, token)

            // Step 7: connected until the socket goes or the Mac unpairs. The link is current
            // before LinkUp is sunk, so the sink can send on it; Connected follows.
            attempt = 0
            val revision = helloReply.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val id = nextLinkId.getAndIncrement()
            linkId = id
            live.linkUp(id) {
                active = ActiveLink(id, live)
                sinkLink(StreamInput.LinkUp(id, revision))
            }
            setState(ClientState.Connected(revision))
            return select {
                live.unpaired.onAwait { macUnpaired(live) }
                live.ended.onAwait { End.Lost(it.relayClose) }
            }
        }

        /** The Mac sent `unpaired`: the same teardown as [unpair], without sending anything. */
        private suspend fun macUnpaired(live: CompanionSession): End {
            log("the Mac unpaired this phone")
            // Closed now; its LinkDown follows from connectOnce once the state is set, so a slow
            // sink never delays honouring the unpair.
            if (active?.session === live) active = null
            live.close()
            freshCode = null
            try {
                clearStoreWithRetry()
            } catch (e: IOException) {
                log("could not clear the store after the Mac unpaired: ${e.message}")
            }
            setState(ClientState.Failed(UNPAIRED_MESSAGE, rescanNeeded = true))
            return End.Stop
        }

        private suspend fun hello(live: CompanionSession): HelloStep =
            rpc(live, PhoneMessage.Hello())?.let { HelloStep.Reply(it) } ?: HelloStep.Ended(lost(live))

        /** Step 3: show the code, wait for the bare verdict, save CONFIRMED at once, then `hello`. */
        private suspend fun confirmThenHello(live: CompanionSession, hash: ByteArray): HelloStep {
            showCode(hash)
            macMayHavePinned = true
            val verdict: Any = withTimeoutOrNull(timeouts.confirmation) {
                select {
                    live.confirmation.onAwait { it }
                    live.ended.onAwait { CLOSED }
                }
            } ?: TIMED_OUT
            return when (verdict) {
                true -> if (checkpointConfirmed()) hello(live) else HelloStep.Ended(End.Retry(WaitReason.STORE_UNAVAILABLE))
                false -> HelloStep.Ended(declined(timedOut = false))
                TIMED_OUT -> HelloStep.Ended(declined(timedOut = true))
                else -> HelloStep.Ended(lost(live))
            }
        }

        /**
         * Step 3a: show the code and send `hello` at once, then take whichever answer comes
         * first. A hello reply means the Mac had pinned this phone; an "accepted" means it was
         * in SAS mode, and its link answers the queued hello next.
         */
        private suspend fun recover(live: CompanionSession, hash: ByteArray): HelloStep = coroutineScope {
            showCode(hash)
            val hello = async {
                try {
                    live.request(PhoneMessage.Hello())
                } catch (_: CompanionRequestException) {
                    null
                }
            }
            val first: Any = withTimeoutOrNull(timeouts.confirmation) {
                select {
                    live.confirmation.onAwait { it }
                    hello.onAwait { it ?: CLOSED }
                    live.ended.onAwait { CLOSED }
                }
            } ?: TIMED_OUT
            val step = when (first) {
                is MacMessage -> {
                    log("pending recovery: the Mac had pinned this phone")
                    if (checkpointConfirmed()) HelloStep.Reply(first) else HelloStep.Ended(End.Retry(WaitReason.STORE_UNAVAILABLE))
                }
                true -> {
                    log("pending recovery: the Mac was in SAS mode and accepted the code")
                    if (checkpointConfirmed()) {
                        withTimeoutOrNull(timeouts.rpc) { hello.await() }?.let { HelloStep.Reply(it) }
                            ?: HelloStep.Ended(lost(live))
                    } else {
                        HelloStep.Ended(End.Retry(WaitReason.STORE_UNAVAILABLE))
                    }
                }
                false -> HelloStep.Ended(declined(timedOut = false))
                TIMED_OUT -> HelloStep.Ended(declined(timedOut = true))
                else -> HelloStep.Ended(lost(live))
            }
            hello.cancel()
            step
        }

        /** Shows the code; the Mac is present, so its absence stops counting. */
        private fun showCode(hash: ByteArray) {
            absence.pause()
            codeShownAt = timeSource.markNow()
            setState(ClientState.ShowCode(sasCode(hash)))
        }

        /** Rejection or timeout of the code: wipe the half-made pairing and ask for a rescan. */
        private suspend fun declined(timedOut: Boolean): End {
            log(if (timedOut) "the code was not confirmed in time" else "the Mac rejected the code")
            forgetUnconfirmed()
            setState(ClientState.Failed(if (timedOut) CONFIRMATION_TIMEOUT_MESSAGE else REJECTED_MESSAGE, rescanNeeded = true))
            return End.Stop
        }

        /** Saves CONFIRMED the moment the Mac is known to have pinned this phone. False if the store is unavailable. */
        private suspend fun checkpointConfirmed(): Boolean {
            if (!unconfirmed) return true
            return try {
                record = onStore { advancePairing(store, record, PairingStage.CONFIRMED) }.also(::notePaired)
                freshCode = null
                true
            } catch (e: IOException) {
                // PairingStoreUnavailableException or a failed write: nothing was saved.
                log("could not save CONFIRMED; recovering on the next connect: ${e.message}")
                false
            }
        }

        /** One request bounded by [ClientTimeouts.rpc]; null on a timeout or a closed connection. */
        private suspend fun rpc(live: CompanionSession, message: PhoneMessage): MacMessage? {
            val name = message.javaClass.simpleName
            return try {
                live.request(message, timeouts.rpc, onSent = {})
                    .also { if (it == null) log("no reply to $name within ${timeouts.rpc}") }
            } catch (e: CompanionRequestException) {
                log("$name failed: ${e.message}")
                null
            }
        }

        /**
         * The attempt ended during setup: retry, passing on the relay's close frame if it sent one.
         * A session that is already closing gets a moment to finish, so a close frame racing a
         * failed send (such as "displaced", which means a 60 s wait) is not lost.
         */
        private suspend fun lost(live: CompanionSession): End {
            val ended = live.endedWith
                ?: if (live.closing) withTimeoutOrNull(CLOSE_GRACE) { live.ended.await() } else null
            return End.Retry(WaitReason.MAC_OFFLINE, ended?.relayClose)
        }

        private suspend fun register(endpoints: RelayEndpoints, token: String) {
            val verifier = JoinSigner(record.roomSecret).verifier
            when (val result = transport.register(endpoints, roomName, token, verifier)) {
                RegisterResult.Registered -> {
                    log("registered with the relay")
                    if (record.stage != PairingStage.REGISTERED) {
                        try {
                            record = onStore { advancePairing(store, record, PairingStage.REGISTERED) }.also(::notePaired)
                        } catch (e: IOException) {
                            log("could not save REGISTERED; registering again next connect: ${e.message}")
                        }
                    }
                    signed = record.stage == PairingStage.REGISTERED
                }
                // 5xx (the relay down behind tailscale serve) and 4xx alike: keep the stage and
                // register again on the next connect. Never save REGISTERED on a failure.
                is RegisterResult.Refused -> {
                    val body = result.body.take(LOG_BODY_CHARS)
                    if (result.status >= HTTP_SERVER_ERROR) {
                        log("register: relay unavailable (HTTP ${result.status}, 5xx), will retry: $body")
                    } else {
                        log("register refused (HTTP ${result.status}, 4xx), will retry: $body")
                    }
                }
                is RegisterResult.TransportError -> log("register failed: ${result.cause}")
            }
        }
    }

    private fun emitEvent(message: MacMessage) {
        // With no collector the message is simply not delivered; with a slow one the buffer fills.
        if (!eventFlow.tryEmit(message)) log("dropped an unsolicited ${message.javaClass.simpleName}: the events buffer is full")
    }

    private sealed interface Admission {
        class Token(val value: String) : Admission {
            override fun toString(): String = "Token(<redacted>)"
        }

        class Ended(val end: End) : Admission
    }

    private sealed interface Shake {
        class Done(val hash: ByteArray, val channel: NoiseChannel) : Shake

        class Failed(val relayClose: RelayClose?) : Shake
    }

    private companion object {
        const val TAG = "CompanionClient"
        const val EVENT_BUFFER = 64
        const val CLEAR_ATTEMPTS = 3
        const val LOG_BODY_CHARS = 200
        const val HTTP_SERVER_ERROR = 500
        const val CLOSE_ABNORMAL = 1006
        const val LOCAL_CLOSE = "closed by client"
        const val SIGNATURE_REQUIRED = "signature required"
        const val TICKET_REQUIRED = "ticket required"
        const val BAD_SIGNATURE = "bad signature"
        val CLOSE_GRACE = 1.seconds
        val UNPAIRED_GRACE = 1.seconds
        val CLOSED = Any()
        val TIMED_OUT = Any()

        const val QR_EXPIRED_MESSAGE =
            "iTerm2 isn't showing that pairing code any more. Open pairing on the Mac and scan again."
        const val BAD_SIGNATURE_MESSAGE =
            "The relay refused this phone's signature. Check the relay address matches iTerm2's " +
                "companionRelayOrigin, or another device may have taken the relay room. If iTerm2 still " +
                "lists this phone, unpair it on the Mac, then scan again."
        const val TLS_MESSAGE =
            "The relay's certificate was rejected. Check the relay address and its tailscale serve certificate."
        const val REJECTED_MESSAGE = "The code was rejected on your Mac. Scan again to pair."
        const val CONFIRMATION_TIMEOUT_MESSAGE = "The code wasn't entered on your Mac in time. Scan again to pair."
        const val UPDATE_APP_MESSAGE = "Update the app: this iTerm2 needs a newer iTerm2 Buddy."
        const val UPDATE_ITERM2_MESSAGE = "Update iTerm2: this app needs iTerm2 3.7.7 or later."
        const val ROOM_SECRET_MESSAGE = "Your Mac couldn't save the pairing. Check iTerm2, then reconnect."
        const val UNPAIRED_MESSAGE = "Unpaired on the Mac"
        const val CYCLE_CAP_MESSAGE = "The relay closed this pairing. Open pairing on the Mac and scan again."
        const val BAD_ORIGIN_MESSAGE = "The stored relay address can't be used. Scan again."
        const val STORE_FAILED_MESSAGE = "This phone couldn't save the pairing. Scan again."
    }
}
