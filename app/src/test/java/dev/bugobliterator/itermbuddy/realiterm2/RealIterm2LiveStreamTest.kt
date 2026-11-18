package dev.bugobliterator.itermbuddy.realiterm2

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.StreamCodec
import dev.bugobliterator.itermbuddy.core.wire.StreamEndReason
import dev.bugobliterator.itermbuddy.core.stream.FakeVideoOutput
import dev.bugobliterator.itermbuddy.core.stream.HevcFramingException
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.MediaFrame
import dev.bugobliterator.itermbuddy.core.stream.MediaFrameException
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.StreamController
import dev.bugobliterator.itermbuddy.core.stream.StreamInput
import dev.bugobliterator.itermbuddy.core.stream.StreamSink
import dev.bugobliterator.itermbuddy.core.stream.VideoReadiness
import dev.bugobliterator.itermbuddy.core.stream.accessUnitToAnnexB
import dev.bugobliterator.itermbuddy.core.stream.hasCompleteHevcParameterSets
import dev.bugobliterator.itermbuddy.core.stream.parseParameterSets
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.failWith
import dev.bugobliterator.itermbuddy.realiterm2.RealIterm2Rig.Companion.report
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 2 of the real-Mac CI, the live view: the app's own [StreamController] starts a live
 * stream of a real iTerm2 session, as the live screen does, and the test checks what iTerm2's
 * VideoToolbox encoder sends on the hosted runner.
 *
 * The client hands every stream input to the controller through a sink that also records the
 * stream-scoped control messages and counts media frames. The controller's video output is
 * [FakeVideoOutput]: the JVM cannot decode HEVC, so it takes a config only when its parameter
 * sets are complete, as the real output does, and records every frame it is given. In order:
 * - [StreamController.open] sends `startSessionStream`; a `streamStarted` with codec HEVC comes
 *   back for that request and the controller adopts it;
 * - a `streamConfig` for that stream arrives, its `codecExtradata` parses
 *   ([parseParameterSets]) and holds a VPS, an SPS and a PPS ([hasCompleteHevcParameterSets]),
 *   and the controller goes Live;
 * - a keyframe media frame of that stream reaches the output, its access unit converts to
 *   Annex-B ([accessUnitToAnnexB]) and holds an IDR NAL (type 19 or 20); every media frame
 *   parsed as a [MediaFrame];
 * - [StreamController.close] sends `stopSessionStream` and iTerm2 answers `streamEnded`
 *   (`stoppedByClient`) for that stream; the client stays Connected and answers a ping.
 *
 * Runs only under `realIterm2Test` with `REAL_ITERM2=1`; skipped otherwise. It prints states,
 * stream IDs, counts, sizes and NAL types, never frame bytes or terminal content.
 */
class RealIterm2LiveStreamTest {
    private val rig = RealIterm2Rig()

    @Before
    fun requireRealIterm2() = RealIterm2Rig.requireRealIterm2()

    @After
    fun tearDown() = rig.close()

