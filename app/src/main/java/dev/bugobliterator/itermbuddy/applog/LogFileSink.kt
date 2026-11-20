package dev.bugobliterator.itermbuddy.applog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/** One log line before it is written: when ([wallMillis], epoch ms), [level], [tag], [message]. */
data class LogRecord(val wallMillis: Long, val level: LogLevel, val tag: String, val message: String)

/**
 * The rolling on-device log: two files in [dir] (app-private: `filesDir/logs`), [CURRENT] and
 * [PREVIOUS], each at most [maxFileBytes] (a line is never split, so a file is over only when
 * one line alone is). When the current file would pass it, it becomes the previous one (the old
 * previous is deleted) and a new current file starts: the oldest lines go first, and the whole
 * log stays under about twice [maxFileBytes].
 *
 * A line is `ISO-8601 local time with ms and offset | level | tag | message` ([formatLine]).
 *
 * **Never blocks a caller.** [offer] puts the line in a bounded queue ([queueCapacity]) and wakes
 * the writer; when the queue is full the line is dropped and counted, and the next drain writes
 * one `dropped N log lines` line after the lines it wrote. The writer ([start], one coroutine on
 * an I/O context) writes everything queued and flushes it to the OS after each batch, so a
 * process death loses at most the lines still queued. [drain] does the same on the caller's
 * thread; [drainWithin] gives up after a timeout (the uncaught-exception handler, before the
 * process dies, must not hang on a stuck write).
 *
 * I/O errors while logging are swallowed (the log must never take the app down); the next batch
 * reopens the file. If the current file cannot be renamed at rotation, it is truncated instead,
 * so the log never grows past its cap. Thread-safe.
 *
 * @param rename renames a file (production: [File.renameTo]); tests make it fail.
 */
