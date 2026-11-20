package dev.bugobliterator.itermbuddy.applog

import android.util.Log

/** A log line's level; [letter] is what the file line shows. */
enum class LogLevel(val letter: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/** Writes one line to logcat (production: [AndroidLogcat]). */
fun interface LogcatWriter {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/** `android.util.Log`, as the app logged before the file sink existed. */
object AndroidLogcat : LogcatWriter {
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }
}

/**
 * The app's one logger: each line goes to logcat as before ([logcat], tags unchanged) and, when
 * there is one, to the rolling on-device file ([file], [LogFileSink]) that Settings › Share log
 * sends. Never blocks: the file side only queues the line.
 *
 * **Privacy.** What reaches here persists on the device, so the existing rule holds: no terminal
 * or chat content, keys, pairing codes or secrets; lengths, counts, IDs and timings only. The
 * typed-key trace (`IB.Input`) is the only exception, and only while it is on. A throwable goes
 * to logcat whole, but to the file as its class names and stack frames only
 * ([stackTraceWithoutMessages]): an exception's message can quote what was being parsed.
 */
class AppLogger(
    private val logcat: LogcatWriter,
    private val file: LogFileSink?,
    private val wallMillis: () -> Long,
) {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        try {
            logcat.write(level, tag, message, throwable)
        } catch (_: RuntimeException) {
            // android.util.Log is a stub in JVM tests; the file line below still goes.
        }
        val sink = file ?: return
        val text = if (throwable == null) message else message + "\n" + stackTraceWithoutMessages(throwable)
        sink.offer(LogRecord(wallMillis(), level, tag, text))
    }

    /** Asks the file writer to write what is queued now; does not wait. */
    fun flush() {
        file?.requestDrain()
    }

    /** Writes what is queued now on this thread (the uncaught handler, before the process dies). */
    fun drainNow() {
        file?.drain()
    }
}

/**
 * Where every log call in the app goes ([AppLogger]); `CompanionApp` installs the file-backed
 * one first thing in `onCreate`. Until then, and in JVM tests, lines go to logcat only.
 */
object AppLog {
    @Volatile
    var logger: AppLogger = AppLogger(AndroidLogcat, file = null, wallMillis = System::currentTimeMillis)

    fun v(tag: String, message: String) = logger.log(LogLevel.VERBOSE, tag, message)

    fun d(tag: String, message: String) = logger.log(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = logger.log(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) = logger.log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = logger.log(LogLevel.ERROR, tag, message, throwable)

    fun flush() = logger.flush()
}

/**
 * [throwable] as class names and stack frames, its causes after it (`Caused by: ...`), with no
 * message: an exception's message can quote its input (a parser's does). At most [MAX_FRAMES]
 * frames per throwable and [MAX_CAUSES] causes.
 */
fun stackTraceWithoutMessages(throwable: Throwable): String {
    val out = StringBuilder()
    val seen = HashSet<Throwable>()
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && seen.add(current) && depth <= MAX_CAUSES) {
        if (depth > 0) out.append("\nCaused by: ")
        out.append(current.javaClass.name)
        val frames = current.stackTrace
        frames.take(MAX_FRAMES).forEach { out.append("\n\tat ").append(it) }
        if (frames.size > MAX_FRAMES) out.append("\n\t... ").append(frames.size - MAX_FRAMES).append(" more")
        current = current.cause
        depth++
    }
    return out.toString()
}

private const val MAX_FRAMES = 64
private const val MAX_CAUSES = 8
