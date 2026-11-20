package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.LinkLookup
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.ScrollbackLayout
import dev.bugobliterator.itermbuddy.core.stream.SelectionDriver
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.TappedLink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
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
import kotlin.time.Duration.Companion.seconds

/**
 * The link tap against a real iTerm2 3.7.7 (the real-Mac CI's report-only step): with a live
 * stream of the first terminal session in the app's [StreamController], it types
 * `clear; echo https://example.com/path` into the session, then taps the URL's cell through the
 * app's own path: the screen point of a cell on the stream's grid (the picture at fit, with no
 * history) mapped by [selectionPointAt], then [LinkLookup] (smart select begin and end, copy,
 * clear, through a [SelectionDriver]). It tries the top rows in turn, as the prompt can take a
 * line, and asserts that a tap found exactly that URL, that the Mac's selection is cleared
 * afterwards (the stream's selection report goes back to none, and `copySelection` answers `""`).
 *
 * Reports lengths, rows and yes/no (`link-tap:` lines); the URL is the test's own.
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
            sender.typeThenReturn(InputTarget(linkId, watchEpoch = 0, sessionGuid = session.guid), "clear; echo $URL")
            // The command runs and the next frames carry the new live top.
            delay(SETTLE)

            var found: TappedLink? = null
            var foundRow = -1
            for (row in 0 until ROWS_TRIED) {
                val stream = controller.activeStream.value ?: failWith("the stream went away")
                val config = stream.config ?: failWith("no config")
                val cell = config.cellGeometry ?: failWith("the config has no cell geometry")
                val liveTop = stream.liveTop ?: failWith("no live top yet")
                // The picture at fit, screen px = image px, no history: the live band alone.
                val fit = FitRect(0f, 0f, config.pixelWidth.toFloat(), config.pixelHeight.toFloat())
                val layout = ScrollbackLayout(liveTop, liveTop, config.rows, config.rows, config.pixelHeight.toFloat() / config.rows, altScreen = false)
                val x = (cell.leftMargin + (TAP_COLUMN + 0.5) * cell.cellWidth).toFloat()
                val y = (cell.topMargin + (row + 0.5) * cell.cellHeight).toFloat()
                val point = selectionPointAt(x, y, ZoomState.FIT, fit, layout, config) ?: failWith("the tap maps to no cell")
                val link = lookup.lookup(session.guid, point)
                report("link-tap: row $row (line ${point.absLine}, column ${point.column}): ${if (link == null) "no link" else "a link of ${link.url.length} chars, ${if (link.url == URL) "the printed URL" else "ANOTHER link"}"}")
                // Each lookup clears; wait for the Mac to report it before the next.
                rig.poll { (controller.selection.value == null).takeIf { it } }
                if (link != null) {
                    found = link
                    foundRow = row
                    break
                }
            }
            val cleared = rig.poll { (controller.selection.value == null).takeIf { it } } == true
            val copied = client.request(PhoneMessage.CopySelection(session.guid))
            val leftText = (copied as? MacMessage.SelectionText)?.text
            report("link-tap: found on row $foundRow; selection report cleared: $cleared; copySelection afterwards: ${leftText?.let { "${it.length} chars" } ?: copied.javaClass.simpleName}")
            report("link-tap: summary: ${logs.size} lookup log lines")
            assertEquals("the tap found the printed URL", TappedLink(URL, macLocal = false), found)
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
        const val URL = "https://example.com/path"
        val SETTLE = 3.seconds

        /** After `clear` the output is on the first row, or the next when the prompt takes one. */
        const val ROWS_TRIED = 4

        /** Inside the URL on its row: "https://exa|mple…". */
        const val TAP_COLUMN = 10
    }
}
