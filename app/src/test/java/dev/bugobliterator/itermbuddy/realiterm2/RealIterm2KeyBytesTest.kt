package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.input.PASTED_KEYS
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The key-bytes probe of the real-Mac CI: which bytes reach a program in a real iTerm2 session when
 * the phone types `/`, `*` and a few other characters, and whether real Claude Code shows them.
 * It reports; it does not assert on the bytes. It fails only when the rig itself broke (no
 * connection, no `probe.env`, or the plain capture never started).
 *
 * `ci/real-iterm2/install-claude-code.sh` wrote `key-bytes/probe.env` beside the pairing file
 * (node, the repository, Claude Code). For each mode the test types
 * `bash ci/real-iterm2/key-probe.sh <probe.env> …` into the first terminal session and waits for
 * the program to be ready:
 * - **capture modes**: `key-capture.mjs` with no mode, bracketed paste, modifyOtherKeys 2,
 *   kitty flags 1, 3 and 31, and Claude Code's own pair, kitty flags 5 with modifyOtherKeys 2;
 *   it logs every chunk its raw tty reads;
 * - **Claude Code** under `script -r`, which records the tty's input and output; ready once its
 *   prompt footer shows.
 *
 * Then it sends characters one at a time through the app's own [InputSender] (`sendKey` text,
 * no modifiers: the phone's path), waiting after each for the bytes to arrive: [SHORT_CHARS] in
 * most capture modes, every printable ASCII character and space ([ALL_ASCII]) under kitty flags
 * 5, and [CLAUDE_CHARS] in Claude Code; then `pasteText` of [PASTE] (slash, star). For Claude
 * Code, `/` is first sent alone on the empty prompt (to see the slash-command menu) by `sendKey`
 * and `pasteText`, and `*` by `pasteText`, each erased with Backspace; then [WORKAROUND_TEXT]
 * (`a/b*c+d=e`) is typed as one key event through the same [InputSender] with the app's "Send
 * / * + = as paste" setting turned on for it (so `/ * + =` go as one-character pastes, the rest
 * as keys; one sender, so nothing sent later can interleave with its runs), and
 * the report says whether Claude drew the whole text and each of the four in place; it is erased
 * with Backspace. The run ends with Return, then Ctrl-C twice.
 *
 * Before typing each probe command the test sends Ctrl-C, so the shell starts from an empty line
 * whatever an earlier mode left on it. A capture program that exits before the test stops it (its
 * own timeout) is reported: the keys sent after that reached the shell instead. (A System Events key press baseline was tried and removed: on the runner it
 * delivered nothing, with iTerm2 frontmost.)
 *
 * The report (stdout and `diagnostics/key-bytes-report.txt`) gives, per mode and character, the
 * bytes as hex and as a readable escape form, and the Mac's keyboard input source. Under kitty
 * flags 5 each CSI u is decoded (key number, shifted and base keys, modifiers) and marked WRONG
 * when the key number is not the character's US ANSI base key or an unshifted character carries
 * Shift, with a summary of the WRONG set among the keys that arrived. For Claude Code it also gives
 * every keyboard-mode sequence it wrote, in order, the terminal's answers, and whether each character appeared in
 * what Claude drew next. Unlike the other real-iTerm2 tests this one prints terminal content: the
 * probe characters and Claude Code's UI text in a throwaway CI terminal.
 */
class RealIterm2KeyBytesTest {
    private val rig = RealIterm2Rig()
    private val lines = mutableListOf<String>()
    private val senderLog = CopyOnWriteArrayList<String>()

