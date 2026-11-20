package dev.bugobliterator.itermbuddy.realiterm2

import com.sun.net.httpserver.HttpServer
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.MediaFrameException
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.macRevisionOf
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.sessionsOf
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The browser probe of the real-Mac CI: what scrolls a web page in a real iTerm2 3.7.7 browser
 * session, and what does not. The owner's report: on a browser session a drag on the phone moves
 * nothing, while taps click.
 *
 * `ci/real-iterm2/install-browser-plugin.sh` installed iTerm2's Browser Plugin and the Dynamic
 * Profile `buddy-ci-browser` (a browser profile whose Initial URL is this test's page server,
 * `127.0.0.1:8793`). The test serves its own synthetic page from the JVM, several screens tall,
 * whose script beacons back to the server on load (its viewport and devicePixelRatio), on every
 * scroll (`scrollY`, and an inner scroller's `scrollTop`), click (`clientX`/`clientY`), wheel
 * event (where WebKit placed it and its deltas) and key, and every 250 ms with its scroll state.
 * It opens a window of that profile ([BrowserWindows]: the bundled it2 run from the test, then
 * typed into the first terminal session, then AppleScript, each attempt's output printed), streams the browser session through the app's own [StreamController] and drives it through the
 * app's own [BrowserDriver] and [InputSender]. Each step loads a fresh page (`browserLoad`), so it
 * starts at the top, and then:
 *
 * - **tap**: [BrowserDriver.tap] at a known image pixel; the click beacon proves the rig and gives
 *   the frame scale (image px per CSS px);
 * - **raw**: one `browserScroll` straight to the link with deltaY −300 and then −1200 encoded px
 *   (content up: the page scrolls down), then +600 (back up);
 * - **drag**: [BrowserDriver.scroll] as a phone drag makes it (one event every 16 ms at a phone's
 *   fit density, flushed every 40 ms), then a fling;
 * - **small**: runs of `browserScroll` steps of 1, 2, 3 and 4 encoded px, for the Mac's rounding
 *   (`deltaY / frameScale` rounded to whole points);
 * - **moved**: the window moved to a few origins (`it2 window move`), one raw scroll each, to see
 *   whether the wheel's location offset follows the window's place on screen;
 * - **inner**: a page whose content scrolls in a full-window `overflow: auto` element, not the
 *   document (common in web apps): a raw scroll at its centre;
 * - **keys**: Page Down, Down ×5 and Space through `sendKey` (the browser key path), on both pages;
 * - **key drag**: the app's drag with "Scroll web pages with keys" on, with the window moved (where
 *   raw scrolls land off target), on both pages, without and then with a tap first;
 * - **slow key drag**: a slow phone drag through the keys at the moved window, the page's scrollY
 *   against the finger's travel in CSS px (the keys should follow it within half a line, not run
 *   to twice as far);
 * - **calibrate**: how far one arrow, bursts of arrows at 0 to 250 ms gaps, and Page Down/Up move.
 *
 * Every result is a `browser:` line on stdout: the stream's size and scale, each step's deltas and
 * the page's scroll offset after it, and the wheel events WebKit delivered with where it put them
 * against where the phone aimed. It fails when the rig broke (no browser session, no stream, a tap
 * that clicks nothing) and, at the end, when no raw `browserScroll` moved either page: the bug.
 * The page is the test's own, so its beacon values may be printed.
 */
class RealIterm2BrowserTest {
    private val rig = RealIterm2Rig()
    private val server = PageServer()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() {
        server.close()
        rig.close()
    }

