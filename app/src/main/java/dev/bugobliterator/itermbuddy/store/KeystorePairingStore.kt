package dev.bugobliterator.itermbuddy.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The device's [PairingStore]: the [PairingRecordCodec] plaintext sealed by [PairingBlobCipher]
 * under an AES-256-GCM key in AndroidKeyStore (alias [KEY_ALIAS]), kept base64-encoded in the
 * private SharedPreferences file [PREFS_NAME]. The manifest disables backup and device transfer,
 * and the key is non-exportable, so a copied blob is useless elsewhere.
 *
 * - The key needs no user authentication and no unlocked device: the app reconnects in the
 *   background, screen locked.
 * - The key is made in StrongBox when the device has one (the Pixel's Titan M2), else in the TEE
 *   (`StrongBoxUnavailableException` falls back). StrongBox is slower per operation, but this
 *   store does a handful of small operations per connect, and the key then never leaves the
 *   secure element.
 * - [save] writes with `commit()`, so a returned save survives an immediate process kill, and
 *   throws [IOException] if the write failed.
 * - [load] clears the store (blob and key) and returns null when the blob cannot be decrypted or
 *   parsed: key lost or invalidated, blob tampered, restored from elsewhere, or of another format.
 *   A failure Keystore marks transient is rethrown instead, so a busy Keystore never wipes a
 *   working pairing.
 * - [clear] deletes the blob and then the key; a new pairing gets a new key.
 *
 * Every method locks this instance (see [PairingStore]); use one instance per process.
 */
class KeystorePairingStore(context: Context) : PairingStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): PairingRecord? {
        val stored = prefs.getString(BLOB_KEY, null) ?: return null
        var plaintext: ByteArray? = null
        try {
            val blob = Base64.getDecoder().decode(stored)
            val key = existingKey() ?: throw GeneralSecurityException("pairing key is missing")
            plaintext = PairingBlobCipher.open(key, blob)
            return PairingRecordCodec.decode(plaintext)
        } catch (e: Exception) {
            if (e.isTransientKeystoreFailure()) throw e
            clear()
            return null
        } finally {
            plaintext?.fill(0)
        }
    }

    @Synchronized
    override fun save(record: PairingRecord) {
        checkStageTransition(load(), record)
        val plaintext = PairingRecordCodec.encode(record)
        val blob = try {
            PairingBlobCipher.seal(existingKey() ?: generateKey(), plaintext)
        } finally {
            plaintext.fill(0)
        }
        val written = prefs.edit().putString(BLOB_KEY, Base64.getEncoder().encodeToString(blob)).commit()
        if (!written) throw IOException("could not write the pairing store")
    }

    @Synchronized
    override fun clear() {
        // Blob first: a kill between the two leaves a key with nothing to decrypt, never a blob
        // whose key is about to change.
        val cleared = prefs.edit().clear().commit()
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        if (!cleared) throw IOException("could not clear the pairing store")
    }

    private fun existingKey(): SecretKey? = androidKeyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun generateKey(): SecretKey =
        try {
            generateKey(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    internal companion object {
        const val KEY_ALIAS = "itermbuddy-pairing"
        const val PREFS_NAME = "itermbuddy-pairing"
        const val BLOB_KEY = "record"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256

        fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        /** True when Keystore says this failure may pass on retry (busy, out of resources). */
        private fun Throwable.isTransientKeystoreFailure(): Boolean =
            generateSequence(this) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .any { it is android.security.KeyStoreException && it.isTransientFailure }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
