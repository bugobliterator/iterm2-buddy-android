package dev.bugobliterator.itermbuddy.store

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.util.Base64
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: AndroidKeyStore cannot be faked on the JVM. */
@RunWith(AndroidJUnit4::class)
class KeystorePairingStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(KeystorePairingStore.PREFS_NAME, Context.MODE_PRIVATE)
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

    private fun keyExists() = KeystorePairingStore.androidKeyStore().containsAlias(KeystorePairingStore.KEY_ALIAS)

    private fun storedBlob(): ByteArray =
        Base64.getDecoder().decode(checkNotNull(prefs.getString(KeystorePairingStore.BLOB_KEY, null)))

    private fun putBlob(blob: ByteArray) {
        assertTrue(prefs.edit().putString(KeystorePairingStore.BLOB_KEY, Base64.getEncoder().encodeToString(blob)).commit())
    }

    private fun assertCleared() {
        assertFalse("blob left behind", prefs.contains(KeystorePairingStore.BLOB_KEY))
        assertFalse("key left behind", keyExists())
        assertNull(store.load())
    }

    @Test
    fun roundTrip() {
        val record = beginFreshPairing(store, code)
        assertTrue(keyExists())
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
    fun freshPairingReplacesTheKeyAndEverySecret() {
        val old = beginFreshPairing(store, code)
        val oldBlob = storedBlob()
        val fresh = beginFreshPairing(store, PairingCode(ByteArray(32) { 0x22 }, "pid-other", ORIGIN))
        assertFalse(fresh.roomSecret.contentEquals(old.roomSecret))
        assertFalse(fresh.phoneStaticPrivate.contentEquals(old.phoneStaticPrivate))
        assertEquals(fresh, store.load())
        // The old blob does not open under the new key.
        putBlob(oldBlob)
        assertCleared()
    }

    @Test
    fun deletedKeyLoadsNullAndClears() {
        beginFreshPairing(store, code)
        KeystorePairingStore.androidKeyStore().deleteEntry(KeystorePairingStore.KEY_ALIAS)
        assertTrue(prefs.contains(KeystorePairingStore.BLOB_KEY))
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
        val key = KeystorePairingStore.androidKeyStore().getKey(KeystorePairingStore.KEY_ALIAS, null) as SecretKey
        val plaintext = PairingRecordCodec.encode(record)
        putBlob(PairingBlobCipher.seal(key, plaintext, "itermbuddy-pairing-v2".toByteArray()))
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun garbageLoadsNullAndClears() {
        beginFreshPairing(store, code)
        assertTrue(prefs.edit().putString(KeystorePairingStore.BLOB_KEY, "not base64!").commit())
        assertNull(store.load())
        assertCleared()
    }

    @Test
    fun stagesOnlyMoveForward() {
        val record = beginFreshPairing(store, code)
        advancePairing(store, record, PairingStage.CONFIRMED)
        val failure = runCatching { store.save(record) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNotNull(store.load())
        assertEquals(PairingStage.CONFIRMED, store.load()?.stage)
    }

    private companion object {
        const val ORIGIN = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"
    }
}
