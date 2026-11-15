package dev.bugobliterator.itermbuddy.store

import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class InMemoryPairingStoreTest {
    private val codeA = PairingCode(ByteArray(32) { 0x11 }, "pid-a", ORIGIN)
    private val codeB = PairingCode(ByteArray(32) { 0x22 }, "pid-b", ORIGIN)

    @Test
    fun freshPairingReplacesEveryOldSecret() {
        val store = InMemoryPairingStore()
        val old = beginFreshPairing(store, codeA)
        advancePairing(store, old, PairingStage.CONFIRMED)
        val registered = advancePairing(store, old, PairingStage.REGISTERED)
        assertEquals(PairingStage.REGISTERED, store.load()?.stage)

        val fresh = beginFreshPairing(store, codeB)

        assertFalse("static key reused", fresh.phoneStaticPrivate.contentEquals(registered.phoneStaticPrivate))
        assertFalse("room secret reused", fresh.roomSecret.contentEquals(registered.roomSecret))
        assertEquals(codeB, fresh.code)
        assertEquals(PairingStage.PENDING_CONFIRMATION, fresh.stage)
        assertEquals(fresh, store.load())
    }

    @Test
    fun roundTrip() {
        val store = InMemoryPairingStore()
        assertNull(store.load())
        val record = beginFreshPairing(store, codeA)
        val loaded = store.load()
        assertEquals(record, loaded)
        assertArrayEquals(record.phoneStaticPrivate, loaded!!.phoneStaticPrivate)
        assertArrayEquals(record.roomSecret, loaded.roomSecret)
        assertArrayEquals(codeA.responderStaticPublicKey, loaded.code.responderStaticPublicKey)
        assertEquals(codeA.pairingID, loaded.code.pairingID)
        assertEquals(codeA.relayOrigin, loaded.code.relayOrigin)

        val confirmed = advancePairing(store, record, PairingStage.CONFIRMED)
        assertEquals(confirmed, store.load())
        assertEquals(PairingStage.CONFIRMED, store.load()?.stage)
    }

    @Test
    fun freshPairingMintsFullSizeSecrets() {
        val record = beginFreshPairing(InMemoryPairingStore(), codeA)
        assertEquals(32, record.phoneStaticPrivate.size)
        assertEquals(32, record.roomSecret.size)
        assertFalse(record.roomSecret.all { it == 0.toByte() })
        assertFalse(record.phoneStaticPrivate.all { it == 0.toByte() })
    }

    @Test
    fun freshPairingClearsBeforeSaving() {
        val store = SaveFailsAfter(InMemoryPairingStore(), allowedSaves = 1)
        beginFreshPairing(store, codeA)
        assertThrows(IllegalStateException::class.java) { beginFreshPairing(store, codeB) }
        assertNull("old pairing must be gone even when saving the new one fails", store.load())
    }

    @Test
    fun clearRemovesTheRecord() {
        val store = InMemoryPairingStore()
        beginFreshPairing(store, codeA)
        store.clear()
        assertNull(store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun stagesAdvanceOneStepAtATime() {
        val store = InMemoryPairingStore()
        val pending = beginFreshPairing(store, codeA)
        assertThrows(IllegalStateException::class.java) {
            advancePairing(store, pending, PairingStage.REGISTERED)
        }
        assertEquals(PairingStage.PENDING_CONFIRMATION, store.load()?.stage)

        val confirmed = advancePairing(store, pending, PairingStage.CONFIRMED)
        assertEquals(PairingStage.CONFIRMED, confirmed.stage)
        assertEquals(pending.withStage(PairingStage.CONFIRMED), confirmed)

        // Re-checkpointing the current stage (say after a restart) is a no-op.
        assertEquals(confirmed, advancePairing(store, confirmed, PairingStage.CONFIRMED))

        val registered = advancePairing(store, confirmed, PairingStage.REGISTERED)
        assertEquals(registered, store.load())
    }

    @Test
    fun skippingAStageIsNotAStaleFlow() {
        val store = InMemoryPairingStore()
        val pending = beginFreshPairing(store, codeA)
        val thrown = assertThrows(IllegalStateException::class.java) {
            advancePairing(store, pending, PairingStage.REGISTERED)
        }
        assertFalse(thrown is StalePairingFlowException)
    }

    @Test
    fun sharedViewsUseOneLock() {
        val store = InMemoryPairingStore()
        val other = store.sharedView()
        val record = beginFreshPairing(store, codeA)
        assertEquals(record, other.load())
        val started = CountDownLatch(1)
        var fresh: PairingRecord? = null
        val thread = Thread {
            started.countDown()
            fresh = beginFreshPairing(other, codeB)
        }
        store.withLock {
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            thread.join(200)
            assertTrue("beginFreshPairing on the other view ran inside this lock", thread.isAlive)
            assertEquals(record, store.load())
            assertEquals(PairingStage.CONFIRMED, advancePairing(store, record, PairingStage.CONFIRMED).stage)
        }
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertEquals(fresh, store.load())
        assertThrows(StalePairingFlowException::class.java) { advancePairing(store, record, PairingStage.REGISTERED) }
    }

    @Test
    fun racingAdvanceAndFreshPairingNeverMixPairings() {
        val store = InMemoryPairingStore()
        val other = store.sharedView()
        repeat(200) {
            val record = beginFreshPairing(store, codeA)
            val advancer = Thread {
                try {
                    advancePairing(store, record, PairingStage.CONFIRMED)
                    advancePairing(store, record, PairingStage.REGISTERED)
                } catch (_: StalePairingFlowException) {
                    // Lost the race to the re-scan: abort, as the client does.
                }
            }
            var fresh: PairingRecord? = null
            val rescanner = Thread { fresh = beginFreshPairing(other, codeB) }
            advancer.start()
            rescanner.start()
            advancer.join()
            rescanner.join()
            val stored = checkNotNull(store.load())
            assertTrue("old pairing's progress landed on the new one", stored.isSamePairingAs(checkNotNull(fresh)))
            assertEquals(codeB, stored.code)
            assertEquals(PairingStage.PENDING_CONFIRMATION, stored.stage)
        }
    }

    @Test
    fun stagesNeverMoveBackward() {
        val store = InMemoryPairingStore()
        val pending = beginFreshPairing(store, codeA)
        advancePairing(store, pending, PairingStage.CONFIRMED)
        val registered = advancePairing(store, pending, PairingStage.REGISTERED)
        for (earlier in listOf(PairingStage.CONFIRMED, PairingStage.PENDING_CONFIRMATION)) {
            assertThrows(earlier.name, StalePairingFlowException::class.java) {
                advancePairing(store, pending, earlier)
            }
            assertThrows(earlier.name, IllegalStateException::class.java) {
                store.save(registered.withStage(earlier))
            }
        }
        assertEquals(registered, store.load())
    }

    @Test
    fun staleFlowCannotAdvanceANewerPairing() {
        val store = InMemoryPairingStore()
        val old = beginFreshPairing(store, codeA)
        val fresh = beginFreshPairing(store, codeA)
        assertThrows(StalePairingFlowException::class.java) {
            advancePairing(store, old, PairingStage.CONFIRMED)
        }
        assertEquals(fresh, store.load())
    }

    @Test
    fun advanceOnEmptyStoreThrows() {
        val store = InMemoryPairingStore()
        val record = beginFreshPairing(store, codeA)
        store.clear()
        assertThrows(StalePairingFlowException::class.java) {
            advancePairing(store, record, PairingStage.CONFIRMED)
        }
        assertNull(store.load())
    }

    @Test
    fun saveRefusesToReplaceAPairingWithoutClear() {
        val store = InMemoryPairingStore()
        val first = beginFreshPairing(store, codeA)
        val other = PairingRecord(codeB, ByteArray(32) { 3 }, ByteArray(32) { 4 }, PairingStage.PENDING_CONFIRMATION)
        assertThrows(IllegalStateException::class.java) { store.save(other) }
        assertEquals(first, store.load())
    }

    @Test
    fun emptyStoreAcceptsOnlyAPendingRecord() {
        for (stage in listOf(PairingStage.CONFIRMED, PairingStage.REGISTERED)) {
            val store = InMemoryPairingStore()
            assertThrows(stage.name, IllegalStateException::class.java) { store.save(record(stage)) }
            assertNull(store.load())
        }
        val store = InMemoryPairingStore()
        store.save(record(PairingStage.PENDING_CONFIRMATION))
        assertEquals(record(PairingStage.PENDING_CONFIRMATION), store.load())
    }

    @Test
    fun storeKeepsItsOwnCopy() {
        val store = InMemoryPairingStore()
        val record = beginFreshPairing(store, codeA)
        record.roomSecret.fill(0)
        record.phoneStaticPrivate.fill(0)
        assertEquals(record, store.load())
        assertNotEquals(store.load(), null)
    }

    @Test
    fun recordCopiesItsSecretsInAndOut() {
        val key = ByteArray(32) { 5 }
        val secret = ByteArray(32) { 6 }
        val record = PairingRecord(codeA, key, secret, PairingStage.PENDING_CONFIRMATION)
        key.fill(0)
        secret.fill(0)
        assertArrayEquals(ByteArray(32) { 5 }, record.phoneStaticPrivate)
        assertArrayEquals(ByteArray(32) { 6 }, record.roomSecret)
        record.phoneStaticPrivate[0] = 9
        record.roomSecret[0] = 9
        assertArrayEquals(ByteArray(32) { 5 }, record.phoneStaticPrivate)
        assertArrayEquals(ByteArray(32) { 6 }, record.roomSecret)
    }

    @Test
    fun recordEqualityIsByContent() {
        val a = record(PairingStage.CONFIRMED)
        val b = record(PairingStage.CONFIRMED)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, record(PairingStage.REGISTERED))
        assertNotEquals(a, PairingRecord(codeA, ByteArray(32) { 7 }, ByteArray(32) { 2 }, PairingStage.CONFIRMED))
        assertNotEquals(a, PairingRecord(codeA, ByteArray(32) { 1 }, ByteArray(32) { 7 }, PairingStage.CONFIRMED))
        assertNotEquals(a, PairingRecord(codeB, ByteArray(32) { 1 }, ByteArray(32) { 2 }, PairingStage.CONFIRMED))
        assertTrue(a.isSamePairingAs(record(PairingStage.REGISTERED)))
    }

    @Test
    fun recordToStringHidesSecrets() {
        val key = ByteArray(32) { (0xA0 + it).toByte() }
        val secret = ByteArray(32) { (0x40 + it).toByte() }
        val text = PairingRecord(codeA, key, secret, PairingStage.CONFIRMED).toString()
        assertTrue(text, text.contains("pid-a") && text.contains("CONFIRMED"))
        for (bytes in listOf(key, secret)) {
            for (encoded in listOf(bytes.toHex(), Base64.getEncoder().encodeToString(bytes), bytes.contentToString())) {
                assertFalse(text, text.contains(encoded))
            }
        }
        assertFalse(text, text.contains("[B@"))
    }

    @Test
    fun recordRejectsWrongSizes() {
        for (size in listOf(0, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                PairingRecord(codeA, ByteArray(size), ByteArray(32), PairingStage.CONFIRMED)
            }
            assertThrows(IllegalArgumentException::class.java) {
                PairingRecord(codeA, ByteArray(32), ByteArray(size), PairingStage.CONFIRMED)
            }
        }
    }

    @Test
    fun withStageKeepsEverythingElse() {
        val a = record(PairingStage.PENDING_CONFIRMATION)
        val b = a.withStage(PairingStage.CONFIRMED)
        assertEquals(PairingStage.CONFIRMED, b.stage)
        assertArrayEquals(a.phoneStaticPrivate, b.phoneStaticPrivate)
        assertArrayEquals(a.roomSecret, b.roomSecret)
        assertSame(a.code, b.code)
    }

    private fun record(stage: PairingStage) =
        PairingRecord(codeA, ByteArray(32) { 1 }, ByteArray(32) { 2 }, stage)

    /** Delegates to [inner] but fails every save after the first [allowedSaves]. */
    private class SaveFailsAfter(private val inner: PairingStore, private var allowedSaves: Int) : PairingStore by inner {
        override fun save(record: PairingRecord) {
            check(allowedSaves-- > 0) { "disk full" }
            inner.save(record)
        }
    }

    private companion object {
        const val ORIGIN = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
