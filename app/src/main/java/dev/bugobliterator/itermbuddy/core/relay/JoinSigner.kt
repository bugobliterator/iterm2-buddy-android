package dev.bugobliterator.itermbuddy.core.relay

import dev.bugobliterator.itermbuddy.core.pairing.canonical
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Which device is joining a relay room; the byte is bound into the signed transcript. */
enum class JoinRole(val byte: Byte) { MAC(0x01), PHONE(0x02) }

/** Upstream `RelayJoin.protocolVersion`; must match the relay's PROTOCOL_VERSION. */
private const val JOIN_PROTOCOL_VERSION: Byte = 0x01
private const val JOIN_DOMAIN = "iterm2-relay-join"
private val JOIN_KEY_INFO = "relay-auth-ed25519".toByteArray(Charsets.UTF_8)
private const val SEED_BYTES = 32
private const val ROOM_SECRET_BYTES = 32

/**
 * The bytes a join signs, as upstream `RelayJoin.transcript`:
 * canonical("iterm2-relay-join", [version, role, nonce, roomName, origin]).
 */
internal fun joinTranscript(role: JoinRole, nonce: ByteArray, roomName: String, origin: String): ByteArray =
    canonical(
        JOIN_DOMAIN,
        listOf(
            byteArrayOf(JOIN_PROTOCOL_VERSION),
            byteArrayOf(role.byte),
            nonce,
            roomName.toByteArray(Charsets.UTF_8),
            origin.toByteArray(Charsets.UTF_8),
        ),
    )

/**
 * Signs relay room joins with the Ed25519 key both devices derive from the room secret:
 * seed = HKDF-SHA256(roomSecret, salt = empty, info = "relay-auth-ed25519", 32).
 *
 * Signing is deterministic RFC 8032; it matches the Linux-generated fixtures. The Mac's
 * CryptoKit randomises its signatures, and the relay only verifies, so the two need not be
 * byte-equal on a Mac.
 *
 * The local seed buffer is zeroed after use, but BouncyCastle keeps its own copy of the seed
 * inside the private key for this signer's lifetime. Hold one JoinSigner per connection and
 * drop it afterwards.
 *
 * @param roomSecret the 32-byte phone-minted room secret.
 */
class JoinSigner(roomSecret: ByteArray, private val role: JoinRole = JoinRole.PHONE) {
    private val signingKey: Ed25519PrivateKeyParameters

    init {
        require(roomSecret.size == ROOM_SECRET_BYTES) { "roomSecret must be 32 bytes" }
        val seed = ByteArray(SEED_BYTES)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(roomSecret, null, JOIN_KEY_INFO))
            generateBytes(seed, 0, seed.size)
        }
        signingKey = Ed25519PrivateKeyParameters(seed, 0)
        seed.fill(0)
    }

    /** The 32-byte Ed25519 public key the relay stores for the room. */
    val verifier: ByteArray
        get() = signingKey.generatePublicKey().encoded

    /** The 64-byte signature over [joinTranscript] for this signer's role. */
    fun sign(nonce: ByteArray, roomName: String, origin: String): ByteArray {
        val transcript = joinTranscript(role, nonce, roomName, origin)
        val signer = Ed25519Signer()
        signer.init(true, signingKey)
        signer.update(transcript, 0, transcript.size)
        return signer.generateSignature()
    }

    override fun toString(): String = "JoinSigner(role=$role)"
}
