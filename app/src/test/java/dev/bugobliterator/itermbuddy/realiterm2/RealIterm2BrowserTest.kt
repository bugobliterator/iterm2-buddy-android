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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
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
 * It opens a window of that profile with the bundled it2, typed into the first terminal session,
 * streams the browser session through the app's own [StreamController] and drives it through the
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
 * - **inner**: a page whose content scrolls in a full-window `overflow: auto` element, not the
 *   document (common in web apps): a raw scroll at its centre;
 * - **keys**: Page Down, Down ×5 and Space through `sendKey` (the browser key path), on both pages,
 *   as a fallback should synthetic scrolls be dead.
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

        val browser = openBrowserSession(client, sender, linkId)
        try {
            probe(client, controller, frames, sender, linkId, browser, sent)
        } finally {
            try {
                closeBrowserSession(client, sender, linkId, browser)
            } catch (e: Exception) {
                line("could not close the browser session: ${e.javaClass.simpleName}")
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
        val probe = Probe(client, driver, sender, server, config, active.streamID, linkId, InputTarget(linkId, active.watchEpoch, browser.guid), sent)

        val scale = probe.tap()
        val raw = probe.raw(scale)
        probe.drag(scale)
        probe.small()
        val inner = probe.inner(scale)
        probe.keys(inner = false)
        probe.keys(inner = true)

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
     * Closes the browser session (`it2 session close`, typed into the terminal session), so the
     * steps after this one see the window iTerm2 opened at launch in front again. Best effort.
     */
    private suspend fun closeBrowserSession(client: CompanionClient, sender: InputSender, linkId: Long, browser: SessionSummary) {
        if (!browser.guid.all { it.isLetterOrDigit() || it == '-' || it == ':' }) return
        val terminal = sessionsOf(rig.sessionTree(client)).firstOrNull { !it.isBrowser } ?: return
        sender.typeThenReturn(InputTarget(linkId, watchEpoch = 0, sessionGuid = terminal.guid), "\"$IT2\" session close --force --session ${browser.guid}")
        val closed = rig.poll { sessionsOf(rig.sessionTree(client)).none { it.guid == browser.guid }.takeIf { it } } != null
        line(if (closed) "closed the browser session" else "the browser session is still open after it2 session close")
    }

    /** Opens a window of the browser profile with the bundled it2, typed into the first terminal session; the browser session. */
    private suspend fun openBrowserSession(client: CompanionClient, sender: InputSender, linkId: Long): SessionSummary {
        sessionsOf(rig.sessionTree(client)).firstOrNull { it.isBrowser }?.let {
            line("a browser session is already open")
            return it
        }
        val terminal = rig.firstTerminalSession(client)
        val stderr = diagnosticsDir().resolve(IT2_STDERR_FILE).also { it.delete() }
        val target = InputTarget(linkId, watchEpoch = 0, sessionGuid = terminal.guid)
        sender.key(target, KeyEvent(Key.Text("c"), KeyModifiers(control = true)))
        delay(300)
        sender.typeThenReturn(target, "\"$IT2\" window new --profile $PROFILE_NAME 2>>\"${stderr.path}\"")
        line("typed it2 window new --profile $PROFILE_NAME into the terminal session")
        return rig.poll { sessionsOf(rig.sessionTree(client)).firstOrNull { it.isBrowser } } ?: run {
            val profile = File(System.getProperty("user.home"), "Library/Application Support/iTerm2/DynamicProfiles/$PROFILE_NAME.json")
            val error = if (stderr.isFile) stderr.readLines().filter { it.isNotBlank() }.takeLast(10).joinToString(" | ") else "none"
            failWith(
                "no browser session appeared within ${rig.requestTimeout}: profile file ${if (profile.isFile) "present" else "missing"}; " +
                    "it2 stderr: $error. A missing browser plugin drops the profile (install-browser-plugin.sh), and it2 then " +
                    "reports an invalid profile name.",
            )
        }
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
        const val IT2_STDERR_FILE = "browser-it2-stderr.txt"

        /** `BROWSER_PAGE_PORT` and `BROWSER_PROFILE_NAME` in `ci/real-iterm2/common.sh`. */
        const val PAGE_PORT = 8793
        const val PAGE_URL = "http://127.0.0.1:$PAGE_PORT/"
        const val PROFILE_NAME = "buddy-ci-browser"

        const val PHONE_WIDTH = 1080.0
        const val DRAG_EVENTS = 30
        val EVENT_GAP = 16.milliseconds
        const val SMALL_STEPS = 20
        val FLUSH_GAP = 40.milliseconds
        val SETTLE = 800.milliseconds
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
