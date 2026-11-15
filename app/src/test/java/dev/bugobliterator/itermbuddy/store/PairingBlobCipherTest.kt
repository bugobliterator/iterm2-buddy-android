package dev.bugobliterator.itermbuddy.store

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The AES-GCM envelope with a software key. AndroidKeyStore itself is covered by the
 * instrumented KeystorePairingStoreTest.
 */
class PairingBlobCipherTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val plaintext = "{\"v\":1}".toByteArray()

    @Test
    fun sealsAndOpens() {
        val blob = PairingBlobCipher.seal(key, plaintext)
        assertArrayEquals(plaintext, PairingBlobCipher.open(key, blob))
    }

    @Test
    fun layoutIsVersionThenIvThenCiphertextAndTag() {
        val blob = PairingBlobCipher.seal(key, plaintext)
        assertEquals(1 + 12 + plaintext.size + 16, blob.size)
        assertEquals(PairingBlobCipher.ENVELOPE_VERSION, blob[0])
        assertEquals("itermbuddy-pairing-v1", PairingBlobCipher.AAD.decodeToString())
    }

    @Test
    fun everySealUsesAFreshIv() {
        val ivs = (1..64).map { PairingBlobCipher.seal(key, plaintext).copyOfRange(1, 13).toList() }.toSet()
        assertEquals(64, ivs.size)
    }

    @Test
    fun anyFlippedBitFailsToOpen() {
        val blob = PairingBlobCipher.seal(key, plaintext)
        for (index in blob.indices) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows("byte $index", GeneralSecurityException::class.java) { PairingBlobCipher.open(key, tampered) }
        }
    }

    @Test
    fun anotherPurposeOrVersionFailsToOpen() {
        val blob = PairingBlobCipher.seal(key, plaintext, "itermbuddy-pairing-v2".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { PairingBlobCipher.open(key, blob) }
        val other = PairingBlobCipher.seal(key, plaintext, "itermbuddy-other-v1".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { PairingBlobCipher.open(key, other) }
    }

    @Test
    fun anotherKeyFailsToOpen() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val blob = PairingBlobCipher.seal(key, plaintext)
        assertThrows(GeneralSecurityException::class.java) { PairingBlobCipher.open(other, blob) }
    }

    @Test
    fun shortOrEmptyBlobsFailToOpen() {
        val blob = PairingBlobCipher.seal(key, plaintext)
        for (length in listOf(0, 1, 12, 13, 28, blob.size - 1)) {
            assertThrows("length $length", GeneralSecurityException::class.java) {
                PairingBlobCipher.open(key, blob.copyOf(length))
            }
        }
        assertThrows(GeneralSecurityException::class.java) { PairingBlobCipher.open(key, blob + 0) }
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val secret = ByteArray(64) { 0x5A }
        val blob = PairingBlobCipher.seal(key, secret)
        assertFalse(blob.toList().windowed(8).any { window -> window.all { it == 0x5A.toByte() } })
    }
}