    @Test
    fun reportsWhatScrollsABrowserPage() = runBlocking {
        server.start()
        val sent = CopyOnWriteArrayList<PhoneMessage.BrowserScroll>()
        val video = FakeVideoOutput(::nowMillis).apply {
            configureResult = { config ->
                if (hasCompleteHevcParameterSets(config.codecExtradata)) VideoReadiness.Ready else VideoReadiness.WaitingForConfig
            }
        }
        val frames = Frames()
        val client = rig.client(frames)
        val controller = StreamController(link = client, video = video, scope = rig.scope, nowMillis = ::nowMillis, log = { line("stream: $it") })
        frames.target = controller
        rig.connect(client)
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val link = RecordingLink(client, sent)
        val senderLog = CopyOnWriteArrayList<String>()
        val sender = InputSender(
            link = link,
            scope = rig.scope,
            isCurrent = { target -> target.linkId == rig.linkId.value && client.state.value is ClientState.Connected },
            log = { senderLog += it; line("input: $it") },
        )

        val windows = BrowserWindows(client, sender, linkId)
        try {
            probe(client, controller, frames, sender, linkId, windows.open(), sent, windows::move)
        } finally {
            try {
                windows.closeAll()
            } catch (e: Exception) {
                line("could not close the browser window: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun probe(
        client: CompanionClient,
        controller: StreamController,
        frames: Frames,
        sender: InputSender,
        linkId: Long,
        browser: SessionSummary,
        sent: CopyOnWriteArrayList<PhoneMessage.BrowserScroll>,
        moveWindow: suspend (x: Int, y: Int) -> Boolean,
    ) {
        val first = server.awaitLoad("", rig.requestTimeout)
            ?: failWith("the browser session did not load $PAGE_URL within ${rig.requestTimeout} (server saw ${server.beacons.size} beacons)")
        line("page loaded: viewport ${first["w"]}x${first["h"]} CSS px, devicePixelRatio ${first["dpr"]}, document ${first["sh"]} px tall")

        controller.open(browser)
        val active = rig.poll { controller.activeStream.value } ?: failWith("no stream adopted: ${controller.state.value}")
        frames.streamID = active.streamID
        rig.poll { controller.state.value as? LiveViewState.Live } ?: failWith("not Live: ${controller.state.value}")
        val config = rig.poll { frames.configs.lastOrNull { it.streamID == active.streamID } } ?: failWith("no streamConfig for stream ${active.streamID}")
        rig.poll { frames.keyframes.takeIf { it > 0 } } ?: failWith("no keyframe for stream ${active.streamID}")
        line(
            "stream ${active.streamID}: ${config.pixelWidth}x${config.pixelHeight} px, scale ${config.scale}, isBrowser ${config.isBrowser}, " +
                "grid ${config.columns}x${config.rows}; Mac revision ${macRevisionOf(client.state.value)}",
        )
        if (!config.isBrowser) failWith("the stream of the browser session says isBrowser false")

        val driver = BrowserDriver(
            sender,
            controller.activeStream,
            controller.browserEditing,
            controller.browserNavigation,
            macRevision = client.state.map(::macRevisionOf).stateIn(rig.scope, SharingStarted.Eagerly, macRevisionOf(client.state.value)),
            scope = rig.scope,
            nowMillis = ::nowMillis,
            log = { line("driver: $it") },
        )
        withTimeoutOrNull(rig.requestTimeout) { driver.inputActive.first { it } }
            ?: failWith("browser input is not active (Mac revision ${macRevisionOf(client.state.value)}, below 15?)")
        // The app's driver with Settings' "Scroll web pages with keys" on: drags go to the page as keys.
        val keysDriver = BrowserDriver(
            sender,
            controller.activeStream,
            controller.browserEditing,
            controller.browserNavigation,
            macRevision = client.state.map(::macRevisionOf).stateIn(rig.scope, SharingStarted.Eagerly, macRevisionOf(client.state.value)),
            scope = rig.scope,
            nowMillis = ::nowMillis,
            scrollKeys = { true },
            log = { line("keys driver: $it") },
        )
        val probe = Probe(client, driver, sender, server, config, active.streamID, linkId, InputTarget(linkId, active.watchEpoch, browser.guid), sent)

        val scale = probe.tap()
        val raw = probe.raw(scale)
        probe.moved(scale, moveWindow)
        // Still at the last (most offset) window position: the app's drag through the key path.
        probe.keyDrag(keysDriver)
        probe.slowKeyDrag(keysDriver, scale)
        if (!moveWindow(0, 0)) line("could not move the window back to (0, 0)")
        probe.drag(scale)
        probe.small()
        val inner = probe.inner(scale)
        probe.keys(inner = false)
        probe.keys(inner = true)
        probe.calibrateKeys()

        line("=== summary ===")
        probe.summary.forEach { line(it) }
        controller.close()
        if (!raw && !inner) {
            failWith(
                "no raw browserScroll moved the page or its inner scroller: iTerm2 3.7.7 takes the message but WebKit does not scroll " +
                    "(see the browser: lines for the wheel events it delivered, and whether the keys scrolled)",
            )
        }
    }

    /**
     * Opens and closes the browser window, with evidence for each attempt. Attempts, until the
     * session tree shows a browser session:
     *
     * 1. the bundled it2 run from the test (`it2 window new --profile buddy-ci-browser`, the API's
     *    createTab by profile name). The API lets it in by "Allow all apps to connect" (the
     *    root-owned `disable-automation-auth` that `seed-claude-status.sh` writes,
     *    iTermAPIHelper.m:1641-1644); a placeholder `ITERM2_COOKIE` only stops it2 asking iTerm2
     *    for a cookie over AppleScript (CookieAuth.swift:7-15), and an unknown cookie is not a
     *    refusal (iTermWebSocketConnection.m:132-141);
     * 2. the same typed into the first terminal session (Ctrl-C and Ctrl-U first, since earlier
     *    steps typed there), its stdout, stderr and exit status written to a file the test reads;
     * 3. AppleScript (`create window with profile`), which needs the Automation permission; its
     *    answer or error is recorded.
     *
     * Each attempt's output (it2 prints window IDs and profile names, never terminal content) and
     * the session tree after it (counts and types; titles are terminal content, so only lengths)
     * are printed.
     */
    private inner class BrowserWindows(private val client: CompanionClient, private val sender: InputSender, private val linkId: Long) {
        private var before: Set<String>? = null
        private val created = mutableSetOf<String>()
        private var opened = false

        suspend fun open(): SessionSummary {
            browserIn(rig.sessionTree(client))?.let {
                line("a browser session is already open")
                opened = true
                return it
            }
            before = windowIds("before")
            treeReport("before")

            val direct = it2("window", "new", "--profile", PROFILE_NAME)
            noteCreated(direct.out)
            awaitBrowser("it2 run from the test")?.let { return it }

            val terminal = rig.firstTerminalSession(client)
            val target = InputTarget(linkId, watchEpoch = 0, sessionGuid = terminal.guid)
            val outFile = diagnosticsDir().resolve(IT2_OUT_FILE).also { it.delete() }
            sender.key(target, KeyEvent(Key.Text("c"), KeyModifiers(control = true)))
            delay(300)
            sender.key(target, KeyEvent(Key.Text("u"), KeyModifiers(control = true)))
            delay(300)
            sender.typeThenReturn(
                target,
                "\"$IT2\" window new --profile $PROFILE_NAME >\"${outFile.path}\" 2>&1; echo \"exit=\$?\" >>\"${outFile.path}\"",
            )
            line("typed it2 window new --profile $PROFILE_NAME into the terminal session")
            val typed = rig.poll { outFile.takeIf { it.isFile }?.readText()?.takeIf { "exit=" in it } }
            line("typed it2: ${typed?.let(::oneLine) ?: "no output file within ${rig.requestTimeout} (the shell did not run the line)"}")
            typed?.let(::noteCreated)
            awaitBrowser("it2 typed into the terminal session")?.let { return it }

            val script = runProcess(
                listOf("osascript", "-e", "tell application \"iTerm2\" to create window with profile \"$PROFILE_NAME\""),
                APPLESCRIPT_TIMEOUT,
            )
            line("osascript create window with profile: ${script.text()}")
            awaitBrowser("AppleScript")?.let { return it }

            val profiles = it2("profile", "list")
            val listed = profiles.out.lines().count { PROFILE_NAME in it }
            val profileFile = File(System.getProperty("user.home"), "Library/Application Support/iTerm2/DynamicProfiles/$PROFILE_NAME.json")
            failWith(
                "no browser session appeared: it2 from the test ${direct.text()}; typed it2 ${typed?.let(::oneLine) ?: "did not run"}; " +
                    "AppleScript ${script.text()}; it2 profile list shows $PROFILE_NAME $listed times; the profile file is " +
                    "${if (profileFile.isFile) "present" else "missing"}",
            )
        }

        /**
         * Closes every window that appeared since [open] began (and any it2 reported creating),
         * retrying, then checks the session tree. When no browser was seen it first waits a little,
         * so a window that comes up after the poll gave up is closed too. Best effort.
         */
        suspend fun closeAll() {
            if (!opened) delay(LATE_WINDOW)
            for (attempt in 1..2) {
                val now = windowIds("closing") ?: emptySet()
                val toClose = (before?.let { now - it } ?: emptySet()) + created.intersect(now)
                if (toClose.isEmpty() && browserIn(rig.sessionTree(client)) == null) break
                for (id in toClose) line("it2 window close $id: ${it2("window", "close", "--force", id).text()}")
                val gone = rig.poll(CLOSE_WAIT) {
                    (browserIn(rig.sessionTree(client)) == null && windowIds(null)?.let { ids -> ids.none { it in toClose } } != false).takeIf { it }
                }
                if (gone != null) {
                    line("closed ${toClose.size} window(s); no browser session left")
                    return
                }
                line("close attempt $attempt left a browser session or window open")
            }
            treeReport("after closing")
        }

        /** Moves the browser window's origin to ([x], [y]) with `it2 window move`, then lists the windows (their frames); whether it moved. */
        suspend fun move(x: Int, y: Int): Boolean {
            val id = created.firstOrNull() ?: before?.let { old -> windowIds(null)?.minus(old)?.firstOrNull() } ?: return false
            val moved = it2("window", "move", x.toString(), y.toString(), id).exit == 0
            windowIds("after moving to ($x, $y)")
            return moved
        }

        private fun noteCreated(output: String) {
            Regex("Created new window: (\\S+)").find(output)?.let { created += it.groupValues[1] }
        }

        private suspend fun awaitBrowser(how: String): SessionSummary? {
            val found = rig.poll(OPEN_WAIT) { browserIn(rig.sessionTree(client)) }
            treeReport("after $how")
            if (found != null) {
                line("browser session opened by $how")
                opened = true
            }
            return found
        }

        private suspend fun treeReport(label: String) {
            val tree = rig.sessionTree(client)
            val sessions = sessionsOf(tree)
            line(
                "session tree $label: ${tree.windows.size} windows " +
                    tree.windows.joinToString(prefix = "[", postfix = "]") { w -> "title(len=${w.title.length}) ${w.tabs.size} tabs" } +
                    ", ${sessions.size} sessions (${sessions.count { it.isBrowser }} browser), ${sessions.map { it.guid }.toSet().size} distinct guids",
            )
        }

        /** The window IDs it2 lists (with their frames, printed), or null when it2 cannot list them. */
        private suspend fun windowIds(label: String?): Set<String>? {
            val listed = it2("window", "list", "--json", quiet = label == null)
            if (listed.exit != 0) {
                if (label != null) line("it2 window list ($label): ${listed.text()}")
                return null
            }
            if (label != null) line("it2 window list ($label): ${oneLine(listed.out)}")
            return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(listed.out).map { it.groupValues[1] }.toSet()
        }

        private fun browserIn(tree: dev.bugobliterator.itermbuddy.core.sessions.SessionTree): SessionSummary? =
            sessionsOf(tree).firstOrNull { it.isBrowser }

        private suspend fun it2(vararg args: String, quiet: Boolean = false): Run {
            val result = runProcess(listOf(IT2) + args, IT2_TIMEOUT, mapOf("ITERM2_COOKIE" to PLACEHOLDER_COOKIE))
            if (!quiet) line("it2 ${args.joinToString(" ")}: ${result.text()}")
            return result
        }
    }

    /** A finished (or killed) process: its exit status (null: timed out), stdout and stderr. */
    private class Run(val exit: Int?, val out: String, val err: String) {
        fun text(): String = "exit ${exit ?: "timed out"}, stdout ${oneLine(out)}, stderr ${oneLine(err)}"
    }

    /** Runs [command] for at most [timeout], with [env] added; never throws for the process's own failure. */
    private suspend fun runProcess(command: List<String>, timeout: Duration, env: Map<String, String> = emptyMap()): Run = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).apply { environment().putAll(env) }.start()
        } catch (e: java.io.IOException) {
            return@withContext Run(-1, "", "could not start: ${e.javaClass.simpleName}")
        }
        process.outputStream.close()
        val out = async { process.inputStream.bufferedReader().readText() }
        val err = async { process.errorStream.bufferedReader().readText() }
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        Run(if (finished) process.exitValue() else null, out.await(), err.await())
    }

