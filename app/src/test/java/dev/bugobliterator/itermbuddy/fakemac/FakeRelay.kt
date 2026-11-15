package dev.bugobliterator.itermbuddy.fakemac

import dev.bugobliterator.itermbuddy.core.RelaySocket
import dev.bugobliterator.itermbuddy.core.RelayTransport
import dev.bugobliterator.itermbuddy.core.relay.AdmissionResult
import dev.bugobliterator.itermbuddy.core.relay.JoinRole
import dev.bugobliterator.itermbuddy.core.relay.Proof
import dev.bugobliterator.itermbuddy.core.relay.RegisterResult
import dev.bugobliterator.itermbuddy.core.relay.RelayClosedException
import dev.bugobliterator.itermbuddy.core.relay.RelayEndpoints
import dev.bugobliterator.itermbuddy.core.relay.RelaySendException
import dev.bugobliterator.itermbuddy.core.relay.UnreachableKind
import dev.bugobliterator.itermbuddy.core.relay.joinTranscript
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64
import kotlin.random.Random

/**
 * The relay (room.js at b8aa2f9) in process, for the client tests: no sockets, no threads, so
 * everything runs in virtual time. It models what the connect flow depends on:
 *
 * - admission: a room with a verifier needs a valid signature over [origin] ("signature
 *   required", "bad signature"); otherwise any proof is admitted (ATTEST_REQUIRED=false). A phone
 *   is rejected "mac offline" unless a Mac is parked. Newest per role wins; the old socket is
 *   closed 1000 "displaced". Admitted phones get a single-use registration token.
 * - splicing: frames go to the other role's socket, in order.
 * - closes: a side closing its own socket makes the relay close the other with 1001 "peer gone"
 *   (closePeerOf); a relay-initiated close does not.
 * - `/register`: stores the verifier for a valid token; an established room answers 403 "already
 *   registered", which the client's registrar reports as Registered.
 *
 * Tests script failures with [phoneAdmissionOverrides], [registerOverrides] and [reachable].
 *
 * @param origin the relay's RELAY_ORIGIN, the string it verifies join signatures against.
 */
internal class FakeRelay(val origin: String) : RelayTransport, MacRelay {
    private class Room {
        var verifier: ByteArray? = null
        var mac: MacSide? = null
        var phone: PhoneSide? = null
        val tokens = HashSet<String>()
    }

    /** One phone admission as the relay saw it. */
    data class PhoneAdmission(val signed: Boolean, val result: String)

    private val rooms = HashMap<String, Room>()
    private val nonces = Random(7)

    /** When false, every connect fails before any HTTP response (an asleep Mac). */
    var reachable = true

    /** Results handed to the next phone admissions instead of running the relay's logic. */
    val phoneAdmissionOverrides = ArrayDeque<AdmissionResult>()

    /** Results handed to the next `/register` calls instead of running the relay's logic. */
    val registerOverrides = ArrayDeque<RegisterResult>()

    /**
     * When set, the next phone frame send throws once and the socket stays open, as a send
     * cancelled part way would leave it. The session must close it itself: its nonce has advanced.
     */
    var failNextPhoneSend = false

    val phoneAdmissions = mutableListOf<PhoneAdmission>()
    val registerResults = mutableListOf<RegisterResult>()

    @Synchronized
    fun verifier(roomName: String): ByteArray? = rooms[roomName]?.verifier?.copyOf()

    /** Stores [verifier] for [roomName] as if a registration had succeeded. */
    @Synchronized
    fun lock(roomName: String, verifier: ByteArray) {
        room(roomName).verifier = verifier.copyOf()
    }

    @Synchronized
    fun macParked(roomName: String): Boolean = rooms[roomName]?.mac != null

    @Synchronized
    fun phoneConnected(roomName: String): Boolean = rooms[roomName]?.phone != null

    /** The relay lost its database: every verifier and token is gone. Live sockets stay. */
    @Synchronized
    fun loseState() {
        rooms.values.forEach {
            it.verifier = null
            it.tokens.clear()
        }
    }

    /** The relay closes the phone's socket with [code] and [reason], as it does for "displaced". */
    @Synchronized
    fun closePhone(roomName: String, code: Int, reason: String) {
        rooms[roomName]?.phone?.closedByRelay(code, reason)
    }

    /** Every socket drops (1006), as when the relay process dies. */
    @Synchronized
    fun dropEverything() {
        rooms.values.forEach { room ->
            room.phone?.closedByRelay(CLOSE_ABNORMAL, "dropped")
            room.mac?.closedByRelay(CLOSE_ABNORMAL, "dropped")
        }
    }

    private fun room(name: String): Room = rooms.getOrPut(name) { Room() }

    private fun newNonce(): ByteArray = nonces.nextBytes(NONCE_BYTES)

