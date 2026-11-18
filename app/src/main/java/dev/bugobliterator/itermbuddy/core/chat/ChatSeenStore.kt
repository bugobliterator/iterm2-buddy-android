package dev.bugobliterator.itermbuddy.core.chat

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Holds chat IDs and numbers only, no content. Owner decision: persisted, and wiped on unpair
 * ([clear], from the unpair cleanups), so marks belong to one pairing.
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
    override fun forget(chatID: String) {
        marks.remove(chatID)
    }

    @Synchronized
    override fun clear() = marks.clear()
}

/**
 * A [ChatSeenStore] in [file]: one JSON object from chat ID to the Mac's milliseconds, written as a
 * plain decimal (never exponent notation) that reads back to the same `Double`. Loaded on first
 * use; every change rewrites the file through a temporary file in the same directory and an atomic
 * rename, so a kill leaves the old marks or the new ones. Thread-safe. File I/O happens on the
 * caller's thread, so call it off the main thread (the chat consumer does).
 *
 * A file that cannot be read or parsed counts as no marks (every chat reads as unseen, never as
 * seen): logged by exception class only. A failed write keeps the marks in memory for this
 * process and is logged the same way.
 *
 * @param log diagnostics: exception classes and counts only, never chat IDs or file bytes.
 */
class FileChatSeenStore(private val file: File, private val log: (String) -> Unit = {}) : ChatSeenStore {
    private var loaded: HashMap<String, Double>? = null

    private fun marks(): HashMap<String, Double> = loaded ?: load().also { loaded = it }

    @Synchronized
    override fun seen(chatID: String): Double? = marks()[chatID]

    @Synchronized
    override fun markSeen(chatID: String, lastModifiedMillis: Double) {
        if (!lastModifiedMillis.isFinite()) return
        val marks = marks()
        if (marks[chatID] == lastModifiedMillis) return
        marks[chatID] = lastModifiedMillis
        save(marks)
    }

    @Synchronized
    override fun forget(chatID: String) {
        val marks = marks()
        if (marks.remove(chatID) != null) save(marks)
    }

    @Synchronized
    override fun clear() {
        loaded = HashMap()
        if (file.exists() && !file.delete()) {
            // Leave no marks behind for the next pairing, deleted or not.
            log("chat seen marks: could not delete the file")
            save(emptyMap())
        }
    }

    private fun load(): HashMap<String, Double> {
        val marks = HashMap<String, Double>()
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
        val dir = file.absoluteFile.parentFile
        var temp: File? = null
        try {
            dir?.mkdirs()
            temp = File.createTempFile(file.name, ".tmp", dir)
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
        } catch (e: IOException) {
            log("chat seen marks not saved: ${e.javaClass.simpleName}")
        } finally {
            temp?.delete()
        }
    }

    companion object {
        /** The file name in the app's `noBackupFilesDir`: marks never go to a cloud backup. */
        const val FILE_NAME = "chat-seen.json"

        /** The store the app uses, in [Context.getNoBackupFilesDir]. */
        fun inNoBackupDir(context: Context, log: (String) -> Unit = {}): FileChatSeenStore =
            FileChatSeenStore(File(context.noBackupFilesDir, FILE_NAME), log)
    }
}
