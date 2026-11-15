package dev.bugobliterator.itermbuddy.store

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * The device's [PairingStore]: the [PairingRecordCodec] plaintext sealed by [PairingBlobCipher]
 * under an AES-256-GCM key in AndroidKeyStore (see [AndroidKeyVault]), kept base64-encoded in a
 * private SharedPreferences file together with the key's alias (see [SharedPrefsBlobFile]).
 * The manifest disables backup and device transfer.
 *
 * **Keys.** Each pairing gets its own key under a fresh alias `itermbuddy-pairing-<32 hex>`.
 * A save into an empty store deletes every other alias with the `itermbuddy-pairing` prefix
 * first, then makes the new key, so a key left behind by a killed [clear] never seals a new
 * pairing. [clear] deletes the stored alias unconditionally (a failure is a clear failure), then
 * any other prefixed alias Keystore lists (listing is best-effort: AndroidKeyStore lists nothing
 * when it fails, and the next save into an empty store retries), then the blob.
 *
 * **Wipe only on proof** ([load] and the read inside [save]). The store is cleared only when the
 * stored data or key is shown to be bad:
 * - the blob file holds a blob without an alias, an alias without a blob, or a foreign alias;
 * - the blob is not base64;
 * - the alias has no key (`getKey` returns null for KEY_NOT_FOUND);
 * - `getKey` throws [UnrecoverableKeyException] caused by a Keystore error `ERROR_KEY_CORRUPTED`
 *   or `ERROR_KEY_DOES_NOT_EXIST` (VALUE_CORRUPTED, KEY_NOT_FOUND, KEY_PERMANENTLY_INVALIDATED);
 * - decryption throws [AEADBadTagException] or [KeyPermanentlyInvalidatedException];
 * - [PairingBlobFormatException] (envelope version or length) or [PairingRecordFormatException].
 *
 * Anything else, including every [ProviderException] (BackendBusyException,
 * KeyStoreConnectException), UserNotAuthenticatedException (how a locked or uninitialised
 * StrongBox surfaces), a Keystore system or transient error, and anything unknown, throws
 * [PairingStoreUnavailableException] and leaves the blob and the key untouched.
 * Known gap: AndroidKeyStore turns KEY_PERMANENTLY_INVALIDATED from `getKey` into an
 * [UnrecoverableKeyException] with no cause, which is classed as unavailable. That cannot happen
 * to these keys, which are not bound to user authentication; and Scan again always works.
 *
 * Every method holds one process-wide lock, so any number of instances is safe.
 */
