package dev.bugobliterator.itermbuddy.core.noise

import com.southernstorm.noise.protocol.CipherState
import java.io.Closeable
import java.security.GeneralSecurityException

/** One continuation byte plus a 16-byte tag must fit in a Noise message: 65535 - 16 - 1. */
private const val MAX_CHUNK = MAX_NOISE_MESSAGE_SIZE - 16 - 1

private const val LAST_CHUNK: Byte = 0
private const val MORE_CHUNKS: Byte = 1

/**
 * The encrypted transport after the handshake, framed as upstream `NoiseChannel`: an
 * application message is split into chunks of at most 65518 bytes, each sent as the Noise
 * transport message `[cont] || chunk` in its own binary WebSocket frame, `cont` 1 while more
 * chunks follow and 0 on the last. An empty message is one frame holding only `cont` 0.
 *
 * Sending and receiving use separate CipherStates with implicit, strictly increasing nonces.
 *
 * Threading: **callers serialise sends.** Each [encryptMessage] call is atomic, but its frames
 * must reach the WebSocket, in order, before the next call's frames, or the peer fails
 * authentication. Receiving is single-threaded too: one receive loop calls [decryptFrame] with
 * frames in arrival order. Both directions are also locked, so misuse cannot corrupt a
 * CipherState, but locking cannot restore a wire order the caller broke.
 *
 * Any receive failure (bad tag, replayed, reordered or truncated frame, a frame over 65535
 * bytes, a message with no continuation byte) or send failure closes the channel: both
 * CipherStates are destroyed and every later call throws [NoiseException]. Upstream likewise
 * ends the connection when `receive()` throws.
 */
class NoiseChannel internal constructor(sender: CipherState, receiver: CipherState) : Closeable {
    private val sendLock = Any()
    private val receiveLock = Any()
    private var sender: CipherState? = sender
    private var receiver: CipherState? = receiver

    /** Chunks of the message being reassembled; zeroed when handed out or on close. */
    private var pending = ByteArray(0)
    private var pendingSize = 0

    @Volatile
    private var closed = false

    val isClosed: Boolean
        get() = closed

    /** Encrypts [plaintext] into one or more transport messages, one per WebSocket frame, in send order. */
    fun encryptMessage(plaintext: ByteArray): List<ByteArray> = failClosed(sendLock) {
        val frames = ArrayList<ByteArray>(maxOf(1, (plaintext.size + MAX_CHUNK - 1) / MAX_CHUNK))
        var offset = 0
        do {
            val end = minOf(offset + MAX_CHUNK, plaintext.size)
            val chunk = ByteArray(1 + end - offset)
            chunk[0] = if (end >= plaintext.size) LAST_CHUNK else MORE_CHUNKS
            plaintext.copyInto(chunk, 1, offset, end)
            try {
                frames += sealLocked(chunk)
            } finally {
                chunk.fill(0)
            }
            offset = end
        } while (offset < plaintext.size)
        frames
    }

    /**
     * Decrypts one received frame. Returns the reassembled message when this frame carries
     * `cont` 0, or null while more chunks are expected (any non-zero `cont`, as upstream).
     * Throws [NoiseException], closing the channel, on any failure.
     */
    fun decryptFrame(frame: ByteArray): ByteArray? = failClosed(receiveLock) {
        val plaintext = openLocked(frame)
        try {
            if (plaintext.isEmpty()) {
                // Every chunk carries at least the continuation byte (upstream malformedFrame).
                throw NoiseException("Noise transport message has no continuation byte")
            }
            appendPending(plaintext, 1, plaintext.size)
            if (plaintext[0] == LAST_CHUNK) takePending() else null
        } finally {
            plaintext.fill(0)
        }
    }

    /** One raw Noise transport message, no chunking: for the cacophony vectors. */
    internal fun sealTransport(plaintext: ByteArray): ByteArray = failClosed(sendLock) { sealLocked(plaintext) }

    /** One raw Noise transport message, no chunking: for the cacophony vectors. */
    internal fun openTransport(ciphertext: ByteArray): ByteArray = failClosed(receiveLock) { openLocked(ciphertext) }

    /** Destroys both CipherStates and any partly reassembled message. Idempotent. */
    override fun close() {
        closed = true
        // Taken one at a time, never nested, so a failing send and a failing receive cannot deadlock.
        synchronized(sendLock) {
            sender?.destroy()
            sender = null
        }
        synchronized(receiveLock) {
            receiver?.destroy()
            receiver = null
            pending.fill(0)
            pending = ByteArray(0)
            pendingSize = 0
        }
    }

    override fun toString(): String = if (closed) "NoiseChannel(closed)" else "NoiseChannel(open)"

    /** Runs [block] under [lock]; if it fails, closes the channel after releasing the lock. */
    private inline fun <T> failClosed(lock: Any, block: () -> T): T {
        try {
            return synchronized(lock) {
                if (closed) throw NoiseException("Noise channel is closed")
                block()
            }
        } catch (e: NoiseException) {
            close()
            throw e
        } catch (e: GeneralSecurityException) {
            close()
            throw NoiseException("Noise transport failed", e)
        } catch (e: IllegalStateException) {
            // noise-java: nonce exhausted.
            close()
            throw NoiseException("Noise transport failed", e)
        } catch (e: IllegalArgumentException) {
            close()
            throw NoiseException("Noise transport failed", e)
        }
    }

    private fun sealLocked(plaintext: ByteArray): ByteArray {
        val cipher = sender ?: throw NoiseException("Noise channel is closed")
        val mac = cipher.macLength
        if (plaintext.size > MAX_NOISE_MESSAGE_SIZE - mac) {
            throw NoiseException("Noise transport message too large")
        }
        val out = ByteArray(plaintext.size + mac)
        val length = cipher.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        check(length == out.size)
        return out
    }

    private fun openLocked(ciphertext: ByteArray): ByteArray {
        val cipher = receiver ?: throw NoiseException("Noise channel is closed")
        val mac = cipher.macLength
        // noise-c refuses a message over NOISE_MAX_PAYLOAD_LEN or shorter than the tag.
        if (ciphertext.size > MAX_NOISE_MESSAGE_SIZE || ciphertext.size < mac) {
            throw NoiseException("Noise transport message has an invalid length")
        }
        val out = ByteArray(ciphertext.size - mac)
        val length = cipher.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        check(length == out.size)
        return out
    }

    private fun appendPending(source: ByteArray, from: Int, to: Int) {
        val count = to - from
        if (pendingSize + count > pending.size) {
            val grown = ByteArray(maxOf(pendingSize + count, pending.size * 2))
            pending.copyInto(grown, 0, 0, pendingSize)
            pending.fill(0)
            pending = grown
        }
        source.copyInto(pending, pendingSize, from, to)
        pendingSize += count
    }

    private fun takePending(): ByteArray {
        val message = pending.copyOf(pendingSize)
        pending.fill(0)
        pending = ByteArray(0)
        pendingSize = 0
        return message
    }
}
