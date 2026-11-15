package dev.bugobliterator.itermbuddy.store

import android.security.keystore.BackendBusyException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeystorePairingStore]'s logic on the JVM: the blob file and the Keystore are fakes, the cipher
 * is the real AES-GCM envelope with software keys, and failures are real exception instances.
 */
class KeystorePairingStoreJvmTest {
    private val codeA = PairingCode(ByteArray(32) { 0x11 }, "pid-a", ORIGIN)
    private val codeB = PairingCode(ByteArray(32) { 0x22 }, "pid-b", ORIGIN)

    private class FakeFile : PairingBlobFile {
        var alias: String? = null
        var blob: String? = null
        var failWrites = false

        override fun read(): StoredEntry? = if (alias == null && blob == null) null else StoredEntry(alias, blob)

        override fun write(alias: String, blob: String): Boolean {
            if (failWrites) return false
            this.alias = alias
            this.blob = blob
            return true
        }

        override fun clear(): Boolean {
            if (failWrites) return false
            alias = null
            blob = null
            return true
        }
    }

    private class FakeVault : PairingKeyVault {
        val keys = linkedMapOf<String, SecretKey>()
        var findFailure: Throwable? = null
        var openFailure: Throwable? = null
        var sealFailure: Throwable? = null
        var createFailure: Throwable? = null
        var deleteFailure: Throwable? = null

        override fun find(alias: String): SecretKey? {
            findFailure?.let { throw it }
            return keys[alias]
        }

        override fun create(alias: String): SecretKey {
            createFailure?.let { throw it }
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
        }

        override fun delete(alias: String) {
            deleteFailure?.let { throw it }
            keys.remove(alias)
        }

        override fun aliases(): List<String> = keys.keys.toList()

        override fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
            sealFailure?.let { throw it }
            return super.seal(key, plaintext)
        }

