package dev.bugobliterator.itermbuddy.store

import dev.bugobliterator.itermbuddy.core.noise.newStaticKeyPair
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
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
 * Persistent storage for the phone's single pairing.
 *
 * Contract for every implementation:
 * - [save] returns only once the record is durable (it survives an immediate process kill),
 *   and throws if it could not be written.
 * - [save] enforces the stage order (see [checkStageTransition]): into an empty store only a
 *   [PairingStage.PENDING_CONFIRMATION] record; over an existing record only the same pairing
 *   at the same or the next stage. A new pairing therefore always goes through [clear].
 *   A disallowed save throws [IllegalStateException] and changes nothing.
 * - [load] returns null when nothing is stored, or when what is stored cannot be read (it then
 *   clears the store).
 * - Implementations lock on the store instance, and [beginFreshPairing] and [advancePairing]
 *   take the same lock, so their read-modify-write is atomic. Keep one instance per process.
 */
interface PairingStore {
    fun load(): PairingRecord?

    fun save(record: PairingRecord)

    fun clear()
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
 */
class InMemoryPairingStore : PairingStore {
    private var encoded: ByteArray? = null

    @Synchronized
    override fun load(): PairingRecord? {
        val bytes = encoded ?: return null
        return try {
            PairingRecordCodec.decode(bytes)
        } catch (_: PairingRecordFormatException) {
            clear()
            null
        }
    }

    @Synchronized
    override fun save(record: PairingRecord) {
        checkStageTransition(load(), record)
        val bytes = PairingRecordCodec.encode(record)
        encoded?.fill(0)
        encoded = bytes
    }

    @Synchronized
    override fun clear() {
        encoded?.fill(0)
        encoded = null
    }
}

private val secureRandom = SecureRandom()

/**
 * Starts a new pairing for a scanned [code]: clears [store] first, so no secret of an earlier
 * pairing survives even if what follows fails, then mints a new X25519 static key pair and a new
 * 32-byte room secret and saves them at [PairingStage.PENDING_CONFIRMATION].
 * Returns the saved record. Throws if the store cannot be cleared or written.
 */
fun beginFreshPairing(store: PairingStore, code: PairingCode): PairingRecord = synchronized(store) {
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
 * Checkpoints the stored pairing at stage [to] and returns the saved record. [expected] is the
 * record the caller is working on; if the store no longer holds that pairing (it was cleared or
 * re-scanned), this throws [IllegalStateException] rather than advance a pairing the caller never
 * saw. [to] must be the stored stage (a no-op, no write) or the next one; anything else throws
 * [IllegalStateException]. The write is a synchronous commit (see [PairingStore.save]).
 */
fun advancePairing(store: PairingStore, expected: PairingRecord, to: PairingStage): PairingRecord =
    synchronized(store) {
        val current = checkNotNull(store.load()) { "no pairing is stored" }
        check(current.isSamePairingAs(expected)) { "the stored pairing is not the one being advanced" }
        if (current.stage == to) return current
        val next = current.withStage(to)
        checkStageTransition(current, next)
        store.save(next)
        next
    }
