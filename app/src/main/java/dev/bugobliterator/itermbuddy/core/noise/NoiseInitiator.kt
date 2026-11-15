package dev.bugobliterator.itermbuddy.core.noise

import androidx.annotation.VisibleForTesting
import com.southernstorm.noise.protocol.HandshakeState

private val EMPTY_PAYLOAD = ByteArray(0)

/**
 * The phone's side of the XK handshake. It knows the Mac's static key from the QR code
 * (`rs`) and sends its own static, encrypted, in message 3; the Mac pins it from there.
 *
 * [prologue] is `"iterm2-companion/v1/pid:" + pairingID` (`PairingCode.prologue`); a
 * mismatch with the Mac's fails message 1. Steps run strictly in order:
 * [writeMessage1], [readMessage2], [writeMessage3], then [handshakeHash] and [split].
 *
 * The public constructor is the only production path. The internal one fixes the ephemeral
 * key for the cacophony vectors; a fixed ephemeral in production would reuse it across
 * handshakes and break forward secrecy.
 */
class NoiseInitiator @VisibleForTesting internal constructor(
    responderStatic: ByteArray,
    localStaticPrivate: ByteArray,
    prologue: ByteArray,
    fixedEphemeralPrivate: ByteArray?,
) : NoiseHandshake(HandshakeState.INITIATOR, localStaticPrivate, responderStatic, prologue, fixedEphemeralPrivate) {
    constructor(responderStatic: ByteArray, localStaticPrivate: ByteArray, prologue: ByteArray) :
        this(responderStatic, localStaticPrivate, prologue, null)

    /** `-> e, es`: 48 bytes. */
    fun writeMessage1(): ByteArray = write(1, EMPTY_PAYLOAD)

    /** `<- e, ee`. Throws [NoiseException] if it does not authenticate (wrong Mac key or prologue). */
    fun readMessage2(msg: ByteArray) {
        read(2, msg).fill(0)
    }

    /** `-> s, se`: 64 bytes. Completes the handshake. */
    fun writeMessage3(): ByteArray = write(3, EMPTY_PAYLOAD)
}