    /** The steps, each on a fresh page; [summary] collects one line per result. */
    private inner class Probe(
        private val client: CompanionClient,
        private val driver: BrowserDriver,
        private val sender: InputSender,
        private val server: PageServer,
        private val config: StreamConfig,
        private val streamID: Long,
        private val linkId: Long,
        private val target: InputTarget,
        private val sent: CopyOnWriteArrayList<PhoneMessage.BrowserScroll>,
    ) {
        val summary = mutableListOf<String>()
        private val w = config.pixelWidth.toDouble()
        private val h = config.pixelHeight.toDouble()
        private var counter = 0

        /** Loads a fresh page (`browserLoad`) for [name]; its step id. */
        private suspend fun navigate(name: String, inner: Boolean = false): String {
            val step = "${++counter}-$name"
            driver.load("${PAGE_URL}?step=$step${if (inner) "&mode=inner" else ""}")
            server.awaitLoad(step, rig.requestTimeout) ?: failWith("step $step: the page did not load within ${rig.requestTimeout}")
            // The frame source needs a frame of the new page for its scale; let it settle.
            delay(SETTLE)
            return step
        }

        private fun report(text: String) {
            line(text)
            summary += text
        }

        /** Clicks at a known pixel; the frame scale WebKit's click implies (image px per CSS px). */
        suspend fun tap(): Double {
            val step = navigate("tap")
            val x = w / 2
            val y = h / 3
            driver.tap(x, y)
            val click = server.await(rig.requestTimeout) { it.step == step && it.kind == "click" }
                ?: failWith("rig: a tap at image ($x, $y) produced no click on the page within ${rig.requestTimeout}")
            val cx = click.number("cx")
            val cy = click.number("cy")
            val scale = if (cx > 0) x / cx else config.scale
            report(fmt("tap at image (%.0f, %.0f) clicked CSS (%.0f, %.0f): frame scale %.3f image px per CSS px (config scale %.2f)", x, y, cx, cy, scale, config.scale))
            return scale
        }

        /** Raw `browserScroll`s straight to the link; whether any moved the page. */
        suspend fun raw(scale: Double): Boolean {
            val step = navigate("raw")
            var moved = false
            for (delta in listOf(-300.0, -1200.0, 600.0)) {
                val before = server.scrollY(step)
                val wheelsBefore = server.count(step, "wheel")
                client.send(PhoneMessage.BrowserScroll(streamID, w / 2, h / 2, 0.0, delta), onLink = linkId)
                val after = server.settledScrollY(step)
                val wheels = server.wheels(step).drop(wheelsBefore)
                if (after != before) moved = true
                report(
                    fmt("raw browserScroll deltaY %+.0f px at image (%.0f, %.0f) (Mac: %+d points): scrollY %.0f -> %.0f; ", delta, w / 2, h / 2, macPoints(delta, scale), before, after) +
                        wheelText(wheels, w / 2 / scale, h / 2 / scale),
                )
            }
            return moved
        }

        /**
         * The wheel's location against the window's place on screen: iTerm2 3.7.7 builds the
         * scroll event from a global location (`CompanionBrowserInput.scroll`) and WebKit reads it
         * back as a window location, so the offset between where the phone aims and where the
         * wheel lands should follow the window. For each position: move the window (`it2 window
         * move`), load a fresh page and send one raw scroll at the centre.
         */
        suspend fun moved(scale: Double, moveWindow: suspend (x: Int, y: Int) -> Boolean) {
            for ((x, y) in WINDOW_POSITIONS) {
                if (!moveWindow(x, y)) {
                    report("moved to ($x, $y): it2 window move failed; skipped")
                    continue
                }
                val step = navigate("moved-$x-$y")
                val wheelsBefore = server.count(step, "wheel")
                client.send(PhoneMessage.BrowserScroll(streamID, w / 2, h / 2, 0.0, -300.0), onLink = linkId)
                val after = server.settledScrollY(step)
                report(fmt("window moved to (%d, %d): raw deltaY -300: scrollY 0 -> %.0f; ", x, y, after) + wheelText(server.wheels(step).drop(wheelsBefore), w / 2 / scale, h / 2 / scale))
            }
        }

        /** [BrowserDriver.scroll] as a phone drag makes it, then a fling. */
        suspend fun drag(scale: Double) {
            val step = navigate("drag")
            // A 1080 px wide phone at fit; the finger moves up 600 view px in 0.48 s.
            val density = (w / PHONE_WIDTH).toFloat()
            val firstSent = sent.size
            val before = server.scrollY(step)
            repeat(DRAG_EVENTS) {
                driver.scroll(0f, -600f / DRAG_EVENTS, density, 1f, (w / 2).toFloat(), (h / 2).toFloat())
                delay(EVENT_GAP)
            }
            val after = server.settledScrollY(step)
            val steps = sent.drop(firstSent)
            report(
                fmt("drag: %d browserScroll steps, deltaY %s (sum %+.0f px, Mac %+d points at scale %.2f): scrollY %.0f -> %.0f", steps.size, steps.joinToString(",") { "%+.0f".format(Locale.ROOT, it.deltaY) }, steps.sumOf { it.deltaY }, steps.sumOf { macPoints(it.deltaY, scale) }, scale, before, after),
            )
            val flingFrom = sent.size
            driver.fling(0f, -3000f, density, 1f, (w / 2).toFloat(), (h / 2).toFloat())
            val flung = server.settledScrollY(step, quiet = 1.seconds, cap = 6.seconds)
            report(fmt("fling at -3000 view px/s: %d steps, sum %+.0f px: scrollY %.0f -> %.0f", sent.size - flingFrom, sent.drop(flingFrom).sumOf { it.deltaY }, after, flung))
        }

        /** Runs of small raw steps, for the Mac's rounding. */
        suspend fun small() {
            val step = navigate("small")
            for (size in listOf(1.0, 2.0, 3.0, 4.0)) {
                val before = server.scrollY(step)
                repeat(SMALL_STEPS) {
                    client.send(PhoneMessage.BrowserScroll(streamID, w / 2, h / 2, 0.0, -size), onLink = linkId)
                    delay(FLUSH_GAP)
                }
                val after = server.settledScrollY(step)
                report(fmt("small: %d steps of deltaY -%.0f px: scrollY %.0f -> %.0f (moved %.0f CSS px)", SMALL_STEPS, size, before, after, after - before))
            }
        }

        /** A raw scroll on a page that scrolls in an inner element; whether it moved. */
        suspend fun inner(scale: Double): Boolean {
            val step = navigate("inner", inner = true)
            val before = server.innerY(step)
            val wheelsBefore = server.count(step, "wheel")
            client.send(PhoneMessage.BrowserScroll(streamID, w / 2, h / 2, 0.0, -600.0), onLink = linkId)
            val after = server.settledInnerY(step)
            report(fmt("inner scroller: raw deltaY -600 px: scrollTop %.0f -> %.0f; ", before, after) + wheelText(server.wheels(step).drop(wheelsBefore), w / 2 / scale, h / 2 / scale))
            return after != before
        }

        /** Page Down, Down ×5 and Space through the browser key path (`sendKey`), after a tap on the page. */
        suspend fun keys(inner: Boolean) {
            val step = navigate("keys", inner)
            val label = if (inner) "inner scroller" else "document"
            driver.tap(w / 2, h / 2)
            server.await(rig.requestTimeout) { it.step == step && it.kind == "click" } ?: report("keys ($label): the focusing tap clicked nothing")
            for ((name, keys) in listOf(
                "Page Down" to listOf(KeyEvent(Key.Special(SpecialKey.PAGE_DOWN))),
                "Down x5" to List(5) { KeyEvent(Key.Special(SpecialKey.DOWN)) },
                "Space" to listOf(KeyEvent(Key.Text(" "))),
            )) {
                val before = if (inner) server.innerY(step) else server.scrollY(step)
                val keysBefore = server.count(step, "key")
                keys.forEach { sender.key(target, it, pasteKeys = false) }
                val after = if (inner) server.settledInnerY(step) else server.settledScrollY(step)
                report(fmt("keys (%s): %s: %s %.0f -> %.0f, %d keydown events on the page", label, name, if (inner) "scrollTop" else "scrollY", before, after, server.count(step, "key") - keysBefore))
            }
        }

        /**
         * The app's drag with "Scroll web pages with keys" on ([keysDriver]), at the window's
         * current (moved) place, where a raw scroll's wheel lands off target: the same 600 px phone
         * drag as [drag], on the document and on the inner-scroller page, first with no tap (as an
         * owner who just drags), then, if that moved nothing, after a tap on the page.
         */
        suspend fun keyDrag(keysDriver: BrowserDriver) {
            val density = (w / PHONE_WIDTH).toFloat()
            for (inner in listOf(false, true)) {
                val label = if (inner) "inner scroller" else "document"
                val step = navigate(if (inner) "keydrag-inner" else "keydrag", inner)
                for (tapFirst in listOf(false, true)) {
                    if (tapFirst) {
                        driver.tap(w / 2, h / 2)
                        server.await(rig.requestTimeout) { it.step == step && it.kind == "click" }
                    }
                    val before = if (inner) server.innerY(step) else server.scrollY(step)
                    val keysBefore = server.count(step, "key")
                    repeat(DRAG_EVENTS) {
                        keysDriver.scroll(0f, -600f / DRAG_EVENTS, density, 1f, (w / 2).toFloat(), (h / 2).toFloat())
                        delay(EVENT_GAP)
                    }
                    val after = if (inner) server.settledInnerY(step) else server.settledScrollY(step)
                    val keys = server.beacons.filter { it.step == step && it.kind == "key" }.drop(keysBefore).joinToString(",") { it.values["key"].orEmpty() }
                    report(fmt("key drag (%s, %s): 600 view px up: %s %.0f -> %.0f; keys on the page: %s", label, if (tapFirst) "after a tap" else "no tap", if (inner) "scrollTop" else "scrollY", before, after, keys.ifEmpty { "none" }))
                    if (after != before) break
                }
            }
        }

        /**
         * A slow drag through the keys ([keysDriver]) at the window's current (moved) place: 900
         * view px up at about 190 view px/s (3 px every 16 ms, slower than the keys' pace), then
         * the page's scrollY against the finger's travel in CSS px (view px × image px per view
         * px at a phone's fit ÷ the frame [scale]), and the keys WebKit saw.
         */
        suspend fun slowKeyDrag(keysDriver: BrowserDriver, scale: Double) {
            val density = (w / PHONE_WIDTH).toFloat()
            val step = navigate("slowkeydrag")
            val before = server.scrollY(step)
            val keysBefore = server.count(step, "key")
            keysDriver.touchDown()
            repeat(SLOW_DRAG_EVENTS) {
                keysDriver.scroll(0f, -SLOW_DRAG_STEP_PX, density, 1f, (w / 2).toFloat(), (h / 2).toFloat())
                delay(EVENT_GAP)
            }
            val after = server.settledScrollY(step)
            val finger = SLOW_DRAG_EVENTS * SLOW_DRAG_STEP_PX * density / scale
            val keys = server.count(step, "key") - keysBefore
            report(
                fmt(
                    "slow key drag: finger %.0f CSS px up over %d ms: scrollY %.0f -> %.0f (%+.0f CSS px, %.2f of the finger), %d keydown events",
                    finger, SLOW_DRAG_EVENTS * EVENT_GAP.inWholeMilliseconds, before, after, after - before, (after - before) / finger, keys,
                ),
            )
        }

        /** How far one arrow, bursts of five arrows at a few gaps, and one Page Down/Up scroll the document. */
        suspend fun calibrateKeys() {
            val step = navigate("calibrate")
            driver.tap(w / 2, h / 2)
            server.await(rig.requestTimeout) { it.step == step && it.kind == "click" }
            suspend fun measure(name: String, presses: Int, key: SpecialKey, gap: Duration) {
                val before = server.scrollY(step)
                repeat(presses) {
                    sender.key(target, KeyEvent(Key.Special(key)), pasteKeys = false)
                    if (gap > Duration.ZERO) delay(gap)
                }
                val after = server.settledScrollY(step)
                report(fmt("calibrate: %s: scrollY %.0f -> %.0f (%+.0f CSS px, %.1f per press)", name, before, after, after - before, (after - before) / presses))
            }
            measure("Down x1", 1, SpecialKey.DOWN, Duration.ZERO)
            for (gap in listOf(0, 60, 120, 250)) measure("Down x5 every $gap ms", 5, SpecialKey.DOWN, gap.milliseconds)
            measure("Page Down x1", 1, SpecialKey.PAGE_DOWN, Duration.ZERO)
            measure("Page Down x3 every 120 ms", 3, SpecialKey.PAGE_DOWN, 120.milliseconds)
            measure("Up x1", 1, SpecialKey.UP, Duration.ZERO)
            measure("Page Up x1", 1, SpecialKey.PAGE_UP, Duration.ZERO)
        }

        private fun wheelText(wheels: List<Beacon>, aimX: Double, aimY: Double): String {
            if (wheels.isEmpty()) return "no wheel event reached the page"
            val first = wheels.first()
            return fmt(
                "%d wheel events on the page, deltaY sum %.0f, the first at CSS (%.0f, %.0f) (aimed at %.0f, %.0f), target %s",
                wheels.size, wheels.sumOf { it.number("dy") }, first.number("cx"), first.number("cy"), aimX, aimY, first.values["target"] ?: "?",
            )
        }
    }

