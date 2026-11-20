package dev.bugobliterator.itermbuddy.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset

class AppLoggerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private data class Logcat(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    private val logcat = mutableListOf<Logcat>()

    private val sink by lazy { LogFileSink(temp.newFolder("logs"), wallMillis = { 0L }, zone = { ZoneOffset.UTC }) }

    private fun LogFileSink.text(): String = ByteArrayOutputStream().also(::copyTo).toString(Charsets.UTF_8)

    @Test
    fun eachLineGoesToLogcatAndTheFileWithItsTag() {
        val logger = AppLogger({ level, tag, message, t -> logcat += Logcat(level, tag, message, t) }, sink, wallMillis = { 5L })
        logger.log(LogLevel.INFO, "StreamController", "stream 3 started")
        logger.log(LogLevel.VERBOSE, "IB.Input", "key U+0061[a]")
        assertEquals(listOf(Logcat(LogLevel.INFO, "StreamController", "stream 3 started", null), Logcat(LogLevel.VERBOSE, "IB.Input", "key U+0061[a]", null)), logcat)
        assertEquals(
            "1970-01-01T00:00:00.005Z | I | StreamController | stream 3 started\n1970-01-01T00:00:00.005Z | V | IB.Input | key U+0061[a]\n",
            sink.text(),
        )
    }

    @Test
    fun aThrowableReachesLogcatWholeButTheFileWithoutItsMessages() {
        val logger = AppLogger({ level, tag, message, t -> logcat += Logcat(level, tag, message, t) }, sink, wallMillis = { 0L })
        val error = IllegalStateException("secret terminal text", java.io.IOException("more content"))
        logger.log(LogLevel.ERROR, "CompanionApp", "uncaught in app scope", error)
        assertSame(error, logcat.single().throwable)
        val text = sink.text()
        assertTrue(text.contains("| E | CompanionApp | uncaught in app scope\njava.lang.IllegalStateException\n\tat "))
        assertTrue(text.contains("\nCaused by: java.io.IOException\n\tat "))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("content"))
    }

    @Test
    fun aStubbedLogcatStillWritesTheFile() {
        val logger = AppLogger({ _, _, _, _ -> throw RuntimeException("Method not mocked") }, sink, wallMillis = { 0L })
        logger.log(LogLevel.WARN, "T", "kept")
        assertTrue(sink.text().endsWith("| W | T | kept\n"))
    }

    @Test
    fun drainNowWritesOnThisThread() {
        val logger = AppLogger({ _, _, _, _ -> }, sink, wallMillis = { 0L })
        logger.log(LogLevel.INFO, "T", "x")
        logger.drainNow()
        assertTrue(sink.sizeBytes() > 0)
    }

    @Test
    fun fileOnlyLinesSkipLogcat() {
        val logger = AppLogger({ level, tag, message, t -> logcat += Logcat(level, tag, message, t) }, sink, wallMillis = { 0L })
        logger.log(LogLevel.VERBOSE, "IB.Input", "key", toLogcat = false)
        assertEquals(emptyList<Logcat>(), logcat)
        assertTrue(sink.text().endsWith("| V | IB.Input | key\n"))
    }

    @Test
    fun theCrashDrainDoesNotWaitOutABusyLog() {
        val logger = AppLogger({ _, _, _, _ -> }, sink, wallMillis = { 0L })
        logger.log(LogLevel.ERROR, "Crash", "x")
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { sink.snapshot { holding.countDown(); release.await() } }
        holder.start()
        holding.await()
        val started = System.nanoTime()
        logger.drainNow(timeoutMillis = 50)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        release.countDown()
        holder.join()
        assertEquals(1_000L, AppLogger.CRASH_DRAIN_TIMEOUT_MILLIS)
    }

    @Test
    fun aLogSummaryNamesClassesNeverMessages() {
        assertEquals("IOException", logSummary(java.io.IOException("wss://host/room")))
        assertEquals(
            "SocketException (caused by SSLException)",
            logSummary(java.net.SocketException("secret").apply { initCause(javax.net.ssl.SSLException("more")) }),
        )
        assertEquals("IOException", logSummary(java.io.IOException("copy", java.io.IOException("original"))))
    }

    @Test
    fun stackTracesStopAtACauseCycle() {
        val a = RuntimeException("a")
        val b = IllegalArgumentException("b", a)
        a.initCause(b)
        val text = stackTraceWithoutMessages(a)
        assertEquals(1, Regex("Caused by: java.lang.IllegalArgumentException").findAll(text).count())
    }
}
