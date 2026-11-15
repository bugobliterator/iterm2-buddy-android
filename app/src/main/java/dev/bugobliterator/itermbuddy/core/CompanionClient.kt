package dev.bugobliterator.itermbuddy.core

import android.util.Log
import dev.bugobliterator.itermbuddy.core.noise.NoiseChannel
import dev.bugobliterator.itermbuddy.core.noise.NoiseException
import dev.bugobliterator.itermbuddy.core.noise.NoiseInitiator
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.pairing.sasCode
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

    /** Show [code]; the user types it on the Mac. */
    data class ShowCode(val code: String) : ClientState

    /** Paired and talking; [macRevision] is the Mac's protocol revision from its `hello`. */
    data class Connected(val macRevision: Int) : ClientState

    /**
     * The flow stopped. [rescanNeeded] means the UI offers Scan again; otherwise the stored pairing
     * is kept and [CompanionClient.start] tries again. Besides the cases in the plan:
     * - an `Unreachable(TLS)` admission ("The relay's certificate was rejected …"), with no retry;
     * - a relay close 1000 "pairing cycle cap" ([requiresRescan]): an unconfirmed pairing is wiped
     *   and [rescanNeeded] is true.
     */
    data class Failed(val message: String, val rescanNeeded: Boolean) : ClientState
}

