package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.stream.AutoFitter
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.FitNotice
import dev.bugobliterator.itermbuddy.core.stream.FitViewport
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.sessionsOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Fit to phone report of the real-Mac CI: what a real iTerm2 3.7.7 does with the app's own
 * [AutoFitter] (the toggle on) for a tall phone, on the runner's small screen, where iTerm2 caps a
 * window's rows to what its screens hold (`PseudoTerminal.m` `safelySetSessionSize:rows:columns:`).
 *
 * With a live stream of the first terminal session open in the app's [StreamController] (the video
 * output records instead of decoding), it reports (`fit:` lines, numbers only):
 * - the stream's first grid, image size, cell and `canResize`;
 * - **tall phone**: the fit for a 1080×2200 viewport (60 columns and the rows that fill it), the
 *   grid the next config shows, and the corrective step (fewer columns, the rows the Mac gave) and
 *   its result, as the [AutoFitter] logs them;
 * - **landscape**: the refit for 2200×1080 (120 columns);
 * - **split pane**: the session split top/bottom with the bundled `it2 session split`, run from the
 *   JVM ("Allow all apps to connect", which the Claude Code status step turns on) or else typed
 *   into the session, then the toggle off and on again to refit the now shorter pane.
 *
 * Afterwards, whatever happened (`finally`): every added pane is closed (`it2 session close
 * --force`) and checked gone from the tree, the session is resized back to its first grid, and the
 * stream is stopped. The fitter waits 10 s for a result ([RESULT_TIMEOUT]).
 *
 * It reports and does not judge; it fails only when the stream could not start or show a config.
 */
class RealIterm2FitTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun reportsFitToPhoneOnTheRunnersScreen() = runBlocking {
        val nowMillis = { System.nanoTime() / NANOS_PER_MILLI }
        val video = FakeVideoOutput(nowMillis).apply {
            configureResult = { config ->
                if (hasCompleteHevcParameterSets(config.codecExtradata)) VideoReadiness.Ready else VideoReadiness.WaitingForConfig
            }
        }
        val forward = ForwardingSink()
        val client = rig.client(forward)
        val controller = StreamController(link = client, video = video, scope = rig.scope, nowMillis = nowMillis, log = {})
        forward.target = controller
        rig.connect(client)
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val session = rig.firstTerminalSession(client)
        val guid = session.guid

