package dev.bugobliterator.itermbuddy.core.noise

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.io.Closeable
import java.security.GeneralSecurityException

/** The suite carried in the pairing code's `proto` and used for every companion handshake. */
const val NOISE_PROTOCOL_NAME = "Noise_XK_25519_ChaChaPoly_BLAKE2s"

/** Noise caps every handshake and transport message at this many bytes (upstream `maxNoiseMessageSize`). */
const val MAX_NOISE_MESSAGE_SIZE = 65535

private const val KEY_BYTES = 32

/**
 * A handshake or transport failure: bad authentication tag, wrong key or prologue, replayed or
 * reordered frame, malformed or oversize message, or use of a channel that has been closed.
 * The connection it happened on is finished.
 */
class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A fresh X25519 static key pair: `(private, public)`, 32 bytes each. */
fun newStaticKeyPair(): Pair<ByteArray, ByteArray> {
    val dh = Noise.createDH("25519")
    try {
        dh.generateKeyPair()
        val privateKey = ByteArray(dh.privateKeyLength).also { dh.getPrivateKey(it, 0) }
        val publicKey = ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
        return privateKey to publicKey
    } finally {
        dh.destroy()
    }
}

/**
 * One side of a `Noise_XK_25519_ChaChaPoly_BLAKE2s` handshake, as upstream `NoiseHandshake`:
 * `<- s`, then `-> e, es`, `<- e, ee`, `-> s, se`, every payload empty. Each message is one
 * WebSocket frame (48, 48 and 64 bytes).
 *
 * Not thread-safe: one caller drives the steps in order. Any failure destroys the handshake
 * state; after that, and after [close] or [split], every step throws [IllegalStateException].
 */
sealed class NoiseHandshake(
    role: Int,
    localStaticPrivate: ByteArray,
    remoteStaticPublic: ByteArray?,
    prologue: ByteArray,
    fixedEphemeralPrivate: ByteArray?,
) : Closeable {
    private var state: HandshakeState?

    /** The number of the next handshake message, 1 to 3; 4 once the last one is done. */
    private var nextMessage = 1
    private var hash: ByteArray? = null
    private var peerStatic: ByteArray? = null

    init {
        require(localStaticPrivate.size == KEY_BYTES) { "static private key must be 32 bytes" }
        require(remoteStaticPublic == null || remoteStaticPublic.size == KEY_BYTES) {
            "responder static public key must be 32 bytes"
        }
        require(fixedEphemeralPrivate == null || fixedEphemeralPrivate.size == KEY_BYTES) {
            "ephemeral private key must be 32 bytes"
        }
        val handshake = HandshakeState(NOISE_PROTOCOL_NAME, role)
        try {
            handshake.setPrologue(prologue, 0, prologue.size)
            handshake.localKeyPair.setPrivateKey(localStaticPrivate, 0)
            if (remoteStaticPublic != null) {
                handshake.remotePublicKey.setPublicKey(remoteStaticPublic, 0)
            }
            if (fixedEphemeralPrivate != null) {
                handshake.fixedEphemeralKey.setPrivateKey(fixedEphemeralPrivate, 0)
            }
            handshake.start()
        } catch (e: Throwable) {
            handshake.destroy()
            throw e
        }
        state = handshake
    }

    /**
     * The handshake transcript hash `h` (32 bytes, BLAKE2s), captured when the last message is
     * written or read, before [split]. Both sides hold the same value only if no one sat in the
     * middle; the SAS code derives from it. Throws [IllegalStateException] before then.
     */
    val handshakeHash: ByteArray
        get() = checkNotNull(hash) { "handshake has not finished" }.copyOf()

    /** The peer's static public key, once the pattern has delivered it (message 3 for the responder). */
    internal val peerStaticPublicKey: ByteArray
        get() = checkNotNull(peerStatic) { "peer static key not received yet" }.copyOf()

    /** Writes handshake message [number] carrying [payload]; the companion always sends an empty payload. */
    internal fun write(number: Int, payload: ByteArray): ByteArray {
        val handshake = step(number, HandshakeState.WRITE_MESSAGE)
        val buffer = ByteArray(MAX_NOISE_MESSAGE_SIZE)
        val length = guard { handshake.writeMessage(buffer, 0, payload, 0, payload.size) }
        finishStep(handshake)
        return buffer.copyOf(length)
    }

    /**
     * Reads handshake message [number] and returns its payload. Upstream passes no payload
     * buffer, so noise-c authenticates any payload and discards it; callers do the same.
     */
    internal fun read(number: Int, message: ByteArray): ByteArray {
        val handshake = step(number, HandshakeState.READ_MESSAGE)
        if (message.size > MAX_NOISE_MESSAGE_SIZE) {
            fail(null)
        }
        val payload = ByteArray(message.size)
        try {
            val length = guard { handshake.readMessage(message, 0, message.size, payload, 0) }
            finishStep(handshake)
            return payload.copyOf(length)
        } finally {
            payload.fill(0)
        }
    }

    /**
     * Finishes the handshake: returns the transport channel and destroys the handshake state.
     * Only valid once message 3 has been written or read.
     */
    fun split(): NoiseChannel {
        val handshake = checkNotNull(state) { "handshake is closed" }
        check(nextMessage == 4 && handshake.action == HandshakeState.SPLIT) { "handshake has not finished" }
        val pair = try {
            guard { handshake.split() }
        } finally {
            close()
        }
        // noise-java swaps the pair for the responder, so the sender is always this side's.
        return NoiseChannel(pair.sender, pair.receiver)
    }

    /** Destroys the handshake state (keys, chaining key). Idempotent. */
    override fun close() {
        state?.destroy()
        state = null
    }

    override fun toString(): String = javaClass.simpleName

    private fun step(number: Int, action: Int): HandshakeState {
        val handshake = checkNotNull(state) { "handshake is closed" }
        check(number == nextMessage && handshake.action == action) {
            "handshake message $number is out of order"
        }
        return handshake
    }

    private fun finishStep(handshake: HandshakeState) {
        nextMessage++
        if (handshake.action == HandshakeState.SPLIT) {
            // Read before split: noise-java's array is live state, so copy it.
            hash = handshake.handshakeHash.copyOf()
            val remote = handshake.remotePublicKey
            if (remote != null && remote.hasPublicKey()) {
                peerStatic = ByteArray(remote.publicKeyLength).also { remote.getPublicKey(it, 0) }
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            fail(e)
        } catch (e: RuntimeException) {
            // noise-java signals nonce exhaustion and bad arguments with runtime exceptions;
            // any other one must still destroy the handshake rather than escape with it live.
            fail(e)
        }

    private fun fail(cause: Throwable?): Nothing {
        close()
        throw NoiseException("Noise handshake failed", cause)
    }
}
