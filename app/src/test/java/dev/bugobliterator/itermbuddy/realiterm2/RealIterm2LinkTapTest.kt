package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.LinkLookup
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.gridLine
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.stream.indexAtCell
import dev.bugobliterator.itermbuddy.core.stream.linkAtTap
import dev.bugobliterator.itermbuddy.core.stream.linksInLine
import dev.bugobliterator.itermbuddy.core.wire.SelectionMode
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.core.wire.SelectionSpan
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import dev.bugobliterator.itermbuddy.ui.live.FitRect
import dev.bugobliterator.itermbuddy.ui.live.ZoomState
import dev.bugobliterator.itermbuddy.ui.live.selectionPointAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The link tap against a real iTerm2 3.7.7 (the real-Mac CI's report-only step): with a live
 * stream of the first terminal session in the app's [StreamController], it types `clear` and
 * three `echo`s into the session: a plain URL, a URL inside other text with brackets and a full
 * stop (`see (https://example.com/a_(b)). ok`), and a bare `www.` link. For each it taps a cell
 * of the link through the app's own path: the screen point of a cell on the stream's grid (the
 * picture at fit, with no history) mapped by [selectionPointAt], then [LinkLookup] (the line
 * selected and copied, the link under the tapped column, the smart selection as a fallback, then
 * clear, through a [SelectionDriver]). It tries the top rows in turn, as the prompt can take a
 * line, and asserts that every link was found exactly, and that the Mac's selection is cleared
 * afterwards (the stream's selection report goes back to none, and `copySelection` answers `""`).
 *
 * Before that it gets a clean prompt (Ctrl-C, Ctrl-U, `clear`, waiting until the rows below the
 * first are empty) and echoes each link with its own command. It dumps the first rows as the
 * lookup sees them: each row's whole-line text escaped (control characters and NULs as `\\uXXXX`),
 * the Mac's selection report, the links found in it with their positions, and the smart selection
 * at column 10; and for each tap the cell's string index and the link the dumped line gives.
 * The content is the test's own, so these `link-tap:` lines print it whole.
 */
class RealIterm2LinkTapTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun aTapOnAPrintedUrlFindsItAndLeavesNoSelection() = runBlocking {
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
        controller.open(session)
        try {
            rig.poll { controller.state.value as? LiveViewState.Live }
                ?: failWith("the controller is not Live within ${rig.requestTimeout}: ${controller.state.value}")
            val logs = CopyOnWriteArrayList<String>()
            val selection = SelectionDriver(client, controller.activeStream, controller.selection, rig.scope, log = { report("link-tap: selection: $it") })
            val lookup = LinkLookup(selection, controller.activeStream, log = { logs += it; report("link-tap: $it") })

            val sender = InputSender(
                link = client,
                scope = rig.scope,
                isCurrent = { target -> target.linkId == rig.linkId.value && client.state.value is ClientState.Connected },
                log = { report("link-tap: input: $it") },
            )
            val target = InputTarget(linkId, watchEpoch = 0, sessionGuid = session.guid)

            /** The cell ([row], [column]) of the live band as the app maps a tap there (the picture at fit, screen px = image px, no history). */
            fun pointAt(row: Int, column: Int): SelectionPoint {
                val stream = controller.activeStream.value ?: failWith("the stream went away")
                val config = stream.config ?: failWith("no config")
                val cell = config.cellGeometry ?: failWith("the config has no cell geometry")
                val liveTop = stream.liveTop ?: failWith("no live top yet")
                val fit = FitRect(0f, 0f, config.pixelWidth.toFloat(), config.pixelHeight.toFloat())
                val layout = ScrollbackLayout(liveTop, liveTop, config.rows, config.rows, config.pixelHeight.toFloat() / config.rows, altScreen = false)
                val x = (cell.leftMargin + (column + 0.5) * cell.cellWidth).toFloat()
                val y = (cell.topMargin + (row + 0.5) * cell.cellHeight).toFloat()
                return selectionPointAt(x, y, ZoomState.FIT, fit, layout, config) ?: failWith("the tap maps to no cell")
            }

            /** A diagnostic selection at [point] in [mode]: the copied text and the Mac's report, then cleared. */
            suspend fun probe(point: SelectionPoint, mode: SelectionMode): Pair<String?, SelectionSpan?> {
                selection.begin(point, mode)
                selection.end(point)
                val text = selection.copy(session.guid, PROBE_TIMEOUT)
                val span = rig.poll(PROBE_TIMEOUT) { controller.selection.value }
                selection.clear()
                rig.poll { (controller.selection.value == null).takeIf { it } }
                return text to span
            }

            // A clean prompt: interrupt anything running, drop a half-typed line, clear the screen.
            sender.key(target, KeyEvent(Key.Text("c"), KeyModifiers(control = true)))
            delay(KEY_PAUSE)
            sender.key(target, KeyEvent(Key.Text("u"), KeyModifiers(control = true)))
            delay(KEY_PAUSE)
            sender.typeThenReturn(target, "clear")
            delay(KEY_PAUSE)
            val clearedScreen = rig.poll {
                (1..3).all { row -> probe(pointAt(row, 0), SelectionMode.LINE).first?.let(::gridLine).isNullOrEmpty() }.takeIf { it }
            }
            report("link-tap: screen cleared: ${clearedScreen == true}")
            // Each link on its own line, one command each.
            for (case in CASES) {
                sender.typeThenReturn(target, "echo ${case.printed}")
                delay(KEY_PAUSE)
            }
            // The commands run and the next frames carry the new live top.
            delay(SETTLE)

            // The screen's first rows as the app's lookup sees them (synthetic content: printed whole).
            val rows = HashMap<Int, Pair<String?, SelectionSpan?>>()
            for (row in 0 until ROWS_TRIED) {
                val point = pointAt(row, 0)
                val (text, span) = probe(point, SelectionMode.LINE)
                rows[row] = text to span
                val smart = probe(pointAt(row, SMART_PROBE_COLUMN), SelectionMode.SMART).first
                report(
                    "link-tap: row $row (line ${point.absLine}): line ${text?.length ?: -1} chars \"${text?.let(::escaped)}\", " +
                        "report $span, links ${text?.let { linksInLine(gridLine(it)).map { l -> "${l.start}..${l.end} ${l.url}" } }}, " +
                        "smart at column $SMART_PROBE_COLUMN \"${smart?.let(::escaped)}\"",
                )
            }

            val found = LinkedHashMap<String, Int>()
            for (case in CASES) {
                for (row in 0 until ROWS_TRIED) {
                    val point = pointAt(row, case.column)
                    val columns = controller.activeStream.value?.config?.columns ?: 0
                    val (text, span) = rows[row] ?: (null to null)
                    val mapping = text?.let { indexAtCell(gridLine(it), point.column) }
                    val local = text?.let { linkAtTap(it, point, span, columns) }
                    val link = lookup.lookup(session.guid, point)
                    val what = when {
                        link == null -> "no link"
                        link.url == case.url -> "the expected link (${case.label})"
                        else -> "another link (${link.url})"
                    }
                    report("link-tap: ${case.label}: row $row (line ${point.absLine}, column ${point.column}, $columns columns): $what; cell to index $mapping; from the dumped line ${local ?: "none"}")
                    // Each lookup clears; wait for the Mac to report it before the next.
                    rig.poll { (controller.selection.value == null).takeIf { it } }
                    if (link?.url == case.url && !link.macLocal) {
                        found[case.label] = row
                        break
                    }
                }
            }
            val cleared = rig.poll { (controller.selection.value == null).takeIf { it } } == true
            val copied = client.request(PhoneMessage.CopySelection(session.guid))
            val leftText = (copied as? MacMessage.SelectionText)?.text
            report("link-tap: found ${found.size}/${CASES.size} ($found); selection report cleared: $cleared; copySelection afterwards: ${leftText?.let { "${it.length} chars" } ?: copied.javaClass.simpleName}")
            report("link-tap: summary: ${logs.size} lookup log lines")
            assertEquals("every printed link was found by a tap", CASES.map { it.label }, found.keys.toList())
            assertEquals("the selection is cleared afterwards (copySelection)", "", leftText)
            assertEquals("the stream's selection report is cleared", true, cleared)
        } finally {
            controller.close()
        }
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
        /** What is printed (shell-quoted), the column tapped on its row, and the link a tap must find. */
        class Case(val label: String, val printed: String, val column: Int, val url: String)

        val CASES = listOf(
            Case("plain URL", "https://example.com/path", 10, "https://example.com/path"),
            Case("URL in text", "'see (https://example.com/a_(b)). ok'", 12, "https://example.com/a_(b)"),
            Case("bare www", "www.example.com/w", 4, "https://www.example.com/w"),
        )
        val SETTLE = 3.seconds

        /** After `clear`: each echo's command row and output row, for three links, and some slack. */
        const val ROWS_TRIED = 9

        /** The smart probe's column on each row (where the plain URL's host is). */
        const val SMART_PROBE_COLUMN = 10
        val PROBE_TIMEOUT = 5.seconds
        val KEY_PAUSE = 500.milliseconds

        /** [text] with every character outside printable ASCII as `\\uXXXX` (NULs, CR, LF, wide characters). */
        fun escaped(text: String): String = buildString {
            for (c in text) if (c.code in 0x20..0x7e && c != '\\') append(c) else append("\\u%04x".format(c.code))
        }
    }
}