    /** The sender's "Send / * + = as paste" setting, read as each key is sent. */
    private val pasteKeys = AtomicBoolean(false)

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun reportsTheBytesEachKeyDelivers() = runBlocking {
        val client = rig.connect()
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val session = rig.firstTerminalSession(client)
        val workDir = File(RealIterm2Rig.requiredEnv("REAL_ITERM2_PAIRING_FILE")).absoluteFile.parentFile
            ?: error("REAL_ITERM2_PAIRING_FILE has no parent directory")
        val keyBytesDir = workDir.resolve("key-bytes")
        val envFile = keyBytesDir.resolve("probe.env")
        if (!envFile.isFile) failWith("no $envFile: the install-claude-code.sh step did not run")
        val env = readEnv(envFile)
        val probeScript = File(env["KEY_BYTES_REPO"] ?: failWith("$envFile has no KEY_BYTES_REPO"), "ci/real-iterm2/key-probe.sh")

        val sender = InputSender(
            link = client,
            scope = rig.scope,
            isCurrent = { target -> target.linkId == rig.linkId.value && client.state.value is ClientState.Connected },
            log = { line -> senderLog += line },
            // The app's "Send / * + = as paste" setting: off, except in the Claude Code step that checks it.
            pasteKeys = pasteKeys::get,
        )
        val target = InputTarget(linkId, watchEpoch = 0, sessionGuid = session.guid)
        val probe = Probe(sender, target, "bash ${quoted(probeScript.path)} ${quoted(envFile.path)}")

        line("Claude Code ${env["KEY_BYTES_CLAUDE_VERSION"] ?: "not installed"}; iTerm2 session ${session.guid}")
        reportInputSource()
        var plainStarted = false
        for (mode in CAPTURE_MODES) {
            val started = captureMode(probe, keyBytesDir.resolve(mode.label), mode)
            if (mode.modes.isEmpty()) plainStarted = started
        }
        claudeMode(probe, keyBytesDir.resolve("claude"), installed = env["KEY_BYTES_CLAUDE"] != null)
        if (senderLog.isNotEmpty()) line("input sender: ${senderLog.toList()}")

        val diagnostics = workDir.resolve("diagnostics").also { it.mkdirs() }
        diagnostics.resolve(REPORT_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
        keyBytesDir.resolve("claude/claude.rec").takeIf { it.isFile }?.copyTo(diagnostics.resolve("key-bytes-claude.rec"), overwrite = true)
        report("key-bytes report written to diagnostics/$REPORT_FILE")
        if (!plainStarted) failWith("the plain key capture never started; see the report above")
    }

    /**
     * One capture mode: [label], the key-capture.mjs modes it enables, the [chars] it sends, and
     * whether each arrival is decoded as kitty CSI u ([decodeKitty]).
     */
    private class CaptureMode(
        val label: String,
        val modes: List<String>,
        val enables: String,
        val chars: List<String> = SHORT_CHARS,
        val decodeKitty: Boolean = false,
    )

    /**
     * Types probe commands into the session and sends the probe keys, through the app's own
     * sender.
     */
    private class Probe(val sender: InputSender, val target: InputTarget, val command: String) {
        /** Ctrl-C first: an empty shell line (no leftover keys, no continuation prompt), then the command. */
        suspend fun type(arguments: String) {
            key("c", KeyModifiers(control = true))
            delay(FRESH_LINE_GAP)
            sender.typeThenReturn(target, "$command $arguments")
        }

        fun key(text: String, modifiers: KeyModifiers = KeyModifiers()) = sender.key(target, KeyEvent(Key.Text(text), modifiers))
        fun special(key: SpecialKey) = sender.key(target, KeyEvent(Key.Special(key)))
        fun paste(text: String) = sender.paste(target, text)
    }

    // --- Capture modes ------------------------------------------------------------------

    private suspend fun captureMode(probe: Probe, dir: File, mode: CaptureMode): Boolean {
        line("")
        line("== ${mode.label}: enables ${mode.enables}")
        dir.resolve("ready").delete()
        dir.resolve("done").delete()
        probe.type("capture ${mode.label} ${mode.modes.joinToString(" ").ifEmpty { "none" }}")
        val ready = rig.poll(START_TIMEOUT) { dir.resolve("ready").takeIf { it.isFile } } != null
        if (!ready) {
            line("  did not start within $START_TIMEOUT; status: ${tail(dir.resolve("status.txt"))}; stderr: ${tail(dir.resolve("stderr.txt"))}")
            stopCapture(dir)
            return false
        }
        val capture = dir.resolve("capture.jsonl")
        val query = chunks(capture).filter { it.first == "query" }.map { it.second }
        line("  terminal's answer to CSI ? u: ${if (query.isEmpty()) "none" else describe(concat(query))}")

        val wrong = mutableListOf<String>()
        var measured = 0
        for (char in mode.chars) {
            val arrival = captureArrival(capture) { probe.key(char) }
            val shown = if (char == " ") "SP" else char
            if (!mode.decodeKitty || arrival.bytes == null) {
                line("  ${shown.padEnd(LABEL_WIDTH)} sendKey   ${arrival.text}")
                continue
            }
            val verdict = kittyVerdict(char, arrival.bytes)
            measured++
            if (verdict.wrong) wrong += shown
            line("  ${shown.padEnd(LABEL_WIDTH)} sendKey   ${arrival.text} | ${verdict.text}")
        }
        // Only what arrived is counted: a key that never arrived was not measured.
        if (mode.decodeKitty) line("  WRONG (${wrong.size} of $measured measured, ${mode.chars.size} sent): ${wrong.joinToString(" ").ifEmpty { "none" }}")
        line("  ${"/*".padEnd(LABEL_WIDTH)} pasteText ${captureArrival(capture) { probe.paste(PASTE) }.text}")
        dir.resolve("exited").takeIf { it.isFile }?.let { line("  key-capture.mjs exited before the test stopped it (${tail(it)}): later keys went to the shell") }
        stopCapture(dir)
        return true
    }

    /** What reached the capture after a send: its [bytes] (null when nothing did) and a [text] for the report. */
    private class Arrival(val bytes: ByteArray?, val text: String)

    /** Runs [send] and returns what reached the capture after it. */
    private suspend fun captureArrival(capture: File, send: suspend () -> Unit): Arrival {
        val before = chunks(capture).size
        send()
        rig.poll(ARRIVAL_TIMEOUT) { chunks(capture).takeIf { it.size > before } }
            ?: return Arrival(null, "nothing arrived within $ARRIVAL_TIMEOUT")
        delay(SETTLE)
        val arrived = chunks(capture).drop(before).map { it.second }
        val bytes = concat(arrived)
        return Arrival(bytes, describe(bytes) + if (arrived.size > 1) " (${arrived.size} reads)" else "")
    }

    private suspend fun stopCapture(dir: File) {
        dir.mkdirs()
        dir.resolve("stop").writeText("")
        if (rig.poll(STOP_TIMEOUT) { dir.resolve("done").takeIf { it.isFile } } == null) {
            line("  did not stop within $STOP_TIMEOUT; killing key-capture.mjs")
            runOnMac("pkill", "-f", "key-capture.mjs")
            rig.poll(STOP_TIMEOUT) { dir.resolve("done").takeIf { it.isFile } }
        }
    }

    /** key-capture.mjs's log: (phase, bytes) per read, in order; a torn last line is left out. */
    private fun chunks(capture: File): List<Pair<String, ByteArray>> {
        if (!capture.isFile) return emptyList()
        return capture.readLines().mapNotNull { text ->
            val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val phase = (json["phase"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val hex = (json["hex"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            phase to hexToBytes(hex)
        }
    }

    // --- Claude Code --------------------------------------------------------------------

    private suspend fun claudeMode(probe: Probe, dir: File, installed: Boolean) {
        line("")
        line("== claude: Claude Code under script -r")
        if (!installed) {
            line("  not installed (the install step failed); skipped")
            return
        }
        dir.resolve("done").delete()
        val recording = dir.resolve("claude.rec")
        probe.type("claude")
        val prompt = rig.poll(CLAUDE_START_TIMEOUT) {
            val text = screenText(records(recording).output())
            when {
                PROMPT_MARKERS.any { it in text } -> "prompt"
                dir.resolve("done").isFile -> "exited"
                else -> null
            }
        }
        delay(CLAUDE_SETTLE)
        val startup = records(recording)
        when (prompt) {
            "prompt" -> line("  landed on the prompt (footer seen) after ${startup.size} records")
            "exited" -> line("  exited before the prompt (status ${tail(dir.resolve("done"))}); stderr: ${tail(dir.resolve("stderr.txt"))}")
            else -> line("  no prompt footer within $CLAUDE_START_TIMEOUT: a dialog, or it is still starting")
        }
        if (prompt != "prompt") line("  screen text so far: ${screenText(startup.output()).takeLast(SCREEN_DUMP_CHARS)}")
        line("  private-mode CSIs Claude wrote: ${modeRequests(startup.output())}")
        line("  keyboard-mode sequences Claude wrote, in order: ${keyboardModes(startup.output())}")
        line("  terminal's answers before any key: ${describe(startup.input()).take(ANSWER_CHARS)}")

        if (prompt != "exited") {
            line("  -- / (and * pasted) alone on the empty prompt, each erased with Backspace")
            line("  ${"/".padEnd(LABEL_WIDTH)} sendKey   ${claudeArrival(recording, "/", menu = true) { probe.key("/") }}")
            claudeArrival(recording, null) { probe.special(SpecialKey.BACKSPACE) }
            line("  ${"/".padEnd(LABEL_WIDTH)} pasteText ${claudeArrival(recording, "/", menu = true) { probe.paste("/") }}")
            claudeArrival(recording, null) { probe.special(SpecialKey.BACKSPACE) }
            line("  ${"*".padEnd(LABEL_WIDTH)} pasteText ${claudeArrival(recording, "*") { probe.paste("*") }}")
            claudeArrival(recording, null) { probe.special(SpecialKey.BACKSPACE) }
            line("  -- $WORKAROUND_TEXT as one key event, \"Send / * + = as paste\" on (the four pasted, the rest keys)")
            line("  ${"".padEnd(LABEL_WIDTH)} ${claudeWorkaround(probe, recording)}")
            claudeArrival(recording, null) { repeat(WORKAROUND_TEXT.length) { probe.special(SpecialKey.BACKSPACE) } }
            line("  -- one at a time into the prompt")
            for (char in CLAUDE_CHARS) {
                line("  ${char.padEnd(LABEL_WIDTH)} sendKey   ${claudeArrival(recording, char) { probe.key(char) }}")
            }
            line("  ${"/*".padEnd(LABEL_WIDTH)} pasteText ${claudeArrival(recording, PASTE) { probe.paste(PASTE) }}")
            line("  ${"Return".padEnd(LABEL_WIDTH)} sendKey   ${claudeArrival(recording, null, settle = CLAUDE_RETURN_SETTLE) { probe.key("\n") }}")
            val output = records(recording).output()
            line("  private-mode CSIs Claude wrote in all: ${modeRequests(output)}")
            line("  keyboard-mode sequences Claude wrote, in order: ${keyboardModes(output)}")
        }
        stopClaude(probe, dir)
    }

    /**
     * Runs [send] and returns what reached Claude Code's tty after it (the recording's input) and,
     * when [glyph] is given, whether Claude drew it in its output up to [settle] later; with
     * [menu], whether a slash-command menu appeared.
     */
    private suspend fun claudeArrival(
        recording: File,
        glyph: String?,
        menu: Boolean = false,
        settle: Duration = CLAUDE_KEY_SETTLE,
        send: suspend () -> Unit,
    ): String {
        val before = records(recording).size
        send()
        val arrived = rig.poll(ARRIVAL_TIMEOUT) { records(recording).drop(before).takeIf { new -> new.any { it.direction == 'i' } } }
        delay(settle)
        val after = records(recording).drop(before)
        val input = if (arrived == null) "nothing arrived within $ARRIVAL_TIMEOUT" else describe(after.input())
        val drawn = screenText(after.output())
        val shown = when {
            glyph == null -> "drew: ${drawn.takeLast(DRAWN_CHARS).ifEmpty { "nothing" }}"
            glyph in drawn -> "shown: yes"
            else -> "shown: NO (drew: ${drawn.takeLast(DRAWN_CHARS).ifEmpty { "nothing" }})"
        }
        val slashMenu = if (menu) {
            val commands = SLASH_COMMAND.findAll(drawn).map { it.value }.distinct().toList()
            "; slash menu: ${if (commands.size >= 2) "yes (${commands.take(MENU_SHOWN).joinToString(" ")})" else "no"}"
        } else {
            ""
        }
        return "$input | $shown$slashMenu"
    }

    /**
     * Types [WORKAROUND_TEXT] with the paste setting on and returns what reached Claude Code's tty
     * and what it drew: the whole text, and for each of `/ * + =` whether it was drawn between its
     * neighbours (`a/b`), only somewhere, or not at all. Waits up to [CLAUDE_WORKAROUND_SETTLE]
     * for the whole text to be drawn. A report, not an assertion.
     */
    private suspend fun claudeWorkaround(probe: Probe, recording: File): String {
        val before = records(recording).size
        val sentAt = System.nanoTime()
        // On until the text was drawn or the wait ran out: the sender reads it when it takes the key.
        pasteKeys.set(true)
        val whole = try {
            probe.key(WORKAROUND_TEXT)
            rig.poll(CLAUDE_WORKAROUND_SETTLE) {
                WORKAROUND_TEXT.takeIf { it in screenText(records(recording).drop(before).output()) }
            }
        } finally {
            pasteKeys.set(false)
        }
        val waited = (System.nanoTime() - sentAt) / 1_000_000
        delay(if (whole == null) CLAUDE_KEY_SETTLE else SETTLE)
        val after = records(recording).drop(before)
        val drawn = screenText(after.output())
        val perChar = WORKAROUND_TEXT.withIndex().filter { (_, c) -> c in PASTED_KEYS }.joinToString(", ") { (i, c) ->
            val context = WORKAROUND_TEXT.substring(i - 1, i + 2)
            "$c " + when {
                context in drawn -> "shown"
                c in drawn -> "drawn but not as $context"
                else -> "NOT shown"
            }
        }
        val input = if (after.none { it.direction == 'i' }) "nothing arrived" else describe(after.input())
        val shown = if (whole != null) "yes (within $waited ms)" else "NO (drew: ${drawn.takeLast(DRAWN_CHARS).ifEmpty { "nothing" }})"
        return "$input | whole text shown: $shown; $perChar"
    }

    private suspend fun stopClaude(probe: Probe, dir: File) {
        repeat(2) {
            probe.key("c", KeyModifiers(control = true))
            delay(CTRL_C_GAP)
        }
        if (rig.poll(STOP_TIMEOUT) { dir.resolve("done").takeIf { it.isFile } } != null) {
            line("  exited on Ctrl-C twice")
            return
        }
        val pid = dir.resolve("pid").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        line("  still running after Ctrl-C twice; ${if (pid == null) "no pid file" else "sending SIGTERM to script ($pid)"}")
        if (pid != null) {
            runOnMac("kill", "-TERM", pid)
            if (rig.poll(STOP_TIMEOUT) { dir.resolve("done").takeIf { it.isFile } } == null) runOnMac("kill", "-KILL", pid)
        }
        runOnMac("pkill", "-f", "claude-code/bin/claude")
        rig.poll(STOP_TIMEOUT) { dir.resolve("done").takeIf { it.isFile } } ?: line("  key-probe.sh never finished")
    }

    /** One record of a macOS `script -r` recording: its direction ('s', 'i', 'o', 'e') and bytes. */
    private class Record(val direction: Char, val data: ByteArray)

    private fun List<Record>.input(): ByteArray = concat(filter { it.direction == 'i' }.map { it.data })

    private fun List<Record>.output(): ByteArray = concat(filter { it.direction == 'o' }.map { it.data })

    /**
     * The records of a `script -r` file: each a little-endian header (u64 length, u64 seconds,
     * u32 microseconds, u32 direction) then its data (FreeBSD script.c, `struct stamp`). Stops at a
     * torn last record; notes once in the report when the format is not what it expects.
     */
    private fun records(file: File): List<Record> {
        if (!file.isFile) return emptyList()
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val records = mutableListOf<Record>()
        while (buffer.remaining() >= RECORD_HEADER) {
            val length = buffer.getLong()
            buffer.getLong()
            buffer.getInt()
            val direction = buffer.getInt()
            if (direction.toChar() !in "sioe" || length < 0 || length > buffer.remaining()) {
                if (direction.toChar() !in "sioe" && !recordingFormatNoted) {
                    recordingFormatNoted = true
                    line("  unexpected recording format at byte ${buffer.position() - RECORD_HEADER}: ${toHex(bytes.copyOf(minOf(bytes.size, HEADER_DUMP)))}")
                }
                break
            }
            val data = ByteArray(length.toInt()).also { buffer.get(it) }
            records += Record(direction.toChar(), data)
        }
        return records
    }

    private var recordingFormatNoted = false

    // --- Helpers ------------------------------------------------------------------------

    /** A decoded arrival under kitty flags: [wrong] by the rules in [kittyVerdict], and its [text]. */
    private class Verdict(val wrong: Boolean, val text: String)

    /**
     * Decodes what [char]'s `sendKey` delivered under kitty flags: a CSI u (key number, shifted and
     * base-layout keys, modifiers), an xterm modifyOtherKeys `CSI 27 ; m ; c ~`, or plain text.
     * WRONG when the key number is not [char]'s US ANSI base key (its own code point when it needs
     * no Shift, else the unshifted key's: `*` → `8`, `A` → `a`), when a character that needs no
     * Shift carries Shift, or when the bytes are neither an encoding nor the character itself.
     */
    private fun kittyVerdict(char: String, bytes: ByteArray): Verdict {
        val c = char.single()
        val needsShift = c in US_SHIFTED || c.isUpperCase()
        val expected = US_SHIFTED[c] ?: c.lowercaseChar()
        val text = String(bytes, Charsets.ISO_8859_1)
        CSI_U.matchEntire(text)?.let { match ->
            val (key, shifted, base, mods) = match.destructured
            val modifiers = mods.ifEmpty { "1" }.toInt()
            val shift = (modifiers - 1) and 1 != 0
            val problems = buildList {
                if (key.toInt() != expected.code) add("key ${key.toInt()} is not ${expected.code} ${glyph(expected.code)}")
                if (shift && !needsShift) add("Shift on a key that needs none")
            }
            val decoded = "CSI u: key $key ${glyph(key.toInt())}" +
                (if (shifted.isNotEmpty()) ", shifted $shifted ${glyph(shifted.toInt())}" else "") +
                (if (base.isNotEmpty()) ", base $base ${glyph(base.toInt())}" else "") +
                ", mods ${modifierNames(modifiers)}"
            return Verdict(problems.isNotEmpty(), decoded + if (problems.isEmpty()) "" else " WRONG (${problems.joinToString("; ")})")
        }
        MODIFY_OTHER_KEYS.matchEntire(text)?.let { match ->
            val (mods, code) = match.destructured
            val shift = (mods.toInt() - 1) and 1 != 0
            val wrong = shift && !needsShift
            return Verdict(wrong, "modifyOtherKeys: code $code ${glyph(code.toInt())}, mods ${modifierNames(mods.toInt())}" + if (wrong) " WRONG (Shift on a key that needs none)" else "")
        }
        return if (text == char) {
            Verdict(false, "plain text")
        } else {
            Verdict(true, "WRONG (neither an encoding nor the character)")
        }
    }

    /** Prints the Mac's keyboard input source, as HIToolbox's defaults hold it. */
    private suspend fun reportInputSource() {
        for (key in listOf("AppleCurrentKeyboardLayoutInputSourceID", "AppleSelectedInputSources")) {
            val result = runOnMac("defaults", "read", "com.apple.HIToolbox", key)
            val value = if (result.ok) result.stdout.replace(Regex("\\s+"), " ") else "${result.summary}: ${result.stderr.lines().firstOrNull().orEmpty()}"
            line("keyboard input source, $key: $value")
        }
    }

    /** A command's outcome: [ok] when it exited 0, its [stdout] and [stderr], and a [summary] when it did not. */
    private class Outcome(val ok: Boolean, val stdout: String, val stderr: String, val summary: String)

    /** Runs a command on this Mac with a bound. */
    private suspend fun runOnMac(vararg command: String): Outcome = withContext(Dispatchers.IO) {
        try {
            val stderrFile = File.createTempFile("key-bytes", ".stderr")
            try {
                val process = ProcessBuilder(*command).redirectError(stderrFile).start()
                val stdout = async { process.inputStream.bufferedReader().readText().trim() }
                if (!process.waitFor(COMMAND_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@withContext Outcome(false, "", stderrFile.readText().trim(), "${command[0]} timed out after $COMMAND_TIMEOUT")
                }
                val out = stdout.await()
                val err = stderrFile.readText().trim()
                Outcome(process.exitValue() == 0, out, err, "${command[0]} exit ${process.exitValue()}")
            } finally {
                stderrFile.delete()
            }
        } catch (e: java.io.IOException) {
            Outcome(false, "", "", "${command[0]} could not run (${e.javaClass.simpleName})")
        }
    }

    private fun line(text: String) {
        lines += text
        println("key-bytes: $text")
    }

    private companion object {
        const val REPORT_FILE = "key-bytes-report.txt"
        const val PASTE = "/*"
        const val WORKAROUND_TEXT = "a/b*c+d=e"
        const val LABEL_WIDTH = 6
        const val RECORD_HEADER = 24
        const val HEADER_DUMP = 48
        const val SCREEN_DUMP_CHARS = 1500
        const val ANSWER_CHARS = 400
        const val DRAWN_CHARS = 60
        const val MENU_SHOWN = 3
        val SHORT_CHARS = listOf("a", "/", "*", "-", "+", "=", ".")
        val CLAUDE_CHARS = listOf("a", "/", "*", ".", "1", "?", "A", "~", "|", "-", "+", "=")
        /** US ANSI: each character that needs Shift, and the unshifted key it is on (letters aside). */
        val US_SHIFTED: Map<Char, Char> = "!1@2#3$4%5^6&7*8(9)0_-+={[}]|\\:;\"'<,>.?/~`".chunked(2).associate { it[0] to it[1] }

        /** CSI key[:shifted[:base]] [; mods[:event]] [; text] u. */
        val CSI_U = Regex("\u001b\\[(\\d+)(?::(\\d*))?(?::(\\d+))?(?:;(\\d*)(?::\\d+)?)?(?:;[\\d:]*)?u")
        val MODIFY_OTHER_KEYS = Regex("\u001b\\[27;(\\d+);(\\d+)~")
        val MODIFIER_NAMES = listOf("shift", "alt", "ctrl", "super", "hyper", "meta", "caps", "num")

        fun modifierNames(modifiers: Int): String {
            val bits = modifiers - 1
            val names = MODIFIER_NAMES.filterIndexed { index, _ -> bits and (1 shl index) != 0 }
            return "$modifiers" + if (names.isEmpty()) " (none)" else " (${names.joinToString("+")})"
        }

        /** A key number as its character when printable ASCII, else U+XXXX. */
        fun glyph(code: Int): String = if (code in 0x20..0x7e) "'${code.toChar()}'" else "U+%04X".format(code)

        val ALL_ASCII = listOf(" ") + (0x21..0x7e).map { it.toChar().toString() }

        val ESC = "\u001b"
        val CAPTURE_MODES = listOf(
            CaptureMode("plain", emptyList(), "nothing"),
            CaptureMode("paste", listOf("paste"), "ESC[?2004h"),
            CaptureMode("mok2", listOf("mok2"), "ESC[>4;2m"),
            CaptureMode("kitty1", listOf("kitty1"), "ESC[>1u"),
            CaptureMode("kitty3", listOf("kitty3"), "ESC[>3u"),
            CaptureMode("kitty31", listOf("kitty31"), "ESC[>31u"),
            CaptureMode("kitty5", listOf("kitty5", "mok2"), "ESC[>5u ESC[>4;2m (Claude Code's)", ALL_ASCII, decodeKitty = true),
        )

        /** Claude Code's prompt footer ("? for shortcuts") and its placeholder ("Try …"). */
        val PROMPT_MARKERS = listOf("for shortcuts", "Try \"")
        val SLASH_COMMAND = Regex("/[a-z][a-z-]{2,}")

        /** A mode request: CSI with a private marker (? > = <), then parameters, then a final letter. */
        val MODE_REQUEST = Regex("\u001b\\[[?>=<][0-9;:]*[a-zA-Z]")

        /**
         * A keyboard-mode sequence: kitty push, pop, set and query (CSI > N u, CSI < N u,
         * CSI = N ; M u, CSI ? u), bracketed paste and focus reporting (CSI ? 2004/1004 h/l),
         * modifyOtherKeys (CSI > 4 ; N m) and modifyOtherKeys query (CSI ? 4 m).
         */
        val KEYBOARD_MODE = Regex("\u001b\\[(?:[>=<][0-9;:]*u|\\?u|\\?(?:2004|1004)[hl]|>4(?:;[0-9]*)?m|\\?4m)")

        val START_TIMEOUT = 15.seconds
        val CLAUDE_START_TIMEOUT = 45.seconds
        val ARRIVAL_TIMEOUT = 1500.milliseconds
        val SETTLE = 250.milliseconds
        val CLAUDE_SETTLE = 1.seconds
        val CLAUDE_KEY_SETTLE = 700.milliseconds
        val CLAUDE_WORKAROUND_SETTLE = 5.seconds
        val FRESH_LINE_GAP = 500.milliseconds
        val CLAUDE_RETURN_SETTLE = 3.seconds
        val CTRL_C_GAP = 500.milliseconds
        val STOP_TIMEOUT = 8.seconds
        val COMMAND_TIMEOUT = 10.seconds

        /** [path] in double quotes for the shell; fails on a path the quotes would not hold. */
        fun quoted(path: String): String {
            require(path.none { it in "\"$`\\\n" }) { "a path the test cannot quote for the shell" }
            return "\"$path\""
        }

        /** probe.env's KEY=value lines; values were written with bash's printf %q, unescaped here. */
        fun readEnv(file: File): Map<String, String> = file.readLines().mapNotNull { text ->
            val at = text.indexOf('=')
            if (at <= 0) null else text.substring(0, at) to text.substring(at + 1).trim('\'').replace(Regex("\\\\(.)"), "$1")
        }.toMap()

        fun concat(parts: List<ByteArray>): ByteArray =
            ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** Hex and a readable form: ESC, \r, \n, \t, printable ASCII as is, anything else \xNN. */
        fun describe(bytes: ByteArray): String {
            if (bytes.isEmpty()) return "(no bytes)"
            return "${toHex(bytes)} = \"${readable(bytes)}\""
        }

        fun readable(bytes: ByteArray): String = bytes.joinToString("") { byte ->
            when (val b = byte.toInt() and 0xff) {
                0x1b -> "ESC"
                0x0d -> "\\r"
                0x0a -> "\\n"
                0x09 -> "\\t"
                in 0x20..0x7e -> b.toChar().toString()
                else -> "\\x%02x".format(b)
            }
        }

        /** The distinct mode requests in [output], in first-seen order, with counts. */
        fun modeRequests(output: ByteArray): String {
            val text = String(output, Charsets.ISO_8859_1)
            val counts = linkedMapOf<String, Int>()
            MODE_REQUEST.findAll(text).forEach { counts.merge(it.value.replace(ESC, "ESC"), 1, Int::plus) }
            return if (counts.isEmpty()) "none" else counts.entries.joinToString(", ") { (seq, n) -> if (n == 1) seq else "$seq x$n" }
        }

        /** Every keyboard-mode sequence in [output], in order, as escapes. */
        fun keyboardModes(output: ByteArray): String {
            val found = KEYBOARD_MODE.findAll(String(output, Charsets.ISO_8859_1)).map { it.value.replace(ESC, "ESC") }.toList()
            return if (found.isEmpty()) "none" else found.joinToString(" ")
        }

        /** The text Claude drew: escape sequences and control characters dropped, runs of spaces collapsed. */
        fun screenText(output: ByteArray): String = String(output, Charsets.UTF_8)
            .replace(Regex("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)"), "")
            .replace(Regex("\u001b[P_^][^\u001b]*\u001b\\\\"), "")
            .replace(Regex("\u001b\\[[0-9;:?<=>]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001b[()][0-9A-Za-z]"), "")
            .replace(Regex("\u001b."), "")
            .replace(Regex("[\u0000-\u001f\u007f]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

        /** The last line of [file], or "none". */
        fun tail(file: File): String = file.takeIf { it.isFile }?.readLines()?.lastOrNull { it.isNotBlank() } ?: "none"
    }
}