    /** Records every `browserScroll` that goes to the link, then sends it. */
    private class RecordingLink(private val delegate: StreamLink, private val sent: MutableList<PhoneMessage.BrowserScroll>) : StreamLink by delegate {
        override suspend fun send(message: PhoneMessage, onLink: Long?) {
            if (message is PhoneMessage.BrowserScroll) sent += message
            delegate.send(message, onLink)
        }
    }

    /** One beacon from the page: its kind, its step and its values. */
    private class Beacon(val kind: String, val step: String, val values: Map<String, String>) {
        fun number(key: String): Double = values[key]?.toDoubleOrNull() ?: Double.NaN
    }

    /** The page server on 127.0.0.1:[PAGE_PORT]: the probe page and its beacons. */
    private class PageServer : AutoCloseable {
        val beacons = CopyOnWriteArrayList<Beacon>()
        private var server: HttpServer? = null

        fun start() {
            val created = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", PAGE_PORT), 0)
            } catch (e: java.io.IOException) {
                failWith("could not serve the page on 127.0.0.1:$PAGE_PORT: ${e.javaClass.simpleName}")
            }
            created.executor = Executors.newFixedThreadPool(4)
            created.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val query = parseQuery(exchange.requestURI.rawQuery)
                val (status, type, body) = when (path) {
                    "/" -> Triple(200, "text/html; charset=utf-8", page(query["mode"] == "inner"))
                    "/beacon" -> {
                        beacons += Beacon(query["kind"].orEmpty(), query["step"].orEmpty(), query)
                        Triple(204, "text/plain", "")
                    }
                    else -> Triple(404, "text/plain", "")
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", type)
                exchange.responseHeaders.add("Cache-Control", "no-store")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            created.start()
            server = created
            line("serving the probe page on $PAGE_URL")
        }