    @Test
    fun liveStreamStartsSendsAnHevcKeyframeAndStops() = runBlocking {
        val nowMillis = { System.nanoTime() / NANOS_PER_MILLI }
        val video = FakeVideoOutput(nowMillis).apply {
            configureResult = { config ->
                if (hasCompleteHevcParameterSets(config.codecExtradata)) VideoReadiness.Ready else VideoReadiness.WaitingForConfig
            }
        }
        // Wired as the app wires them (wireHome): the client first, with a sink that forwards
        // to the controller built on it, and only then started.
        val recorder = RecordingSink()
        val client = rig.client(recorder)
        val controller = StreamController(
            link = client,
            video = video,
            scope = rig.scope,
            nowMillis = nowMillis,
            log = { report("stream: $it") },
        )
        recorder.target = controller
        rig.connect(client)

        val session = rig.firstTerminalSession(client)
        controller.open(session)

        val started = rig.poll { recorder.controls.firstNotNullOfOrNull { it as? MacMessage.StreamStarted } }
            ?: failWith("no streamStarted within ${rig.requestTimeout}; controller ${controller.state.value}")
        report("streamStarted: stream ${started.streamID}, codec ${started.codec}")
        assertEquals("the stream's codec", StreamCodec.HEVC, started.codec)
        val active = rig.poll { controller.activeStream.value?.takeIf { it.streamID == started.streamID } }
            ?: failWith("the controller did not adopt stream ${started.streamID}: ${controller.state.value}")
        assertEquals(session.guid, active.sessionGuid)

        val config = rig.poll { recorder.configs.firstOrNull { it.streamID == started.streamID } }
            ?: failWith("no streamConfig for stream ${started.streamID} within ${rig.requestTimeout}")
        val setTypes = try {
            parseParameterSets(config.codecExtradata).map(::nalTypeOf)
        } catch (e: HevcFramingException) {
            failWith("the streamConfig's codecExtradata does not parse: ${e.message}")
        }
        report(
            "streamConfig: stream ${config.streamID}, generation ${config.generationId}, ${config.pixelWidth}x${config.pixelHeight} px, " +
                "${config.columns}x${config.rows} cells, parameter set NAL types $setTypes",
        )
        assertTrue("the parameter sets are not a VPS, an SPS and a PPS: NAL types $setTypes", hasCompleteHevcParameterSets(config.codecExtradata))

        val live = rig.poll { controller.state.value as? LiveViewState.Live }
            ?: failWith("the controller is not Live within ${rig.requestTimeout}: ${controller.state.value}")
        report("live view: Live, stalled=${live.stalled}")

        val keyframe = rig.poll { video.submits.firstOrNull { it.streamID == started.streamID && it.isKeyframe } }
            ?: failWith(
                "no keyframe of stream ${started.streamID} reached the video output within ${rig.requestTimeout}: " +
                    "${recorder.mediaFrames.get()} media frames, ${video.submits.size} submitted, ${recorder.unparseable.get()} unparseable",
            )
        val nalTypes = try {
            nalTypesOf(keyframe)
        } catch (e: HevcFramingException) {
            failWith("the keyframe's access unit does not parse: ${e.message}")
        }
        report(
            "keyframe: version ${keyframe.version}, flags ${keyframe.flags}, sequence ${keyframe.sequence}, " +
                "generation ${keyframe.generationId}, ${keyframe.payloadSize} bytes, NAL types $nalTypes",
        )
        assertTrue("the keyframe holds no IDR NAL (19 or 20): NAL types $nalTypes", nalTypes.any { it in IDR_TYPES })
        assertEquals("media frames that did not parse", 0, recorder.unparseable.get())

        controller.close()
        val ended = rig.poll { recorder.controls.firstNotNullOfOrNull { (it as? MacMessage.StreamEnded)?.takeIf { e -> e.streamID == started.streamID } } }
            ?: failWith("no streamEnded for stream ${started.streamID} within ${rig.requestTimeout} of stopping it")
        report(
            "streamEnded: stream ${ended.streamID}, ${ended.reason}; ${recorder.mediaFrames.get()} media frames of " +
                "${recorder.mediaBytes.get()} bytes in all, ${video.submits.size} submitted, ${video.configures.size} configs",
        )
        assertEquals("why the stream ended", StreamEndReason.STOPPED_BY_CLIENT, ended.reason)
        assertEquals(LiveViewState.Closed, controller.state.value)
        assertEquals(null, controller.activeStream.value)
        assertEquals("still answers once the stream is stopped", MacMessage.Pong, client.request(PhoneMessage.Ping))
        assertTrue("still Connected", client.state.value is ClientState.Connected)
    }

    /**
     * Forwards every input to [target] (the controller) and records, on the way, the
     * stream-scoped control messages of the current link and the media frames' count and size.
     * Holds no frame bytes.
     */
    private class RecordingSink : StreamSink {
        @Volatile var target: StreamSink? = null
        val controls = CopyOnWriteArrayList<MacMessage>()
        val configs = CopyOnWriteArrayList<StreamConfig>()
        val mediaFrames = AtomicInteger()
        val mediaBytes = AtomicLong()
        val unparseable = AtomicInteger()

        override suspend fun accept(input: StreamInput) {
            when (input) {
                is StreamInput.Control -> {
                    controls += input.message
                    (input.message as? MacMessage.StreamConfigMessage)?.let { configs += it.config }
                }
                is StreamInput.Media -> {
                    mediaFrames.incrementAndGet()
                    mediaBytes.addAndGet(input.size.toLong())
                    try {
                        input.parse()
                    } catch (_: MediaFrameException) {
                        unparseable.incrementAndGet()
                    }
                }
                else -> Unit
            }
            checkNotNull(target) { "the stream controller is not wired yet" }.accept(input)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** IDR_W_RADL and IDR_N_LP (H.265 table 7-1). */
        val IDR_TYPES = setOf(19, 20)

        fun nalTypeOf(nal: ByteArray): Int = if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3F

        /** The NAL unit types of [frame]'s access unit, in order; throws [HevcFramingException] when it does not parse. */
        fun nalTypesOf(frame: MediaFrame): List<Int> {
            frame.annexBAccessUnit() // validates the AVCC framing, as the decoder's input does
            val payload = frame.payload
            val types = mutableListOf<Int>()
            var cursor = 0
            while (cursor < payload.size) {
                val length = ((payload[cursor].toLong() and 0xFF) shl 24) or ((payload[cursor + 1].toLong() and 0xFF) shl 16) or
                    ((payload[cursor + 2].toLong() and 0xFF) shl 8) or (payload[cursor + 3].toLong() and 0xFF)
                cursor += NAL_LENGTH_SIZE
                types += if (length == 0L) -1 else (payload[cursor].toInt() shr 1) and 0x3F
                cursor += length.toInt()
            }
            return types
        }

        const val NAL_LENGTH_SIZE = 4
    }
}
