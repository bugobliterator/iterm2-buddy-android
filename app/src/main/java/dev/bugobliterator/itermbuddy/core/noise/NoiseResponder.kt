package dev.bugobliterator.itermbuddy.core.noise

import androidx.annotation.VisibleForTesting
import com.southernstorm.noise.protocol.HandshakeState

private val EMPTY_PAYLOAD = ByteArray(0)

/**
 * The Mac's side of the XK handshake, used by the fake Mac in tests. It learns the phone's
 * static key from message 3 ([remoteStaticPublicKey]). Steps run strictly in order:
 * [readMessage1], [writeMessage2], [readMessage3], then [handshakeHash] and [split].
 *
 * The public constructor is the only production path; the internal one fixes the ephemeral
 * key for the cacophony vectors.
 */
class NoiseResponder @VisibleForTesting internal constructor(
    localStaticPrivate: ByteArray,
    prologue: ByteArray,
    fixedEphemeralPrivate: ByteArray?,
) : NoiseHandshake(HandshakeState.RESPONDER, localStaticPrivate, null, prologue, fixedEphemeralPrivate) {
    constructor(localStaticPrivate: ByteArray, prologue: ByteArray) : this(localStaticPrivate, prologue, null)

    /** `-> e, es`. Throws [NoiseException] if the phone used another Mac key or prologue. */
    fun readMessage1(msg: ByteArray) {
        read(1, msg).fill(0)
    }

    /** `<- e, ee`: 48 bytes. */
    fun writeMessage2(): ByteArray = write(2, EMPTY_PAYLOAD)

    /** `-> s, se`. Completes the handshake. */
    fun readMessage3(msg: ByteArray) {
        read(3, msg).fill(0)
    }

    /** The phone's static public key from message 3, which the Mac pins. */
    val remoteStaticPublicKey: ByteArray
        get() = peerStaticPublicKey
}