        suspend fun await(timeout: Duration, match: (Beacon) -> Boolean): Beacon? = withTimeoutOrNull(timeout) {
            var found = beacons.firstOrNull(match)
            while (found == null) {
                delay(POLL)
                found = beacons.firstOrNull(match)
            }
            found
        }

        suspend fun awaitLoad(step: String, timeout: Duration): Map<String, String>? = await(timeout) { it.kind == "load" && it.step == step }?.values

        fun count(step: String, kind: String): Int = beacons.count { it.step == step && it.kind == kind }

        fun wheels(step: String): List<Beacon> = beacons.filter { it.step == step && it.kind == "wheel" }

        /** The page's last reported `scrollY` in [step]. */
        fun scrollY(step: String): Double = last(step, "y")

        fun innerY(step: String): Double = last(step, "iy")

        private fun last(step: String, key: String): Double =
            beacons.lastOrNull { it.step == step && it.values[key] != null }?.number(key) ?: 0.0

        suspend fun settledScrollY(step: String, quiet: Duration = QUIET, cap: Duration = SETTLE_CAP): Double = settled(quiet, cap) { scrollY(step) }

        suspend fun settledInnerY(step: String): Double = settled(QUIET, SETTLE_CAP) { innerY(step) }

        /** [read] once it has not changed for [quiet] (the page beacons its state every 250 ms), at most [cap]. */
        private suspend fun settled(quiet: Duration, cap: Duration, read: () -> Double): Double {
            var value = read()
            var since = System.nanoTime()
            withTimeoutOrNull(cap) {
                while (System.nanoTime() - since < quiet.inWholeNanoseconds) {
                    delay(POLL)
                    val now = read()
                    if (now != value) {
                        value = now
                        since = System.nanoTime()
                    }
                }
            }
            return read()
        }

