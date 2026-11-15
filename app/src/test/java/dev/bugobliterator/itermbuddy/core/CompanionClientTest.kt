package dev.bugobliterator.itermbuddy.core

import com.southernstorm.noise.protocol.Noise
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
import dev.bugobliterator.itermbuddy.fakemac.FakeMac
import dev.bugobliterator.itermbuddy.fakemac.FakeRelay
import dev.bugobliterator.itermbuddy.store.InMemoryPairingStore
import dev.bugobliterator.itermbuddy.store.PairingRecord
import dev.bugobliterator.itermbuddy.store.PairingStage
import dev.bugobliterator.itermbuddy.store.PairingStore
import dev.bugobliterator.itermbuddy.store.PairingStoreUnavailableException
import dev.bugobliterator.itermbuddy.store.advancePairing
import dev.bugobliterator.itermbuddy.store.beginFreshPairing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
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
            EmptyCoroutineContext,
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
        assertEquals(FakeMac.fixtureJson("hello-phone.json"), sent[0])
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
        h.mac.minimumPeer = 12
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

    /** A store whose loads, or saves at one stage, fail as a busy Keystore does. */
    private class FlakyStore(private val delegate: PairingStore) : PairingStore {
        @Volatile var loadFailures = 0

        @Volatile var saveFailures = 0

        @Volatile var failSavesAt: PairingStage? = null

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
        }

        override fun clear() = delegate.clear()

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

        fun withSecret(sample: JsonObject, secret: ByteArray): JsonObject {
            val payload = sample.getValue("payload") as JsonObject
            val body = JsonObject(mapOf("_0" to JsonPrimitive(Base64.getEncoder().encodeToString(secret))))
            return JsonObject(sample + ("payload" to JsonObject(payload + ("relayRoomSecret" to body))))
        }
    }
}
