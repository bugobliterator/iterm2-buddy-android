package dev.bugobliterator.itermbuddy.store

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: AndroidKeyStore cannot be faked on the JVM. */
@RunWith(AndroidJUnit4::class)
class KeystorePairingStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SharedPrefsBlobFile.PREFS_NAME, Context.MODE_PRIVATE)
    private val code = PairingCode(ByteArray(32) { 0x11 }, "pid-device-test", ORIGIN)
    private lateinit var store: KeystorePairingStore

    @Before
    fun setUp() {
        store = KeystorePairingStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(AndroidKeyVault.ANDROID_KEYSTORE).apply { load(null) }

    private fun pairingAliases(): Set<String> =
        keyStore().aliases().toList().filter { KeystorePairingStore.isPairingAlias(it) }.toSet()

    private fun storedAlias(): String = checkNotNull(prefs.getString(SharedPrefsBlobFile.ALIAS_KEY, null))

    private fun storedBlob(): ByteArray =
        Base64.getDecoder().decode(checkNotNull(prefs.getString(SharedPrefsBlobFile.BLOB_KEY, null)))

    private fun putBlob(blob: ByteArray) {
        assertTrue(prefs.edit().putString(SharedPrefsBlobFile.BLOB_KEY, Base64.getEncoder().encodeToString(blob)).commit())
    }

    private fun assertCleared() {
        assertFalse("blob left behind", prefs.contains(SharedPrefsBlobFile.BLOB_KEY))
        assertFalse("alias left behind", prefs.contains(SharedPrefsBlobFile.ALIAS_KEY))
        assertTrue("keys left behind: ${pairingAliases()}", pairingAliases().isEmpty())
        assertNull(store.load())
    }

    @Test
    fun roundTrip() {
        val record = beginFreshPairing(store, code)
        assertEquals(setOf(storedAlias()), pairingAliases())
        assertEquals(record, store.load())
        assertEquals(record, KeystorePairingStore(context).load())

        advancePairing(store, record, PairingStage.CONFIRMED)
        val registered = advancePairing(store, record, PairingStage.REGISTERED)
        assertEquals(registered, KeystorePairingStore(context).load())
    }

    @Test
    fun blobIsNotPlaintext() {
        val record = beginFreshPairing(store, code)
        val blob = storedBlob()
        assertEquals(PairingBlobCipher.ENVELOPE_VERSION, blob[0])
        val text = String(blob, Charsets.ISO_8859_1)
        assertFalse(text.contains("pid-device-test"))
        assertFalse(text.contains(Base64.getEncoder().encodeToString(record.roomSecret)))
    }

    @Test
    fun freshPairingRotatesTheAliasAndEverySecret() {
        val old = beginFreshPairing(store, code)
        val oldAlias = storedAlias()
        val oldBlob = storedBlob()
        val fresh = beginFreshPairing(store, PairingCode(ByteArray(32) { 0x22 }, "pid-other", ORIGIN))
        val newAlias = storedAlias()
        assertNotEquals(oldAlias, newAlias)
        assertEquals(setOf(newAlias), pairingAliases())
        assertFalse(fresh.roomSecret.contentEquals(old.roomSecret))
        assertFalse(fresh.phoneStaticPrivate.contentEquals(old.phoneStaticPrivate))
        assertEquals(fresh, store.load())
        // The old blob, under its own alias, has no key any more.
        assertTrue(
            prefs.edit()
                .putString(SharedPrefsBlobFile.ALIAS_KEY, oldAlias)
                .putString(SharedPrefsBlobFile.BLOB_KEY, Base64.getEncoder().encodeToString(oldBlob))
                .commit(),
        )
        assertCleared()
    }

    @Test
    fun keyPresentButNoBlobIsReplacedOnSave() {
        // The window a kill can leave: a pairing key with no blob beside it.
        val record = beginFreshPairing(store, code)
        val staleAlias = storedAlias()
        assertTrue(prefs.edit().clear().commit())
        assertEquals(setOf(staleAlias), pairingAliases())
        assertNull(store.load())

        store.save(record)
        val newAlias = storedAlias()
        assertNotEquals(staleAlias, newAlias)
        assertEquals(setOf(newAlias), pairingAliases())
        assertEquals(record, store.load())
    }

    @Test
    fun twoInstancesShareOneLock() {
        val other = KeystorePairingStore(context)
        val record = beginFreshPairing(store, code)
        val started = CountDownLatch(1)
        var fresh: PairingRecord? = null
        val thread = Thread {
            started.countDown()
            fresh = beginFreshPairing(other, PairingCode(ByteArray(32) { 0x33 }, "pid-racer", ORIGIN))
        }
        store.withLock {
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            thread.join(500)
            assertTrue("the other instance ran inside this lock", thread.isAlive)
            assertEquals(PairingStage.CONFIRMED, advancePairing(store, record, PairingStage.CONFIRMED).stage)
        }
        thread.join(30_000)
        assertFalse(thread.isAlive)
        assertEquals(fresh, store.load())
        assertThrows(StalePairingFlowException::class.java) {
            advancePairing(store, record, PairingStage.REGISTERED)
        }
    }

    @Test
    fun deletedKeyLoadsNullAndClears() {
        beginFreshPairing(store, code)
        keyStore().deleteEntry(storedAlias())
        assertTrue(prefs.contains(SharedPrefsBlobFile.BLOB_KEY))
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun tamperedBlobLoadsNullAndClears() {
        beginFreshPairing(store, code)
        val blob = storedBlob()
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0x01).toByte()
        putBlob(blob)
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun blobSealedForAnotherPurposeLoadsNullAndClears() {
        val record = beginFreshPairing(store, code)
        val key = keyStore().getKey(storedAlias(), null) as SecretKey
        val plaintext = PairingRecordCodec.encode(record)
        putBlob(PairingBlobCipher.seal(key, plaintext, "itermbuddy-pairing-v2".toByteArray()))
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun garbageLoadsNullAndClears() {
        beginFreshPairing(store, code)
        assertTrue(prefs.edit().putString(SharedPrefsBlobFile.BLOB_KEY, "not base64!").commit())
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun stagesOnlyMoveForward() {
        val record = beginFreshPairing(store, code)
        advancePairing(store, record, PairingStage.CONFIRMED)
        assertThrows(IllegalStateException::class.java) { store.save(record) }
        assertThrows(StalePairingFlowException::class.java) {
            advancePairing(store, record, PairingStage.PENDING_CONFIRMATION)
        }
        assertEquals(PairingStage.CONFIRMED, store.load()?.stage)
    }

    private companion object {
        const val ORIGIN = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"
    }
}