        override fun close() {
            server?.stop(0)
            (server?.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }

        private fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
            }
    }

    /** Forwards every input to [target]; records the watched stream's configs and counts its keyframes. Holds no frame bytes. */
    private class Frames : StreamSink {
        @Volatile var target: StreamSink? = null

        @Volatile var streamID: Long? = null
        val configs = CopyOnWriteArrayList<StreamConfig>()

        @Volatile var keyframes = 0

        override suspend fun accept(input: StreamInput) {
            when (input) {
                is StreamInput.Control -> (input.message as? MacMessage.StreamConfigMessage)?.let { configs += it.config }
                is StreamInput.Media -> try {
                    val frame = input.parse()
                    if (frame.isKeyframe && (streamID == null || frame.streamID == streamID)) keyframes++
                } catch (_: MediaFrameException) {
                    // The controller handles it.
                }
                else -> Unit
            }
            checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
        }
    }

    private companion object {
        /** The it2 iTerm2 bundles (`ITERM2_IT2` in `ci/real-iterm2/common.sh`). */
        const val IT2 = "/Applications/iTerm.app/Contents/Resources/utilities/it2"
        const val IT2_OUT_FILE = "browser-it2-output.txt"

        /** Stops it2 asking iTerm2 for a cookie over AppleScript; "Allow all apps to connect" lets it in. */
        const val PLACEHOLDER_COOKIE = "buddy-ci-no-cookie"
        val IT2_TIMEOUT = 20.seconds
        val APPLESCRIPT_TIMEOUT = 20.seconds
        val OPEN_WAIT = 15.seconds
        val CLOSE_WAIT = 10.seconds
        val LATE_WINDOW = 5.seconds

        /** [text] on one line, at most 400 characters (it2's own messages and IDs). */
        fun oneLine(text: String): String {
            val flat = text.trim().replace(Regex("\\s*\\n\\s*"), " | ")
            return if (flat.isEmpty()) "(empty)" else if (flat.length > 400) flat.take(400) + "…" else flat
        }

        /** `BROWSER_PAGE_PORT` and `BROWSER_PROFILE_NAME` in `ci/real-iterm2/common.sh`. */
        const val PAGE_PORT = 8793
        const val PAGE_URL = "http://127.0.0.1:$PAGE_PORT/"
        const val PROFILE_NAME = "buddy-ci-browser"

        const val PHONE_WIDTH = 1080.0
        const val DRAG_EVENTS = 30
        const val SLOW_DRAG_EVENTS = 300
        const val SLOW_DRAG_STEP_PX = 3f
        val EVENT_GAP = 16.milliseconds
        const val SMALL_STEPS = 20
        val FLUSH_GAP = 40.milliseconds
        val SETTLE = 500.milliseconds

        /** Window origins (screen points) the wheel-offset step tries. */
        val WINDOW_POSITIONS = listOf(0 to 0, 200 to 150, 400 to 300)
        val QUIET = 700.milliseconds
        val SETTLE_CAP = 4.seconds
        val POLL = 20.milliseconds

        fun nowMillis(): Long = System.nanoTime() / 1_000_000

        fun line(text: String) = println("browser: $text")

        fun fmt(format: String, vararg args: Any?): String = String.format(Locale.ROOT, format, *args)

        /** What iTerm2 3.7.7 scrolls the web view by: [delta] / [scale], rounded half away from zero (Swift `rounded()`). */
        fun macPoints(delta: Double, scale: Double): Int {
            if (!(scale > 0)) return 0
            val points = delta / scale
            return (sign(points) * floor(abs(points) + 0.5)).toInt()
        }

        /** `$WORK_DIR/diagnostics`, beside the pairing file; the workflow uploads it. */
        fun diagnosticsDir(): File {
            val workDir = File(RealIterm2Rig.requiredEnv("REAL_ITERM2_PAIRING_FILE")).absoluteFile.parentFile
                ?: error("REAL_ITERM2_PAIRING_FILE has no parent directory")
            return workDir.resolve("diagnostics").also { it.mkdirs() }
        }

        /**
         * The probe page: a document 8000 CSS px tall, or ([inner]) one whose content
         * scrolls in a full-window `overflow: auto` element. It beacons its load (viewport,
         * devicePixelRatio, height), every scroll, click, wheel and keydown, and its scroll state
         * every 250 ms.
         */
        fun page(inner: Boolean): String = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>buddy browser probe</title>
            <style>
              html, body { margin: 0; padding: 0; font: 32px sans-serif; }
              .tall { height: 8000px; background: linear-gradient(#ffffff, #3050ff); }
              #inner { position: fixed; left: 0; top: 0; right: 0; bottom: 0; overflow: auto; }
              ${if (inner) "html, body { height: 100%; overflow: hidden; }" else ""}
            </style></head>
            <body>
            ${if (inner) "<div id=\"inner\"><div class=\"tall\">inner scroller</div></div>" else "<div class=\"tall\">document</div>"}
            <script>
              const step = new URLSearchParams(location.search).get('step') || '';
              const inner = document.getElementById('inner');
              function beacon(kind, data) {
                const q = new URLSearchParams(Object.assign({ kind: kind, step: step }, data || {}));
                fetch('/beacon?' + q.toString(), { cache: 'no-store' }).catch(function () {});
              }
              function state() { return { y: Math.round(window.scrollY), iy: inner ? Math.round(inner.scrollTop) : 0 }; }
              beacon('load', Object.assign({ w: innerWidth, h: innerHeight, dpr: devicePixelRatio, sh: document.documentElement.scrollHeight }, state()));
              window.addEventListener('scroll', function () { beacon('scroll', state()); }, { passive: true });
              if (inner) inner.addEventListener('scroll', function () { beacon('scroll', state()); }, { passive: true });
              document.addEventListener('click', function (e) { beacon('click', { cx: e.clientX, cy: e.clientY }); }, true);
              window.addEventListener('wheel', function (e) {
                beacon('wheel', { cx: e.clientX, cy: e.clientY, dx: e.deltaX, dy: e.deltaY, mode: e.deltaMode, target: e.target && e.target.id ? e.target.id : (e.target ? e.target.nodeName : '') });
              }, { passive: true, capture: true });
              document.addEventListener('keydown', function (e) { beacon('key', { key: e.key.length === 1 ? 'char' : e.key }); }, true);
              setInterval(function () { beacon('state', state()); }, 250);
            </script>
            </body></html>
        """.trimIndent()
    }
}