class LogFileSink(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val queue = ArrayBlockingQueue<LogRecord>(queueCapacity)
    private val dropped = AtomicLong()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val fileLock = ReentrantLock()

    // Guarded by fileLock.
    private var out: OutputStream? = null
    private var currentSize = 0L

    private val current get() = File(dir, CURRENT)
    private val previous get() = File(dir, PREVIOUS)

    /** Queues [record] and wakes the writer; false (and counted) when the queue is full. */
    fun offer(record: LogRecord): Boolean {
        val queued = queue.offer(record)
        if (!queued) dropped.incrementAndGet()
        wake.trySend(Unit)
        return queued
    }

    /** Wakes the writer to write what is queued (onTrimMemory, the app stopping). */
    fun requestDrain() {
        wake.trySend(Unit)
    }

    /**
     * Starts the writer in [scope] on [ioContext]; it drains on every wake until [scope] ends. A
     * batch that throws anything, an Error included, is given up and the writer goes on.
     */
    fun start(scope: CoroutineScope, ioContext: CoroutineContext): Job = scope.launch(ioContext) {
        while (true) {
            wake.receive()
            drainSafely()
        }
    }

    /** One writer batch: [drain], surviving anything but cancellation. */
    internal fun drainSafely() {
        try {
            drain()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Never let one bad batch stop the writer.
        }
    }

    /** Writes every queued line, then the dropped count if any, and flushes; on this thread. */
    fun drain() {
        fileLock.withLock { drainLocked() }
    }

    /**
     * [drain], unless the log is busy for longer than [timeoutMillis] (a write stuck on the
     * disk): then nothing is written and it returns false. For the crash path.
     */
    fun drainWithin(timeoutMillis: Long): Boolean {
        if (!fileLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        try {
            drainLocked()
        } finally {
            fileLock.unlock()
        }
        return true
    }

    /** Lines dropped since the last drain (for tests and the header). */
    val droppedSinceDrain: Long get() = dropped.get()

    /**
     * Runs [block] on the log as it stands, with nothing written meanwhile: the queue is drained
     * first, so it includes every line logged before this call. [block] gets a [LogSnapshot].
     */
    fun <T> snapshot(block: (LogSnapshot) -> T): T = fileLock.withLock {
        drainLocked()
        try {
            out?.flush()
        } catch (_: IOException) {
            closeLocked()
        }
        block(LogSnapshot(listOf(previous, current).filter { it.exists() }))
    }

    /** Writes the whole log, oldest first, to [target] ([LogSnapshot.copyTo]). */
    fun copyTo(target: OutputStream) = snapshot { it.copyTo(target) }

    /** Clear log: forgets the queued lines and deletes both files. */
    fun clear() {
        fileLock.withLock {
            queue.clear()
            dropped.set(0)
            closeLocked()
            previous.delete()
            current.delete()
            currentSize = 0
        }
    }

    /** The log's size on disk in bytes (both files). */
    fun sizeBytes(): Long = fileLock.withLock { previous.length() + current.length() }

    private fun drainLocked() {
        var wrote = false
        while (true) {
            val record = queue.poll() ?: break
            writeLocked(record)
            wrote = true
        }
        val lost = dropped.getAndSet(0)
        if (lost > 0) {
            writeLocked(LogRecord(wallMillis(), LogLevel.WARN, TAG, "dropped $lost log lines: the log queue was full"))
            wrote = true
        }
        if (wrote) {
            try {
                out?.flush()
            } catch (_: IOException) {
                closeLocked()
            }
        }
    }

    private fun writeLocked(record: LogRecord) {
        val bytes = formatLine(record, zone()).toByteArray(Charsets.UTF_8)
        try {
            var stream = out ?: open()
            if (currentSize > 0 && currentSize + bytes.size > maxFileBytes) stream = rotateLocked()
            stream.write(bytes)
            currentSize += bytes.size
        } catch (_: IOException) {
            closeLocked()
        }
    }

    private fun open(truncate: Boolean = false): OutputStream {
        dir.mkdirs()
        val file = current
        currentSize = if (!truncate && file.exists()) file.length() else 0L
        return FileOutputStream(file, !truncate).buffered(BUFFER_BYTES).also { out = it }
    }

    /** Starts a new current file; the old one becomes [PREVIOUS], or is truncated if it cannot. */
    private fun rotateLocked(): OutputStream {
        closeLocked()
        previous.delete()
        val renamed = rename(current, previous)
        return open(truncate = !renamed)
    }

    private fun closeLocked() {
        try {
            out?.close()
        } catch (_: IOException) {
            // Already broken: dropped below.
        }
        out = null
    }

    companion object {
        const val CURRENT = "app.log"
        const val PREVIOUS = "app.log.1"
        const val DEFAULT_MAX_FILE_BYTES = 1L shl 20
        const val DEFAULT_QUEUE_CAPACITY = 4_096

        /** A message longer than this is cut, so one line cannot crowd out the log. */
        const val MAX_MESSAGE_CHARS = 16_384

        const val TAG = "AppLog"

        private const val BUFFER_BYTES = 16 * 1024

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        /** One file line, newline-terminated: `2026-11-20T14:03:05.123+01:00 | I | Tag | message`. */
        fun formatLine(record: LogRecord, zone: ZoneId): String {
            val time = TIME.format(Instant.ofEpochMilli(record.wallMillis).atZone(zone))
            val message = if (record.message.length <= MAX_MESSAGE_CHARS) record.message else record.message.take(MAX_MESSAGE_CHARS) + "…[truncated]"
            return "$time | ${record.level.letter} | ${record.tag} | $message\n"
        }
    }
}

/**
 * The log's files at one moment, oldest first ([LogFileSink.snapshot]); valid only inside the
 * snapshot's block.
 */
class LogSnapshot internal constructor(val files: List<File>) {
    /** Whether any line has [tag] at [level] (a scan of the files; unreadable parts count as no). */
    fun hasLine(level: LogLevel, tag: String): Boolean {
        val marker = " | ${level.letter} | $tag | "
        return files.any { file ->
            try {
                file.bufferedReader().useLines { lines -> lines.any { marker in it } }
            } catch (_: IOException) {
                false
            }
        }
    }

    /**
     * Writes the files to [target], oldest first. A file that cannot be read is left out (the
     * rest still goes), but a failure to write [target] throws: the copy is then incomplete.
     */
    fun copyTo(target: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        for (file in files) {
            val input = try {
                file.inputStream()
            } catch (_: IOException) {
                continue
            }
            input.use {
                while (true) {
                    val n = try {
                        it.read(buffer)
                    } catch (_: IOException) {
                        -1
                    }
                    if (n < 0) break
                    target.write(buffer, 0, n)
                }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER = 8 * 1024
    }
}
