package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.isPng
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import dev.bugobliterator.itermbuddy.store.PairingStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 1 of the real-Mac CI: the app's own `CompanionClient`, as the phone, against a real
 * iTerm2 3.7.7 on a macOS runner (`.github/workflows/real-iterm2.yml`, `ci/real-iterm2/`).
 *
 * From the seeded, confirmed pairing the client must reach Connected ([RealIterm2Rig.connect]),
 * answer a ping, get a session tree with at least one session, and get rendered content for a
 * terminal session with `fetchSessionContent`.
 *
 * Runs only under `realIterm2Test` with `REAL_ITERM2=1`; skipped otherwise. It prints states,
 * the client's diagnostics, counts and sizes, never terminal content.
 */
class RealIterm2PairingTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun seededPairingReachesTheSessionTree() = runBlocking {
        val client = rig.connect()
        assertEquals("the room is registered once connected", PairingStage.REGISTERED, rig.storedStage())

        assertEquals(MacMessage.Pong, client.request(PhoneMessage.Ping))
        report("ping: pong")

        val tree = rig.sessionTree(client)
        val sessions = RealIterm2Rig.sessionsOf(tree)
        report("session tree: ${tree.windows.size} windows, ${sessions.size} sessions")
        assertTrue("the session tree has no session", sessions.isNotEmpty())

        val terminal = sessions.firstOrNull { !it.isBrowser } ?: failWith("every session is a browser: $sessions")
        val contentReply = client.request(PhoneMessage.FetchSessionContent(terminal.guid, firstLine = 0, lineCount = CONTENT_LINES))
        val content = (contentReply as? MacMessage.SessionContentMessage)?.content
            ?: failWith("fetchSessionContent got $contentReply")
        report("session content: ${content.lineCount} lines from line ${content.firstLine}, PNG of ${content.pngSize} bytes")
        assertEquals(terminal.guid, content.guid)
        assertTrue("no lines rendered", content.lineCount > 0)
        assertTrue("the content is not a PNG (${content.pngSize} bytes)", isPng(content.pngData))
        assertTrue("still Connected", client.state.value is ClientState.Connected)
    }

    private companion object {
        /** Enough for a prompt; the Mac clamps the range to the lines the session has. */
        const val CONTENT_LINES = 10
    }
}
