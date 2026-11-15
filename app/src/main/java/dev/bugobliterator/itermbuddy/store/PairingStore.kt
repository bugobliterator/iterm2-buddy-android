package dev.bugobliterator.itermbuddy.store

import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How far a pairing has got. A stage only moves forward, one step at a time, and only a
 * [PairingStore.clear] (which [beginFreshPairing] does first) starts again from the beginning.
 */
enum class PairingStage {
    /** Keys and room secret minted for a scanned code; the Mac has not accepted the code yet. */
    PENDING_CONFIRMATION,

    /** The Mac accepted the code and pinned this phone's static key; not yet registered. */
    CONFIRMED,

    /** The room secret was stored by the Mac and the room registered with the relay. */
    REGISTERED,
}

private const val SECRET_SIZE = 32

/**
 * Everything the phone keeps about its one pairing: the scanned [code] (Mac static key, pid,
 * relay origin), the phone's X25519 static private key, the 32-byte room secret, and the [stage].
 *
 * Secrets are copied in and out, so no caller can change the record's bytes. Equality is by
 * content (secrets compared in constant time); [hashCode] and [toString] never use the secrets.
 */
class PairingRecord(
    val code: PairingCode,
    phoneStaticPrivate: ByteArray,
    roomSecret: ByteArray,
    val stage: PairingStage,
) {
    private val staticKey: ByteArray = phoneStaticPrivate.copyOf()
    private val secret: ByteArray = roomSecret.copyOf()

    init {
        require(staticKey.size == SECRET_SIZE) { "phone static private key must be $SECRET_SIZE bytes, got ${staticKey.size}" }
        require(secret.size == SECRET_SIZE) { "room secret must be $SECRET_SIZE bytes, got ${secret.size}" }
    }

    /** The phone's X25519 static private key (32 bytes); a fresh copy on every read. */
    val phoneStaticPrivate: ByteArray
        get() = staticKey.copyOf()

    /** The 32-byte relay room secret; a fresh copy on every read. */
    val roomSecret: ByteArray
        get() = secret.copyOf()

    /** The same pairing at [stage]. */
    fun withStage(stage: PairingStage): PairingRecord = PairingRecord(code, staticKey, secret, stage)

    /** True when [other] holds the same code and secrets, whatever its stage. */
    fun isSamePairingAs(other: PairingRecord): Boolean =
        code == other.code &&
            // Evaluate both comparisons: no short-circuit on the first secret.
            MessageDigest.isEqual(staticKey, other.staticKey) and MessageDigest.isEqual(secret, other.secret)

    override fun equals(other: Any?): Boolean =
        other is PairingRecord && stage == other.stage && isSamePairingAs(other)

    override fun hashCode(): Int = code.hashCode() * 31 + stage.hashCode()

    override fun toString(): String =
        "PairingRecord(pairingID=${code.pairingID}, relayOrigin=${code.relayOrigin}, stage=$stage)"
}

/**
 * The store cannot be read or written right now (Keystore busy, restarting or unreachable, or
 * any failure that is not proof the stored data is bad). Nothing was wiped or changed.
 *
 * Client contract: treat it as "Waiting for your Mac"; back off and retry. Never re-pair
 * because of it: the stored pairing is intact and the Mac still pins this phone's key.
 */
class PairingStoreUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [advancePairing] was called by a flow that is behind the store: the pairing it holds was
 * cleared, replaced or wiped as bad data, or another flow already moved it past the requested
 * stage.
 *
 * Client contract: abort this flow; call [PairingStore.load] to see what is stored now. That may
 * be a later stage of the same pairing, a newer pairing, or nothing (then show Scan again):
 * a proof-based wipe inside the load that [advancePairing] does can precede this exception.
 */
class StalePairingFlowException(message: String) : IllegalStateException(message)

/**
 * Persistent storage for the phone's single pairing.
 *
 * Contract for every implementation:
 * - [save] returns only once the record is durable (it survives an immediate process kill),
 *   and throws if it could not be written ([IOException], or [PairingStoreUnavailableException]
 *   for a passing failure).
 * - [save] enforces the stage order (see [checkStageTransition]): into an empty store only a
 *   [PairingStage.PENDING_CONFIRMATION] record; over an existing record only the same pairing
 *   at the same or the next stage. A new pairing therefore always goes through [clear].
 *   A disallowed save throws [IllegalStateException] and changes nothing.
 * - [load] returns null when nothing is stored, or when there is proof that what is stored is
 *   bad (it then clears the store). It throws only [PairingStoreUnavailableException]: on any
 *   other failure, and when the clearing of proof-bad data cannot finish; the next load retries.
 * - Every method runs under [withLock], whose lock covers every instance over the same storage,
 *   so [beginFreshPairing] and [advancePairing] are atomic however many instances exist.
 */