        override fun open(key: SecretKey, blob: ByteArray): ByteArray {
            openFailure?.let { throw it }
            return super.open(key, blob)
        }
    }

    private val file = FakeFile()
    private val vault = FakeVault()
    private var errorCode: (android.security.KeyStoreException) -> Int = { it.numericErrorCode }
    private val store = KeystorePairingStore(file, vault) { errorCode(it) }

    private fun keystoreException(): android.security.KeyStoreException =
        android.security.KeyStoreException::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()

    private fun unrecoverable(cause: Throwable?): UnrecoverableKeyException =
        UnrecoverableKeyException("Failed to obtain information about key").also { e -> cause?.let { e.initCause(it) } }

    private fun assertWiped(label: String) {
        assertNull("$label: file", file.read())
        assertTrue("$label: keys ${vault.keys.keys}", vault.keys.isEmpty())
    }

    private fun assertWipedByLoad(label: String) {
        assertNull(label, store.load())
        assertWiped(label)
    }

    /** Saves a CONFIRMED pairing and returns it with a snapshot of the file and key. */
    private fun confirmed(): PairingRecord {
        val pending = beginFreshPairing(store, codeA)
        return advancePairing(store, pending, PairingStage.CONFIRMED)
    }

    @Test
    fun roundTripsUnderAFreshPrefixedAlias() {
        val record = confirmed()
        assertEquals(record, store.load())
        assertEquals(record, KeystorePairingStore(file, vault).load())
        val alias = checkNotNull(file.alias)
        assertTrue(alias, Regex("itermbuddy-pairing-[0-9a-f]{32}").matches(alias))
        assertEquals(setOf(alias), vault.keys.keys)
    }

    // --- Wipe on proof ---------------------------------------------------------------------

    @Test
    fun missingKeyWipes() {
        confirmed()
        vault.keys.clear()
        assertWipedByLoad("missing key")
    }

    @Test
    fun tamperedBlobWipes() {
        confirmed()
        val blob = Base64.getDecoder().decode(file.blob)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        file.blob = Base64.getEncoder().encodeToString(blob)
        assertWipedByLoad("tampered")
    }

    @Test
    fun blobSealedForAnotherPurposeWipes() {
        val record = confirmed()
        val key = vault.keys.getValue(checkNotNull(file.alias))
        val sealed = PairingBlobCipher.seal(key, PairingRecordCodec.encode(record), "itermbuddy-pairing-v2".toByteArray())
        file.blob = Base64.getEncoder().encodeToString(sealed)
        assertWipedByLoad("other AAD")
    }

    @Test
    fun badBase64Wipes() {
        confirmed()
        file.blob = "not base64!"
        assertWipedByLoad("bad base64")
    }

    @Test
    fun envelopeFormatErrorsWipe() {
        for ((label, mutate) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "version" to { it.copyOf().also { b -> b[0] = 2 } },
            "short" to { it.copyOf(20) },
        )) {
            confirmed()
            file.blob = Base64.getEncoder().encodeToString(mutate(Base64.getDecoder().decode(file.blob)))
            assertWipedByLoad(label)
        }
    }

    @Test
    fun undecodableRecordWipes() {
        confirmed()
        val key = vault.keys.getValue(checkNotNull(file.alias))
        file.blob = Base64.getEncoder().encodeToString(PairingBlobCipher.seal(key, "{\"v\":2}".toByteArray()))
        assertWipedByLoad("record format")
    }

    @Test
    fun brokenEntryWipes() {
        for ((label, mutate) in listOf<Pair<String, (FakeFile) -> Unit>>(
            "no alias" to { it.alias = null },
            "no blob" to { it.blob = null },
            "foreign alias" to { it.alias = "someone-else" },
        )) {
            confirmed()
            val keep = vault.keys.toMap()
            mutate(file)
            assertNull(label, store.load())
            assertNull("$label: file", file.read())
            // A foreign alias is never deleted; our own prefixed keys are.
            assertTrue(label, vault.keys.keys.none { KeystorePairingStore.isPairingAlias(it) })
            assertTrue(label, keep.keys.all { KeystorePairingStore.isPairingAlias(it) })
        }
    }

    @Test
    fun injectedProofWipes() {
        val proofs = listOf(
            "AEADBadTagException on open" to { vault.openFailure = AEADBadTagException() },
            "KeyPermanentlyInvalidatedException on open" to { vault.openFailure = KeyPermanentlyInvalidatedException() },
            "KeyPermanentlyInvalidatedException on find" to { vault.findFailure = KeyPermanentlyInvalidatedException() },
            "UnrecoverableKey(KEY_CORRUPTED)" to {
                errorCode = { android.security.KeyStoreException.ERROR_KEY_CORRUPTED }
                vault.findFailure = unrecoverable(keystoreException())
            },
            "UnrecoverableKey(KEY_DOES_NOT_EXIST)" to {
                errorCode = { android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST }
                vault.findFailure = unrecoverable(keystoreException())
            },
            "UnrecoverableKey(wrapped KEY_CORRUPTED)" to {
                errorCode = { android.security.KeyStoreException.ERROR_KEY_CORRUPTED }
                vault.findFailure = unrecoverable(GeneralSecurityException("wrapper", keystoreException()))
            },
        )
        for ((label, inject) in proofs) {
            confirmed()
            inject()
            assertNull(label, store.load())
            vault.findFailure = null
            vault.openFailure = null
            errorCode = { it.numericErrorCode }
            assertWiped(label)
        }
    }

    // --- Retry, never wipe -----------------------------------------------------------------

    private fun outages(): List<Pair<String, Throwable>> = listOf(
        "ProviderException" to ProviderException("keystore went away"),
        "BackendBusyException" to BackendBusyException(100L, "busy"),
        "UserNotAuthenticatedException" to UserNotAuthenticatedException(),
        "java KeyStoreException" to java.security.KeyStoreException("system error"),
        "UnrecoverableKey without cause" to unrecoverable(null),
        "UnrecoverableKey with unreadable code" to unrecoverable(keystoreException()),
        "IllegalBlockSizeException" to javax.crypto.IllegalBlockSizeException(),
        "unknown RuntimeException" to IllegalStateException("surprise"),
    )

    @Test
    fun outagesDuringLoadNeverWipe() {
        val record = confirmed()
        val alias = file.alias
        val blob = file.blob
        val keys = vault.keys.toMap()
        val systemError = { _: android.security.KeyStoreException -> android.security.KeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR }
        for (step in listOf("find", "open")) {
            for ((label, failure) in outages() + ("UnrecoverableKey(SYSTEM_ERROR)" to unrecoverable(keystoreException()))) {
                if (label.endsWith("(SYSTEM_ERROR)")) errorCode = systemError
                if (step == "find") vault.findFailure = failure else vault.openFailure = failure
                val thrown = assertThrows("$step $label", PairingStoreUnavailableException::class.java) { store.load() }
                assertSame("$step $label", failure, thrown.cause)
                vault.findFailure = null
                vault.openFailure = null
                errorCode = { it.numericErrorCode }
                assertEquals("$step $label alias", alias, file.alias)
                assertEquals("$step $label blob", blob, file.blob)
                assertEquals("$step $label keys", keys, vault.keys.toMap())
            }
        }
        assertEquals(record, store.load())
    }

    @Test
    fun saveDuringAnOutageDoesNotWipe() {
        val pending = beginFreshPairing(store, codeA)
        val blob = file.blob
        for ((label, failure) in outages()) {
            vault.openFailure = failure
            assertThrows(label, PairingStoreUnavailableException::class.java) {
                advancePairing(store, pending, PairingStage.CONFIRMED)
            }
            assertThrows(label, PairingStoreUnavailableException::class.java) {
                store.save(pending.withStage(PairingStage.CONFIRMED))
            }
            vault.openFailure = null
            assertEquals(label, blob, file.blob)
        }
        vault.sealFailure = BackendBusyException(10L)
        assertThrows(PairingStoreUnavailableException::class.java) { advancePairing(store, pending, PairingStage.CONFIRMED) }
        vault.sealFailure = null
        assertEquals(pending, store.load())
        assertEquals(PairingStage.CONFIRMED, advancePairing(store, pending, PairingStage.CONFIRMED).stage)
    }

    // --- Key rotation ----------------------------------------------------------------------

    @Test
    fun everyPairingGetsANewKeyAndTheOldOneIsDeleted() {
        beginFreshPairing(store, codeA)
        val oldAlias = checkNotNull(file.alias)
        val oldKey = vault.keys.getValue(oldAlias)
        val oldBlob = file.blob
        beginFreshPairing(store, codeB)
        val newAlias = checkNotNull(file.alias)
        assertNotEquals(oldAlias, newAlias)
        assertEquals(setOf(newAlias), vault.keys.keys)
        assertNotEquals(oldKey, vault.keys.getValue(newAlias))
        // The old blob is useless now: its key is gone.
        file.alias = oldAlias
        file.blob = oldBlob
        assertWipedByLoad("old blob")
    }

    @Test
    fun keyLeftByAKilledClearNeverSealsTheNextPairing() {
        // A kill after the key delete was skipped, or a listing that failed: keys with no blob.
        vault.create("itermbuddy-pairing-0000000000000000000000000000dead")
        vault.create(KeystorePairingStore.LEGACY_ALIAS)
        vault.create("another-feature")
        assertNull(store.load())
        val record = beginFreshPairing(store, codeA)
        val alias = checkNotNull(file.alias)
        assertEquals(setOf("another-feature", alias), vault.keys.keys)
        assertEquals(record, store.load())

        // Same when a save into an empty store is not preceded by clear().
        file.clear()
        vault.create("itermbuddy-pairing-stale")
        store.save(record)
        assertEquals(setOf("another-feature", checkNotNull(file.alias)), vault.keys.keys)
    }

    @Test
    fun clearDeletesTheKeyWithoutAskingIfItExists() {
        confirmed()
        val alias = checkNotNull(file.alias)
        // The vault's listing is empty, as AndroidKeyStore's is when listing fails.
        val unlisted = object : PairingKeyVault by vault {
            override fun aliases(): List<String> = emptyList()
        }
        KeystorePairingStore(file, unlisted).clear()
        assertFalse(vault.keys.containsKey(alias))
        assertNull(file.read())
    }

    @Test
    fun clearFailsWhenTheKeyCannotBeDeleted() {
        val record = confirmed()
        vault.deleteFailure = java.security.KeyStoreException("Failed to delete entry")
        assertThrows(PairingStoreUnavailableException::class.java) { store.clear() }
        assertThrows(PairingStoreUnavailableException::class.java) { beginFreshPairing(store, codeB) }
        vault.deleteFailure = null
        assertEquals(record, store.load())
    }

    @Test
    fun keyGenerationFailureAfterClearLeavesNoPairing() {
        confirmed()
        vault.createFailure = ProviderException("StrongBox and TEE both failed")
        assertThrows(PairingStoreUnavailableException::class.java) { beginFreshPairing(store, codeB) }
        vault.createFailure = null
        assertNull(store.load())
        assertWiped("after failed generation")
    }

    @Test
    fun runtimeFailuresInSaveAndClearAreUnavailable() {
        // keystore2 not yet re-registered after a restart: getService throws NullPointerException.
        val record = confirmed()
        vault.deleteFailure = NullPointerException("keystore2 service is null")
        assertThrows(PairingStoreUnavailableException::class.java) { store.clear() }
        assertThrows(PairingStoreUnavailableException::class.java) { beginFreshPairing(store, codeB) }
        vault.deleteFailure = null
        assertEquals(record, store.load())

        vault.sealFailure = NullPointerException("keystore2 service is null")
        assertThrows(PairingStoreUnavailableException::class.java) {
            advancePairing(store, record, PairingStage.REGISTERED)
        }
        vault.sealFailure = null
        assertEquals(record, store.load())

        store.clear()
        vault.createFailure = NullPointerException("keystore2 service is null")
        assertThrows(PairingStoreUnavailableException::class.java) { beginFreshPairing(store, codeA) }
        vault.createFailure = null
        assertNull(store.load())
    }

    @Test
    fun loadThrowsOnlyUnavailableWhenAWipeCannotBeWritten() {
        confirmed()
        vault.keys.clear()
        file.failWrites = true
        val thrown = assertThrows(IOException::class.java) { store.load() }
        assertEquals(PairingStoreUnavailableException::class.java, thrown.javaClass)
        file.failWrites = false
        assertWipedByLoad("after the write recovers")
    }

    @Test
    fun loadThrowsOnlyUnavailableForAnUnreadableFile() {
        val broken = object : PairingBlobFile by file {
            override fun read(): StoredEntry? = throw ClassCastException("String expected")
        }
        val thrown = assertThrows(Exception::class.java) { KeystorePairingStore(broken, vault).load() }
        assertEquals(PairingStoreUnavailableException::class.java, thrown.javaClass)
    }

    // --- Writes and locking ------------------------------------------------------------------

    @Test
    fun failedClearWriteStillDestroysTheKey() {
        val pending = beginFreshPairing(store, codeA)
        file.failWrites = true
        assertThrows(IOException::class.java) { advancePairing(store, pending, PairingStage.CONFIRMED) }
        assertThrows(IOException::class.java) { store.clear() }
        file.failWrites = false
        // clear() deleted the key before its write failed, so the surviving blob is proof-bad.
        assertNull(store.load())
    }

    @Test
    fun failedSaveKeepsThePreviousRecord() {
        val pending = beginFreshPairing(store, codeA)
        file.failWrites = true
        assertThrows(IOException::class.java) { advancePairing(store, pending, PairingStage.CONFIRMED) }
        file.failWrites = false
        assertEquals(pending, store.load())
    }

    @Test
    fun backwardAdvanceIsAStaleFlowAndLosesNothing() {
        val pending = beginFreshPairing(store, codeA)
        advancePairing(store, pending, PairingStage.CONFIRMED)
        val registered = advancePairing(store, pending, PairingStage.REGISTERED)
        assertThrows(StalePairingFlowException::class.java) { advancePairing(store, pending, PairingStage.CONFIRMED) }
        assertEquals(registered, store.load())
    }

    @Test
    fun instancesShareOneProcessWideLock() {
        val other = KeystorePairingStore(file, vault)
        val record = beginFreshPairing(store, codeA)
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
            assertTrue("the other instance ran inside this lock", thread.isAlive)
            // The read-modify-write is still atomic: nothing changed under us.
            assertEquals(PairingStage.CONFIRMED, advancePairing(store, record, PairingStage.CONFIRMED).stage)
        }
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertNotNull(fresh)
        assertEquals(fresh, store.load())
    }

    private companion object {
        const val ORIGIN = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"
    }
}