/**
 * The phone's connect flow to iTerm2 through the relay: admission, Noise XK, the SAS code,
 * `hello`, the room secret and `/register` (*notes* → Relay admission, Noise, After pairing).
 * Owned by the foreground service; it holds at most one connection.
 *
 * [pair], [start], [cancel] and [unpair] are serialised: each one cancels and waits for the
 * previous one's work before it starts.
 *
 * @param store the one pairing; every blocking store call runs on Dispatchers.IO.
 * @param scope where the flow runs; cancelling it stops everything, as a process kill would.
 * @param endpointsFor where to connect for a stored relay origin; null refuses the origin.
 * @param random jitter for [nextDelayMillis]; tests pass a seeded one.
 * @param log diagnostics; never given secrets or registration tokens.
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
) {
    constructor(
        store: PairingStore,
        http: OkHttpClient,
        scope: CoroutineScope,
        endpointsFor: (String) -> RelayEndpoints? = { RelayEndpoints.forOrigin(it) },
        timeouts: ClientTimeouts = ClientTimeouts(),
        random: Random = Random.Default,
        log: (String) -> Unit = { Log.i(TAG, it) },
    ) : this(
        store,
        OkHttpRelayTransport(http, timeouts.admission.inWholeMilliseconds, timeouts.register.inWholeMilliseconds),
        scope,
        endpointsFor,
        timeouts,
        random,
        TimeSource.Monotonic,
        Dispatchers.IO,
        log,
    )

    private val stateFlow = MutableStateFlow<ClientState>(ClientState.Unpaired)

    /** Starts as [ClientState.Unpaired] until [start] has looked at the store. */
    val state: StateFlow<ClientState> = stateFlow.asStateFlow()

    private val eventFlow = MutableSharedFlow<MacMessage>(extraBufferCapacity = EVENT_BUFFER)

    /** Unsolicited messages from the Mac: envelopes with no request ID or one nobody waits for. */
    val events: SharedFlow<MacMessage> = eventFlow.asSharedFlow()

    private val jobLock = Any()
    private var job: Job? = null

    @Volatile
    private var session: CompanionSession? = null

    /** The record [pair] began, while it is still unconfirmed; [cancel] wipes it. */
    @Volatile
    private var freshRecord: PairingRecord? = null

    /** Starts a new pairing for a scanned [code], replacing whatever is stored. */
    fun pair(code: PairingCode) {
        launchControl { runFresh(code) }
    }

    /**
     * Resumes the stored pairing. A `PENDING_CONFIRMATION` record is kept and recovered
     * (flow step 3a), because the Mac may already have pinned this phone.
     */
    fun start() {
        launchControl { runStored() }
    }

    /** Stops the current attempt. A fresh pairing is wiped; a stored one stays and goes [ClientState.Idle]. */
    fun cancel() {
        launchControl {
            val fresh = freshRecord
            if (fresh != null) {
                freshRecord = null
                wipe(fresh)
                setState(ClientState.Unpaired)
            } else {
                setState(if (hasStoredPairing()) ClientState.Idle else ClientState.Unpaired)
            }
        }
    }

    /**
     * Cancels reconnect work, sends `unpairing` without waiting for a reply, clears the store and
     * goes [ClientState.Unpaired], even if the send fails or nothing is connected.
     *
     * @throws PairingStoreUnavailableException if the store still cannot be cleared after a few
     *   tries; the state is then [ClientState.Idle] and the pairing is still stored.
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
                previous?.cancelAndJoin()
                freshRecord = null
                clearStoreWithRetry()
                setState(ClientState.Unpaired)
                result.complete(Unit)
            } catch (e: PairingStoreUnavailableException) {
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
     * Sends [message] to the Mac and returns its reply, bounded by [ClientTimeouts.rpc].
     *
     * @throws CompanionRequestException when not connected, on a timeout, or if the connection drops.
     */
    suspend fun request(message: PhoneMessage): MacMessage {
        val live = session ?: throw CompanionRequestException("not connected")
        return withTimeoutOrNull(timeouts.rpc) { live.request(message) }
            ?: throw CompanionRequestException("no reply within ${timeouts.rpc}")
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
                if (cancelPrevious) previous?.cancelAndJoin()
                try {
                    block(if (cancelPrevious) null else previous)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("connect flow failed: $e")
                    setState(ClientState.Failed("Something went wrong: ${e.message}", rescanNeeded = false))
                }
            }
            job = next
            next
        }

    private suspend fun <T> onStore(block: () -> T): T = withContext(storeContext) { block() }

    private suspend fun hasStoredPairing(): Boolean = try {
        onStore { store.load() } != null
    } catch (_: PairingStoreUnavailableException) {
        true
    }

    /** Clears the store if it still holds [record]'s pairing. A store that is unavailable keeps it. */
    private suspend fun wipe(record: PairingRecord) {
        try {
            onStore {
                store.withLock {
                    if (store.load()?.isSamePairingAs(record) == true) store.clear()
                }
            }
        } catch (e: PairingStoreUnavailableException) {
            log("could not wipe the pairing: ${e.message}")
        }
    }

    private suspend fun clearStoreWithRetry() {
        var attempt = 1
        while (true) {
            try {
                onStore { store.clear() }
                return
            } catch (e: PairingStoreUnavailableException) {
                if (attempt >= CLEAR_ATTEMPTS) throw e
                log("store unavailable while unpairing: ${e.message}")
                delay(nextDelayMillis(attempt++, null, random))
            }
        }
    }

    private suspend fun runFresh(code: PairingCode) {
        setState(ClientState.Connecting)
        val deadline = timeSource.markNow() + timeouts.freshMacWait
        var attempt = 0
        var record: PairingRecord? = null
        while (record == null) {
            try {
                record = onStore { beginFreshPairing(store, code) }
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
        freshRecord = record
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

        /** While unconfirmed: when to give up on an absent Mac. Restarts at each completed handshake. */
        private var macWaitDeadline = timeSource.markNow() + timeouts.freshMacWait

        private val unconfirmed get() = record.stage == PairingStage.PENDING_CONFIRMATION

        suspend fun run() {
            val endpoints = endpointsFor(record.code.relayOrigin)
            if (endpoints == null) {
                setState(ClientState.Failed(BAD_ORIGIN_MESSAGE, rescanNeeded = true))
                return
            }
            while (true) {
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
                            end.relayClose != null && requiresRescan(end.relayClose) -> rescan()
                            else -> {
                                setState(ClientState.Connecting)
                                backOff(null, end.relayClose)
                            }
                        }
                    }
                    is End.Retry -> {
                        proofSwitches = 0
                        when {
                            end.relayClose != null && requiresRescan(end.relayClose) -> rescan()
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
            if (unconfirmed && macWaitDeadline.hasPassedNow()) {
                log("the Mac did not come back within ${timeouts.freshMacWait}; giving up on this code")
                freshRecord = null
                wipe(record)
                setState(ClientState.Failed(QR_EXPIRED_MESSAGE, rescanNeeded = true))
                return false
            }
            if (reason != null) setState(ClientState.WaitingForMac(reason))
            var wait = nextDelayMillis(++attempt, relayClose, random)
            if (unconfirmed) wait = minOf(wait, remainingMillis(macWaitDeadline))
            log("retrying in $wait ms (attempt $attempt)")
            delay(wait)
            return true
        }

        /** The relay tore the pairing room down (1000 "pairing cycle cap"): the QR is dead. */
        private suspend fun rescan(): Boolean {
            log("the relay tore the pairing room down; the code is dead")
            if (unconfirmed) {
                freshRecord = null
                wipe(record)
            }
            setState(ClientState.Failed(CYCLE_CAP_MESSAGE, rescanNeeded = true))
            return false
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
                macWaitDeadline = timeSource.markNow() + timeouts.freshMacWait
                return coroutineScope {
                    val live = CompanionSession(
                        socket,
                        handshake.channel,
                        confirmationMode(),
                        onEvent = { eventFlow.tryEmit(it) },
                        log = log,
                    )
                    live.start(this)
                    try {
                        converse(live, handshake.hash, endpoints, token)
                    } finally {
                        session = null
                        live.close()
                    }
                }
            } finally {
                socket.close()
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
                    Shake.Done(initiator.handshakeHash, initiator.split())
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

            // Step 7: connected until the socket goes or the Mac unpairs.
            attempt = 0
            session = live
            setState(ClientState.Connected(helloReply.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
            return select {
                live.unpaired.onAwait {
                    log("the Mac unpaired this phone")
                    session = null
                    live.close()
                    freshRecord = null
                    try {
                        clearStoreWithRetry()
                    } catch (e: PairingStoreUnavailableException) {
                        log("could not clear the store after the Mac unpaired: ${e.message}")
                    }
                    setState(ClientState.Failed(UNPAIRED_MESSAGE, rescanNeeded = true))
                    End.Stop
                }
                live.ended.onAwait { End.Lost(it.relayClose) }
            }
        }

        private suspend fun hello(live: CompanionSession): HelloStep =
            rpc(live, PhoneMessage.Hello())?.let { HelloStep.Reply(it) } ?: HelloStep.Ended(lost(live))

        /** Step 3: show the code, wait for the bare verdict, save CONFIRMED at once, then `hello`. */
        private suspend fun confirmThenHello(live: CompanionSession, hash: ByteArray): HelloStep {
            setState(ClientState.ShowCode(sasCode(hash)))
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
            setState(ClientState.ShowCode(sasCode(hash)))
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

        /** Rejection or timeout of the code: wipe the half-made pairing and ask for a rescan. */
        private suspend fun declined(timedOut: Boolean): End {
            log(if (timedOut) "the code was not confirmed in time" else "the Mac rejected the code")
            freshRecord = null
            wipe(record)
            setState(ClientState.Failed(if (timedOut) CONFIRMATION_TIMEOUT_MESSAGE else REJECTED_MESSAGE, rescanNeeded = true))
            return End.Stop
        }

        /** Saves CONFIRMED the moment the Mac is known to have pinned this phone. False if the store is unavailable. */
        private suspend fun checkpointConfirmed(): Boolean {
            if (!unconfirmed) return true
            return try {
                record = onStore { advancePairing(store, record, PairingStage.CONFIRMED) }
                freshRecord = null
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
                withTimeoutOrNull(timeouts.rpc) { live.request(message) }
                    .also { if (it == null) log("no reply to $name within ${timeouts.rpc}") }
            } catch (e: CompanionRequestException) {
                log("$name failed: ${e.message}")
                null
            }
        }

        /** The attempt ended during setup: retry, passing on the relay's close frame if it sent one. */
        private fun lost(live: CompanionSession): End {
            return End.Retry(WaitReason.MAC_OFFLINE, live.endedWith?.relayClose)
        }

        private suspend fun register(endpoints: RelayEndpoints, token: String) {
            val verifier = JoinSigner(record.roomSecret).verifier
            when (val result = transport.register(endpoints, roomName, token, verifier)) {
                RegisterResult.Registered -> {
                    log("registered with the relay")
                    if (record.stage != PairingStage.REGISTERED) {
                        try {
                            record = onStore { advancePairing(store, record, PairingStage.REGISTERED) }
                        } catch (e: IOException) {
                            log("could not save REGISTERED; registering again next connect: ${e.message}")
                        }
                    }
                    signed = record.stage == PairingStage.REGISTERED
                }
                // 5xx (the relay down behind tailscale serve) and 4xx alike: keep the stage and
                // register again on the next connect. Never save REGISTERED on a failure.
                is RegisterResult.Refused ->
                    log("register refused (HTTP ${result.status}): ${result.body.take(LOG_BODY_CHARS)}")
                is RegisterResult.TransportError -> log("register failed: ${result.cause}")
            }
        }
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
        const val CLOSE_ABNORMAL = 1006
        const val LOCAL_CLOSE = "closed by client"
        const val SIGNATURE_REQUIRED = "signature required"
        const val TICKET_REQUIRED = "ticket required"
        const val BAD_SIGNATURE = "bad signature"
        val CLOSED = Any()
        val TIMED_OUT = Any()

        const val QR_EXPIRED_MESSAGE =
            "iTerm2 isn't showing that pairing code any more. Open pairing on the Mac and scan again."
        const val BAD_SIGNATURE_MESSAGE =
            "The relay refused this phone's signature. Check the relay address matches iTerm2's companionRelayOrigin."
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
