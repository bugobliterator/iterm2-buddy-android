package dev.bugobliterator.itermbuddy.core

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.roomName
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.JoinSigner
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.UnreachableKind
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.ProtocolRevision
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingRecord
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.PairingStore
import dev.bugobliterator.itermbuddy.store.PairingStoreUnavailableException
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import dev.bugobliterator.itermbuddy.ui.AppScreen
import dev.bugobliterator.itermbuddy.ui.appScreenFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The connect flow against an in-process relay ([FakeRelay]) and a [FakeMac] built from the real
 * Noise responder, join signer and golden samples. Everything runs in virtual time, so the
 * minutes these scenarios span take milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionClientTest {
    private val macKeys = newStaticKeyPair()
    private val code = PairingCode(macKeys.second, "client-test-pid", ORIGIN)
    private val room = roomName(code)
    private val endpoints = RelayEndpoints(ORIGIN, "wss://unused.invalid/", "https://unused.invalid")

    /** One client, relay, Mac and store, with every state the client passes through and when. */
    private inner class Harness(
        private val test: TestScope,
        val relay: FakeRelay = FakeRelay(ORIGIN),
        val store: PairingStore = InMemoryPairingStore(),
        timeouts: ClientTimeouts = ClientTimeouts(),
        storeContext: CoroutineContext = EmptyCoroutineContext,
    ) {
        val logs = CopyOnWriteArrayList<String>()
        val history = CopyOnWriteArrayList<Pair<Long, ClientState>>()
        private val connections = MutableStateFlow(0)
        val client = CompanionClient(
            store,
            relay,
            test.backgroundScope,
            { endpoints },
            timeouts,
            Random(SEED),
            test.testScheduler.timeSource,
            storeContext,
            { logs += it },
        )
        val mac = FakeMac(relay, code, macKeys.first, test.backgroundScope)

        init {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                client.state.collect {
                    history += test.testScheduler.currentTime to it
                    if (it is ClientState.Connected) connections.value++
                }
            }
        }

        val states: List<ClientState> get() = history.map { it.second }

        suspend fun await(within: Duration = 30.minutes, predicate: (ClientState) -> Boolean): ClientState =
            withTimeout(within) { client.state.first(predicate) }

        /** Waits until the client has reached Connected [times] times in all. */
        suspend fun awaitConnected(times: Int = 1): ClientState.Connected {
            withTimeout(30.minutes) { connections.first { it >= times } }
            return client.state.value as ClientState.Connected
        }

        /** Stores this phone's pairing at [stage], as an earlier run of the app would have. */
        fun stored(stage: PairingStage): PairingRecord {
            var record = beginFreshPairing(store, code)
            if (stage >= PairingStage.CONFIRMED) record = advancePairing(store, record, PairingStage.CONFIRMED)
            if (stage >= PairingStage.REGISTERED) {
                record = advancePairing(store, record, PairingStage.REGISTERED)
                relay.lock(room, JoinSigner(record.roomSecret).verifier)
                mac.roomSecret = record.roomSecret
            }
            return record
        }

        /** The Mac accepted the code for [record] earlier and pinned its static key. */
        fun macPinned(record: PairingRecord) {
            mac.pinnedPhoneStatic = publicKeyOf(record.phoneStaticPrivate)
        }

        fun stage(): PairingStage? = store.load()?.stage
    }

    // Pairing basics

    @Test
    fun freshPairingReachesConnectedAndRegisters() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)

        val shown = h.await { it is ClientState.ShowCode } as ClientState.ShowCode
        assertEquals("both ends derive the same code", shown.code, h.mac.awaitingCode.first { it != null })
        assertEquals(PairingStage.PENDING_CONFIRMATION, h.stage())
        assertTrue("the Mac is silent until the code is typed", h.mac.received.isEmpty())

        h.mac.enterCode(accept = true)
        assertEquals(FakeMac.MAC_REVISION, h.awaitConnected().macRevision)
        val record = h.store.load()!!
        assertEquals(PairingStage.REGISTERED, record.stage)
        assertArrayEquals(JoinSigner(record.roomSecret).verifier, h.relay.verifier(room))
        assertArrayEquals("the Mac holds the room secret", record.roomSecret, h.mac.roomSecret)

        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))

        // Sends to the Mac match the v3.7.7 golden samples, request IDs included.
        val sent = h.mac.receivedEnvelopes()
        assertEquals(withRevision(FakeMac.fixtureJson("hello-phone.json"), ProtocolRevision.REVISION), sent[0])
        val secretSample = FakeMac.fixtureJson("relay-room-secret.json")
        assertEquals(withSecret(secretSample, record.roomSecret), sent[1])
        assertEquals(FakeMac.fixtureJson("ping.json"), sent[2])
        assertEquals(3, sent.size)

        val order = h.states.filter { it is ClientState.ShowCode || it is ClientState.Connected }
        assertTrue("$order", order.first() is ClientState.ShowCode && order.last() is ClientState.Connected)
        assertFalse(h.states.any { it is ClientState.WaitingForMac })
        assertEquals(listOf(false), h.relay.phoneAdmissions.map { it.signed })
    }

    @Test
    fun acceptedIsSavedBeforeHello() = runTest {
        val h = Harness(this)
        h.mac.answering = false
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }

        h.mac.enterCode(accept = true)
        runCurrent()
        assertEquals("hello is in flight", listOf("hello"), h.mac.receivedCases())
        assertEquals("the Mac has pinned this phone: CONFIRMED is already saved", PairingStage.CONFIRMED, h.stage())

        // The stalled hello times out; the next connect is a plain reconnect.
        h.mac.answering = true
        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertEquals(1, h.mac.confirmationsSent.get())
    }

    // Mac not there

    @Test
    fun storedPairingWaitsForMacOffline() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.client.start()

        assertEquals(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE), h.await { it is ClientState.WaitingForMac })
        advanceTimeBy(10.minutes)
        assertEquals(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE), h.client.state.value)
        assertTrue("retries indefinitely: ${h.relay.phoneAdmissions.size}", h.relay.phoneAdmissions.size > 10)
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertTrue(h.relay.phoneAdmissions.all { it.signed && it.result.contains("mac offline") })

        h.mac.start()
        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun storedPairingWaitsWhenRelayUnreachable() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.relay.reachable = false
        h.mac.start()
        h.client.start()

        assertEquals(ClientState.WaitingForMac(WaitReason.RELAY_UNREACHABLE), h.await { it is ClientState.WaitingForMac })
        advanceTimeBy(10.minutes)
        assertEquals(ClientState.WaitingForMac(WaitReason.RELAY_UNREACHABLE), h.client.state.value)
        assertEquals(PairingStage.REGISTERED, h.stage())

        h.relay.reachable = true
        h.awaitConnected()
    }

    @Test
    fun freshPairingGivesUpAfterQrExpiry() = runTest {
        val h = Harness(this)
        h.client.pair(code)
        assertEquals(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE), h.await { it is ClientState.WaitingForMac })
        val started = currentTime

        advanceTimeBy(119.seconds)
        assertEquals(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE), h.client.state.value)
        assertNotNull("still pending inside the window", h.store.load())

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertTrue(failed.message, failed.message.contains("isn't showing that pairing code"))
        assertTrue("gave up at ${currentTime - started} ms", currentTime - started in 119_000..121_000)
        assertNull("the half-made pairing is wiped", h.store.load())
    }

    @Test
    fun cancelDuringFreshWaitWipes() = runTest {
        val h = Harness(this)
        h.client.pair(code)
        h.await { it is ClientState.WaitingForMac }
        assertNotNull(h.store.load())

        h.client.cancel()
        assertEquals(ClientState.Unpaired, h.await { it !is ClientState.WaitingForMac })
        assertNull(h.store.load())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals("no retries after cancel", attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun cancelDuringStoredWaitKeepsPairing() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.client.start()
        h.await { it is ClientState.WaitingForMac }

        h.client.cancel()
        assertEquals(ClientState.Idle, h.await { it !is ClientState.WaitingForMac })
        assertEquals(record, h.store.load())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals("no retries after cancel", attempts, h.relay.phoneAdmissions.size)

        // start() resumes it.
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()
    }

    // Confirmation

    @Test
    fun rejectedCodeWipesStoreAndAsksRescan() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }

        h.mac.enterCode(accept = false)
        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertTrue(failed.message, failed.message.contains("rejected"))
        assertNull(h.store.load())
        advanceTimeBy(1.seconds)
        assertTrue("the Mac advertises a new pid", h.mac.code.pairingID != code.pairingID)
        assertTrue(h.relay.macParked(roomName(h.mac.code)))
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals(attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun confirmationTimeoutWipesStore() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        val shownAt = currentTime

        advanceTimeBy(179.seconds)
        assertTrue(h.client.state.value is ClientState.ShowCode)
        assertEquals(PairingStage.PENDING_CONFIRMATION, h.stage())

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertEquals(180_000L, currentTime - shownAt)
        assertNull(h.store.load())
    }

    // Process death

    @Test
    fun killAfterMacAcceptBeforeCheckpointRecovers() = runTest {
        val h = Harness(this)
        // The Mac sent "accepted" and pinned the phone; the app died before saving CONFIRMED.
        val record = h.stored(PairingStage.PENDING_CONFIRMATION)
        h.macPinned(record)
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        val shown = h.history.filter { it.second is ClientState.ShowCode }
        assertEquals("the code shows while the Mac's state is unknown", 1, shown.size)
        val after = h.history[h.history.indexOf(shown.single()) + 1]
        assertTrue("$after", after.second is ClientState.Connecting || after.second is ClientState.Connected)
        assertTrue("replaced within the hello round trip", after.first - shown.single().first <= 15_000)
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertTrue(h.store.load()!!.isSamePairingAs(record))
        assertEquals("the pinned Mac sends no confirmation", 0, h.mac.confirmationsSent.get())
        assertEquals("same static key: the pinned Mac accepted it", 0, h.mac.staticMismatches.get())
        assertEquals(1, h.mac.handshakes.get())
    }

    @Test
    fun pendingRecoveryWhileMacStillInSasShowsCode() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.PENDING_CONFIRMATION)
        h.mac.start()
        h.client.start()

        val shown = h.await { it is ClientState.ShowCode } as ClientState.ShowCode
        assertEquals(shown.code, h.mac.awaitingCode.first { it != null })
        runCurrent()
        assertTrue("the early hello waits in the Mac's queue", h.mac.received.isEmpty())
        assertEquals(PairingStage.PENDING_CONFIRMATION, h.stage())

        h.mac.enterCode(accept = true)
        h.awaitConnected()
        assertEquals("the queued hello is answered after the verdict", "hello", h.mac.receivedCases().first())
        assertEquals(1, h.mac.confirmationsSent.get())
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun pendingRecoveryWithMacGoneClears() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.PENDING_CONFIRMATION)
        h.client.start()
        h.await { it is ClientState.WaitingForMac }
        val started = currentTime

        advanceTimeBy(119.seconds)
        assertNotNull("kept inside the window", h.store.load())
        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertTrue(currentTime - started in 119_000..121_000)
        assertNull(h.store.load())
    }

    @Test
    fun killAfterConfirmationReconnectsWithoutCode() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        assertFalse(h.states.any { it is ClientState.ShowCode })
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertEquals(listOf(false), h.relay.phoneAdmissions.map { it.signed })
        assertArrayEquals(JoinSigner(record.roomSecret).verifier, h.relay.verifier(room))
    }

    @Test
    fun lostRegisterResponseSelfHeals() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        // The relay stored the verifier, but the 200 never reached the phone.
        h.relay.lock(room, JoinSigner(record.roomSecret).verifier)
        h.mac.roomSecret = record.roomSecret
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        val admissions = h.relay.phoneAdmissions
        assertEquals(2, admissions.size)
        assertFalse(admissions[0].signed)
        assertTrue(admissions[0].result, admissions[0].result.contains("signature required"))
        assertTrue(admissions[1].signed)
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun confirmedCheckpointFailureRecoversOnReconnect() = runTest {
        val store = FlakyStore(InMemoryPairingStore()).apply { failSavesAt = PairingStage.CONFIRMED; saveFailures = 1 }
        val h = Harness(this, store = store)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }
        h.mac.enterCode(accept = true)

        assertEquals(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE), h.await { it is ClientState.WaitingForMac })
        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertEquals("the reconnect found the pinned Mac", 1, h.mac.confirmationsSent.get())
    }

    // Registration

    @Test
    fun refusedRegistrationIsNotSaved() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.relay.registerOverrides += RegisterResult.Refused(502, "Bad Gateway")
        h.relay.registerOverrides += RegisterResult.Refused(403, """{"ok":false,"error":"bad token"}""")
        h.mac.start()
        h.client.start()

        h.awaitConnected(1)
        assertEquals("stays connected; stage kept", PairingStage.CONFIRMED, h.stage())
        assertTrue(h.logs.any { it.contains("HTTP 502") })

        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals(PairingStage.CONFIRMED, h.stage())
        assertTrue(h.logs.any { it.contains("HTTP 403") })

        h.mac.dropSocket()
        h.awaitConnected(3)
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertEquals(3, h.relay.registerResults.size)
        assertEquals("unsigned until registered", listOf(false, false, false), h.relay.phoneAdmissions.map { it.signed })
    }

    @Test
    fun relayLostVerifierIsRelocked() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected(1)

        h.relay.loseState()
        assertNull(h.relay.verifier(room))
        h.mac.dropSocket()
        h.awaitConnected(2)

        assertTrue("signed admit succeeds open", h.relay.phoneAdmissions.last().signed)
        assertArrayEquals("registered again", JoinSigner(record.roomSecret).verifier, h.relay.verifier(room))
        assertEquals(RegisterResult.Registered, h.relay.registerResults.last())
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    // Self-heal and failures

    @Test
    fun signatureRequiredSelfHealsToSigned() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.relay.phoneAdmissionOverrides += AdmissionResult.Rejected("signature required")
        h.mac.start()
        runCurrent()
        h.client.start()

        h.awaitConnected()
        assertEquals(listOf(false, true), h.relay.phoneAdmissions.map { it.signed })
        assertEquals("retried at once", 0L, h.history.first { it.second is ClientState.Connected }.first)
        assertFalse(h.states.any { it is ClientState.WaitingForMac })
    }

    @Test
    fun ticketRequiredSelfHealsToFresh() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        // The relay lost the verifier (and here answers as an attesting relay would).
        h.relay.loseState()
        h.relay.phoneAdmissionOverrides += AdmissionResult.Rejected("ticket required")
        h.mac.start()
        runCurrent()
        h.client.start()

        h.awaitConnected()
        assertEquals(listOf(true, false), h.relay.phoneAdmissions.map { it.signed })
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertArrayEquals("the fresh connect re-locks the room", JoinSigner(record.roomSecret).verifier, h.relay.verifier(room))
    }

    @Test
    fun badSignatureSurfacesAfterThreeRejections() = runTest {
        // QR origin without the port, relay RELAY_ORIGIN with an explicit :443: every signed join
        // verifies over a different string.
        val h = Harness(this, relay = FakeRelay("$ORIGIN:443"))
        h.stored(PairingStage.REGISTERED)
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertTrue(failed.message, failed.message.contains("companionRelayOrigin"))
        assertTrue(failed.message, failed.message.contains("another device may have taken the relay room"))
        assertTrue(failed.message, failed.message.contains("unpair it on the Mac"))
        assertEquals(3, h.relay.phoneAdmissions.size)
        assertTrue(h.relay.phoneAdmissions.all { it.result.contains("bad signature") })
        advanceTimeBy(10.minutes)
        assertEquals("no silent retry loop", 3, h.relay.phoneAdmissions.size)
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun fatalHttpFailsWithoutRetry() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.CONFIRMED)
        h.relay.phoneAdmissionOverrides += AdmissionResult.HttpFailure(403, """{"error":"origin not allowed"}""")
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertEquals("""The relay refused the connection (HTTP 403): {"error":"origin not allowed"}""", failed.message)
        assertFalse(failed.rescanNeeded)
        advanceTimeBy(10.minutes)
        assertEquals(1, h.relay.phoneAdmissions.size)
        assertEquals(PairingStage.CONFIRMED, h.stage())
    }

    @Test
    fun unknownRejectionRetries() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.relay.phoneAdmissionOverrides += listOf(
            AdmissionResult.Rejected("rate limited"),
            AdmissionResult.Rejected("slot occupied"),
            AdmissionResult.Closed(1008, "admission timeout"),
            AdmissionResult.HttpFailure(429),
            AdmissionResult.HttpFailure(503),
            AdmissionResult.ProtocolError("bad result"),
        )
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        assertTrue(h.states.contains(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE)))
        assertEquals(7, h.relay.phoneAdmissions.size)
    }

    @Test
    fun pairingCycleCapAsksRescan() = runTest {
        val h = Harness(this)
        h.relay.phoneAdmissionOverrides += AdmissionResult.Closed(1000, "pairing cycle cap")
        h.client.pair(code)

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertNull("the dead pairing is wiped", h.store.load())
        advanceTimeBy(10.minutes)
        assertEquals("no backoff loop", 1, h.relay.phoneAdmissions.size)
    }

    @Test
    fun tlsFailureFailsWithoutRetry() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.REGISTERED)
        h.relay.phoneAdmissionOverrides += AdmissionResult.Unreachable(UnreachableKind.TLS, "SSLHandshakeException")
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.message, failed.message.startsWith("The relay's certificate was rejected"))
        assertFalse(failed.rescanNeeded)
        assertFalse(h.states.any { it is ClientState.WaitingForMac })
        advanceTimeBy(10.minutes)
        assertEquals(1, h.relay.phoneAdmissions.size)
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    /**
     * Scan again from a stored pairing that failed: the home must never show, not even while the
     * new record cannot be written. Records the root screen on every state and paired change.
     */
    @Test
    fun rescanFromStoredFailureNeverShowsHome() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store)
        // Confirmed but not registered: the relay room is not locked, so the rescan can admit unsigned.
        h.stored(PairingStage.CONFIRMED)
        h.relay.phoneAdmissionOverrides += AdmissionResult.Unreachable(UnreachableKind.TLS, "SSLHandshakeException")
        val screens = CopyOnWriteArrayList<AppScreen>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(h.client.state, h.client.paired) { state, paired -> appScreenFor(state, paired) }.collect { screens += it }
        }
        h.client.start()
        h.await { it is ClientState.Failed }
        assertEquals(true, h.client.paired.value)

        store.failSavesAt = PairingStage.PENDING_CONFIRMATION
        store.saveFailures = 5
        h.mac.start()
        assertEquals(AppScreen.PAIRING, screens.last())
        screens.clear()
        h.client.pair(code)
        h.await { it is ClientState.WaitingForMac }
        assertEquals(false, h.client.paired.value)
        h.await { it is ClientState.ShowCode }
        assertEquals(0, store.saveFailures)
        assertFalse("$screens", AppScreen.HOME in screens)
        assertTrue("$screens", screens.isNotEmpty())
        assertEquals(AppScreen.PAIRING, screens.last())
    }

    /** The Mac unpairs: the state leaves Connected before paired turns false, so no hidden Connected screen flashes. */
    @Test
    fun macUnpairNeverShowsConnectedWhileUnpaired() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        val seen = CopyOnWriteArrayList<Pair<ClientState, Boolean?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(h.client.state, h.client.paired) { state, paired -> state to paired }.collect { seen += it }
        }
        h.mac.start()
        h.client.start()
        h.awaitConnected()
        h.mac.sendUnpaired()
        h.await { it is ClientState.Failed }
        runCurrent()
        assertEquals(false, h.client.paired.value)
        assertFalse("$seen", seen.any { (state, paired) -> state is ClientState.Connected && paired != true })
    }

    @Test
    fun storeUnavailableWaitsAndRetries() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        store.loadFailures = 3
        h.mac.start()
        h.client.start()

        assertEquals(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE), h.await { it is ClientState.WaitingForMac })
        h.awaitConnected()
        assertEquals(record, store.load())
    }

    // Reconnects

    @Test
    fun droppedSocketReconnectsSigned() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected(1)

        h.mac.dropSocket()
        h.awaitConnected(2)
        assertEquals(listOf(true, true), h.relay.phoneAdmissions.map { it.signed })
        assertFalse(h.states.any { it is ClientState.ShowCode })
        assertEquals(2, h.mac.handshakes.get())
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    @Test
    fun displacedWaitsBeforeReconnect() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected(1)

        h.relay.closePhone(room, 1000, "displaced")
        runCurrent()
        assertFalse(h.client.state.value is ClientState.Connected)
        advanceTimeBy(59.seconds)
        assertEquals("waits 60 s after displaced", 1, h.relay.phoneAdmissions.size)
        advanceTimeBy(2.seconds)
        assertTrue(h.relay.phoneAdmissions.size >= 2)
        h.awaitConnected(2)
    }

    // Version

    @Test
    fun macTooOldFailsWithUpdateITerm2() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.mac.revision = 10
        h.mac.start()
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.message, failed.message.startsWith("Update iTerm2"))
        assertFalse(failed.rescanNeeded)
        assertEquals(PairingStage.CONFIRMED, h.stage())
        advanceTimeBy(10.minutes)
        assertEquals(1, h.relay.phoneAdmissions.size)
    }

    @Test
    fun appTooOldFailsWithUpdateTheApp() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.mac.minimumPeer = ProtocolRevision.REVISION + 1
        h.mac.start()
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.message, failed.message.startsWith("Update the app"))
        assertEquals(PairingStage.CONFIRMED, h.stage())
    }

    // Stalled Mac

    @Test
    fun helloTimeoutRetriesKeepingPairing() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.answering = false
        h.mac.start()
        h.client.start()

        h.await { it is ClientState.WaitingForMac }
        assertTrue("closed after the rpc bound: $currentTime", currentTime >= 15_000)
        advanceTimeBy(2.minutes)
        assertTrue("retries: ${h.mac.handshakes.get()}", h.mac.handshakes.get() >= 2)
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertFalse(h.states.any { it is ClientState.Connected })

        h.mac.answering = true
        h.awaitConnected()
    }

    @Test
    fun roomSecretErrorRetriesThenFails() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.mac.roomSecretFailures.set(3)
        h.mac.start()
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertEquals("Your Mac couldn't save the pairing. Check iTerm2, then reconnect.", failed.message)
        assertFalse(failed.rescanNeeded)
        assertEquals("three attempts", 3, h.mac.handshakes.get())
        assertEquals(PairingStage.CONFIRMED, h.stage())
        assertTrue(h.relay.registerResults.isEmpty())
    }

    // Session layer

    @Test
    fun undecodableFrameKeepsSession() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()

        h.mac.sendRaw("not json".toByteArray())
        h.mac.sendRaw(ByteArray(0))
        h.mac.sendRaw("""{"payload":{"hello":{"revision":"fifteen"}}}""".toByteArray())
        runCurrent()
        assertTrue(h.client.state.value is ClientState.Connected)
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
        assertEquals(1, h.mac.handshakes.get())
    }

    @Test
    fun failedSendClosesTheConnection() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected(1)

        h.relay.failNextPhoneSend = true
        val failure = runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        h.awaitConnected(2)
        assertEquals(2, h.mac.handshakes.get())
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    // Unpair

    @Test
    fun unpairSendsFarewellAndClearsWithoutReply() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()

        h.client.unpair()
        assertEquals(ClientState.Unpaired, h.client.state.value)
        assertNull(h.store.load())
        runCurrent()
        assertEquals(1, h.mac.unpairings.get())
        assertEquals(FakeMac.fixtureJson("unpairing.json"), h.mac.receivedEnvelopes().last())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals("no reconnect", attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun unpairClearsEvenWhenOffline() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.REGISTERED)
        h.client.start()
        h.await { it is ClientState.WaitingForMac }

        h.client.unpair()
        assertEquals(ClientState.Unpaired, h.client.state.value)
        assertNull(h.store.load())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals(attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun unpairRetriesAFailedClearWrite() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store)
        h.stored(PairingStage.REGISTERED)
        store.clearWriteFailures = 2
        h.client.start()
        h.await { it is ClientState.WaitingForMac }

        h.client.unpair()
        assertEquals(ClientState.Unpaired, h.client.state.value)
        assertNull(h.store.load())
    }

    @Test
    fun unpairThatCannotClearThrowsIOExceptionAndGoesIdle() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store)
        h.stored(PairingStage.REGISTERED)
        store.clearWriteFailures = Int.MAX_VALUE
        h.client.start()
        h.await { it is ClientState.WaitingForMac }

        val thrown = runCatching { h.client.unpair() }.exceptionOrNull()
        assertTrue("$thrown", thrown is IOException && thrown !is PairingStoreUnavailableException)
        assertEquals(ClientState.Idle, h.client.state.value)
        assertNotNull("the pairing is still stored", store.load())
    }

    @Test
    fun startShowsConnectingBeforeTheStoreIsRead() = runTest {
        val h = Harness(this, storeContext = StandardTestDispatcher(testScheduler, "store"))
        h.stored(PairingStage.REGISTERED)
        assertEquals(ClientState.Unpaired, h.client.state.value)

        h.client.start()
        assertEquals("set before start() returns", ClientState.Connecting, h.client.state.value)
    }

    @Test
    fun macInitiatedUnpairTearsDown() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()

        h.mac.sendUnpaired()
        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertEquals(ClientState.Failed("Unpaired on the Mac", rescanNeeded = true), failed)
        assertNull(h.store.load())
        assertEquals("nothing sent back", 0, h.mac.unpairings.get())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals(attempts, h.relay.phoneAdmissions.size)
    }

    // Mac absence, not pairing time (review C1)

    @Test
    fun slowCodeEntryThenStoreFailureKeepsPairing() = runTest {
        val store = FlakyStore(InMemoryPairingStore()).apply { failSavesAt = PairingStage.CONFIRMED; saveFailures = 1 }
        val h = Harness(this, store = store)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }

        // The user takes 130 s to type the code; the Mac accepts and pins; the save then fails once.
        advanceTimeBy(130.seconds)
        h.mac.enterCode(accept = true)
        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertFalse(h.states.any { it is ClientState.Failed })
        assertTrue(h.states.contains(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE)))
    }

    @Test
    fun droppedWhileShowingCodeRecoversWithoutWipe() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }

        // The phone's connection dies 125 s into code entry; the Mac dismisses the entry and re-parks.
        advanceTimeBy(125.seconds)
        h.relay.dropPhone(room)
        h.await { it is ClientState.WaitingForMac || it is ClientState.Connecting }
        val second = h.await { it is ClientState.ShowCode } as ClientState.ShowCode
        assertEquals(PairingStage.PENDING_CONFIRMATION, h.stage())
        assertEquals("a new handshake, a new code", second.code, h.mac.awaitingCode.first { it != null })

        h.mac.enterCode(accept = true)
        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertFalse(h.states.any { it is ClientState.Failed })
    }

    @Test
    fun acceptLostInFlightAfterLongOutageKeepsPairing() = runTest {
        // Review probe P23. The seeded backoff draw lands past the old remainder, so a clock that
        // resumed from 115 s would give up and wipe a pairing the Mac has pinned.
        val h = Harness(this)
        h.relay.reachable = false
        h.client.pair(code)
        h.await { it is ClientState.WaitingForMac }
        advanceTimeBy(115.seconds)
        h.relay.reachable = true
        h.mac.start()
        h.await(within = 3.minutes) { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }
        advanceTimeBy(5.seconds)

        // The phone's socket dies just before the verdict; the Mac accepts, pins, then its socket goes too.
        h.relay.closePhone(room, 1006, "dropped")
        h.mac.enterCode(accept = true)
        runCurrent()
        assertNotNull(h.mac.pinnedPhoneStatic)
        h.mac.dropSocket()

        h.awaitConnected()
        assertFalse(h.states.any { it is ClientState.Failed })
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun cancelSupersededByStartStillWipes() = runTest {
        // Review probe P25: pair, cancel and start back to back.
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        h.client.cancel()
        h.client.start()
        advanceTimeBy(5.seconds)

        assertNull("the cancel's wipe landed", h.store.load())
        assertEquals(ClientState.Unpaired, h.client.state.value)
    }

    @Test
    fun pendingRecoveryAgainstMacPinnedToAnotherKeyGivesUp() = runTest {
        val h = Harness(this)
        h.stored(PairingStage.PENDING_CONFIRMATION)
        h.mac.pinnedPhoneStatic = newStaticKeyPair().second
        h.mac.start()
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertTrue(failed.rescanNeeded)
        assertTrue("absent long enough: $currentTime", currentTime >= 120_000)
        assertTrue("it kept trying meanwhile", h.mac.staticMismatches.get() >= 2)
        assertNull(h.store.load())
    }

    @Test
    fun sasCodeIsNeverLogged() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        val shown = h.await { it is ClientState.ShowCode } as ClientState.ShowCode
        h.mac.enterCode(accept = true)
        h.awaitConnected()
        assertTrue(h.logs.any { it.contains("ShowCode") })
        assertEquals(emptyList<String>(), h.logs.filter { it.contains(shown.code) })
    }

    // The Mac unpairs before step 7 (review I2)

    @Test
    fun macUnpairDuringHelloTearsDown() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.answering = false
        h.mac.start()
        h.client.start()
        withTimeout(1.minutes) { while (h.mac.received.isEmpty()) advanceTimeBy(10) }
        val sentAt = currentTime

        h.mac.sendUnpaired()
        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertEquals(ClientState.Failed("Unpaired on the Mac", rescanNeeded = true), failed)
        assertTrue("at once, not after the rpc bound", currentTime - sentAt < 15_000)
        assertNull(h.store.load())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals(attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun roomSecretErrorDuringMacUnpairIsNotCounted() = runTest {
        val h = Harness(this, timeouts = ClientTimeouts(roomSecretErrorLimit = 1))
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.mac.unpairOnRoomSecret = true
        h.mac.start()
        h.client.start()

        val failed = h.await { it is ClientState.Failed } as ClientState.Failed
        assertEquals("the unpair wins over the error", ClientState.Failed("Unpaired on the Mac", rescanNeeded = true), failed)
        assertNull(h.store.load())
    }

    // Teardown and races (review M2-M9)

    @Test
    fun cancelDuringConfirmedSaveKeepsPairing() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store, storeContext = StandardTestDispatcher(testScheduler, "store"))
        store.afterSave = PairingStage.CONFIRMED to { h.client.cancel() }
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }
        h.mac.awaitingCode.first { it != null }

        h.mac.enterCode(accept = true)
        assertEquals(ClientState.Idle, h.await { it is ClientState.Idle || it is ClientState.Unpaired })
        assertEquals("the Mac pinned it: never wiped", PairingStage.CONFIRMED, h.stage())
    }

    @Test
    fun cancelDuringBeginFreshPairingLeavesNothing() = runTest {
        val store = FlakyStore(InMemoryPairingStore())
        val h = Harness(this, store = store, storeContext = StandardTestDispatcher(testScheduler, "store"))
        store.afterSave = PairingStage.PENDING_CONFIRMATION to { h.client.cancel() }
        h.client.pair(code)

        assertEquals(ClientState.Unpaired, h.await { it is ClientState.Idle || it is ClientState.Unpaired })
        assertNull("no orphan PENDING record", h.store.load())
        advanceTimeBy(10.minutes)
        assertNull(h.store.load())
        assertTrue(h.relay.phoneAdmissions.isEmpty())
    }

    @Test
    fun rapidControlCallsRunInOrder() = runTest {
        val h = Harness(this, storeContext = StandardTestDispatcher(testScheduler, "store"))
        h.mac.start()
        h.client.pair(code)
        h.client.pair(code)
        h.client.cancel()

        advanceTimeBy(10.minutes)
        assertEquals(ClientState.Unpaired, h.client.state.value)
        assertNull("the last call wins; no superseded pairing lands after it", h.store.load())
        assertTrue(h.relay.phoneAdmissions.isEmpty())
    }

    @Test
    fun cancelDuringShowCodeWipes() = runTest {
        val h = Harness(this)
        h.mac.start()
        h.client.pair(code)
        h.await { it is ClientState.ShowCode }

        h.client.cancel()
        assertEquals(ClientState.Unpaired, h.await { it !is ClientState.ShowCode })
        assertNull(h.store.load())
        val attempts = h.relay.phoneAdmissions.size
        advanceTimeBy(10.minutes)
        assertEquals(attempts, h.relay.phoneAdmissions.size)
    }

    @Test
    fun cycleCapOnConfirmedPairingReconnects() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.CONFIRMED)
        h.macPinned(record)
        h.relay.phoneAdmissionOverrides += AdmissionResult.Closed(1000, "pairing cycle cap")
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        assertFalse(h.states.any { it is ClientState.Failed })
        assertEquals(PairingStage.REGISTERED, h.stage())
    }

    @Test
    fun displacedCloseRacingFailedSendKeepsLongWait() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected(1)

        // The relay's "displaced" close and a send meet: the send fails first.
        h.relay.closePhone(room, 1000, "displaced")
        val failure = runCatching { h.client.request(PhoneMessage.Ping) }.exceptionOrNull()
        assertTrue("$failure", failure is CompanionRequestException)
        advanceTimeBy(59.seconds)
        assertEquals("still waits 60 s", 1, h.relay.phoneAdmissions.size)
        h.awaitConnected(2)
    }

    @Test
    fun unsolicitedMessagesReachEvents() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()

        val received = CopyOnWriteArrayList<MacMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.client.events.collect { received += it } }
        h.mac.sendRaw("""{"payload":{"futureThing":{"x":1}}}""".toByteArray())
        runCurrent()
        assertEquals(listOf("futureThing"), received.map { (it as MacMessage.Unsupported).case })
    }

    @Test
    fun sessionTreePushDuringFetchReachesEventsAndReplyCompletesRequest() = runTest {
        val h = Harness(this)
        val record = h.stored(PairingStage.REGISTERED)
        h.macPinned(record)
        h.mac.start()
        h.client.start()
        h.awaitConnected()

        val events = CopyOnWriteArrayList<MacMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.client.events.collect { events += it } }
        val reply = async { h.client.request(PhoneMessage.FetchSessionTree) }
        runCurrent()
        val fetch = h.mac.receivedEnvelopes().last()
        assertTrue("$fetch", "fetchSessionTree" in fetch.getValue("payload").jsonObject)
        val requestID = fetch.getValue("requestID").jsonPrimitive.long

        h.mac.sendSample("messages/live-session/session-tree-push.json", null)
        runCurrent()
        assertFalse("the push does not answer the request", reply.isCompleted)
        h.mac.sendSample("messages/live-session/session-tree-reply.json", requestID)

        val tree = reply.await() as MacMessage.SessionTreeMessage
        assertEquals((liveSample("session-tree-reply.json") as MacMessage.SessionTreeMessage).tree, tree.tree)
        val push = events.single() as MacMessage.SessionTreeMessage
        assertEquals((liveSample("session-tree-push.json") as MacMessage.SessionTreeMessage).tree, push.tree)
        // Both paths carry the arrival order: the push came first.
        assertTrue("${push.arrival} < ${tree.arrival}", push.arrival in 1 until tree.arrival)
        assertEquals(tree.arrival, h.client.newestArrival)
    }

    @Test
    fun sessionTreePushBeforeHelloReplyOnReconnectIsHarmless() = runTest {
        // NONE: a reconnect with a confirmed pairing.
        pushBeforeHelloReply(PairingStage.REGISTERED)
    }

    @Test
    fun sessionTreePushBeforeHelloReplyInPendingRecoveryIsHarmless() = runTest {
        // POSSIBLE: unconfirmed, but the Mac pinned this phone, so its first frame is an envelope.
        pushBeforeHelloReply(PairingStage.PENDING_CONFIRMATION)
    }

    /** The Mac persisted the phone's revision, so it may push a tree before answering the hello. */
    private suspend fun TestScope.pushBeforeHelloReply(stage: PairingStage) {
        val h = Harness(this)
        val record = h.stored(stage)
        h.macPinned(record)
        h.mac.beforeHelloReply += "messages/live-session/session-tree-push.json"
        val events = CopyOnWriteArrayList<MacMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.client.events.collect { events += it } }
        h.mac.start()
        h.client.start()

        h.awaitConnected()
        assertEquals(PairingStage.REGISTERED, h.stage())
        assertTrue(h.store.load()!!.isSamePairingAs(record))
        assertEquals(1, h.mac.handshakes.get())
        assertEquals("no confirmation from a pinned Mac", 0, h.mac.confirmationsSent.get())
        assertEquals(listOf(MacMessage.SessionTreeMessage((liveSample("session-tree-push.json") as MacMessage.SessionTreeMessage).tree, arrival = 1)), events.toList())
        assertEquals(MacMessage.Pong, h.client.request(PhoneMessage.Ping))
    }

    private fun liveSample(name: String): MacMessage {
        val json = Json.parseToJsonElement(Fixtures.read("messages/live-session/$name")).jsonObject.getValue("json")
        return decodeEnvelope(json.toString().toByteArray()).second
    }

    /** A store whose loads, or saves at one stage, fail as a busy Keystore does. */
    private class FlakyStore(private val delegate: PairingStore) : PairingStore {
        @Volatile var loadFailures = 0

        @Volatile var saveFailures = 0

        @Volatile var failSavesAt: PairingStage? = null

        /** Clears that fail with a plain [IOException], as a failed SharedPreferences commit does. */
        @Volatile var clearWriteFailures = 0

        /** Runs after a save of this stage lands, as if the user acted in that instant. */
        @Volatile var afterSave: Pair<PairingStage, () -> Unit>? = null

        override fun load(): PairingRecord? {
            if (loadFailures > 0) {
                loadFailures--
                throw PairingStoreUnavailableException("keystore busy")
            }
            return delegate.load()
        }

        override fun save(record: PairingRecord) {
            if (saveFailures > 0 && record.stage == failSavesAt) {
                saveFailures--
                throw PairingStoreUnavailableException("keystore busy")
            }
            delegate.save(record)
            afterSave?.takeIf { it.first == record.stage }?.let {
                afterSave = null
                it.second()
            }
        }

        override fun clear() {
            if (clearWriteFailures > 0) {
                clearWriteFailures--
                throw IOException("could not clear the pairing store")
            }
            delegate.clear()
        }

        override fun <T> withLock(block: () -> T): T = delegate.withLock(block)
    }

    private companion object {
        const val ORIGIN = "https://relay.example.ts.net"
        const val SEED = 20261115

        fun publicKeyOf(privateKey: ByteArray): ByteArray {
            val dh = Noise.createDH("25519")
            try {
                dh.setPrivateKey(privateKey, 0)
                return ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
            } finally {
                dh.destroy()
            }
        }

        /** [sample] with its hello's `revision` replaced: the v3.7.7 sample advertises 11. */
        fun withRevision(sample: JsonObject, revision: Int): JsonObject {
            val payload = sample.getValue("payload") as JsonObject
            val hello = payload.getValue("hello") as JsonObject
            val body = JsonObject(hello + ("revision" to JsonPrimitive(revision)))
            return JsonObject(sample + ("payload" to JsonObject(payload + ("hello" to body))))
        }

        fun withSecret(sample: JsonObject, secret: ByteArray): JsonObject {
            val payload = sample.getValue("payload") as JsonObject
            val body = JsonObject(mapOf("_0" to JsonPrimitive(Base64.getEncoder().encodeToString(secret))))
            return JsonObject(sample + ("payload" to JsonObject(payload + ("relayRoomSecret" to body))))
        }
    }
}
