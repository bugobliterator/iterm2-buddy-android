package dev.bugobliterator.itermbuddy.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class LogShareTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val header = LogHeader(
        createdAt = "2026-11-20T14:03:05.123Z",
        appVersionName = "0.1.0",
        appVersionCode = 7,
        debugBuild = false,
        androidRelease = "16",
        sdkInt = 36,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8",
        protocolRevision = 15,
        minimumPeer = 11,
        macRevision = 15,
        paired = true,
        pasteKeys = true,
        scrollKeys = false,
        keyTrace = true,
        keyTraceLinesPresent = true,
    )

    @Test
    fun headerNamesVersionsDeviceRevisionsPairingAndSwitches() {
        assertEquals(
            """
            iTerm Buddy log
            Created: 2026-11-20T14:03:05.123Z
            App: 0.1.0 (build 7, release)
            Android: 16 (API 36)
            Device: Google Pixel 8
            Protocol revision: 15 (minimum peer 11)
            Mac revision: 15
            Paired: yes
            Send / * + = as paste: on
            Scroll web pages with keys: off
            Include typed-key trace: on
            Key-trace lines present: yes
            Line format: local time | level | tag | message
            ----

            """.trimIndent(),
            header.render(),
        )
        val idle = header.copy(macRevision = null, paired = false, debugBuild = true, keyTrace = false).render()
        assertTrue(idle.contains("Mac revision: not connected\n"))
        assertTrue(idle.contains("Paired: no\n"))
        assertTrue(idle.contains("(build 7, debug)"))
        assertTrue(header.copy(keyTraceLinesPresent = false).render().contains("Key-trace lines present: no\n"))
        assertTrue(header.copy(paired = null).render().contains("Paired: unknown\n"))
    }

    @Test
    fun headerHasNoFieldThatCouldCarryASecret() {
        // Only versions, device names, revisions, flags and the time: no string field for a key,
        // code, room secret, relay token or content. Adding one should be a deliberate change here.
        val fields = LogHeader::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "createdAt", "appVersionName", "appVersionCode", "debugBuild", "androidRelease", "sdkInt",
                "deviceManufacturer", "deviceModel", "protocolRevision", "minimumPeer", "macRevision", "paired",
                "pasteKeys", "scrollKeys", "keyTrace", "keyTraceLinesPresent",
            ),
            fields,
        )
        val text = header.render().lowercase()
        for (word in listOf("secret", "token", "key:", "code:", "room")) assertFalse(word, text.contains(word))
    }

    @Test
    fun fileNameIsTheLocalTimestamp() {
        assertEquals("itermbuddy-log-20261120-150305-123.txt", LogShare.fileName(1_795_183_385_123L, ZoneOffset.ofHours(1)))
        assertEquals("2026-11-20T15:03:05.123+01:00", LogShare.headerTime(1_795_183_385_123L, ZoneOffset.ofHours(1)))
    }

    @Test
    fun theShareIsTheHeaderThenTheLogOldestFirstInTheSharedDirectory() {
        val sink = LogFileSink(temp.newFolder("logs"), maxFileBytes = 300, wallMillis = { 0L }, zone = { ZoneOffset.UTC })
        repeat(8) { sink.offer(LogRecord(0L, LogLevel.INFO, "T", "line $it " + "p".repeat(30))) }
        sink.drain()
        sink.offer(LogRecord(0L, LogLevel.INFO, "T", "last"))
        val cache = temp.newFolder("cache")
        val now = 1_795_183_385_123L
        val file = LogShare.assemble(sink, { header }, cache, now, ZoneOffset.UTC)
        assertEquals(File(cache, LogShare.SHARE_DIR), file.parentFile)
        assertEquals("itermbuddy-log-20261120-140305-123.txt", file.name)
        val text = file.readText()
        assertTrue(text.startsWith(header.render()))
        val lines = text.removePrefix(header.render()).lines().filter { it.isNotEmpty() }.map { it.substringAfterLast(" | ") }
        assertEquals("last", lines.last())
        val numbers = lines.dropLast(1).map { it.substring(5, 6).toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(7, numbers.last())
    }

    @Test
    fun oldSharesAreDeletedAndRecentOnesKept() {
        val cache = temp.newFolder("cache")
        val dir = File(cache, LogShare.SHARE_DIR).apply { mkdirs() }
        val now = 1_795_183_385_123L
        val old = File(dir, "itermbuddy-log-old.txt").apply { writeText("x"); setLastModified(now - LogShare.KEEP_MILLIS - 1_000) }
        val recent = File(dir, "itermbuddy-log-recent.txt").apply { writeText("x"); setLastModified(now - 60_000) }
        val sink = LogFileSink(temp.newFolder("logs"))
        val file = LogShare.assemble(sink, { header }, cache, now, ZoneOffset.UTC)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(file.exists())
    }

    @Test
    fun theHeaderSaysWhetherTheSharedLogHoldsKeyTraceLines() {
        val sink = LogFileSink(temp.newFolder("logs"), wallMillis = { 0L }, zone = { ZoneOffset.UTC })
        val cache = temp.newFolder("cache")
        val seen = mutableListOf<Boolean>()
        sink.offer(LogRecord(0L, LogLevel.INFO, "InputSender", "sent"))
        LogShare.assemble(sink, { present -> seen += present; header.copy(keyTraceLinesPresent = present) }, cache, 1_000L, ZoneOffset.UTC)
        sink.offer(LogRecord(0L, LogLevel.VERBOSE, "IB.Input", "key U+0061[a]"))
        val traced = LogShare.assemble(sink, { present -> seen += present; header.copy(keyTraceLinesPresent = present) }, cache, 2_000L, ZoneOffset.UTC)
        assertTrue(traced.readText().contains("Key-trace lines present: yes\n"))
        sink.clear()
        LogShare.assemble(sink, { present -> seen += present; header.copy(keyTraceLinesPresent = present) }, cache, 3_000L, ZoneOffset.UTC)
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun aShareThatCannotBeWrittenWholeThrowsAndLeavesNoFile() {
        val sink = LogFileSink(temp.newFolder("logs"))
        val cache = temp.newFolder("cache")
        val thrown = runCatching { LogShare.assemble(sink, { throw java.io.IOException("no space") }, cache, 1_000L, ZoneOffset.UTC) }.exceptionOrNull()
        assertTrue("$thrown", thrown is java.io.IOException)
        assertEquals(emptyList<String>(), File(cache, LogShare.SHARE_DIR).list()!!.toList())
    }

    @Test
    fun clearSharesDeletesEveryShareFile() {
        val cache = temp.newFolder("cache")
        val sink = LogFileSink(temp.newFolder("logs"))
        LogShare.assemble(sink, { header }, cache, 1_000L, ZoneOffset.UTC)
        LogShare.assemble(sink, { header }, cache, 2_000L, ZoneOffset.UTC)
        assertEquals(2, File(cache, LogShare.SHARE_DIR).list()!!.size)
        LogShare.clearShares(cache)
        assertEquals(0, File(cache, LogShare.SHARE_DIR).list()!!.size)
        LogShare.clearShares(temp.newFolder("empty"))
    }

    @Test
    fun sharesMadeInTheSameSecondGetDifferentNames() {
        assertTrue(LogShare.fileName(1_000L, ZoneOffset.UTC) != LogShare.fileName(1_001L, ZoneOffset.UTC))
    }
}
