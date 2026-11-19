package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.sessionsOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Claude Code status probe of the real-Mac CI: Claude Code's state reaches the phone through
 * the session subtitle, read with `fetchSessionTree` as the Sessions list re-fetches it.
 *
 * `ci/real-iterm2/seed-claude-status.sh` made the default profile a Dynamic Profile whose Subtitle
 * is `\(status)`, the session's status text, and turned on what the bundled it2 needs to reach
 * iTerm2 from a session (the API socket, the session cookie, "Allow all apps to connect"). The
 * test types, through the app's [InputSender] as [RealIterm2SessionInputTest] does, one line per
 * Claude Code hook event into the first terminal session: the event's JSON piped into
 * `<it2> handle-agent-hook`, the program Claude Code's hook configuration names. It runs in the
 * session, so it addresses the session by `TERM_SESSION_ID` and authenticates with
 * `ITERM2_COOKIE`, as a real hook does. After each line the test polls the session tree until the
 * session's subtitle, trimmed and lowercased, is the state the hook decides
 * (AgentHookEvent.swift:229-455):
 * - `SessionStart` → `idle`. A session boundary, so a hook that cannot reach iTerm2 says why on
 *   stderr, which goes to the diagnostics (`claude-status-it2-stderr.txt`);
 * - `UserPromptSubmit` → `working`;
 * - `PermissionRequest` for the `Bash` tool → `waiting`;
 * - `Stop` → `done`;
 * - `StopFailure` → `error`. Typing this line turns `done` to `idle` first (a key press is the
 *   owner seeing the result, iTermTabStatusController.swift:256-272), then the hook says `error`.
 *
 * A subtitle change does not push a session tree (iTerm2 pushes one when a session ends, the
 * session count changes or a presentation name changes, CompanionHostBridge.swift:191-201), so
 * the phone must re-fetch. The test records every unsolicited session tree that arrives while it
 * runs (none expected from the status; a job name in the session's title can still push one) and
 * logs their count and what each said about the session.
 *
 * When a step times out, the test asks it2 directly, in the session: `it2 session get-status` (is
 * the status stored, whatever the subtitle shows?) and `it2 set-status --status probe` (does the
 * subtitle follow a status set without the hook?), and puts both answers in the failure.
 *
 * Runs only under `realIterm2Test` with `REAL_ITERM2=1`; skipped otherwise. It prints states,
 * counts and lengths, never terminal content or what was typed; a subtitle that is not a known
 * state is printed by its length.
 */
class RealIterm2ClaudeStatusTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun hookEventsReachThePhoneAsTheSessionSubtitle() = runBlocking {
        val client = rig.connect()
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val session = rig.firstTerminalSession(client)
        val guid = session.guid
        val diagnostics = diagnosticsDir()
        val stderrFile = diagnostics.resolve(IT2_STDERR_FILE).also { it.delete() }

        val pushes = AtomicInteger()
        val pushedStates = CopyOnWriteArrayList<String>()
        rig.scope.launch {
            client.events.collect { message ->
                if (message is MacMessage.SessionTreeMessage) {
                    pushes.incrementAndGet()
                    val pushed = sessionsOf(message.tree).firstOrNull { it.guid == guid }
                    val state = pushed?.let { stateOf(it.subtitle) } ?: "session absent"
                    pushedStates += state
                    report("unsolicited session tree #${pushes.get()}: the session's subtitle reads $state")
                }
            }
        }

        report("before any hook: subtitle ${stateOf(subtitleOf(client, guid))}")

        val senderLog = CopyOnWriteArrayList<String>()
        val sender = InputSender(
            link = client,
            scope = rig.scope,
            isCurrent = { target -> target.linkId == rig.linkId.value && client.state.value is ClientState.Connected },
            log = { line ->
                senderLog += line
                report("input: $line")
            },
        )
        val target = InputTarget(linkId, watchEpoch = 0, sessionGuid = guid)
        val shell = Shell(sender, target, stderrFile)

        for (step in HOOK_STEPS) {
            shell.run("printf '%s' '${step.payload}' | ${quoted(IT2)} handle-agent-hook")
            report("typed the ${step.event} hook line")
            var last = ""
            val reached = rig.poll {
                subtitleOf(client, guid).also { last = it }.takeIf { stateOf(it) == step.expected }
            }
            if (reached == null) {
                report("${step.event}: the subtitle reads ${stateOf(last)}, not ${step.expected}, after ${rig.requestTimeout}")
                val diagnosis = diagnose(client, shell, guid, diagnostics, stderrFile)
                failWith(
                    "${step.event} did not reach the phone: the subtitle reads ${stateOf(last)} after ${rig.requestTimeout}, " +
                        "expected ${step.expected}. $diagnosis",
                )
            }
            report("${step.event}: the subtitle reads ${step.expected}")
        }

        report("unsolicited session trees while the hooks ran: ${pushes.get()} ${pushedStates.toList()} (the phone must re-fetch)")
        report("it2 stderr: ${stderrSummary(stderrFile)}")
        assertEquals("the input sender reported a problem: $senderLog", emptyList<String>(), senderLog.toList())
        assertTrue("still Connected", client.state.value is ClientState.Connected)
    }

    /** One hook event: its name, the JSON Claude Code writes to the hook's stdin, and the subtitle it leads to. */
    private class HookStep(val event: String, val payload: String, val expected: String)

    /** Types command lines into the session, each with it2's stderr appended to [stderrFile]. */
    private class Shell(private val sender: InputSender, private val target: InputTarget, private val stderrFile: File) {
        fun run(command: String) = sender.typeThenReturn(target, "$command 2>>${quoted(stderrFile.path)}")
    }

    private suspend fun subtitleOf(client: CompanionClient, guid: String): String {
        val tree = rig.sessionTree(client)
        return sessionsOf(tree).firstOrNull { it.guid == guid }?.subtitle ?: failWith("the session left the tree")
    }

    /**
     * Asks it2 itself, in the session, what is wrong: the stored status (`session get-status`),
     * then whether a status set directly (`set-status`, the fallback to the hook) shows in the
     * subtitle. Returns a sentence for the failure; prints states only.
     */
    private suspend fun diagnose(client: CompanionClient, shell: Shell, guid: String, diagnostics: File, stderrFile: File): String {
        if (!guid.all { it.isLetterOrDigit() || it == '-' }) return "no diagnosis: the session GUID is not safe to type into a shell"
        val statusFile = diagnostics.resolve(GET_STATUS_FILE).also { it.delete() }
        shell.run("${quoted(IT2)} session get-status --session $guid >${quoted(statusFile.path)}")
        val stored = rig.poll { statusFile.takeIf { it.isFile && it.length() > 0 }?.let(::storedStatus) }
        report("it2 session get-status: ${stored ?: "no answer"}")

        shell.run("${quoted(IT2)} set-status --session $guid --status $PROBE_STATUS")
        var last = ""
        val probed = rig.poll { subtitleOf(client, guid).also { last = it }.takeIf { stateOf(it) == PROBE_STATUS } } != null
        report("it2 set-status --status $PROBE_STATUS: the subtitle ${if (probed) "followed" else "reads ${stateOf(last)}"}")

        val stderr = stderrSummary(stderrFile)
        report("it2 stderr: $stderr")
        return "it2 session get-status says ${stored ?: "nothing within ${rig.requestTimeout}"}; a direct set-status " +
            (if (probed) "does show in the subtitle (the hook's path or decision is at fault)" else "does not show either (it2 cannot reach iTerm2, or the session is not on the \\(status) profile)") +
            "; it2 stderr: $stderr. See ci/real-iterm2/README.md (Known risks: the status probe)."
    }

    /** The state fields of `it2 session get-status`'s JSON; the detail is left out. */
    private fun storedStatus(file: File): String = try {
        val json = Json.parseToJsonElement(file.readText()) as? JsonObject
        if (json == null) {
            "not a JSON object (${file.length()} bytes)"
        } else {
            STATUS_FIELDS.joinToString { field ->
                val value = (json[field] as? JsonPrimitive)?.contentOrNull
                "$field=${if (field == "status") value?.let(::stateOf) else value}"
            }
        }
    } catch (e: IllegalArgumentException) {
        "unreadable (${file.length()} bytes, ${e.javaClass.simpleName})"
    }

    private companion object {
        /** The it2 iTerm2 bundles (`ITERM2_IT2` in `ci/real-iterm2/common.sh`). */
        const val IT2 = "/Applications/iTerm.app/Contents/Resources/utilities/it2"
        const val IT2_STDERR_FILE = "claude-status-it2-stderr.txt"
        const val GET_STATUS_FILE = "claude-status-get-status.json"
        const val PROBE_STATUS = "probe"
        const val STDERR_LINES = 20
        val STATUS_FIELDS = listOf("status", "background_tasks", "turn_open")

        val HOOK_STEPS = listOf(
            HookStep("SessionStart", """{"hook_event_name":"SessionStart"}""", "idle"),
            HookStep("UserPromptSubmit", """{"hook_event_name":"UserPromptSubmit"}""", "working"),
            HookStep("PermissionRequest", """{"hook_event_name":"PermissionRequest","tool_name":"Bash"}""", "waiting"),
            HookStep("Stop", """{"hook_event_name":"Stop"}""", "done"),
            HookStep("StopFailure", """{"hook_event_name":"StopFailure"}""", "error"),
        )

        /** What double quotes do not keep literal in a shell, and the end of the line. */
        val UNQUOTABLE = setOf('"', '$', '`', '\\', '\n')

        val KNOWN_STATES = setOf("", "idle", "working", "waiting", "done", "error", PROBE_STATUS)

        /** The subtitle as a state: trimmed and lowercased when it is one, else only its length. */
        fun stateOf(subtitle: String): String {
            val state = subtitle.trim().lowercase()
            return when {
                state !in KNOWN_STATES -> "other(len=${subtitle.length})"
                state.isEmpty() -> "empty"
                else -> state
            }
        }

        /** `$WORK_DIR/diagnostics`, beside the pairing file (`ci/real-iterm2/common.sh`); the workflow uploads it. */
        fun diagnosticsDir(): File {
            val workDir = File(RealIterm2Rig.requiredEnv("REAL_ITERM2_PAIRING_FILE")).absoluteFile.parentFile
                ?: error("REAL_ITERM2_PAIRING_FILE has no parent directory")
            return workDir.resolve("diagnostics").also { it.mkdirs() }
        }

        /** [path] in double quotes for the shell; fails on a path the quotes would not hold. */
        fun quoted(path: String): String {
            require(path.none { it in UNQUOTABLE }) { "a path the test cannot quote for the shell" }
            return "\"$path\""
        }

        /** How many lines it2 wrote to stderr, and the last few: it2's own messages, never terminal content. */
        fun stderrSummary(file: File): String {
            if (!file.isFile) return "none"
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return "empty"
            return "${lines.size} lines: " + lines.takeLast(STDERR_LINES).joinToString(" | ")
        }
    }
}
