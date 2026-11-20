package dev.bugobliterator.itermbuddy.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UncaughtLoggingTest {
    private val calls = mutableListOf<String>()
    private val crash = IllegalStateException("boom")
    private val thread = Thread("worker")

    private val previous = Thread.UncaughtExceptionHandler { t, e ->
        assertSame(thread, t)
        assertSame(crash, e)
        calls += "previous"
    }

    @Test
    fun logsThenFlushesThenDelegates() {
        UncaughtLogging(previous, log = { t, _ -> calls += "log ${t.name}" }, flush = { calls += "flush" }).uncaughtException(thread, crash)
        assertEquals(listOf("log worker", "flush", "previous"), calls)
    }

    @Test
    fun delegatesEvenWhenLoggingFails() {
        UncaughtLogging(previous, log = { _, _ -> throw OutOfMemoryError() }, flush = { calls += "flush" }).uncaughtException(thread, crash)
        assertEquals(listOf("previous"), calls)
        calls.clear()
        UncaughtLogging(previous, log = { _, _ -> calls += "log" }, flush = { throw RuntimeException() }).uncaughtException(thread, crash)
        assertEquals(listOf("log", "previous"), calls)
    }

    @Test
    fun noPreviousHandlerIsFine() {
        UncaughtLogging(null, log = { _, _ -> calls += "log" }, flush = { calls += "flush" }).uncaughtException(thread, crash)
        assertEquals(listOf("log", "flush"), calls)
    }
}
