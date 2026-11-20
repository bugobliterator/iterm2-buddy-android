package dev.bugobliterator.itermbuddy.applog

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
import java.util.concurrent.atomic.AtomicLong
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
 * thread (the uncaught-exception handler, before the process dies).
 *
 * I/O errors are swallowed (the log must never take the app down); the next batch reopens the
 * file. Thread-safe.
 */
class LogFileSink(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val queue = ArrayBlockingQueue<LogRecord>(queueCapacity)
    private val dropped = AtomicLong()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val fileLock = Any()

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

    /** Starts the writer in [scope] on [ioContext]; it drains on every wake until [scope] ends. */
    fun start(scope: CoroutineScope, ioContext: CoroutineContext): Job = scope.launch(ioContext) {
        while (true) {
            wake.receive()
            try {
                drain()
            } catch (_: RuntimeException) {
                // Never let one bad batch stop the writer.
            }
        }
    }

    /** Writes every queued line, then the dropped count if any, and flushes; on this thread. */
    fun drain() {
        synchronized(fileLock) { drainLocked() }
    }

    /** Lines dropped since the last drain (for tests and the header). */
    val droppedSinceDrain: Long get() = dropped.get()

    /**
     * Writes the log, oldest first ([PREVIOUS] then [CURRENT]), to [target], after draining the
     * queue so it includes every line logged before this call.
     */
    fun copyTo(target: OutputStream) {
        synchronized(fileLock) {
            drainLocked()
            for (file in listOf(previous, current)) {
                try {
                    if (file.exists()) file.inputStream().use { it.copyTo(target) }
                } catch (_: IOException) {
                    // A file that cannot be read is left out; the rest is still sent.
                }
            }
        }
    }

    /** Clear log: forgets the queued lines and deletes both files. */
    fun clear() {
        synchronized(fileLock) {
            queue.clear()
            dropped.set(0)
            closeLocked()
            previous.delete()
            current.delete()
            currentSize = 0
        }
    }

    /** The log's size on disk in bytes (both files). */
    fun sizeBytes(): Long = synchronized(fileLock) { previous.length() + current.length() }

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
            if (currentSize > 0 && currentSize + bytes.size > maxFileBytes) {
                rotateLocked()
                stream = open()
            }
            stream.write(bytes)
            currentSize += bytes.size
        } catch (_: IOException) {
            closeLocked()
        }
    }

    private fun open(): OutputStream {
        dir.mkdirs()
        val file = current
        currentSize = if (file.exists()) file.length() else 0L
        return FileOutputStream(file, true).buffered(BUFFER_BYTES).also { out = it }
    }

    private fun rotateLocked() {
        closeLocked()
        previous.delete()
        current.renameTo(previous)
        currentSize = 0
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
