package dev.bugobliterator.itermbuddy.core.noise

import com.southernstorm.noise.crypto.Blake2sMessageDigest
import com.southernstorm.noise.protocol.Noise
import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import java.security.Security

/** cacophony's `Noise_XK_25519_ChaChaPoly_BLAKE2s` vectors, driven through the app's initiator and responder. */
class NoiseVectorsTest {
    private val vectors: List<Pair<String, JsonObject>> by lazy {
        val array = Json.parseToJsonElement(Fixtures.read("noise/xk-25519-chachapoly-blake2s.json"))
            .jsonObject.getValue("vectors").jsonArray
        assertTrue("vectors is empty", array.isNotEmpty())
        array.mapIndexed { index, element -> "vectors[$index]" to element.jsonObject }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.bytes(key: String): ByteArray = hex(string(key))

    @Test
    fun cacophonyXkVectorsPass() {
        runVectors("default BLAKE2s")
    }

    /** noise-java's own BLAKE2s, which runs whenever no JCA provider offers `BLAKE2S-256` (Android today). */
    @Test
    fun cacophonyXkVectorsPassWithFallbackBlake2s() {
        Noise.setForceFallbacks(true)
        try {
            assertTrue(Noise.createHash("BLAKE2s") is Blake2sMessageDigest)
            runVectors("fallback BLAKE2s")
        } finally {
            Noise.setForceFallbacks(false)
        }
    }

    /** BouncyCastle's `BLAKE2S-256`, which noise-java prefers once BC is a registered JCA provider. */
    @Test
    fun cacophonyXkVectorsPassWithBouncyCastleBlake2s() {
        val added = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null &&
            Security.addProvider(BouncyCastleProvider()) != -1
        try {
            assertEquals(BouncyCastleProvider.PROVIDER_NAME, Noise.createHash("BLAKE2s").provider.name)
            runVectors("BouncyCastle BLAKE2s")
        } finally {
            if (added) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
    }

    private fun runVectors(path: String) {
        for ((vectorLabel, vector) in vectors) {
            val label = "$vectorLabel ($path)"
            assertEquals("$label protocol_name", NOISE_PROTOCOL_NAME, vector.string("protocol_name"))
            val messages = vector.getValue("messages").jsonArray.map { it.jsonObject }
            assertTrue("$label has fewer than 3 messages", messages.size >= 3)

            val initiator = NoiseInitiator(
                responderStatic = vector.bytes("init_remote_static"),
                localStaticPrivate = vector.bytes("init_static"),
                prologue = vector.bytes("init_prologue"),
                fixedEphemeralPrivate = vector.bytes("init_ephemeral"),
            )
            val responder = NoiseResponder(
                localStaticPrivate = vector.bytes("resp_static"),
                prologue = vector.bytes("resp_prologue"),
                fixedEphemeralPrivate = vector.bytes("resp_ephemeral"),
            )

            // -> e, es / <- e, ee / -> s, se, each carrying the vector's payload.
            for ((index, message) in messages.take(3).withIndex()) {
                val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
                val payload = message.bytes("payload")
                val ciphertext = writer.write(index + 1, payload)
                assertEquals("$label messages[$index] ciphertext", message.string("ciphertext"), ciphertext.toHex())
                assertEquals("$label messages[$index] payload", payload.toHex(), reader.read(index + 1, ciphertext).toHex())
            }

            assertEquals("$label initiator handshake_hash", vector.string("handshake_hash"), initiator.handshakeHash.toHex())
            assertEquals("$label responder handshake_hash", vector.string("handshake_hash"), responder.handshakeHash.toHex())
            assertEquals(
                "$label responder learned the initiator static",
                publicKeyOf(vector.bytes("init_static")).toHex(),
                responder.remoteStaticPublicKey.toHex(),
            )

            // Transport messages keep alternating: even index from the initiator, odd from the responder.
            val initiatorChannel = initiator.split()
            val responderChannel = responder.split()
            for ((index, message) in messages.withIndex().drop(3)) {
                val (writer, reader) =
                    if (index % 2 == 0) initiatorChannel to responderChannel else responderChannel to initiatorChannel
                val payload = message.bytes("payload")
                val ciphertext = writer.sealTransport(payload)
                assertEquals("$label messages[$index] ciphertext", message.string("ciphertext"), ciphertext.toHex())
                assertEquals("$label messages[$index] payload", payload.toHex(), reader.openTransport(ciphertext).toHex())
            }
            initiatorChannel.close()
            responderChannel.close()
        }
    }

    private fun publicKeyOf(privateKey: ByteArray): ByteArray {
        val dh = Noise.createDH("25519")
        try {
            dh.setPrivateKey(privateKey, 0)
            return ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
        } finally {
            dh.destroy()
        }
    }
}

internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd-length hex: $text" }
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
