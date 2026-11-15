package dev.bugobliterator.itermbuddy.store

import android.security.keystore.BackendBusyException
import android.security.keystore.StrongBoxUnavailableException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class StrongBoxKeyGenerationTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val attempts = mutableListOf<Boolean>()
    private val sleeps = mutableListOf<Long>()

    /** Generates with each attempt's outcome taken in order from [outcomes]. */
    private fun generate(vararg outcomes: Throwable?): SecretKey {
        val queue = ArrayDeque(outcomes.toList())
        return generatePreferringStrongBox(
            generate = { strongBox ->
                attempts += strongBox
                queue.removeFirstOrNull()?.let { throw it }
                key
            },
            sleep = { sleeps += it },
            backOffHint = { hints.getValue(it) },
        )
    }

    private val hints = mutableMapOf<BackendBusyException, Long>()

    private fun busy(hint: Long) = BackendBusyException(hint).also { hints[it] = hint }

    @Test
    fun usesStrongBoxWhenItWorks() {
        assertSame(key, generate(null))
        assertEquals(listOf(true), attempts)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun fallsBackToTeeOnlyWhenThereIsNoStrongBox() {
        assertSame(key, generate(StrongBoxUnavailableException()))
        assertEquals(listOf(true, false), attempts)
    }

    @Test
    fun busyStrongBoxIsRetriedOnceAfterTheHintNotAbandoned() {
        assertSame(key, generate(busy(40)))
        assertEquals(listOf(true, true), attempts)
        assertEquals(listOf(40L), sleeps)
    }

    @Test
    fun backOffIsCapped() {
        generate(busy(60_000))
        assertEquals(listOf(MAX_KEY_GENERATION_BACKOFF_MILLIS), sleeps)
        sleeps.clear()
        attempts.clear()
        generate(busy(-5))
        assertEquals(listOf(0L), sleeps)
    }

    @Test
    fun busyTwiceFails() {
        val second = busy(10)
        val thrown = assertThrows(BackendBusyException::class.java) { generate(busy(10), second) }
        assertSame(second, thrown)
        assertEquals(listOf(true, true), attempts)
    }

    @Test
    fun busyTeeAfterFallbackIsRetriedOnce() {
        assertSame(key, generate(StrongBoxUnavailableException(), busy(5)))
        assertEquals(listOf(true, false, false), attempts)
    }

    @Test
    fun otherFailuresDoNotFallBack() {
        val failure = ProviderException("StrongBox unreachable")
        assertSame(failure, assertThrows(ProviderException::class.java) { generate(failure) })
        assertEquals(listOf(true), attempts)
    }
}
