package dev.bugobliterator.itermbuddy.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** What the blob file holds: the alias of the key that sealed [blob], and the base64 blob. */
internal class StoredEntry(val alias: String?, val blob: String?)

/** Where [KeystorePairingStore] keeps its sealed blob. A seam for JVM tests. */
internal interface PairingBlobFile {
    /** Null when the file holds nothing. */
    fun read(): StoredEntry?

    /** Writes [alias] and [blob] together, durably; false when the write failed. */
    fun write(alias: String, blob: String): Boolean

    /** Removes everything, durably; false when the write failed. */
    fun clear(): Boolean
}

/**
 * The key operations [KeystorePairingStore] needs. A seam so the failure classifier and key
 * rotation can be tested on the JVM with real exception instances.
 */
internal interface PairingKeyVault {
    /** The key under [alias], or null when there is none (Keystore's KEY_NOT_FOUND). */
    fun find(alias: String): SecretKey?

    /** Makes a new AES-256-GCM key under [alias], replacing any key there. */
    fun create(alias: String): SecretKey

    /** Deletes [alias]; no-op when absent; throws on any other failure. */
    fun delete(alias: String)

    /** Every alias this app has. AndroidKeyStore returns an empty list when listing fails. */
    fun aliases(): List<String>

    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray = PairingBlobCipher.seal(key, plaintext)

    fun open(key: SecretKey, blob: ByteArray): ByteArray = PairingBlobCipher.open(key, blob)
}

/**
 * The private SharedPreferences file [PREFS_NAME]. Both keys go in one `commit()`, so a kill never
 * leaves a blob beside the wrong alias.
 *
 * SharedPreferences changes its in-memory map before writing to disk, so after a failed commit
 * memory would be ahead of disk. The previous values are then put back in memory (and queued
 * for disk with `apply()`, a rewrite of what disk already holds), so a later read in this process
 * sees what a restart would.
 */
internal class SharedPrefsBlobFile(context: Context) : PairingBlobFile {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): StoredEntry? {
        val alias = prefs.getString(ALIAS_KEY, null)
        val blob = prefs.getString(BLOB_KEY, null)
        return if (alias == null && blob == null) null else StoredEntry(alias, blob)
    }

    override fun write(alias: String, blob: String): Boolean {
        val before = read()
        val committed = prefs.edit().putString(ALIAS_KEY, alias).putString(BLOB_KEY, blob).commit()
        if (!committed) restore(before)
        return committed
    }

    override fun clear(): Boolean {
        val before = read()
        val committed = prefs.edit().clear().commit()
        if (!committed) restore(before)
        return committed
    }

    /** Puts [before] back in memory after a failed commit; disk already holds it. */
    private fun restore(before: StoredEntry?) {
        prefs.edit(commit = false) {
            clear()
            before?.alias?.let { putString(ALIAS_KEY, it) }
            before?.blob?.let { putString(BLOB_KEY, it) }
        }
    }

    companion object {
        const val PREFS_NAME = "itermbuddy-pairing"
        const val ALIAS_KEY = "alias"
        const val BLOB_KEY = "record"
    }
}

/**
 * AndroidKeyStore. Keys are AES-256 for GCM, randomized encryption required, with no
 * user-authentication or unlocked-device requirement: the app reconnects in the background with
 * the screen locked. The trade-off: on a live rooted or compromised device, code running as this
 * app can use the key; the key is non-exportable, so a copied blob or an offline image is useless.
 *
 * Keys are made in StrongBox (the Pixel's Titan M2) when possible, else in the TEE: generation
 * falls back on any [ProviderException], which covers `StrongBoxUnavailableException` (no
 * StrongBox) and a busy or unreachable StrongBox. StrongBox is slower per operation, but this
 * store does a handful of small operations per connect, and the key then never leaves the secure
 * element.
 */
internal class AndroidKeyVault : PairingKeyVault {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun find(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    override fun create(alias: String): SecretKey =
        try {
            generate(alias, strongBox = true)
        } catch (_: ProviderException) {
            generate(alias, strongBox = false)
        }

    override fun delete(alias: String) = keyStore().deleteEntry(alias)

    override fun aliases(): List<String> = keyStore().aliases().toList()

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256
    }
}