interface PairingStore {
    fun load(): PairingRecord?

    fun save(record: PairingRecord)

    fun clear()

    /** Runs [block] holding the lock every method of every instance over this storage takes. */
    fun <T> withLock(block: () -> T): T
}

/**
 * Throws [IllegalStateException] unless a store holding [current] may be overwritten with [next].
 */
internal fun checkStageTransition(current: PairingRecord?, next: PairingRecord) {
    if (current == null) {
        check(next.stage == PairingStage.PENDING_CONFIRMATION) {
            "a new pairing must start at ${PairingStage.PENDING_CONFIRMATION}, not ${next.stage}"
        }
        return
    }
    check(current.isSamePairingAs(next)) { "a different pairing is stored; clear the store first" }
    check(next.stage.ordinal == current.stage.ordinal || next.stage.ordinal == current.stage.ordinal + 1) {
        "pairing stage cannot move from ${current.stage} to ${next.stage}"
    }
}

/**
 * A [PairingStore] in memory, for tests. It keeps the record in its encoded form, so every
 * save and load goes through [PairingRecordCodec] as the Keystore store's does.
 * [sharedView] gives a second instance over the same storage and lock, as two
 * `KeystorePairingStore`s in one process are.
 */
class InMemoryPairingStore private constructor(private val storage: Storage) : PairingStore {
    constructor() : this(Storage())

    private class Storage {
        var encoded: ByteArray? = null
    }

    /** Another instance over the same storage and lock. */
    fun sharedView(): InMemoryPairingStore = InMemoryPairingStore(storage)

    override fun <T> withLock(block: () -> T): T = synchronized(storage) { block() }

    override fun load(): PairingRecord? = withLock {
        storage.encoded?.let { bytes ->
            try {
                PairingRecordCodec.decode(bytes)
            } catch (_: PairingRecordFormatException) {
                clear()
                null
            }
        }
    }

    override fun save(record: PairingRecord) = withLock {
        checkStageTransition(load(), record)
        val bytes = PairingRecordCodec.encode(record)
        storage.encoded?.fill(0)
        storage.encoded = bytes
    }

    override fun clear() = withLock {
        storage.encoded?.fill(0)
        storage.encoded = null
    }
}

private val secureRandom = SecureRandom()

/**
 * Starts a new pairing for a scanned [code]: clears [store] first, so no secret of an earlier
 * pairing survives even if what follows fails, then mints a new X25519 static key pair and a new
 * 32-byte room secret and saves them at [PairingStage.PENDING_CONFIRMATION].
 * Returns the saved record. Throws if the store cannot be cleared or written; if that happens
 * after the clear (say the new key cannot be made), no pairing is left and the client shows
 * Scan again.
 */
fun beginFreshPairing(store: PairingStore, code: PairingCode): PairingRecord = store.withLock {
    store.clear()
    val (staticPrivate, _) = newStaticKeyPair()
    val roomSecret = ByteArray(SECRET_SIZE).also(secureRandom::nextBytes)
    try {
        val record = PairingRecord(code, staticPrivate, roomSecret, PairingStage.PENDING_CONFIRMATION)
        store.save(record)
        record
    } finally {
        staticPrivate.fill(0)
        roomSecret.fill(0)
    }
}

/**
 * Checkpoints the stored pairing at stage [to] and returns the saved record. The write is a
 * synchronous commit (see [PairingStore.save]).
 *
 * - [expected] is the record the caller is working on. If the store no longer holds that pairing
 *   (cleared, re-scanned or wiped as bad data), or already holds it at a later stage, this throws
 *   [StalePairingFlowException]: the caller aborts this flow and calls [PairingStore.load] to see
 *   what is stored now.
 * - [to] equal to the stored stage is a no-op with no write.
 * - Skipping a stage is a programming error and throws [IllegalStateException].
 */
fun advancePairing(store: PairingStore, expected: PairingRecord, to: PairingStage): PairingRecord =
    store.withLock {
        val current = store.load() ?: throw StalePairingFlowException("no pairing is stored")
        if (!current.isSamePairingAs(expected)) {
            throw StalePairingFlowException("the stored pairing is not the one being advanced")
        }
        if (to.ordinal < current.stage.ordinal) {
            throw StalePairingFlowException("the pairing is already at ${current.stage}")
        }
        if (current.stage == to) return@withLock current
        val next = current.withStage(to)
        checkStageTransition(current, next)
        store.save(next)
        next
    }
