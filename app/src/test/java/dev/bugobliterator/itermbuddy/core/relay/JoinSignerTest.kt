package dev.bugobliterator.itermbuddy.core.relay

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinSignerTest {
    private val vectors: List<Pair<String, JsonObject>> by lazy {
        val array = Json.parseToJsonElement(Fixtures.read("crypto-vectors.json"))
            .jsonObject.getValue("joins").jsonArray
        assertTrue("joins is empty", array.isNotEmpty())
        array.mapIndexed { index, element ->
            val entry = element.jsonObject
            "joins[#$index ${entry["role"]?.jsonPrimitive?.content}]" to entry
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.role(): JoinRole = when (val role = string("role")) {
        "phone" -> JoinRole.PHONE
        "mac" -> JoinRole.MAC
        else -> throw AssertionError("unknown role $role")
    }

    @Test
    fun joinsMatchVectorsForBothRoles() {
        val roles = vectors.map { (_, entry) -> entry.role() }.toSet()
        assertEquals("vectors cover both roles", JoinRole.entries.toSet(), roles)
        for ((label, entry) in vectors) {
            val role = entry.role()
            val nonce = hex(entry.string("nonce"))
            val roomName = entry.string("roomName")
            val origin = entry.string("origin")
            val signer = JoinSigner(hex(entry.string("roomSecret")), role)
            assertEquals("$label verifier", entry.string("verifier"), signer.verifier.toHex())
            assertEquals(
                "$label transcript",
                entry.string("transcript"),
                joinTranscript(role, nonce, roomName, origin).toHex(),
            )
            assertEquals(
                "$label signature",
                entry.string("signature"),
                signer.sign(nonce, roomName, origin).toHex(),
            )
        }
    }

    @Test
    fun signaturesVerifyAgainstVerifier() {
        for ((label, entry) in vectors) {
            val role = entry.role()
            val nonce = hex(entry.string("nonce"))
            val roomName = entry.string("roomName")
            val origin = entry.string("origin")
            val signer = JoinSigner(hex(entry.string("roomSecret")), role)
            val signature = signer.sign(nonce, roomName, origin)
            assertTrue("$label verifies", verify(signer.verifier, joinTranscript(role, nonce, roomName, origin), signature))
            val otherRole = if (role == JoinRole.PHONE) JoinRole.MAC else JoinRole.PHONE
            assertFalse(
                "$label must not verify for the other role",
                verify(signer.verifier, joinTranscript(otherRole, nonce, roomName, origin), signature),
            )
        }
    }

    @Test
    fun defaultRoleIsPhone() {
        assertEquals("phone role byte", 0x02.toByte(), JoinRole.PHONE.byte)
        assertEquals("mac role byte", 0x01.toByte(), JoinRole.MAC.byte)
        val (label, entry) = vectors.first { (_, entry) -> entry.string("role") == "phone" }
        val signer = JoinSigner(hex(entry.string("roomSecret")))
        val signature = signer.sign(hex(entry.string("nonce")), entry.string("roomName"), entry.string("origin"))
        assertEquals("$label default-role signature", entry.string("signature"), signature.toHex())
    }

    @Test
    fun roomSecretMustBeThirtyTwoBytes() {
        for (size in listOf(0, 31, 33)) {
            val error = assertThrows("roomSecret of $size bytes", IllegalArgumentException::class.java) {
                JoinSigner(ByteArray(size))
            }
            assertEquals("roomSecret of $size bytes", "roomSecret must be 32 bytes", error.message)
        }
        JoinSigner(ByteArray(32))
    }

    @Test
    fun toStringHidesSecrets() {
        val (_, entry) = vectors.first()
        val secret = entry.string("roomSecret")
        val text = JoinSigner(hex(secret)).toString()
        assertFalse(text, text.contains(secret, ignoreCase = true))
        assertFalse(text, text.contains(entry.string("seed"), ignoreCase = true))
    }

    private fun verify(verifier: ByteArray, transcript: ByteArray, signature: ByteArray): Boolean {
        val verifierSigner = Ed25519Signer()
        verifierSigner.init(false, Ed25519PublicKeyParameters(verifier, 0))
        verifierSigner.update(transcript, 0, transcript.size)
        return verifierSigner.verifySignature(signature)
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd-length hex: $text" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
