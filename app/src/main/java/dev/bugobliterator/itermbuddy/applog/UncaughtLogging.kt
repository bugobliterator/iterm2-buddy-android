package dev.bugobliterator.itermbuddy.applog

/**
 * The process's default uncaught-exception handler: logs the crash ([log]: the thread's name and
 * the stack trace, which the file keeps without messages), writes the log out on this thread
 * ([flush]), then hands the exception to [previous] (the platform's, which ends the process) as
 * if this handler were not there. Nothing it does can stop [previous] from running.
 */
class UncaughtLogging(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val log: (Thread, Throwable) -> Unit,
    private val flush: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            log(thread, throwable)
            flush()
        } catch (_: Throwable) {
            // The log failed too: the crash still goes to the previous handler.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val TAG = "Crash"

        /** Installs it over the current default handler, logging to [AppLog]. */
        fun install() {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                UncaughtLogging(
                    previous,
                    log = { thread, e -> AppLog.e(TAG, "uncaught exception in thread ${thread.name}", e) },
                    flush = { AppLog.logger.drainNow() },
                ),
            )
        }
    }
}
