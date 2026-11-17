package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.LiveProblem
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.video.FakeCodecFactory
import dev.bugobliterator.itermbuddy.video.FixedCatalog
import dev.bugobliterator.itermbuddy.video.HevcVideoOutput
import dev.bugobliterator.itermbuddy.video.TestDecoderThread
import dev.bugobliterator.itermbuddy.video.decoder
import dev.bugobliterator.itermbuddy.video.fakeSurface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The stale-frame rule end to end: the real [StreamController] driving the real [HevcVideoOutput]
 * (fake codec, real decoder thread), with the visibility read exactly as the live screen reads it
 * ([videoVisible]: the state first, then the output's counter). The TextureView keeps its last
 * buffer through a reset, so a previous session's or stream's picture must never be shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoVisibilityTest {
    private val thread = TestDecoderThread()
    private val factory = FakeCodecFactory(thread)

    @After
    fun tearDown() {
        thread.quit()
    }

    private inner class Rig(val test: TestScope) {
        val link = ScriptedStreamLink()
        val video = HevcVideoOutput(
            catalog = FixedCatalog(listOf(decoder("hw.hevc"))),
            codecFactory = factory::create,
            nowMillis = { test.currentTime },
            decoderThread = thread,
            log = {},
        )
        val controller = StreamController(
            link, video, test.backgroundScope, nowMillis = { test.currentTime }, log = {}, ackContext = EmptyCoroutineContext,
        )

        val visible: Boolean get() = videoVisible(controller.state, video.stats)

        val state: LiveViewState get() = controller.state.value

        fun settle() {
            test.runCurrent()
            thread.drain()
            test.runCurrent()
        }

        /** Answers the pending start with stream [streamID] on [linkId] and sends its config. */
        suspend fun start(linkId: Long, streamID: Long) {
            val pending = link.requests.receive()
            val started = MacMessage.StreamStarted(streamID, StreamCodec.HEVC)
            controller.accept(StreamInput.Control(linkId, pending.requestID, started))
            pending.reply.complete(started)
            settle()
            controller.accept(StreamInput.Control(linkId, null, MacMessage.StreamConfigMessage(config(streamID))))
            settle()
        }

        /** A keyframe on stream [streamID], accepted by the codec. */
        suspend fun keyframe(linkId: Long, streamID: Long, pts: Long) {
            controller.accept(StreamInput.Media(linkId, byteArrayOf(0x01) + frame(streamID, pts)))
            settle()
        }

        /** The current codec outputs the frame with [pts]. */
        fun output(pts: Long) {
            factory.last.output(pts * 1000)
            settle()
        }
    }

    @Test
    fun videoHiddenUntilFirstOutputAcrossSwitchEndFailureAndReconnect() = runTest {
        val rig = Rig(this)
        rig.video.attachSurface(fakeSurface())
        rig.controller.accept(StreamInput.LinkUp(1, 13))
        rig.controller.open(A)
        rig.settle()
        assertEquals(LiveViewState.Starting(A), rig.state)
        assertFalse(rig.visible)

        // Live for A, but nothing output yet: hidden; the first output shows it.
        rig.start(linkId = 1, streamID = 1)
        assertTrue("${rig.state}", rig.state is LiveViewState.Live)
        assertFalse("Live before the first output", rig.visible)
        rig.keyframe(1, 1, pts = 10)
        assertFalse(rig.visible)
        rig.output(10)
        assertTrue("Live after the first output", rig.visible)

        // Switch to B. A frame of A still in A's codec is output after the switch: it must not count.
        rig.keyframe(1, 1, pts = 20)
        val codecOfA = factory.last
        rig.controller.open(B)
        rig.settle()
        assertEquals(LiveViewState.Starting(B), rig.state)
        assertFalse("A's picture under B's Starting", rig.visible)
        codecOfA.output(20_000)
        rig.settle()
        assertFalse(rig.visible)
        rig.start(linkId = 1, streamID = 2)
        assertTrue("${rig.state}", rig.state is LiveViewState.Live)
        assertFalse("A's picture under B's Live", rig.visible)
        rig.keyframe(1, 2, pts = 30)
        rig.output(30)
        assertTrue(rig.visible)

        // Ended: hidden, and Restart comes back hidden until its own first output.
        rig.controller.accept(StreamInput.Control(1, null, MacMessage.StreamEnded(2, StreamEndReason.DATA_LIMIT_REACHED)))
        rig.settle()
        assertEquals(LiveViewState.Ended(B, StreamEndReason.DATA_LIMIT_REACHED), rig.state)
        assertFalse(rig.visible)
        rig.controller.retry()
        rig.settle()
        rig.start(linkId = 1, streamID = 3)
        assertTrue("${rig.state}", rig.state is LiveViewState.Live)
        assertFalse(rig.visible)
        rig.keyframe(1, 3, pts = 40)
        rig.output(40)
        assertTrue(rig.visible)

        // Failed (too large for the decoder): hidden. A new size rebuilds the decoder, which
        // counts as a new picture: hidden again until it outputs.
        rig.controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(config(3, width = 5120, height = 2880))))
        rig.settle()
        assertTrue("${rig.state}", (rig.state as? LiveViewState.Failed)?.problem is LiveProblem.TooLarge)
        assertFalse(rig.visible)
        rig.controller.accept(StreamInput.Control(1, null, MacMessage.StreamConfigMessage(config(3, width = 1440, height = 900))))
        rig.settle()
        assertTrue("${rig.state}", rig.state is LiveViewState.Live)
        assertFalse(rig.visible)
        rig.keyframe(1, 3, pts = 50)
        rig.output(50)
        assertTrue(rig.visible)

        // Reconnecting: hidden; back to Live on the new link only after its first output.
        rig.controller.accept(StreamInput.LinkDown(1))
        rig.settle()
        assertEquals(LiveViewState.Reconnecting(B), rig.state)
        assertFalse(rig.visible)
        rig.link.currentLink = 2
        rig.controller.accept(StreamInput.LinkUp(2, 13))
        rig.settle()
        assertFalse(rig.visible)
        rig.start(linkId = 2, streamID = 1)
        assertTrue("${rig.state}", rig.state is LiveViewState.Live)
        assertFalse("stream 1 on link 2 is not stream 1 on link 1", rig.visible)
        rig.keyframe(2, 1, pts = 60)
        rig.output(60)
        assertTrue(rig.visible)

        // Losing the surface (rotation) keeps the picture rule as it is: the new surface is blank,
        // never stale, and nothing is reset.
        rig.video.detachSurface()
        rig.video.attachSurface(fakeSurface())
        rig.settle()
        assertTrue(rig.visible)

        // Back to the list: Closed, hidden.
        rig.controller.close()
        rig.settle()
        assertFalse(rig.visible)
    }

    private companion object {
        val A = SessionSummary("guid-alpha", "alpha", "", isBrowser = false)
        val B = SessionSummary("guid-bravo", "bravo", "", isBrowser = false)

        /** The terminal fixture's config (real parameter sets) for [streamID]. */
        fun config(streamID: Long, width: Int = 1280, height: Int = 800): StreamConfig {
            val file = Json.parseToJsonElement(Fixtures.read("messages/live-session/stream-config-terminal.json")).jsonObject
            val fixture = (decodeEnvelope(file.getValue("json").toString().toByteArray()).second as MacMessage.StreamConfigMessage).config
            return fixture.copy(streamID = streamID, pixelWidth = width, pixelHeight = height)
        }

        /** A version 2 keyframe whose payload is one 12-byte NAL. */
        fun frame(streamID: Long, pts: Long): ByteArray {
            val nal = 12
            val buffer = ByteBuffer.allocate(34 + 4 + nal)
            buffer.put(2.toByte())
            buffer.put(1.toByte())
            buffer.putInt(streamID.toInt())
            buffer.putInt(pts.toInt())
            buffer.putLong(pts)
            buffer.putInt(1)
            buffer.putLong(0)
            buffer.putInt(4 + nal)
            buffer.putInt(nal)
            return buffer.array()
        }
    }
}