    /** room.js `verifyJoin`: the signature must verify under the stored verifier over [origin]. */
    private fun verifies(signature: ByteArray, role: JoinRole, nonce: ByteArray, roomName: String, verifier: ByteArray): Boolean {
        val transcript = joinTranscript(role, nonce, roomName, origin)
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(verifier, 0))
        signer.update(transcript, 0, transcript.size)
        return signer.verifySignature(signature)
    }

    override fun open(endpoints: RelayEndpoints, roomName: String): RelaySocket = PhoneSide(endpoints.origin, roomName)

    override suspend fun register(
        endpoints: RelayEndpoints,
        roomName: String,
        registrationToken: String,
        verifier: ByteArray,
    ): RegisterResult = synchronized(this) {
        val result = registerOverrides.removeFirstOrNull() ?: run {
            val room = room(roomName)
            when {
                room.verifier != null -> RegisterResult.Registered // 403 "already registered"
                !room.tokens.remove(registrationToken) -> RegisterResult.Refused(403, """{"ok":false,"error":"bad token"}""")
                else -> {
                    room.verifier = verifier.copyOf()
                    RegisterResult.Registered
                }
            }
        }
        registerResults += result
        result
    }

    override suspend fun park(roomName: String, sign: ((ByteArray) -> ByteArray)?): MacPark = synchronized(this) {
        if (!reachable) return MacPark.Refused("unreachable")
        val room = room(roomName)
        val nonce = newNonce()
        val verifier = room.verifier
        if (verifier != null) {
            val signature = sign?.invoke(nonce) ?: return MacPark.Refused("signature required")
            if (!verifies(signature, JoinRole.MAC, nonce, roomName, verifier)) return MacPark.Refused("bad signature")
        }
        room.mac?.closedByRelay(CLOSE_NORMAL, "displaced")
        val side = MacSide(roomName)
        room.mac = side
        MacPark.Parked(side)
    }

    private inner class PhoneSide(private val signedOrigin: String, private val roomName: String) : RelaySocket {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private var admitted = false
        private var closed = false

        override val incoming: ReceiveChannel<ByteArray> get() = frames

        override suspend fun admit(proof: Proof): AdmissionResult = synchronized(this@FakeRelay) {
            val result = decide(proof)
            phoneAdmissions += PhoneAdmission(proof is Proof.Signed, result.toString())
            if (result !is AdmissionResult.Admitted) {
                closed = true
                frames.close()
            }
            result
        }

        private fun decide(proof: Proof): AdmissionResult {
            phoneAdmissionOverrides.removeFirstOrNull()?.let { return it }
            if (!reachable) return AdmissionResult.Unreachable(UnreachableKind.CONNECT, "fake relay unreachable")
            val room = room(roomName)
            val nonce = newNonce()
            val verifier = room.verifier
            if (verifier != null) {
                if (proof !is Proof.Signed) return AdmissionResult.Rejected("signature required")
                // The phone signs over its own origin; the relay verifies over RELAY_ORIGIN.
                val signature = proof.signer.sign(nonce, roomName, signedOrigin)
                if (!verifies(signature, JoinRole.PHONE, nonce, roomName, verifier)) {
                    return AdmissionResult.Rejected("bad signature")
                }
            }
            if (room.mac == null) return AdmissionResult.Rejected("mac offline")
            room.phone?.closedByRelay(CLOSE_NORMAL, "displaced")
            room.phone = this
            admitted = true
            val token = Base64.getEncoder().encodeToString(nonces.nextBytes(TOKEN_BYTES))
            room.tokens += token
            return AdmissionResult.Admitted(token)
        }

        override suspend fun send(frame: ByteArray) {
            synchronized(this@FakeRelay) {
                if (!admitted || closed) throw RelaySendException("cannot send: connection is closed")
                if (failNextPhoneSend) {
                    failNextPhoneSend = false
                    throw RelaySendException("cannot send: the frame was not queued")
                }
                rooms[roomName]?.mac?.deliver(frame)
            }
        }

        override fun close() {
            synchronized(this@FakeRelay) { closeLocked() }
        }

        /** The phone closed its socket: the relay closes the Mac too (closePeerOf). */
        private fun closeLocked() {
            if (closed) return
            closed = true
            frames.close()
            val room = rooms[roomName] ?: return
            if (room.phone === this) {
                room.phone = null
                room.mac?.closedByRelay(CLOSE_GOING_AWAY, "peer gone")
            }
        }

        fun deliver(frame: ByteArray) {
            if (!closed) frames.trySend(frame)
        }

        fun closedByRelay(code: Int, reason: String) {
            if (closed) return
            closed = true
            frames.close(RelayClosedException(code, reason))
            val room = rooms[roomName] ?: return
            if (room.phone === this) room.phone = null
        }
    }

    private inner class MacSide(private val roomName: String) : MacSocket {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private var closed = false

        override val incoming: ReceiveChannel<ByteArray> get() = frames

        override suspend fun send(frame: ByteArray) {
            synchronized(this@FakeRelay) {
                if (closed) throw RelaySendException("cannot send: connection is closed")
                rooms[roomName]?.phone?.deliver(frame)
            }
        }

        override fun close() {
            synchronized(this@FakeRelay) {
                if (closed) return
                closed = true
                frames.close()
                val room = rooms[roomName] ?: return
                if (room.mac === this) {
                    room.mac = null
                    room.phone?.closedByRelay(CLOSE_GOING_AWAY, "peer gone")
                }
            }
        }

        fun deliver(frame: ByteArray) {
            if (!closed) frames.trySend(frame)
        }

        fun closedByRelay(code: Int, reason: String) {
            if (closed) return
            closed = true
            frames.close(RelayClosedException(code, reason))
            val room = rooms[roomName] ?: return
            if (room.mac === this) room.mac = null
        }
    }

    private companion object {
        const val NONCE_BYTES = 32
        const val TOKEN_BYTES = 24
        const val CLOSE_NORMAL = 1000
        const val CLOSE_GOING_AWAY = 1001
        const val CLOSE_ABNORMAL = 1006
    }
}
