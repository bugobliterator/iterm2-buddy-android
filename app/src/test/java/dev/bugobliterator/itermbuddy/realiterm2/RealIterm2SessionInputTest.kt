package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.CompanionClient
import dev.bugobliterator.itermbuddy.core.input.InputSender
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.SessionContent
import dev.bugobliterator.itermbuddy.core.wire.SessionScreenInfo
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.isPng
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 2 of the real-Mac CI, input: the phone types a command into a real iTerm2 session through
 * the app's own input path and sees the session change.
 *
 * The command goes through [InputSender.typeThenReturn], the composer's Paste ⏎: a single-line
 * draft typed as `sendKey` text events, then Return (`sendKey` `"\n"`), which iTerm2 runs
 * through the session's key mapper into the shell. The command is
 * `seq -f buddy-ci-<random>-%g <OUTPUT_LINES>`: a marker unique to the run that prints more
 * lines than a default window has rows, so the session's line count (scrollback plus screen,
 * `fetchSessionScreenInfo`) must grow once the shell has run it. The PNG of the session's last
 * lines (`fetchSessionContent`) must change too. The PNG cannot be read on the JVM, so the test
 * compares line counts and PNG digests, polling until both changed or the request timeout passes.
 *
 * The app sends input only to the session of the adopted stream; this test has no stream, so its
 * [InputTarget] is the link that is up (watch epoch 0) and the sender's `isCurrent` checks that
 * link is still the current one.
 *
 * Runs only under `realIterm2Test` with `REAL_ITERM2=1`; skipped otherwise. It prints states,
 * line counts and PNG sizes, never terminal content or the command.
 */
class RealIterm2SessionInputTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun typedCommandRunsInTheSessionAndChangesItsContent() = runBlocking {
        val client = rig.connect()
        val linkId = rig.linkId.value ?: failWith("Connected but no link is up")
        val session = rig.firstTerminalSession(client)

        val before = snapshot(client, session.guid)
        report("before typing: ${before.lineCount} lines, last ${before.tail.lineCount} rendered as a PNG of ${before.tail.pngSize} bytes")

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
        val marker = "buddy-ci-" + randomHex(MARKER_BYTES)
        sender.typeThenReturn(InputTarget(linkId, watchEpoch = 0, sessionGuid = session.guid), "seq -f $marker-%g $OUTPUT_LINES")
        report("typed a ${marker.length}-character marker command and Return on link $linkId")

        var last = before
        val after = rig.poll {
            snapshot(client, session.guid).also { last = it }.takeIf { it.lineCount > before.lineCount && it.tailDigest != before.tailDigest }
        }
        val grew = last.lineCount - before.lineCount
        val tailChanged = last.tailDigest != before.tailDigest
        report("after typing: ${last.lineCount} lines (grew by $grew), last lines ${if (tailChanged) "changed" else "unchanged"}, PNG of ${last.tail.pngSize} bytes")
        assertEquals("the input sender reported a problem: $senderLog", emptyList<String>(), senderLog.toList())
        if (after == null) {
            failWith(
                when {
                    tailChanged -> "the session changed but its line count did not grow within ${rig.requestTimeout}: " +
                        "the text arrived, but the command did not run (Return lost, or the shell not at a prompt)"
                    else -> "the session did not change within ${rig.requestTimeout} of typing (${last.lineCount} lines, as before)"
                },
            )
        }
        assertTrue("still Connected", client.state.value is ClientState.Connected)
    }

    /** The session's line count and a digest of the PNG of its last [TAIL_LINES] lines. */
    private class Snapshot(val lineCount: Int, val tail: SessionContent) {
        val tailDigest: String = MessageDigest.getInstance("SHA-256").digest(tail.pngData).joinToString("") { "%02x".format(it) }
    }

    private suspend fun snapshot(client: CompanionClient, guid: String): Snapshot {
        val infoReply = client.request(PhoneMessage.FetchSessionScreenInfo(guid))
        val info: SessionScreenInfo = (infoReply as? MacMessage.SessionScreenInfoMessage)?.info
            ?: failWith("fetchSessionScreenInfo got $infoReply")
        val first = maxOf(0, info.lineCount - TAIL_LINES)
        val contentReply = client.request(PhoneMessage.FetchSessionContent(guid, firstLine = first, lineCount = TAIL_LINES))
        val content = (contentReply as? MacMessage.SessionContentMessage)?.content
            ?: failWith("fetchSessionContent got $contentReply")
        assertEquals(guid, content.guid)
        assertTrue("the content is not a PNG (${content.pngSize} bytes)", isPng(content.pngData))
        return Snapshot(info.lineCount, content)
    }

    private companion object {
        /** More than a default window's 25 rows, so the output always pushes lines into scrollback. */
        const val OUTPUT_LINES = 100

        /** The bottom of the screen: where the prompt, the command and its output land. */
        const val TAIL_LINES = 10
        const val MARKER_BYTES = 6

        fun randomHex(bytes: Int): String =
            ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
