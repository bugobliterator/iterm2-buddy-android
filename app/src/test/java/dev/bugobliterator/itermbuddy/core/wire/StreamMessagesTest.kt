package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.KeyModifiers
import dev.bugobliterator.itermbuddy.core.input.SpecialKey
import dev.bugobliterator.itermbuddy.core.sessions.PaneEntry
import dev.bugobliterator.itermbuddy.core.sessions.PeerEntry
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionTree
import dev.bugobliterator.itermbuddy.core.sessions.TabEntry
import dev.bugobliterator.itermbuddy.core.sessions.WindowEntry
import dev.bugobliterator.itermbuddy.core.stream.CellGeometry
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMessagesTest {
    /** [bytes] is the sample's `json` text exactly as the fixture file holds it, escapes included. */
    private class Sample(val path: String, val direction: String, val json: JsonObject, val bytes: ByteArray) {
        val name: String get() = path.substringAfterLast('/').removeSuffix(".json")
    }

    /** Slices the raw `json` value, which the generator writes last, out of a fixture file. */
    private fun rawJson(path: String, text: String): String {
        val marker = "\"json\" : "
        check(text.indexOf(marker) == text.lastIndexOf(marker) && marker in text) { "$path: no single json key" }
        val raw = text.substringAfter(marker).trimEnd().dropLast(1).trimEnd()
        check(raw.endsWith("}")) { "$path: json is not the last value" }
        return raw
    }

    private val samples: List<Sample> by lazy {
        val paths = Fixtures.list("messages/live-session")
        assertTrue("messages/live-session is empty", paths.isNotEmpty())
        paths.map { path ->
            val text = Fixtures.read(path)
            val file = Json.parseToJsonElement(text).jsonObject
            val json = file.getValue("json").jsonObject
            val raw = rawJson(path, text)
            assertEquals("$path: sliced json", json, Json.parseToJsonElement(raw))
            Sample(path, file.getValue("direction").jsonPrimitive.content, json, raw.toByteArray(Charsets.UTF_8))
        }
    }

    private fun samples(direction: String): List<Sample> =
        samples.filter { it.direction == direction }.also { assertTrue("no $direction samples", it.isNotEmpty()) }

    private fun sample(name: String): Sample = samples.single { it.name == name }

    /** Runs [check] on each sample; any failure becomes an AssertionError naming the sample. */
    private fun forEachSample(samples: List<Sample>, check: (Sample) -> Unit) {
        for (sample in samples) {
            try {
                check(sample)
            } catch (e: Throwable) {
                throw AssertionError("${sample.path}: $e", e)
            }
        }
    }

    private fun tree(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    private val guid = "ptys_7K2M9Q4R8T1VH"

    private fun key(key: Key, modifiers: KeyModifiers = KeyModifiers()) = PhoneMessage.SendKey(guid, KeyEvent(key, modifiers))

    /** Every phoneToMac sample, with the message and request ID iOS sends it with. */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        "fetch-session-tree" to (PhoneMessage.FetchSessionTree to 4L),
        "start-session-stream" to (PhoneMessage.StartSessionStream(guid) to 5L),
        "stop-session-stream" to (PhoneMessage.StopSessionStream(1) to null),
        "stream-ack" to (PhoneMessage.StreamAck(1, 523118042) to null),
        "request-keyframe" to (PhoneMessage.RequestKeyframe(1) to null),
        "resize-session" to (PhoneMessage.ResizeSession(guid, 60, 45) to null),
        "paste-text-multiline" to (PhoneMessage.PasteText(guid, "make test\ngit status --short\n") to null),
        "send-key-text" to (key(Key.Text("ls")) to null),
        "send-key-return" to (key(Key.Text("\n")) to null),
        "send-key-control-c" to (key(Key.Text("c"), KeyModifiers(control = true)) to null),
        "send-key-left-option-text" to (key(Key.Text("b"), KeyModifiers(leftOption = true)) to null),
    ) + SpecialKey.entries.associate { "send-key-special-${it.raw}" to (key(Key.Special(it)) to null) }

    private fun summary(guid: String, name: String, subtitle: String, isBrowser: Boolean = false) =
        SessionSummary(guid, name, subtitle, isBrowser)

    private val zsh = summary("ptys_7K2M9Q4R8T1VH", "zsh", "~/src/widget")
    private val planner = summary("ptys_1F5Y7R3MQ9KD4", "claude", "planning")

    /** The sample trees: [withTail] adds the split pane's second pane, [withBrowser] the browser window. */
    private fun expectedTree(withTail: Boolean, withBrowser: Boolean): SessionTree {
        val widgetPanes = listOfNotNull(
            PaneEntry(
                zsh,
                listOf(
                    PeerEntry("Implementer", zsh),
                    PeerEntry("Code Review", summary("ptys_3HX6W0ZD5N2BT", "Code Review", "reviewing widget.c")),
                ),
            ),
            if (withTail) PaneEntry(summary("ptys_9PJ4C8EGA6S0J", "tail", "tail -f build.log"), emptyList()) else null,
        )
        val agents = PaneEntry(
            planner,
            listOf(PeerEntry("Planner", planner), PeerEntry("Tester", summary("ptys_6V0T2B8NW4HEQ", "claude", ""))),
        )
        val first = WindowEntry(
            "demo@buildhost: ~/src/widget",
            listOf(
                TabEntry("widget", widgetPanes),
                TabEntry("agents", listOf(agents)),
                TabEntry("Tab 3", listOf(PaneEntry(summary("ptys_8M3B5X1QJ7TW1", "vim", "notes.md"), emptyList()))),
            ),
        )
        val browser = WindowEntry(
            "Window 2",
            listOf(
                TabEntry(
                    "Widget Docs",
                    listOf(PaneEntry(summary("ptys_4Q8S1D6KZ3MAC", "Widget Docs", "", isBrowser = true), emptyList())),
                ),
            ),
        )
        return SessionTree(listOfNotNull(first, if (withBrowser) browser else null))
    }

    private val extradata: ByteArray = Base64.getDecoder().decode(
        "AAMAAAAYQAEMAf//AWAAAAMAkAAAAwAAAwB4koCQAAAAKkIBAQFgAAADAJAAAAMAAAMAeKACgIAyFlkqSTK8BaAgAAADACAAAAMDwQAAAAdEAcFytGJA",
    )

    private fun terminalConfig(cellGeometry: CellGeometry? = CellGeometry(15.75, 32.0, 10.0, 0.0)) = StreamConfig(
        streamID = 1, generationId = 1, codecExtradata = extradata, pixelWidth = 1280, pixelHeight = 800,
        scale = 2.0, columns = 80, rows = 25, cellGeometry = cellGeometry, firstAbsLine = 3000, totalLines = 1025,
        canResize = true, altScreen = false, scrollWheelReporting = false, isBrowser = false,
    )

    private val browserConfig = StreamConfig(
        streamID = 2, generationId = 1, codecExtradata = extradata, pixelWidth = 1280, pixelHeight = 800,
        scale = 2.0, columns = 0, rows = 0, cellGeometry = null, firstAbsLine = 0, totalLines = 0,
        canResize = false, altScreen = false, scrollWheelReporting = false, isBrowser = true,
    )

    private fun error(sample: Sample): MacMessage.Error = MacMessage.Error(
        sample.json.getValue("payload").jsonObject.getValue("error").jsonObject.getValue("_0").jsonObject,
    )

    /** Every macToPhone sample by name, with the request ID and message it decodes to. */
    private val macMessages: Map<String, (Sample) -> Pair<Long?, MacMessage>> = mapOf(
        "session-tree-reply" to { _ -> 4L to MacMessage.SessionTreeMessage(expectedTree(withTail = true, withBrowser = true)) },
        "session-tree-reply-without-is-browser" to
            { _ -> 4L to MacMessage.SessionTreeMessage(expectedTree(withTail = true, withBrowser = false)) },
        "session-tree-push" to { _ -> null to MacMessage.SessionTreeMessage(expectedTree(withTail = false, withBrowser = true)) },
        "stream-started" to { _ -> 5L to MacMessage.StreamStarted(1, StreamCodec.HEVC) },
        "stream-started-browser" to { _ -> 6L to MacMessage.StreamStarted(2, StreamCodec.HEVC) },
        "stream-config-terminal" to { _ -> null to MacMessage.StreamConfigMessage(terminalConfig()) },
        "stream-config-terminal-without-is-browser" to { _ -> null to MacMessage.StreamConfigMessage(terminalConfig()) },
        "stream-config-terminal-cell-geometry-absent" to { _ -> null to MacMessage.StreamConfigMessage(terminalConfig(null)) },
        "stream-config-browser" to { _ -> null to MacMessage.StreamConfigMessage(browserConfig) },
        "stream-ended-stoppedByClient" to { _ -> null to MacMessage.StreamEnded(1, StreamEndReason.STOPPED_BY_CLIENT) },
        "stream-ended-sessionClosed" to { _ -> null to MacMessage.StreamEnded(1, StreamEndReason.SESSION_CLOSED) },
        "stream-ended-superseded" to { _ -> null to MacMessage.StreamEnded(1, StreamEndReason.SUPERSEDED) },
        "stream-ended-dataLimitReached" to { _ -> null to MacMessage.StreamEnded(1, StreamEndReason.DATA_LIMIT_REACHED) },
        "stream-ended-error" to { _ -> null to MacMessage.StreamEnded(1, StreamEndReason.ERROR) },
        "error-no-supported-codec" to { sample -> 5L to error(sample) },
        "error-unknown-session" to { sample -> 5L to error(sample) },
        "error-unknown-session-not-started" to { sample -> 5L to error(sample) },
        "messages-removed" to { _ ->
            null to MacMessage.MessagesRemoved(
                "5E0B7C1A-9D24-4F36-8A51-0C7E2D9B4F18",
                listOf("A1C3E5F7-0B2D-4F6A-8C1E-3D5F7A9B0C2D", "B2D4F6A8-1C3E-4A7B-9D2F-4E6A8B0C1D3E"),
            )
        },
        "ai-availability-changed" to { _ -> null to MacMessage.AiAvailabilityChanged(false) },
    )

    @Test
    fun liveSessionPhoneSamplesEncodeExactly() {
        val phoneSamples = samples("phoneToMac")
        forEachSample(phoneSamples) { sample ->
            val (message, requestID) = phoneMessages[sample.name] ?: throw AssertionError("no table entry")
            assertEquals(sample.path, sample.json, tree(encodeEnvelope(message, requestID)))
        }
        assertEquals("table entries without a sample", phoneMessages.keys, phoneSamples.map { it.name }.toSet())
    }

    @Test
    fun liveSessionMacSamplesDecode() {
        val macSamples = samples("macToPhone")
        forEachSample(macSamples) { sample ->
            val expected = macMessages[sample.name]?.invoke(sample) ?: throw AssertionError("no expectation")
            assertEquals(sample.path, expected, decodeEnvelope(sample.bytes))
        }
        assertEquals("expectations without a sample", macMessages.keys, macSamples.map { it.name }.toSet())

        val reply = decodeEnvelope(sample("session-tree-reply").bytes).second as MacMessage.SessionTreeMessage
        assertEquals(2, reply.tree.windows.size)
        val browser = reply.tree.windows.flatMap { it.tabs }.flatMap { it.panes }.single { it.session.isBrowser }
        assertEquals("ptys_4Q8S1D6KZ3MAC", browser.session.guid)

        val older = decodeEnvelope(sample("session-tree-reply-without-is-browser").bytes).second
        val olderSessions = (older as MacMessage.SessionTreeMessage).tree.windows
            .flatMap { it.tabs }.flatMap { it.panes }.flatMap { pane -> listOf(pane.session) + pane.peers.map { it.session } }
        assertTrue(olderSessions.isNotEmpty())
        assertTrue("absent isBrowser reads false", olderSessions.none { it.isBrowser })

        val browserConfig = (decodeEnvelope(sample("stream-config-browser").bytes).second as MacMessage.StreamConfigMessage).config
        assertEquals(0, browserConfig.columns)
        assertEquals(0, browserConfig.rows)
        assertNull(browserConfig.cellGeometry)
        assertTrue(browserConfig.isBrowser)

        val olderConfig = decodeEnvelope(sample("stream-config-terminal-without-is-browser").bytes).second
        assertFalse((olderConfig as MacMessage.StreamConfigMessage).config.isBrowser)
        val noGeometry = decodeEnvelope(sample("stream-config-terminal-cell-geometry-absent").bytes).second
        assertNull((noGeometry as MacMessage.StreamConfigMessage).config.cellGeometry)
    }

    @Test
    fun everySpecialKeyHasASample() {
        val prefix = "send-key-special-"
        val sampled = samples("phoneToMac").map { it.name }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        assertEquals(25, SpecialKey.entries.size)
        assertEquals(sampled.toSet(), SpecialKey.entries.map { it.raw }.toSet())
        assertEquals(sampled.size, sampled.toSet().size)
    }

    @Test
    fun startParamsAreExactlyIos() {
        val payload = PhoneMessage.StartSessionStream(guid).payload()
        val params = payload.getValue("startSessionStream").jsonObject.getValue("params")
        assertEquals("""{"supportedCodecs":["hevc"],"maxFrameRate":30,"maxMediaFrameVersion":2}""", params.toString())
        val withoutVersion = PhoneMessage.StartSessionStream(guid, StreamParams(maxMediaFrameVersion = null)).payload()
        assertEquals(
            """{"supportedCodecs":["hevc"],"maxFrameRate":30}""",
            withoutVersion.getValue("startSessionStream").jsonObject.getValue("params").toString(),
        )
    }

    @Test
    fun revisionThirteenChatEventsDecode() {
        // Revision 13 claims messagesRemoved (12) and aiAvailabilityChanged (13); Agent Chat decodes both.
        assertTrue(decodeEnvelope(sample("messages-removed").bytes).second is MacMessage.MessagesRemoved)
        assertEquals(MacMessage.AiAvailabilityChanged(false), decodeEnvelope(sample("ai-availability-changed").bytes).second)
    }

    @Test
    fun unknownEndReasonIsUnknown() {
        val text = """{"payload":{"streamEnded":{"reason":"futureReason","streamID":3}}}"""
        assertEquals(null to MacMessage.StreamEnded(3, StreamEndReason.UNKNOWN), decodeEnvelope(text.toByteArray()))
        assertNull(StreamEndReason.UNKNOWN.raw)
        // A missing or non-string reason is UNKNOWN too: a stream end is never refused.
        for (body in listOf("""{"streamID":3}""", """{"streamID":3,"reason":1}""", """{"streamID":3,"reason":null}""")) {
            val frame = """{"payload":{"streamEnded":$body}}"""
            assertEquals(frame, null to MacMessage.StreamEnded(3, StreamEndReason.UNKNOWN), decodeEnvelope(frame.toByteArray()))
        }
        // The stream ID is still required.
        val noStream = """{"payload":{"streamEnded":{"reason":"superseded"}}}"""
        assertThrows(noStream, WireDecodeException::class.java) { decodeEnvelope(noStream.toByteArray()) }
    }

    @Test
    fun unknownCodecIsUnknown() {
        val text = """{"payload":{"streamStarted":{"_0":{"codec":"vp9","streamID":1}}},"requestID":5}"""
        assertEquals(5L to MacMessage.StreamStarted(1, StreamCodec.UNKNOWN), decodeEnvelope(text.toByteArray()))
        assertNull(StreamCodec.UNKNOWN.raw)
        // Only known codecs can be offered.
        assertThrows(IllegalArgumentException::class.java) { StreamParams(supportedCodecs = listOf(StreamCodec.UNKNOWN)) }
        val both = PhoneMessage.StartSessionStream(guid, StreamParams(listOf(StreamCodec.HEVC, StreamCodec.H264))).payload()
        assertEquals(
            """["hevc","h264"]""",
            both.getValue("startSessionStream").jsonObject.getValue("params").jsonObject.getValue("supportedCodecs").toString(),
        )
    }

    @Test
    fun exponentLiteralsInIntegerFieldsAreRejected() {
        val config = sample("stream-config-terminal").json.toString()
        val bad = listOf(
            config.replace("\"columns\":80", "\"columns\":8e1"),
            config.replace("\"pixelWidth\":1280", "\"pixelWidth\":1.28e3"),
            config.replace("\"streamID\":1", "\"streamID\":1e0"),
            """{"payload":{"streamStarted":{"_0":{"codec":"hevc","streamID":1e2}}}}""",
        )
        for (text in bad) {
            assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
        }
    }

    @Test
    fun streamConfigOwnsItsExtradata() {
        val input = extradata.copyOf()
        val config = terminalConfig().copy(codecExtradata = input)
        val hash = config.hashCode()
        input.fill(0)
        config.codecExtradata.fill(0)
        assertEquals(terminalConfig(), config)
        assertEquals(hash, config.hashCode())
        assertTrue(config.codecExtradata.contentEquals(extradata))

        val resized = config.copy(columns = 60, rows = 45, generationId = 2)
        assertEquals(60, resized.columns)
        assertEquals(45, resized.rows)
        assertEquals(2L, resized.generationId)
        assertEquals(terminalConfig(), resized.copy(columns = 80, rows = 25, generationId = 1))
    }

    @Test
    fun streamIdAboveUInt32IsRejected() {
        val config = sample("stream-config-terminal").json.toString()
        val bodies = listOf<(String) -> String>(
            { """{"payload":{"streamStarted":{"_0":{"codec":"hevc","streamID":$it}}},"requestID":5}""" },
            { """{"payload":{"streamEnded":{"reason":"superseded","streamID":$it}}}""" },
            { config.replace("\"streamID\":1", "\"streamID\":$it") },
            { config.replace("\"generationId\":1", "\"generationId\":$it") },
        )
        for (frame in bodies) {
            decodeEnvelope(frame("4294967295").toByteArray())
            for (bad in listOf("4294967296", "-1", "1.0", "\"1\"")) {
                val text = frame(bad)
                assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.StopSessionStream(4294967296).payload() }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.RequestKeyframe(-1).payload() }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.StreamAck(4294967296, 0).payload() }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.StreamAck(1, -1).payload() }
    }

    @Test
    fun streamConfigOptionalFieldsTakeUpstreamDefaults() {
        val text = """{"payload":{"streamConfig":{"_0":{"streamID":1,"generationId":2,"codecExtradata":"AAA=",""" +
            """"pixelWidth":10,"pixelHeight":20,"scale":1.5,"columns":3,"rows":4,"canResize":null,"future":[1]}}}}"""
        val config = (decodeEnvelope(text.toByteArray()).second as MacMessage.StreamConfigMessage).config
        val expected = StreamConfig(
            streamID = 1, generationId = 2, codecExtradata = byteArrayOf(0, 0), pixelWidth = 10, pixelHeight = 20,
            scale = 1.5, columns = 3, rows = 4, cellGeometry = null, firstAbsLine = 0, totalLines = 0,
            canResize = null, altScreen = false, scrollWheelReporting = false, isBrowser = false,
        )
        assertEquals(expected, config)
        assertEquals(expected.hashCode(), config.hashCode())
    }

    @Test
    fun badLiveSessionBodiesThrow() {
        val config = sample("stream-config-terminal").json.toString()
        val tree = sample("session-tree-reply").json.toString()
        val bad = listOf(
            config.replace("\"pixelWidth\":1280,", ""),
            config.replace("\"scale\":2", "\"scale\":\"2\""),
            config.replace("\"columns\":80", "\"columns\":80.5"),
            config.replace("\"rows\":25", "\"rows\":4294967296"),
            config.replace("\"codecExtradata\":\"AAMA", "\"codecExtradata\":\"AAM"),
            config.replace("\"codecExtradata\":\"AAMA", "\"codecExtradata\":\"*AMA"),
            config.replace("\"altScreen\":false", "\"altScreen\":0"),
            config.replace("\"isBrowser\":false", "\"isBrowser\":\"no\""),
            config.replace("\"cellHeight\":32,", ""),
            config.replace("\"firstAbsLine\":3000", "\"firstAbsLine\":3000.5"),
            tree.replace("\"isBrowser\":true", "\"isBrowser\":1"),
            tree.replace("\"roleName\":\"Planner\",", ""),
            tree.replace("\"title\":\"agents\"", "\"title\":null"),
            tree.replace("\"peers\":[]", "\"peers\":{}"),
            """{"payload":{"sessionTree":{"windows":[]}}}""",
            """{"payload":{"streamStarted":{"_0":{"streamID":1}}}}""",
            """{"payload":{"streamStarted":{"_0":{"codec":1,"streamID":1}}}}""",
            """{"payload":{"streamStarted":{"streamID":1,"codec":"hevc"}}}""",
            """{"payload":{"streamConfig":[]}}""",
        )
        for (text in bad) {
            assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
        }
    }

    @Test
    fun unknownLiveSessionKeysAreIgnored() {
        val text = """{"payload":{"streamStarted":{"_0":{"codec":"h264","streamID":7,"x":1},"_1":2}},"requestID":5}"""
        assertEquals(5L to MacMessage.StreamStarted(7, StreamCodec.H264), decodeEnvelope(text.toByteArray()))
        val ended = """{"payload":{"streamEnded":{"reason":"error","streamID":1,"detail":"x"}}}"""
        assertEquals(null to MacMessage.StreamEnded(1, StreamEndReason.ERROR), decodeEnvelope(ended.toByteArray()))
    }

    @Test
    fun scaleAndCellGeometryAcceptAnyNumberLiteral() {
        val config = sample("stream-config-terminal").json.toString()
            .replace("\"scale\":2", "\"scale\":2.0")
            .replace("\"cellWidth\":15.75", "\"cellWidth\":1.575e1")
        val decoded = (decodeEnvelope(config.toByteArray()).second as MacMessage.StreamConfigMessage).config
        assertEquals(terminalConfig(), decoded)
    }

    @Test
    fun userContentIsRedactedInToString() {
        val secret = "kittens-secret"
        val text = Key.Text(secret)
        val event = KeyEvent(text, KeyModifiers(control = true))
        val summary = SessionSummary("ptys_X", secret, secret, isBrowser = false)
        val peer = PeerEntry(secret, summary)
        val pane = PaneEntry(summary, listOf(peer))
        val tab = TabEntry(secret, listOf(pane))
        val window = WindowEntry(secret, listOf(tab))
        val tree = SessionTree(listOf(window))
        val values = listOf<Any>(
            text, event, PhoneMessage.SendKey("ptys_X", event), PhoneMessage.PasteText("ptys_X", secret),
            summary, peer, pane, tab, window, tree, MacMessage.SessionTreeMessage(tree),
            decodeEnvelope("""{"payload":{"modalAlertAnswerRejected":{"alertID":"$secret"}}}""".toByteArray()).second,
        )
        for (value in values) {
            val printed = value.toString()
            assertFalse("${value.javaClass.simpleName}: $printed", secret in printed)
        }
        assertEquals("Text(len=14)", text.toString())
        assertEquals(
            "Unsupported(case=modalAlertAnswerRejected, keys=2)",
            MacMessage.Unsupported(
                "modalAlertAnswerRejected",
                Json.parseToJsonElement("""{"modalAlertAnswerRejected":{"a":"$secret","b":1}}""").jsonObject,
            ).toString(),
        )
    }
}