        controller.open(session)
        val live = rig.poll { controller.state.value as? LiveViewState.Live }
            ?: failWith("the controller is not Live within ${rig.requestTimeout}: ${controller.state.value}")
        report("fit: first config ${describe(live.config)}")
        val firstGrid = live.config.columns to live.config.rows
        val before = sessionsOf(rig.sessionTree(client)).map { it.guid }.toSet()
        try {
            runCases(client, controller, linkId, guid, before)
        } finally {
            cleanUp(client, controller, guid, before, firstGrid)
        }
    }

    /** The cases; see the class comment. */
    private suspend fun runCases(
        client: dev.bugobliterator.itermbuddy.core.CompanionClient,
        controller: StreamController,
        linkId: Long,
        guid: String,
        before: Set<String>,
    ) {

        val logs = CopyOnWriteArrayList<String>()
        val enabled = MutableStateFlow(true)
        val fitter = AutoFitter(
            link = client,
            enabled = enabled,
            active = controller.activeStream,
            decoderMax = { null },
            sizeSupported = null,
            scope = rig.scope,
            resultTimeout = RESULT_TIMEOUT,
            log = { line ->
                logs += line
                report("fit: ${line.replace(guid, "<session>")}")
            },
        )
        val notices = CopyOnWriteArrayList<FitNotice>()
        rig.scope.launch { fitter.notices.collect { notices += it; report("fit: notice: ${it.text}") } }

        suspend fun case(label: String, width: Int, height: Int, refit: Boolean = false) {
            report("fit: === $label: viewport ${width}x$height ===")
            val from = logs.size
            if (refit) {
                enabled.value = false
                delay(SETTLE_PAUSE)
                enabled.value = true
            }
            fitter.setViewport(FitViewport(guid, width, height))
            val first = rig.poll(RESULT_TIMEOUT + OUTCOME_SLACK) { logs.drop(from).firstOrNull(::isFirstOutcome) }
            if (first == null) {
                report("fit: $label: no outcome within ${RESULT_TIMEOUT + OUTCOME_SLACK}")
                return
            }
            // A corrective step is sent right after the first result; wait for its own result.
            delay(SETTLE_PAUSE)
            if (logs.drop(from).any { "corrective sent" in it }) {
                rig.poll(RESULT_TIMEOUT + OUTCOME_SLACK) { logs.drop(from).firstOrNull { "corrective requested" in it } }
                    ?: report("fit: $label: no corrective result within ${RESULT_TIMEOUT + OUTCOME_SLACK}")
            }
            report("fit: $label: final config ${controller.activeStream.value?.config?.let(::describe) ?: "none"}")
        }

        case("tall phone", 1080, 2200)
        case("landscape", 2200, 1080)
        case("tall phone again", 1080, 2200)

        // Split the session top/bottom with it2, then refit the shorter pane.
        val rowsBefore = controller.activeStream.value?.config?.rows ?: 0
        val sender = InputSender(
            link = client,
            scope = rig.scope,
            isCurrent = { target -> target.linkId == rig.linkId.value && client.state.value is ClientState.Connected },
            log = { report("fit: input: $it") },
        )
        if (guid.all { it.isLetterOrDigit() || it == '-' }) {
            // The bundled it2 from the JVM ("Allow all apps to connect", as the browser probe);
            // typed into the session when that does not split it.
            val run = runIt2("session", "split", "--session", guid)
            report("fit: split pane: it2 session split from the JVM: $run")
            var split = rig.poll { controller.activeStream.value?.config?.takeIf { it.rows < rowsBefore } }
            if (split == null) {
                val stderr = diagnosticsDir().resolve(IT2_STDERR_FILE).also { it.delete() }
                sender.typeThenReturn(InputTarget(linkId, watchEpoch = 0, sessionGuid = guid), "\"$IT2\" session split --session $guid 2>>\"${stderr.path}\"")
                split = rig.poll { controller.activeStream.value?.config?.takeIf { it.rows < rowsBefore } }
                if (split == null) report("fit: split pane: typed it2 did not shrink the grid either (it2 stderr: ${stderrSummary(stderr)})")
            }
            if (split != null) {
                report("fit: split pane: the session is now ${describe(split)}")
                case("split pane, tall phone", 1080, 2200, refit = true)
            }
        } else {
            report("fit: split pane skipped: the session GUID is not safe to pass to it2")
        }

        report("fit: summary: ${logs.size} log lines, ${notices.size} notices")
    }

    /**
     * Whatever happened: closes every pane the test added (`it2 session close --force`) and checks
     * the tree no longer has it, puts the session back at its first grid, and stops the stream.
     * Reports each step; never throws over the test's own outcome.
     */
    private suspend fun cleanUp(
        client: dev.bugobliterator.itermbuddy.core.CompanionClient,
        controller: StreamController,
        guid: String,
        before: Set<String>,
        firstGrid: Pair<Int, Int>,
    ) {
        try {
            val added = sessionsOf(rig.sessionTree(client)).map { it.guid }.filter { it !in before }
            for (pane in added) {
                if (pane.all { it.isLetterOrDigit() || it == '-' }) {
                    report("fit: cleanup: it2 session close: ${runIt2("session", "close", "--force", "--session", pane)}")
                }
            }
            if (added.isNotEmpty()) {
                val gone = rig.poll { sessionsOf(rig.sessionTree(client)).none { it.guid in added }.takeIf { it } }
                report("fit: cleanup: ${added.size} added pane(s) ${if (gone == true) "closed" else "STILL OPEN after ${rig.requestTimeout}"}")
            }
            val (columns, rows) = firstGrid
            val stream = controller.activeStream.value
            if (stream?.config?.let { it.columns to it.rows } != firstGrid && stream?.config?.canResize == true) {
                client.send(PhoneMessage.ResizeSession(guid, columns, rows), onLink = stream.linkId)
                val restored = rig.poll { controller.activeStream.value?.config?.takeIf { it.columns == columns && it.rows == rows } }
                report("fit: cleanup: restore ${columns}x$rows: ${restored?.let { "done" } ?: "the grid is ${controller.activeStream.value?.config?.let { "${it.columns}x${it.rows}" }}"}")
            }
        } catch (e: Exception) {
            // A request that failed or timed out: its class only.
            report("fit: cleanup failed: ${e.javaClass.simpleName}")
        } finally {
            controller.close()
        }
    }

    /** Runs the bundled it2 with [args] for at most [IT2_TIMEOUT]: its exit status and stderr's last lines (it2's own messages). */
    private suspend fun runIt2(vararg args: String): String = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(IT2) + args).apply { environment()["ITERM2_COOKIE"] = PLACEHOLDER_COOKIE }.start()
        } catch (e: java.io.IOException) {
            return@withContext "could not start: ${e.javaClass.simpleName}"
        }
        process.outputStream.close()
        // Stdout is drained and dropped: it could name sessions.
        val out = async { process.inputStream.bufferedReader().readText().length }
        val err = async { process.errorStream.bufferedReader().readText() }
        val finished = process.waitFor(IT2_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        val stderr = err.await().lines().filter { it.isNotBlank() }.takeLast(STDERR_LINES).joinToString(" | ")
        "exit ${if (finished) process.exitValue() else "timed out"}, stdout ${out.await()} chars, stderr ${stderr.ifEmpty { "empty" }}"
    }

    /** Forwards every stream input to [target], the controller. */
    private class ForwardingSink : StreamSink {
        @Volatile var target: StreamSink? = null

        override suspend fun accept(input: StreamInput) {
            checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val SETTLE_PAUSE = 1.seconds

        /** How long the fitter waits for the Mac's config to show a resize. */
        val RESULT_TIMEOUT = 10.seconds

        /** Beyond [RESULT_TIMEOUT]: the settle delay and the send. */
        val OUTCOME_SLACK = 5.seconds
        const val IT2 = "/Applications/iTerm.app/Contents/Resources/utilities/it2"
        const val IT2_STDERR_FILE = "fit-it2-stderr.txt"
        const val PLACEHOLDER_COOKIE = "buddy-ci-no-cookie"
        val IT2_TIMEOUT = 20.seconds

        /** A first fit's outcome line from the [AutoFitter]'s log. */
        fun isFirstOutcome(line: String): Boolean =
            ("fit requested" in line && ("got" in line || "stayed" in line || "no matching result" in line)) || "cannot resize" in line ||
                "not sent" in line || "already at" in line

        fun describe(config: StreamConfig): String {
            val cell = config.cellGeometry?.let { "cell ${it.cellWidth}x${it.cellHeight}" } ?: "no cell geometry"
            return "${config.columns}x${config.rows} cells, ${config.pixelWidth}x${config.pixelHeight} px, $cell, " +
                "scale ${config.scale}, canResize ${config.canResize}, generation ${config.generationId}"
        }

        fun diagnosticsDir(): File {
            val workDir = File(RealIterm2Rig.requiredEnv("REAL_ITERM2_PAIRING_FILE")).absoluteFile.parentFile
                ?: error("REAL_ITERM2_PAIRING_FILE has no parent directory")
            return workDir.resolve("diagnostics").also { it.mkdirs() }
        }

        /** How many lines it2 wrote to stderr, and the last few: it2's own messages, never terminal content. */
        fun stderrSummary(file: File): String {
            if (!file.isFile) return "none"
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return "empty"
            return "${lines.size} lines: " + lines.takeLast(STDERR_LINES).joinToString(" | ")
        }

        const val STDERR_LINES = 10
    }
}
