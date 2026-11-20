package dev.bugobliterator.itermbuddy.applog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

class LogFileSinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.ofHours(1)

    private var now = 1_795_000_000_000L

    private fun sink(dir: File = temp.newFolder("logs"), maxFileBytes: Long = 1L shl 20, capacity: Int = 1_000) =
        LogFileSink(dir, maxFileBytes, capacity, wallMillis = { now }, zone = { zone })

    private fun record(message: String, tag: String = "T", level: LogLevel = LogLevel.INFO) = LogRecord(now, level, tag, message)

    private fun LogFileSink.text(): String = ByteArrayOutputStream().also(::copyTo).toString(Charsets.UTF_8)

    private fun messages(text: String): List<String> = text.lines().filter { it.isNotEmpty() }.map { it.substringAfterLast(" | ") }

    @Test
    fun lineFormatIsLocalIsoTimeWithMillisLevelTagMessage() {
        val record = LogRecord(SAMPLE_TIME, LogLevel.WARN, "CompanionClient", "state: Connecting")
        assertEquals(
            "2026-11-20T15:03:05.123+01:00 | W | CompanionClient | state: Connecting\n",
            LogFileSink.formatLine(record, ZoneOffset.ofHours(1)),
        )
        assertEquals(
            "2026-11-20T14:03:05.123Z | I | T | m\n",
            LogFileSink.formatLine(record.copy(level = LogLevel.INFO, tag = "T", message = "m"), ZoneOffset.UTC),
        )
    }

    @Test
    fun aLongMessageIsCut() {
        val line = LogFileSink.formatLine(record("x".repeat(LogFileSink.MAX_MESSAGE_CHARS + 10)), zone)
        assertTrue(line.endsWith("x…[truncated]\n"))
        assertEquals(LogFileSink.MAX_MESSAGE_CHARS, line.count { it == 'x' })
    }

    @Test
    fun linesAreWrittenInOrderAndOnlyWhenDrained() {
        val dir = temp.newFolder("logs")
        val sink = sink(dir)
        repeat(5) { sink.offer(record("line $it")) }
        assertFalse(File(dir, LogFileSink.CURRENT).exists())
        sink.drain()
        assertEquals((0 until 5).map { "line $it" }, messages(File(dir, LogFileSink.CURRENT).readText()))
    }

    @Test
    fun linesFromManyThreadsKeepEachThreadsOrder() {
        val sink = sink(capacity = 10_000)
        val threads = (0 until 4).map { t -> Thread { repeat(500) { sink.offer(record("$t:$it")) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val lines = messages(sink.text())
        assertEquals(2_000, lines.size)
        for (t in 0 until 4) assertEquals((0 until 500).map { "$t:$it" }, lines.filter { it.startsWith("$t:") })
    }

    @Test
    fun rotatesIntoThePreviousFileAndKeepsTheTotalUnderTwoFiles() {
        val dir = temp.newFolder("logs")
        val max = 4_096L
        val sink = sink(dir, maxFileBytes = max)
        // Each line is about 100 bytes: 1000 lines are about 24 files' worth.
        repeat(1_000) {
            sink.offer(record("line ${it.toString().padStart(4, '0')} " + "y".repeat(50)))
            if (it % 10 == 0) sink.drain()
        }
        sink.drain()
        val current = File(dir, LogFileSink.CURRENT)
        val previous = File(dir, LogFileSink.PREVIOUS)
        assertTrue(previous.exists())
        assertTrue(current.length() <= max)
        assertTrue(previous.length() <= max)
        assertTrue(previous.length() > max - 200)
        assertEquals(setOf(LogFileSink.CURRENT, LogFileSink.PREVIOUS), dir.list()!!.toSet())
        // The newest lines survive, in order, oldest first; the oldest were dropped.
        val lines = messages(sink.text()).map { it.substring(5, 9).toInt() }
        assertEquals(999, lines.last())
        assertEquals((lines.first()..999).toList(), lines)
        assertTrue(lines.first() > 900)
        assertEquals(previous.length() + current.length(), sink.sizeBytes())
    }

    @Test
    fun reopensAnExistingLogAndCountsItsSize() {
        val dir = temp.newFolder("logs")
        val first = sink(dir, maxFileBytes = 1_000)
        repeat(5) { first.offer(record("a".repeat(100))) }
        first.drain()
        // A new process: the next lines append, and rotation counts what is already there.
        val second = sink(dir, maxFileBytes = 1_000)
        repeat(5) { second.offer(record("b".repeat(100))) }
        second.drain()
        val previous = File(dir, LogFileSink.PREVIOUS).readText()
        assertEquals(5, messages(previous).count { it.startsWith("a") })
        assertTrue(messages(File(dir, LogFileSink.CURRENT).readText()).all { it.startsWith("b") })
    }

    @Test
    fun aFullQueueDropsAndCountsWithoutBlocking() {
        val sink = sink(capacity = 3)
        val accepted = (0 until 10).map { sink.offer(record("line $it")) }
        assertEquals(listOf(true, true, true) + List(7) { false }, accepted)
        assertEquals(7, sink.droppedSinceDrain)
        sink.drain()
        assertEquals(0, sink.droppedSinceDrain)
        val lines = sink.text().lines().filter { it.isNotEmpty() }
        assertEquals(listOf("line 0", "line 1", "line 2"), lines.take(3).map { it.substringAfterLast(" | ") })
        assertTrue(lines[3].contains(" | W | AppLog | dropped 7 log lines: the log queue was full"))
        // Room again: the next lines are kept and nothing more is reported dropped.
        sink.offer(record("after"))
        sink.drain()
        assertEquals(listOf("after"), messages(sink.text()).drop(4))
    }

    @Test
    fun copyToIncludesQueuedLinesOldestFirst() {
        val sink = sink(maxFileBytes = 200)
        repeat(6) { sink.offer(record("line $it " + "z".repeat(40))) }
        sink.drain()
        sink.offer(record("queued"))
        val lines = messages(sink.text())
        assertEquals("queued", lines.last())
        assertEquals(lines.dropLast(1).sortedBy { it.substring(5, 6).toInt() }, lines.dropLast(1))
    }

    @Test
    fun clearDeletesBothFilesAndTheQueue() {
        val dir = temp.newFolder("logs")
        val sink = sink(dir, maxFileBytes = 200)
        repeat(10) { sink.offer(record("line $it " + "z".repeat(40))) }
        sink.drain()
        sink.offer(record("queued"))
        sink.clear()
        assertEquals("", sink.text())
        assertEquals(0L, sink.sizeBytes())
        sink.offer(record("fresh"))
        sink.drain()
        assertEquals(listOf("fresh"), messages(sink.text()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWriterDrainsEachWakeInVirtualTime() = runTest {
        val dir = temp.newFolder("logs")
        val sink = sink(dir)
        val writerScope = TestScope(StandardTestDispatcher(testScheduler))
        val writer = sink.start(writerScope, StandardTestDispatcher(testScheduler))
        sink.offer(record("one"))
        assertFalse(File(dir, LogFileSink.CURRENT).exists())
        testScheduler.runCurrent()
        assertEquals(listOf("one"), messages(File(dir, LogFileSink.CURRENT).readText()))
        sink.offer(record("two"))
        sink.requestDrain()
        testScheduler.runCurrent()
        assertEquals(listOf("one", "two"), messages(File(dir, LogFileSink.CURRENT).readText()))
        writer.cancel()
    }

    private companion object {
        /** 2026-11-20T14:03:05.123Z. */
        const val SAMPLE_TIME = 1_795_183_385_123L
    }
}
