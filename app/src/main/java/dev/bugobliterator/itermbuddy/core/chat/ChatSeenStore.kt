package dev.bugobliterator.itermbuddy.core.chat

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What the owner has seen of each chat: the Mac's `lastModified` (milliseconds since 1970, as the
 * chat list carries it) at the owner's last look. A chat is unread when a later list carries a
 * larger Mac value and the change was not the owner's own words; that comparison is the
 * repository's, and it only ever compares Mac values with Mac values, never with phone time
 * (`ChatListModel.swift:122-129` stamps `lastModifiedDate` with the Mac's clock).
 *
 * Holds chat IDs and numbers only, no content. Owner decision: persisted, and wiped on unpair,
 * so marks belong to one pairing.
 *
 * **Writers.** Marks are written by the chat consumer only (the chat repository's single inbox
 * consumer), so a mark can never land after the unpair wipe: `ChatRepository.clear()` clears this
 * store itself, on the consumer, after its barrier against inputs from earlier links.
 * [clear] is therefore **not** registered as a separate unpair cleanup, which would run on another
 * thread and race a mark still in the inbox.
 *
 * **Threads.** An implementation may do file I/O on the caller's thread: never call it from the
 * UI thread.
 */
interface ChatSeenStore {
    /** The Mac's `lastModified` when the owner last saw [chatID], or null when never. */
    fun seen(chatID: String): Double?

    /**
     * The owner has seen [chatID] as of the Mac's [lastModifiedMillis]. The value is recorded as
     * given, also when smaller than the mark (a Mac clock that went back), so later changes on that
     * clock still count. A non-finite value is ignored.
     */
    fun markSeen(chatID: String, lastModifiedMillis: Double)

    /**
     * [markSeen] for every entry of [marks] at once: a file store writes (and syncs) once for the
     * whole batch, as the repository does for each list's first appearances.
     */
    fun markSeenAll(marks: Map<String, Double>)

    /** Drops [chatID]'s mark (the chat was deleted). */
    fun forget(chatID: String)

    /** Drops every mark (unpair). */
    fun clear()
}

/** A [ChatSeenStore] in memory only, for tests and previews. Thread-safe. */
class InMemoryChatSeenStore : ChatSeenStore {
    private val marks = HashMap<String, Double>()

    @Synchronized
    override fun seen(chatID: String): Double? = marks[chatID]

    @Synchronized
    override fun markSeen(chatID: String, lastModifiedMillis: Double) {
        if (lastModifiedMillis.isFinite()) marks[chatID] = lastModifiedMillis
    }

    @Synchronized
    override fun markSeenAll(marks: Map<String, Double>) {
        for ((chatID, millis) in marks) markSeen(chatID, millis)
    }

    @Synchronized
    override fun forget(chatID: String) {
        marks.remove(chatID)
    }

    @Synchronized
    override fun clear() = marks.clear()
}

/**
 * A [ChatSeenStore] in [file]: one JSON object from chat ID to the Mac's milliseconds, written as a
 * plain decimal (never exponent notation) that reads back to the same `Double`. Loaded on first
 * use; every change rewrites the file through [TEMP_SUFFIX] beside it (a fixed name) and an atomic
 * rename, so a kill leaves the old marks or the new ones; a temporary file left by a kill is
 * deleted on load and by [clear]. Thread-safe. The marks change in memory at once; the file is
 * written on [writer] (the app passes an IO executor, so the chat consumer never waits on a
 * sync), one write at a time, and changes made while a write is queued share the next write.
 * The first read loads the file on the caller's thread, and [clear] deletes it there (after any
 * write in progress): never call it from the UI thread.
 *
 * A file that cannot be read or parsed counts as no marks (every chat reads as unseen, never as
 * seen): logged by exception class only. A failed write keeps the marks in memory for this
 * process and is logged the same way.
 *
 * @param log diagnostics: exception classes and counts only, never chat IDs or file bytes.
 * @param writer where the file is written; by default on the caller's thread, at once.
 */
class FileChatSeenStore(
    private val file: File,
    private val log: (String) -> Unit = {},
    private val writer: Executor = Executor(Runnable::run),
) : ChatSeenStore {
    private var loaded: HashMap<String, Double>? = null

    /** A write is queued on [writer] and has not taken its snapshot yet. Guarded by `this`. */
    private var writeQueued = false

    /** Serialises the file's writes and [clear]'s delete; taken before `this`, never inside it. */
    private val fileLock = Any()

    private val temp = File(file.path + TEMP_SUFFIX)

    /** For tests: how many times the file was written. */
    @Volatile
    internal var saves = 0
        private set

    private fun marks(): HashMap<String, Double> = loaded ?: load().also { loaded = it }

    @Synchronized
    override fun seen(chatID: String): Double? = marks()[chatID]

    override fun markSeen(chatID: String, lastModifiedMillis: Double) {
        if (!lastModifiedMillis.isFinite()) return
        val queue = synchronized(this) {
            val marks = marks()
            if (marks[chatID] == lastModifiedMillis) return
            marks[chatID] = lastModifiedMillis
            queueWrite()
        }
        if (queue) writer.execute(::write)
    }

    override fun markSeenAll(marks: Map<String, Double>) {
        val queue = synchronized(this) {
            val held = marks()
            var changed = false
            for ((chatID, millis) in marks) {
                if (!millis.isFinite() || held[chatID] == millis) continue
                held[chatID] = millis
                changed = true
            }
            changed && queueWrite()
        }
        if (queue) writer.execute(::write)
    }

    override fun forget(chatID: String) {
        val queue = synchronized(this) { marks().remove(chatID) != null && queueWrite() }
        if (queue) writer.execute(::write)
    }

    override fun clear() = synchronized(fileLock) {
        synchronized(this) {
            loaded = HashMap()
            // A write still queued finds nothing to write.
            writeQueued = false
        }
        deleteTemp()
        if (file.exists() && !file.delete()) {
            // Leave no marks behind for the next pairing, deleted or not.
            log("chat seen marks: could not delete the file")
            save(emptyMap())
        }
    }

    /** Whether a write must be queued now (none is waiting to take its snapshot). Holds `this`. */
    private fun queueWrite(): Boolean {
        if (writeQueued) return false
        writeQueued = true
        return true
    }

    /** On [writer]: writes the marks as they are now, unless [clear] came first. */
    private fun write() = synchronized(fileLock) {
        val snapshot = synchronized(this) {
            if (!writeQueued) return@synchronized null
            writeQueued = false
            HashMap(marks())
        } ?: return@synchronized
        save(snapshot)
    }

    private fun load(): HashMap<String, Double> {
        val marks = HashMap<String, Double>()
        deleteTemp()
        if (!file.exists()) return marks
        val root = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            log("chat seen marks unreadable: ${e.javaClass.simpleName}")
            return marks
        } catch (e: SerializationException) {
            // Never the exception's message: kotlinx quotes the input.
            log("chat seen marks unreadable: ${e.javaClass.simpleName}")
            return marks
        }
        if (root !is JsonObject) {
            log("chat seen marks unreadable: not an object")
            return marks
        }
        var skipped = 0
        for ((chatID, value) in root) {
            val millis = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (millis != null && millis.isFinite()) marks[chatID] = millis else skipped++
        }
        if (skipped > 0) log("chat seen marks: skipped $skipped unreadable entries")
        return marks
    }

    private fun save(marks: Map<String, Double>) {
        val text = buildString {
            append('{')
            marks.entries.forEachIndexed { index, (chatID, millis) ->
                if (index > 0) append(',')
                append(JsonPrimitive(chatID).toString())
                append(':')
                append(BigDecimal.valueOf(millis).stripTrailingZeros().toPlainString())
            }
            append('}')
        }
        var written = false
        saves++
        try {
            file.absoluteFile.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            written = true
        } catch (e: IOException) {
            log("chat seen marks not saved: ${e.javaClass.simpleName}")
        } finally {
            if (!written) deleteTemp()
        }
    }

    private fun deleteTemp() {
        if (temp.exists() && !temp.delete()) log("chat seen marks: could not delete the temporary file")
    }

    companion object {
        /** The file name in the app's `noBackupFilesDir`: marks never go to a cloud backup. */
        const val FILE_NAME = "chat-seen.json"

        /** Appended to the store's file name for the file each write goes through. */
        const val TEMP_SUFFIX = ".tmp"

        /** The store the app uses, in [Context.getNoBackupFilesDir], written on [writer]. */
        fun inNoBackupDir(context: Context, writer: Executor, log: (String) -> Unit = {}): FileChatSeenStore =
            FileChatSeenStore(File(context.noBackupFilesDir, FILE_NAME), log, writer)
    }
}
