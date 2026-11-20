package dev.bugobliterator.itermbuddy.applog

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What a shared log starts with: the app and device, the protocol, the connection, and the
 * settings that change what the app sends. By construction it holds no keys, codes, room
 * secrets or content: only these fields, none of which can carry one.
 *
 * @param macRevision the connected Mac's protocol revision; null when not connected.
 * @param paired whether a pairing is stored; null while the client has not looked yet.
 */
data class LogHeader(
    val createdAt: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val debugBuild: Boolean,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val protocolRevision: Int,
    val minimumPeer: Int,
    val macRevision: Int?,
    val paired: Boolean?,
    val pasteKeys: Boolean,
    val scrollKeys: Boolean,
    val keyTrace: Boolean,
) {
    fun render(): String = buildString {
        appendLine("iTerm Buddy log")
        appendLine("Created: $createdAt")
        appendLine("App: $appVersionName (build $appVersionCode, ${if (debugBuild) "debug" else "release"})")
        appendLine("Android: $androidRelease (API $sdkInt)")
        appendLine("Device: $deviceManufacturer $deviceModel")
        appendLine("Protocol revision: $protocolRevision (minimum peer $minimumPeer)")
        appendLine("Mac revision: ${macRevision?.toString() ?: "not connected"}")
        appendLine("Paired: ${when (paired) { true -> "yes"; false -> "no"; null -> "unknown" }}")
        appendLine("Send / * + = as paste: ${onOff(pasteKeys)}")
        appendLine("Scroll web pages with keys: ${onOff(scrollKeys)}")
        appendLine("Include typed-key trace: ${onOff(keyTrace)}")
        appendLine("Line format: local time | level | tag | message")
        appendLine(SEPARATOR)
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    companion object {
        const val SEPARATOR = "----"
    }
}

/**
 * Settings › Share log: one plain-text file in the app's cache, [SHARE_DIR] (the only directory
 * the FileProvider serves, `res/xml/log_share_paths.xml`), named [fileName]: the header, then
 * the rolling log oldest to newest. Earlier shares older than [KEEP_MILLIS] are deleted first, so
 * the cache never collects them, while one just handed to another app stays readable.
 */
object LogShare {
    const val SHARE_DIR = "shared-logs"

    /** The FileProvider authority suffix: `<applicationId>.logs`. */
    const val AUTHORITY_SUFFIX = ".logs"

    const val KEEP_MILLIS = 60 * 60 * 1000L

    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val HEADER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    /** `itermbuddy-log-20261120-140305.txt`, in local time. */
    fun fileName(nowMillis: Long, zone: ZoneId): String = "itermbuddy-log-${FILE_TIME.format(Instant.ofEpochMilli(nowMillis).atZone(zone))}.txt"

    /** The header's Created time, as the log lines write theirs. */
    fun headerTime(nowMillis: Long, zone: ZoneId): String = HEADER_TIME.format(Instant.ofEpochMilli(nowMillis).atZone(zone))

    /** Writes the share file under [cacheDir] and returns it. Disk I/O: never on the main thread. */
    fun assemble(sink: LogFileSink, header: LogHeader, cacheDir: File, nowMillis: Long, zone: ZoneId): File {
        val dir = File(cacheDir, SHARE_DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { if (nowMillis - it.lastModified() > KEEP_MILLIS) it.delete() }
        val file = File(dir, fileName(nowMillis, zone))
        file.outputStream().buffered().use { out ->
            out.write(header.render().toByteArray(Charsets.UTF_8))
            sink.copyTo(out)
        }
        return file
    }
}