class KeystorePairingStore internal constructor(
    private val file: PairingBlobFile,
    private val vault: PairingKeyVault,
    /** Reads a Keystore error's public code; a seam because the JVM stub cannot. */
    private val keystoreErrorCode: (android.security.KeyStoreException) -> Int = { it.numericErrorCode },
) : PairingStore {
    constructor(context: Context) : this(SharedPrefsBlobFile(context), AndroidKeyVault())

    private class Current(val record: PairingRecord, val alias: String, val key: SecretKey)

    override fun <T> withLock(block: () -> T): T = synchronized(LOCK) { block() }

    override fun load(): PairingRecord? = withLock { readCurrent()?.record }

    /**
     * Throws [IllegalStateException] for a disallowed stage change, [PairingStoreUnavailableException]
     * when Keystore fails, and [IOException] when the file write fails.
     */
    override fun save(record: PairingRecord) = withLock {
        val current = readCurrent()
        checkStageTransition(current?.record, record)
        val alias: String
        val key: SecretKey
        if (current != null) {
            alias = current.alias
            key = current.key
        } else {
            alias = newAlias()
            key = keystore("make the pairing key") {
                vault.aliases().filter(::isPairingAlias).forEach(vault::delete)
                vault.create(alias)
            }
        }
        val plaintext = PairingRecordCodec.encode(record)
        val blob = try {
            keystore("seal the pairing") { vault.seal(key, plaintext) }
        } finally {
            plaintext.fill(0)
        }
        if (!file.write(alias, Base64.getEncoder().encodeToString(blob))) {
            throw IOException("could not write the pairing store")
        }
    }

    /** Throws [PairingStoreUnavailableException] when a key cannot be deleted, [IOException] when the file write fails. */
    override fun clear() = withLock {
        // Key first: if the blob then survives (failed write, kill), its key is gone, which the
        // next load takes as proof and wipes.
        val alias = file.read()?.alias
        keystore("delete the pairing key") {
            if (alias != null && isPairingAlias(alias)) vault.delete(alias)
            vault.aliases().filter(::isPairingAlias).forEach(vault::delete)
        }
        if (!file.clear()) throw IOException("could not clear the pairing store")
    }

    private fun readCurrent(): Current? {
        val entry = file.read() ?: return null
        val alias = entry.alias
        val text = entry.blob
        if (alias == null || text == null || !isPairingAlias(alias)) return wipe()
        val blob = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return wipe()
        }
        val key = try {
            vault.find(alias)
        } catch (e: Exception) {
            return failed(e)
        } ?: return wipe()
        var plaintext: ByteArray? = null
        try {
            plaintext = vault.open(key, blob)
            return Current(PairingRecordCodec.decode(plaintext), alias, key)
        } catch (e: Exception) {
            return failed(e)
        } finally {
            plaintext?.fill(0)
        }
    }

    private fun failed(e: Exception): Current? {
        if (isProofOfBadData(e, keystoreErrorCode)) return wipe()
        throw PairingStoreUnavailableException("the pairing store is unavailable", e)
    }

    private fun wipe(): Current? {
        clear()
        return null
    }

    private fun newAlias(): String =
        ALIAS_PREFIX + ByteArray(ALIAS_RANDOM_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    internal companion object {
        /** The alias every pairing key starts with; 8f57a6a used it bare. */
        const val LEGACY_ALIAS = "itermbuddy-pairing"
        const val ALIAS_PREFIX = "$LEGACY_ALIAS-"
        private const val ALIAS_RANDOM_BYTES = 16

        /** Public `KeyStoreException` codes that prove the key is gone or corrupt. */
        private val KEY_GONE_CODES = setOf(
            android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
            android.security.KeyStoreException.ERROR_KEY_CORRUPTED,
        )
        private const val MAX_CAUSE_DEPTH = 16

        private val LOCK = Any()
        private val random = SecureRandom()

        fun isPairingAlias(alias: String): Boolean = alias == LEGACY_ALIAS || alias.startsWith(ALIAS_PREFIX)

        /**
         * True only when [e] proves the stored blob or key is bad (see the class comment). A code
         * that cannot be read counts as no proof.
         */
        fun isProofOfBadData(e: Throwable, keystoreErrorCode: (android.security.KeyStoreException) -> Int): Boolean =
            when (e) {
                is PairingRecordFormatException,
                is PairingBlobFormatException,
                is AEADBadTagException,
                is KeyPermanentlyInvalidatedException,
                -> true
                is UnrecoverableKeyException -> {
                    val cause = generateSequence(e.cause) { it.cause }
                        .take(MAX_CAUSE_DEPTH)
                        .firstOrNull { it is android.security.KeyStoreException } as android.security.KeyStoreException?
                    val code = cause?.let { runCatching { keystoreErrorCode(it) }.getOrNull() }
                    code in KEY_GONE_CODES
                }
                else -> false
            }

        /** Runs a Keystore step, turning its failure into [PairingStoreUnavailableException]. */
        private inline fun <T> keystore(what: String, block: () -> T): T =
            try {
                block()
            } catch (e: GeneralSecurityException) {
                throw PairingStoreUnavailableException("could not $what", e)
            } catch (e: ProviderException) {
                throw PairingStoreUnavailableException("could not $what", e)
            }
    }
}
