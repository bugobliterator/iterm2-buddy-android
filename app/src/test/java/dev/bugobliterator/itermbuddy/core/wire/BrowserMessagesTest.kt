package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.stream.isStreamScoped
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

class BrowserMessagesTest {
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
        val paths = Fixtures.list("messages/browser-sessions")
        assertTrue("messages/browser-sessions is empty", paths.isNotEmpty())
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

    private fun sessionSample(name: String): ByteArray {
        val text = Fixtures.read("messages/session/$name.json")
        return rawJson(name, text).toByteArray(Charsets.UTF_8)
    }

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

    /** The browser stream: the link's second stream. */
    private val stream = 2L

    /** Every phoneToMac sample, with the message and request ID iOS sends it with. */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        "hello-phone-revision-fifteen" to (PhoneMessage.Hello() to 1L),
        "browser-tap" to (PhoneMessage.BrowserTap(stream, x = 412.5, y = 300.0) to null),
        "browser-scroll" to (PhoneMessage.BrowserScroll(stream, x = 640.0, y = 400.0, deltaX = 0.0, deltaY = -48.0) to null),
        "browser-go-back" to (PhoneMessage.BrowserGoBack(stream) to null),
        "browser-go-forward" to (PhoneMessage.BrowserGoForward(stream) to null),
        "browser-reload" to (PhoneMessage.BrowserReload(stream) to null),
        "browser-stop-loading" to (PhoneMessage.BrowserStopLoading(stream) to null),
        "browser-load" to (PhoneMessage.BrowserLoad(stream, "iterm2.com/documentation-web-browser.html") to null),
    )

    private val rejectedAlert = "6F1C2B3A-4D5E-4F60-8172-93A4B5C6D7E8"

    /** Every macToPhone sample by name, with the request ID and message it decodes to. */
    private val macMessages: Map<String, (Sample) -> Pair<Long?, MacMessage>> = mapOf(
        "browser-editing-changed" to { _ -> null to MacMessage.BrowserEditingChanged(stream, editing = true) },
        "browser-navigation-changed" to { _ ->
            null to MacMessage.BrowserNavigationChanged(
                stream,
                BrowserNavigationState(
                    url = "https://iterm2.com/documentation-web-browser.html",
                    title = "Web Browser - Documentation - iTerm2",
                    canGoBack = true,
                    canGoForward = false,
                    isLoading = true,
                ),
            )
        },
        "browser-navigation-changed-minimal" to { _ ->
            null to MacMessage.BrowserNavigationChanged(stream, BrowserNavigationState(null, null, false, false, false))
        },
        "mac-status-changed-blocked" to { _ -> null to MacMessage.MacStatusChanged(MacStatus(mainBlocked = true, alertCount = 1)) },
        "mac-status-changed-clear" to { _ -> null to MacMessage.MacStatusChanged(MacStatus(mainBlocked = false, alertCount = 0)) },
        "modal-alert-answer-rejected" to { sample ->
            null to MacMessage.Unsupported("modalAlertAnswerRejected", sample.json.getValue("payload").jsonObject)
        },
    )

    @Test
    fun browserPhoneSamplesEncodeExactly() {
        val phoneSamples = samples("phoneToMac")
        forEachSample(phoneSamples) { sample ->
            val (message, requestID) = phoneMessages[sample.name] ?: throw AssertionError("no table entry")
            assertEquals(sample.path, sample.json, tree(encodeEnvelope(message, requestID)))
        }
        assertEquals("table entries without a sample", phoneMessages.keys, phoneSamples.map { it.name }.toSet())

        // Integral coordinates are integer literals, as Swift's JSONEncoder writes them; trees
        // compare number literals as text, so `300.0` would fail above, but say it plainly too.
        val tap = encodeEnvelope(PhoneMessage.BrowserTap(stream, 412.5, 300.0), null).decodeToString()
        assertTrue(tap, "\"x\":412.5" in tap && "\"y\":300" in tap && "300.0" !in tap)
        val scroll = encodeEnvelope(PhoneMessage.BrowserScroll(stream, 640.0, 400.0, -0.0, -48.0), null).decodeToString()
        assertTrue(scroll, "\"deltaX\":-0," in scroll && "\"deltaY\":-48}" in scroll && ".0" !in scroll)
    }

    @Test
    fun browserPhoneMessagesRefuseWhatSwiftCannotEncode() {
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.BrowserTap(stream, Double.NaN, 1.0).payload() }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneMessage.BrowserScroll(stream, 1.0, 1.0, Double.POSITIVE_INFINITY, 0.0).payload()
        }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.BrowserGoBack(-1).payload() }
        assertThrows(IllegalArgumentException::class.java) { PhoneMessage.BrowserLoad(1L shl 32, "a").payload() }
    }

    @Test
    fun browserMacSamplesDecode() {
        val macSamples = samples("macToPhone")
        forEachSample(macSamples) { sample ->
            val expected = macMessages[sample.name]?.invoke(sample) ?: throw AssertionError("no expectation")
            assertEquals(sample.path, expected, decodeEnvelope(sample.bytes))
        }
        assertEquals("expectations without a sample", macMessages.keys, macSamples.map { it.name }.toSet())
    }

    @Test
    fun minimalNavigationStateIsLenient() {
        assertEquals("""{"payload":{"browserNavigationChanged":{"state":{},"streamID":2}}}""", sample("browser-navigation-changed-minimal").bytes.decodeToString())
        val cases = mapOf(
            """{"state":{},"streamID":2}""" to BrowserNavigationState(null, null, false, false, false),
            """{"state":{"url":null,"title":null,"canGoBack":null},"streamID":2}""" to
                BrowserNavigationState(null, null, false, false, false),
            """{"state":{"url":"","title":"","isLoading":true,"future":[1]},"streamID":2,"x":1}""" to
                BrowserNavigationState("", "", false, false, true),
        )
        for ((body, state) in cases) {
            val frame = """{"payload":{"browserNavigationChanged":$body}}"""
            assertEquals(frame, null to MacMessage.BrowserNavigationChanged(2, state), decodeEnvelope(frame.toByteArray()))
        }
        // Present with the wrong type throws, as decodeIfPresent does; state and streamID are required.
        for (
            body in listOf(
                """{"state":{"url":1},"streamID":2}""",
                """{"state":{"canGoBack":"yes"},"streamID":2}""",
                """{"streamID":2}""",
                """{"state":{}}""",
                """{"state":[],"streamID":2}""",
            )
        ) {
            val frame = """{"payload":{"browserNavigationChanged":$body}}"""
            assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
        }
        for (body in listOf("""{"streamID":2}""", """{"editing":true}""", """{"streamID":2,"editing":1}""")) {
            val frame = """{"payload":{"browserEditingChanged":$body}}"""
            assertThrows(frame, WireDecodeException::class.java) { decodeEnvelope(frame.toByteArray()) }
        }
    }

    @Test
    fun macStatusFromHelloAndEvent() {
        val withAlerts = decodeEnvelope(sessionSample("hello-mac-with-alerts")).second as MacMessage.HelloReply
        assertEquals(MacStatus(mainBlocked = true, alertCount = 1), withAlerts.macStatus())
        val plain = decodeEnvelope(sessionSample("hello-mac")).second as MacMessage.HelloReply
        assertNull("a reply to a phone below revision 14 has no status", plain.macStatus())

        // Upstream's lenient decode: absent or null fields take their defaults; alert contents are not kept.
        val statuses = mapOf(
            """{}""" to MacStatus(false, 0),
            """{"modalAlerts":null,"mainBlocked":null}""" to MacStatus(false, 0),
            """{"mainBlocked":true}""" to MacStatus(true, 0),
            """{"modalAlerts":[{"id":"a"},{"id":"b","heading":"h","future":1}],"x":2}""" to MacStatus(false, 2),
        )
        for ((status, expected) in statuses) {
            val event = """{"payload":{"macStatusChanged":{"status":$status}}}"""
            assertEquals(event, null to MacMessage.MacStatusChanged(expected), decodeEnvelope(event.toByteArray()))
            val hello = """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":$status}}}"""
            assertEquals(hello, expected, (decodeEnvelope(hello.toByteArray()).second as MacMessage.HelloReply).macStatus())
        }
        val nullStatus = """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":null}}}"""
        assertNull((decodeEnvelope(nullStatus.toByteArray()).second as MacMessage.HelloReply).macStatus())

        // A malformed status fails a macStatusChanged, as upstream's decode does; in a hello it
        // reads as no status (a deliberate leniency: a bad status must not cost the connection).
        for (
            status in listOf(
                """{"mainBlocked":1}""",
                """{"modalAlerts":{}}""",
                """{"modalAlerts":[1]}""",
                """{"modalAlerts":[{"heading":"no id"}]}""",
                """{"modalAlerts":[{"id":7}]}""",
            )
        ) {
            val event = """{"payload":{"macStatusChanged":{"status":$status}}}"""
            assertThrows(event, WireDecodeException::class.java) { decodeEnvelope(event.toByteArray()) }
            val hello = """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":$status}}}"""
            val reply = decodeEnvelope(hello.toByteArray()).second as MacMessage.HelloReply
            assertNull(hello, reply.macStatus())
            assertTrue(hello, reply.hasUndecodableMacStatus())
        }
        for (status in listOf("3", "[]", "\"x\"")) {
            val hello = """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":$status}}}"""
            assertNull(hello, (decodeEnvelope(hello.toByteArray()).second as MacMessage.HelloReply).macStatus())
        }
        assertFalse(withAlerts.hasUndecodableMacStatus())
        assertFalse(plain.hasUndecodableMacStatus())
        val missing = """{"payload":{"macStatusChanged":{}}}"""
        assertThrows(missing, WireDecodeException::class.java) { decodeEnvelope(missing.toByteArray()) }

        assertTrue(MacStatus(mainBlocked = true, alertCount = 0).showsAlert)
        assertTrue(MacStatus(mainBlocked = false, alertCount = 2).showsAlert)
        assertFalse(MacStatus(mainBlocked = false, alertCount = 0).showsAlert)
    }

    @Test
    fun browserEventsAreStreamScopedAndStatusIsNot() {
        assertTrue(isStreamScoped(MacMessage.BrowserEditingChanged(2, true)))
        assertTrue(isStreamScoped(MacMessage.BrowserNavigationChanged(2, BrowserNavigationState(null, null, false, false, false))))
        assertFalse(isStreamScoped(MacMessage.MacStatusChanged(MacStatus(true, 1))))
        assertFalse(isStreamScoped(decodeEnvelope(sample("modal-alert-answer-rejected").bytes).second))
    }

    @Test
    fun browserContentIsRedactedInToString() {
        val secret = "kittens-secret"
        val state = BrowserNavigationState(secret, secret, canGoBack = true, canGoForward = false, isLoading = false)
        val values = listOf<Any>(
            PhoneMessage.BrowserLoad(2, secret),
            state,
            MacMessage.BrowserNavigationChanged(2, state),
            decodeEnvelope(
                """{"payload":{"browserNavigationChanged":{"streamID":2,"state":{"url":"$secret","title":"$secret"}}}}""".toByteArray(),
            ),
            decodeEnvelope(
                """{"payload":{"macStatusChanged":{"status":{"modalAlerts":[{"id":"$secret","heading":"$secret"}]}}}}""".toByteArray(),
            ),
        )
        for (value in values) {
            val printed = value.toString()
            assertFalse("${value.javaClass.simpleName}: $printed", secret in printed)
        }
        assertEquals("BrowserLoad(streamID=2, text(len=14))", PhoneMessage.BrowserLoad(2, secret).toString())
        assertEquals(
            "BrowserNavigationState(url(len=14), title(len=14), canGoBack=true, canGoForward=false, isLoading=false)",
            state.toString(),
        )
        assertEquals(
            "BrowserNavigationState(url=null, title=null, canGoBack=false, canGoForward=false, isLoading=false)",
            BrowserNavigationState(null, null, false, false, false).toString(),
        )
    }

    @Test
    fun helloReplyToStringShowsOnlyKeys() {
        val text = Fixtures.read("messages/session/hello-mac-with-alerts.json")
        val reply = decodeEnvelope(sessionSample("hello-mac-with-alerts")).second as MacMessage.HelloReply
        val printed = reply.toString()
        assertEquals(
            "HelloReply(revision=15, minimumPeer=11, keys=[aiAvailable, macStatus, minimumPeer, revision, wantsNotificationPermission])",
            printed,
        )
        // Every alert string in the fixture is a sentinel: heading, body, buttons, inputs, GUIDs.
        val sentinels = listOf(
            "Close Session?", "A job is still running in this session.", "Do not ask again", "Show Details",
            "6F1C2B3A-4D5E-4F60-8172-93A4B5C6D7E8", "w0t0p0:2B5D1E7A-0C9F-4E3B-A1D2-3C4B5A697887", "build", "Count",
        )
        for (sentinel in sentinels) {
            assertTrue("the fixture has $sentinel", sentinel in text)
            assertFalse("$sentinel in $printed", sentinel in printed)
            assertFalse("$sentinel in the decoded pair", sentinel in decodeEnvelope(sessionSample("hello-mac-with-alerts")).toString())
        }
    }

    @Test
    fun advertisedRevisionIsFifteen() {
        val hello = PhoneMessage.Hello().payload().getValue("hello").jsonObject
        assertEquals("15", hello.getValue("revision").jsonPrimitive.content)
        assertEquals("11", hello.getValue("minimumPeer").jsonPrimitive.content)
        assertEquals(sample("hello-phone-revision-fifteen").json, tree(encodeEnvelope(PhoneMessage.Hello(), 1)))
    }
}
