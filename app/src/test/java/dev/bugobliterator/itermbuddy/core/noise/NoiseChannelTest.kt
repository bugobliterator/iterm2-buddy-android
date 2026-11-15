package dev.bugobliterator.itermbuddy.core.noise

import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoiseChannelTest {
    /** Prologues for distinct pairing IDs, as upstream builds them. */
    private val prologues: List<ByteArray> by lazy {
        Json.parseToJsonElement(Fixtures.read("crypto-vectors.json")).jsonObject.getValue("prologues").jsonArray
            .map { hex(it.jsonObject.getValue("prologue").jsonPrimitive.content) }
            .also { assertTrue("need two prologues", it.size >= 2) }
    }

    private class Pairing(
        val initiator: NoiseInitiator,
        val responder: NoiseResponder,
        val initiatorPublic: ByteArray,
    )

    private fun pairing(
        initiatorPrologue: ByteArray = prologues[0],
        responderPrologue: ByteArray = prologues[0],
        wrongResponderStatic: Boolean = false,
    ): Pairing {
        val (macPrivate, macPublic) = newStaticKeyPair()
        val (phonePrivate, phonePublic) = newStaticKeyPair()
        val responderStatic = if (wrongResponderStatic) newStaticKeyPair().second else macPublic
        return Pairing(
            NoiseInitiator(responderStatic, phonePrivate, initiatorPrologue),
            NoiseResponder(macPrivate, responderPrologue),
            phonePublic,
        )
    }

    /** A completed handshake: the phone's channel and the Mac's channel. */
    private fun channels(): Pair<NoiseChannel, NoiseChannel> {
        val p = pairing()
        p.responder.readMessage1(p.initiator.writeMessage1())
        p.initiator.readMessage2(p.responder.writeMessage2())
        p.responder.readMessage3(p.initiator.writeMessage3())
        return p.initiator.split() to p.responder.split()
    }

    @Test
    fun handshakeFrameSizesAre48_48_64() {
        val p = pairing()
        val message1 = p.initiator.writeMessage1()
        p.responder.readMessage1(message1)
        val message2 = p.responder.writeMessage2()
        p.initiator.readMessage2(message2)
        val message3 = p.initiator.writeMessage3()
        p.responder.readMessage3(message3)
        assertEquals(listOf(48, 48, 64), listOf(message1.size, message2.size, message3.size))
    }

    @Test
    fun handshakeAgreesOnHashAndPinsInitiatorStatic() {
        val p = pairing()
        p.responder.readMessage1(p.initiator.writeMessage1())
        p.initiator.readMessage2(p.responder.writeMessage2())
        p.responder.readMessage3(p.initiator.writeMessage3())
        assertEquals(32, p.initiator.handshakeHash.size)
        assertArrayEquals(p.initiator.handshakeHash, p.responder.handshakeHash)
        assertArrayEquals(p.initiatorPublic, p.responder.remoteStaticPublicKey)
        val hash = p.initiator.handshakeHash
        p.initiator.split().close()
        p.responder.split().close()
        assertArrayEquals("hash survives split", hash, p.initiator.handshakeHash)
    }

    @Test
    fun handshakeHashIsUnavailableBeforeTheLastMessage() {
        val p = pairing()
        p.responder.readMessage1(p.initiator.writeMessage1())
        p.initiator.readMessage2(p.responder.writeMessage2())
        assertThrows(IllegalStateException::class.java) { p.initiator.handshakeHash }
        assertThrows(IllegalStateException::class.java) { p.initiator.split() }
    }

    @Test
    fun handshakeStepsMustRunInOrder() {
        val p = pairing()
        assertThrows(IllegalStateException::class.java) { p.initiator.writeMessage3() }
        assertThrows(IllegalStateException::class.java) { p.responder.writeMessage2() }
        p.responder.readMessage1(p.initiator.writeMessage1())
        assertThrows(IllegalStateException::class.java) { p.initiator.writeMessage1() }
        assertThrows(IllegalStateException::class.java) { p.responder.readMessage3(ByteArray(64)) }
    }

    @Test
    fun wrongResponderStaticFailsHandshake() {
        val p = pairing(wrongResponderStatic = true)
        val message1 = p.initiator.writeMessage1()
        assertThrows(NoiseException::class.java) { p.responder.readMessage1(message1) }
        // A failed handshake is finished: no further step runs on it.
        assertThrows(IllegalStateException::class.java) { p.responder.writeMessage2() }
    }

    @Test
    fun wrongPrologueFailsHandshake() {
        assertFalse(prologues[0].contentEquals(prologues[1]))
        val p = pairing(initiatorPrologue = prologues[0], responderPrologue = prologues[1])
        val message1 = p.initiator.writeMessage1()
        assertThrows(NoiseException::class.java) { p.responder.readMessage1(message1) }
    }

    @Test
    fun tamperedHandshakeMessageFails() {
        val p = pairing()
        p.responder.readMessage1(p.initiator.writeMessage1())
        val message2 = p.responder.writeMessage2()
        message2[40] = (message2[40].toInt() xor 0x01).toByte()
        assertThrows(NoiseException::class.java) { p.initiator.readMessage2(message2) }
        assertThrows(IllegalStateException::class.java) { p.initiator.writeMessage3() }
    }

    @Test
    fun handshakeMessageOverMaximumSizeFails() {
        val p = pairing()
        assertThrows(NoiseException::class.java) { p.responder.readMessage1(ByteArray(MAX_NOISE_MESSAGE_SIZE + 1)) }
    }

    @Test
    fun channelRoundTripsAcrossChunkBoundary() {
        val (phone, mac) = channels()
        val random = Random(0x6e6f697365)
        for ((size, expectedFrames) in listOf(0 to 1, 65518 to 1, 65519 to 2, 200_000 to 4)) {
            for ((label, pair) in listOf("phone->mac" to (phone to mac), "mac->phone" to (mac to phone))) {
                val (sender, receiver) = pair
                val plaintext = random.nextBytes(size)
                val frames = sender.encryptMessage(plaintext)
                assertEquals("$label $size bytes: frames", expectedFrames, frames.size)
                var remaining = size
                for ((index, frame) in frames.withIndex()) {
                    val chunk = minOf(remaining, MAX_CHUNK)
                    remaining -= chunk
                    assertEquals("$label $size bytes: frame $index size", 1 + chunk + TAG_BYTES, frame.size)
                    assertTrue(frame.size <= MAX_NOISE_MESSAGE_SIZE)
                    val result = receiver.decryptFrame(frame)
                    if (index < frames.lastIndex) {
                        assertNull("$label $size bytes: frame $index is not last", result)
                    } else {
                        assertArrayEquals("$label $size bytes: reassembled", plaintext, result)
                    }
                }
            }
        }
    }

    @Test
    fun anyNonZeroContinuationByteMeansMoreFollow() {
        // Upstream reassembles until the continuation byte is 0; any other value means "more".
        val (phone, mac) = channels()
        assertNull(mac.decryptFrame(phone.sealTransport(byteArrayOf(2, 'a'.code.toByte()))))
        assertNull(mac.decryptFrame(phone.sealTransport(byteArrayOf(0xff.toByte(), 'b'.code.toByte()))))
        assertNull(mac.decryptFrame(phone.sealTransport(byteArrayOf(1))))
        assertArrayEquals("abc".toByteArray(), mac.decryptFrame(phone.sealTransport(byteArrayOf(0, 'c'.code.toByte()))))
        assertArrayEquals(ByteArray(0), mac.decryptFrame(phone.sealTransport(byteArrayOf(0))))
    }

    @Test
    fun transportMessageWithoutContinuationByteClosesChannel() {
        val (phone, mac) = channels()
        assertThrows(NoiseException::class.java) { mac.decryptFrame(phone.sealTransport(ByteArray(0))) }
        assertTrue(mac.isClosed)
    }

    @Test
    fun tamperedCiphertextClosesChannel() {
        for (position in listOf(0, 1, 17, 33)) {
            val (phone, mac) = channels()
            val frame = phone.encryptMessage(ByteArray(17) { it.toByte() }).single()
            val tampered = frame.copyOf().also { it[position] = (it[position].toInt() xor 0x80).toByte() }
            assertThrows("byte $position", NoiseException::class.java) { mac.decryptFrame(tampered) }
            assertTrue("byte $position: closed", mac.isClosed)
            // The untampered frame would authenticate at the unchanged nonce; a closed channel refuses it.
            assertThrows("byte $position: after failure", NoiseException::class.java) { mac.decryptFrame(frame) }
            assertThrows("byte $position: send after failure", NoiseException::class.java) {
                mac.encryptMessage(ByteArray(1))
            }
        }
    }

    @Test
    fun truncatedOrOversizeFrameClosesChannel() {
        for (frame in listOf(ByteArray(0), ByteArray(TAG_BYTES - 1), ByteArray(MAX_NOISE_MESSAGE_SIZE + 1))) {
            val (_, mac) = channels()
            assertThrows("${frame.size} bytes", NoiseException::class.java) { mac.decryptFrame(frame) }
            assertTrue("${frame.size} bytes: closed", mac.isClosed)
        }
        // A frame that would authenticate but is one byte over the Noise maximum is refused too, as noise-c does.
        val key = ByteArray(32) { it.toByte() }
        fun cipher() = Noise.createCipher("ChaChaPoly").apply { initializeKey(key, 0) }
        val plaintext = ByteArray(MAX_NOISE_MESSAGE_SIZE - TAG_BYTES + 1)
        val oversize = ByteArray(plaintext.size + TAG_BYTES)
        cipher().encryptWithAd(null, plaintext, 0, oversize, 0, plaintext.size)
        assertEquals(MAX_NOISE_MESSAGE_SIZE + 1, oversize.size)
        val oversizeReceiver = NoiseChannel(cipher(), cipher())
        assertThrows(NoiseException::class.java) { oversizeReceiver.decryptFrame(oversize) }
        assertTrue(oversizeReceiver.isClosed)
        // The same check one byte smaller passes the length gate and authenticates.
        val fits = ByteArray(MAX_NOISE_MESSAGE_SIZE)
        cipher().encryptWithAd(null, ByteArray(MAX_NOISE_MESSAGE_SIZE - TAG_BYTES), 0, fits, 0, MAX_NOISE_MESSAGE_SIZE - TAG_BYTES)
        assertArrayEquals(ByteArray(MAX_CHUNK), NoiseChannel(cipher(), cipher()).decryptFrame(fits))
    }

    @Test
    fun replayedFrameFails() {
        val (phone, mac) = channels()
        val frame = phone.encryptMessage("hello".toByteArray()).single()
        assertArrayEquals("hello".toByteArray(), mac.decryptFrame(frame))
        assertThrows(NoiseException::class.java) { mac.decryptFrame(frame) }
        assertTrue(mac.isClosed)
    }

    @Test
    fun reorderedFrameFails() {
        val (phone, mac) = channels()
        val first = phone.encryptMessage("first".toByteArray()).single()
        val second = phone.encryptMessage("second".toByteArray()).single()
        assertThrows(NoiseException::class.java) { mac.decryptFrame(second) }
        assertTrue(mac.isClosed)
        assertThrows(NoiseException::class.java) { mac.decryptFrame(first) }
    }

    @Test
    fun reorderedChunkFails() {
        val (phone, mac) = channels()
        val frames = phone.encryptMessage(ByteArray(MAX_CHUNK + 1))
        assertEquals(2, frames.size)
        assertThrows(NoiseException::class.java) { mac.decryptFrame(frames[1]) }
    }

    @Test
    fun directionsUseSeparateCipherStates() {
        val (phone, mac) = channels()
        // Each side's own frame does not open on its own receive side: send and receive keys differ.
        val phoneFrame = phone.encryptMessage("ping".toByteArray()).single()
        assertThrows(NoiseException::class.java) { phone.decryptFrame(phoneFrame.copyOf()) }

        val (_, macOnly) = channels()
        val macFrame = macOnly.encryptMessage("pong".toByteArray()).single()
        assertThrows(NoiseException::class.java) { macOnly.decryptFrame(macFrame) }

        // Interleaved traffic: each direction keeps its own nonce, starting at zero.
        val (phone2, mac2) = channels()
        assertArrayEquals("pong".toByteArray(), phone2.decryptFrame(mac2.encryptMessage("pong".toByteArray()).single()))
        for (round in 0 until 3) {
            val up = "up $round".toByteArray()
            val down = "down $round".toByteArray()
            val upFrame = phone2.encryptMessage(up).single()
            val downFrame = mac2.encryptMessage(down).single()
            assertNotEquals(upFrame.toHex(), downFrame.toHex())
            assertArrayEquals(down, phone2.decryptFrame(downFrame))
            assertArrayEquals(up, mac2.decryptFrame(upFrame))
        }
        mac.close()
    }

    @Test
    fun closeDestroysStateAndRefusesUse() {
        val (phone, mac) = channels()
        assertFalse(phone.isClosed)
        val frame = phone.encryptMessage("x".toByteArray()).single()
        phone.close()
        phone.close()
        assertTrue(phone.isClosed)
        assertThrows(NoiseException::class.java) { phone.encryptMessage(ByteArray(1)) }
        assertThrows(NoiseException::class.java) { phone.decryptFrame(frame) }
        mac.close()
        assertThrows(NoiseException::class.java) { mac.decryptFrame(frame) }
    }

    @Test
    fun closedHandshakeRefusesUse() {
        val p = pairing()
        p.initiator.close()
        p.initiator.close()
        assertThrows(IllegalStateException::class.java) { p.initiator.writeMessage1() }
    }

    @Test
    fun toStringRevealsNoKeys() {
        val (macPrivate, macPublic) = newStaticKeyPair()
        val (phonePrivate, _) = newStaticKeyPair()
        val initiator = NoiseInitiator(macPublic, phonePrivate, prologues[0])
        val responder = NoiseResponder(macPrivate, prologues[0])
        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        responder.readMessage3(initiator.writeMessage3())
        val texts = listOf(initiator.toString(), responder.toString(), initiator.split().toString(), responder.split().toString())
        val secrets = listOf(macPrivate, phonePrivate, initiator.handshakeHash).map { it.toHex().substring(0, 8) }
        for (text in texts) {
            for (secret in secrets) {
                assertFalse("$text leaks", text.lowercase().contains(secret))
            }
        }
        assertEquals("NoiseInitiator", texts[0])
        assertEquals("NoiseResponder", texts[1])
        assertEquals("NoiseChannel(open)", texts[2])
    }

    @Test
    fun staticKeyPairsAreFreshAndConsistent() {
        val (privateA, publicA) = newStaticKeyPair()
        val (privateB, publicB) = newStaticKeyPair()
        assertEquals(32, privateA.size)
        assertEquals(32, publicA.size)
        assertFalse(privateA.contentEquals(privateB))
        assertFalse(publicA.contentEquals(publicB))
        // The responder built from privateA is the one an initiator holding publicA reaches.
        val (phonePrivate, _) = newStaticKeyPair()
        val initiator = NoiseInitiator(publicA, phonePrivate, prologues[0])
        NoiseResponder(privateA, prologues[0]).readMessage1(initiator.writeMessage1())
    }

    @Test
    fun keysMustBeThirtyTwoBytes() {
        val (privateKey, publicKey) = newStaticKeyPair()
        assertThrows(IllegalArgumentException::class.java) { NoiseInitiator(ByteArray(31), privateKey, prologues[0]) }
        assertThrows(IllegalArgumentException::class.java) { NoiseInitiator(publicKey, ByteArray(33), prologues[0]) }
        assertThrows(IllegalArgumentException::class.java) { NoiseResponder(ByteArray(0), prologues[0]) }
    }

    private companion object {
        const val TAG_BYTES = 16
        const val MAX_CHUNK = 65518
    }
}
