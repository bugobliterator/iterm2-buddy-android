package dev.bugobliterator.itermbuddy.core.wire

import dev.bugobliterator.itermbuddy.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessagesTest {
    /** [bytes] is the sample's `json` text exactly as the fixture file holds it, escapes included. */
    private class Sample(val path: String, val direction: String, val json: JsonObject, val bytes: ByteArray) {
        val name: String get() = path.substringAfterLast('/').removeSuffix(".json")
    }

    /**
     * Slices the raw `json` value out of a fixture file. The generator writes it last,
     * embedded verbatim: `"json" : {...}` then the file's closing `}`.
     */
    private fun rawJson(path: String, text: String): String {
        val marker = "\"json\" : "
        check(text.indexOf(marker) == text.lastIndexOf(marker) && marker in text) { "$path: no single json key" }
        val rest = text.substringAfter(marker).trimEnd()
        check(rest.endsWith("}")) { "$path: file does not end with }" }
        val raw = rest.dropLast(1).trimEnd()
        check(raw.endsWith("}")) { "$path: json is not the last value" }
        return raw
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

    private val samples: List<Sample> by lazy {
        val paths = Fixtures.list("messages/session")
        assertTrue("messages/session is empty", paths.isNotEmpty())
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

    /** The 32-byte secret in `relay-room-secret.json`: 0xF0, 0xF3, 0xF6, ... */
    private val sampleSecret = ByteArray(32) { (0xF0 + 3 * it).toByte() }

    /** Every phoneToMac sample, with the message and request ID the Android client sends. */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        // The v3.7.7 sample advertises 11; the app now advertises ProtocolRevision.REVISION.
        "hello-phone" to (PhoneMessage.Hello(revision = 11, minimumPeer = 11) to 1L),
        "relay-room-secret" to (PhoneMessage.RelayRoomSecret(sampleSecret) to 2L),
        "ping" to (PhoneMessage.Ping to 3L),
        "unpairing" to (PhoneMessage.Unpairing to null),
    )

    private fun body(sample: Sample, case: String): JsonObject =
        sample.json.getValue("payload").jsonObject.getValue(case).jsonObject

    /** Every macToPhone sample, with the request ID and message it decodes to. */
    private fun expectedMacMessage(sample: Sample): Pair<Long?, MacMessage>? = when (sample.name) {
        "hello-mac" -> 1L to MacMessage.HelloReply(15L, 11L, body(sample, "hello"))
        "hello-mac-with-alerts" -> 1L to MacMessage.HelloReply(15L, 11L, body(sample, "hello"))
        "relay-room-secret-stored" -> 2L to MacMessage.RelayRoomSecretStored
        "pong" -> 3L to MacMessage.Pong
        "unpaired" -> null to MacMessage.Unpaired
        "error-reply" -> 2L to MacMessage.Error(body(sample, "error").getValue("_0").jsonObject)
        else -> null
    }

    private fun tree(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    @Test
    fun phoneSamplesEncodeExactly() {
        val phoneSamples = samples("phoneToMac")
        assertEquals("phone samples vs messages", phoneMessages.keys, phoneSamples.map { it.name }.toSet())
        forEachSample(phoneSamples) { sample ->
            val (message, requestID) = phoneMessages.getValue(sample.name)
            assertEquals(sample.path, sample.json, tree(encodeEnvelope(message, requestID)))
        }
    }

    @Test
    fun phoneSampleRequestIdsFollowAndroidOrder() {
        forEachSample(samples("phoneToMac")) { sample ->
            val expected = phoneMessages.getValue(sample.name).second
            val actual = sample.json["requestID"]?.jsonPrimitive?.content?.toLong()
            assertEquals(sample.path, expected, actual)
        }
    }

    @Test
    fun phoneHelloAdvertisesProtocolRevision() {
        assertTrue(ProtocolRevision.REVISION >= ProtocolRevision.MINIMUM_PEER)
        assertEquals(11, ProtocolRevision.MINIMUM_PEER)
        assertEquals(PhoneMessage.Hello(ProtocolRevision.REVISION, ProtocolRevision.MINIMUM_PEER), PhoneMessage.Hello())
        // What the Mac speaks at v3.7.7 must accept us, and we must accept it.
        val mac = Json.parseToJsonElement(Fixtures.read("messages/revision.json")).jsonObject
        val macRevision = mac.getValue("revision").jsonPrimitive.int
        val macMinimum = mac.getValue("minimumPeer").jsonPrimitive.int
        assertTrue("Mac minimumPeer $macMinimum > our revision", ProtocolRevision.REVISION >= macMinimum)
        assertTrue("Mac revision $macRevision < our minimumPeer", macRevision >= ProtocolRevision.MINIMUM_PEER)
    }

    @Test
    fun missingRequestIdIsOmittedNotNull() {
        val encoded = tree(encodeEnvelope(PhoneMessage.Unpairing, null)).jsonObject
        assertEquals(setOf("payload"), encoded.keys)
    }

    @Test
    fun negativeRequestIdIsRefusedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) { encodeEnvelope(PhoneMessage.Ping, -1) }
    }

    @Test
    fun macSamplesDecode() {
        forEachSample(samples("macToPhone")) { sample ->
            val expected = expectedMacMessage(sample) ?: throw AssertionError("${sample.path}: no expectation")
            assertEquals(sample.path, expected, decodeEnvelope(sample.bytes))
        }
    }

    @Test
    fun rawSampleTextKeepsSlashEscapes() {
        val secret = samples.single { it.name == "relay-room-secret" }
        assertTrue(secret.path, "\\/" in secret.bytes.decodeToString())
    }

    @Test
    fun helloReplyKeepsMacStatusInRaw() {
        val withAlerts = samples.single { it.name == "hello-mac-with-alerts" }
        val reply = decodeEnvelope(withAlerts.bytes).second as MacMessage.HelloReply
        assertTrue(withAlerts.path, "macStatus" in reply.raw)
        val plain = samples.single { it.name == "hello-mac" }
        val plainReply = decodeEnvelope(plain.bytes).second as MacMessage.HelloReply
        assertTrue(plain.path, "macStatus" !in plainReply.raw)
    }

    @Test
    fun unknownCaseIsUnsupported() {
        val cases = listOf(
            """{"requestID":7,"payload":{"futureThing":{"x":1}}}""" to (7L to "futureThing"),
            """{"payload":{"futureThing":3}}""" to (null to "futureThing"),
            // A phone-to-Mac case is not a host case: upstream's host knownPayloadKeys lacks it.
            """{"requestID":4,"payload":{"ping":{}}}""" to (4L to "ping"),
            """{"payload":{"unsupported":{}}}""" to (null to "unsupported"),
        )
        for ((text, expected) in cases) {
            val (requestID, message) = decodeEnvelope(text.toByteArray())
            assertEquals(text, expected.first, requestID)
            val unsupported = message as? MacMessage.Unsupported ?: throw AssertionError("$text: got $message")
            assertEquals(text, expected.second, unsupported.case)
            assertEquals(text, Json.parseToJsonElement(text).jsonObject.getValue("payload"), unsupported.raw)
        }
    }

    @Test
    fun mediaFrameIsUnsupportedMedia() {
        val (requestID, message) = decodeEnvelope(byteArrayOf(0x01, 0x7B, 0x00, 0xFF.toByte()))
        assertNull(requestID)
        assertEquals("media", (message as MacMessage.Unsupported).case)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val cases = mapOf(
            """{"requestID":1,"payload":{"hello":{"revision":16,"minimumPeer":11,"newField":[1]}}}""" to 1L,
            """{"requestID":3,"payload":{"pong":{"extra":true}}}""" to 3L,
            """{"requestID":3,"payload":{"pong":{}},"trace":"abc"}""" to 3L,
            """{"payload":{"unpaired":{}},"requestID":null}""" to null,
            """{"requestID":2,"payload":{"error":{"_0":{"code":"aiUnavailable","message":"m","x":1},"_1":2}}}""" to 2L,
        )
        for ((text, expectedID) in cases) {
            val (requestID, message) = decodeEnvelope(text.toByteArray())
            assertEquals(text, expectedID, requestID)
            assertTrue("$text: got $message", message !is MacMessage.Unsupported)
        }
    }

    @Test
    fun nullRequestIdIsAbsent() {
        val (requestID, message) = decodeEnvelope("""{"requestID":null,"payload":{"pong":{}}}""".toByteArray())
        assertNull(requestID)
        assertEquals(MacMessage.Pong, message)
    }

    @Test
    fun requestIdMustBeUnsignedInteger() {
        val accepted = mapOf("0" to 0L, "9223372036854775807" to Long.MAX_VALUE)
        for ((literal, expected) in accepted) {
            val text = """{"requestID":$literal,"payload":{"pong":{}}}"""
            assertEquals(text, expected, decodeEnvelope(text.toByteArray()).first)
        }
        val rejected = listOf(
            "\"1\"", "-1", "1.5", "1.0", "1e2", "true", "{}", "[1]",
            "9223372036854775808", "18446744073709551616",
        )
        for (literal in rejected) {
            val text = """{"requestID":$literal,"payload":{"pong":{}}}"""
            assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
        }
    }

    @Test
    fun badBodyThrows() {
        val bad = listOf(
            """{"requestID":1,"payload":{"hello":{"minimumPeer":11}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":"15","minimumPeer":11}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":15.5,"minimumPeer":11}}}""",
            // Deliberate deviation: Swift's JSONDecoder may take 15.0 as an Int; the Mac writes literals.
            """{"requestID":1,"payload":{"hello":{"revision":15.0,"minimumPeer":11}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":9223372036854775808,"minimumPeer":11}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":null}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"aiAvailable":"yes"}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"wantsNotificationPermission":1}}}""",
            """{"requestID":1,"payload":{"hello":{"revision":15,"minimumPeer":11,"macStatus":3}}}""",
            """{"requestID":1,"payload":{"hello":[]}}""",
            """{"requestID":3,"payload":{"pong":null}}""",
            """{"requestID":3,"payload":{"pong":1}}""",
            """{"requestID":2,"payload":{"relayRoomSecretStored":"ok"}}""",
            """{"payload":{"unpaired":[]}}""",
            """{"requestID":2,"payload":{"error":{}}}""",
            """{"requestID":2,"payload":{"error":{"_0":"boom"}}}""",
            """{"requestID":2,"payload":{"error":{"_0":{"code":1,"message":"m"}}}}""",
            """{"requestID":2,"payload":{"error":{"_0":{"code":"internalError"}}}}""",
            // Upstream decides on the FIRST key in document order, and the synthesized decode's
            // allKeys hides unknown keys, so upstream reads the first as `.pong` and the second
            // as `.unsupported`. Android deliberately throws on any payload without one key.
            """{"payload":{"pong":{},"futureThing":{}}}""",
            """{"payload":{"newA":{},"newB":{}}}""",
            """{"payload":{}}""",
            """{"payload":"pong"}""",
            """{"payload":null}""",
            """{"requestID":1}""",
            """[{"payload":{"pong":{}}}]""",
            "",
            "pong",
            """{"payload":{"pong":{}}""",
        )
        for (text in bad) {
            assertThrows(text, WireDecodeException::class.java) { decodeEnvelope(text.toByteArray()) }
        }
        val invalidUtf8 = """{"payload":{"pong":{}},"x":"?"}""".toByteArray().also { it[it.size - 3] = 0xC3.toByte() }
        assertThrows("invalid UTF-8", WireDecodeException::class.java) { decodeEnvelope(invalidUtf8) }
        assertThrows("empty", WireDecodeException::class.java) { decodeEnvelope(ByteArray(0)) }
        val pong = """{"payload":{"pong":{}}}"""
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + pong.toByteArray()
        assertThrows("BOM", WireDecodeException::class.java) { decodeEnvelope(bom) }
        assertThrows("UTF-16", WireDecodeException::class.java) { decodeEnvelope(pong.toByteArray(Charsets.UTF_16LE)) }
    }

    @Test
    fun helloRevisionsAreSixtyFourBit() {
        val text = """{"requestID":1,"payload":{"hello":{"revision":4294967296,"minimumPeer":-1}}}"""
        val reply = decodeEnvelope(text.toByteArray()).second as MacMessage.HelloReply
        assertEquals(4294967296L, reply.revision)
        assertEquals(-1L, reply.minimumPeer)
    }

    @Test
    fun confirmationFixturesDecode() {
        val expected = mapOf("accepted" to true, "rejected" to false)
        val paths = Fixtures.list("messages/confirmation")
        assertEquals("confirmation samples", expected.keys, paths.map { it.substringAfterLast('/').removeSuffix(".json") }.toSet())
        for (path in paths) {
            val bytes = Json.parseToJsonElement(Fixtures.read(path)).jsonObject
                .getValue("bytes").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            val name = path.substringAfterLast('/').removeSuffix(".json")
            assertEquals(path, expected.getValue(name), decodePairingConfirmation(bytes))
        }
    }

    @Test
    fun confirmationAcceptsWhitespace() {
        assertEquals(true, decodePairingConfirmation("""{ "pairing" : "accepted" }""".toByteArray()))
        assertEquals(false, decodePairingConfirmation("\n{\"pairing\":\"rejected\"}\n".toByteArray()))
    }

    @Test
    fun confirmationRejectsNearMisses() {
        val nearMisses = listOf(
            """{"pairing":"Accepted"}""",
            """{"pairing":"ACCEPTED"}""",
            """{"pairing":"accepted "}""",
            """{"pairing":"accepted","extra":1}""",
            """{"extra":1,"pairing":"rejected"}""",
            """{"payload":{"pairing":"accepted"}}""",
            """{"requestID":1,"payload":{"pairing":{"_0":"accepted"}}}""",
            """{"pairing":true}""",
            """{"pairing":null}""",
            """{"pairing":["accepted"]}""",
            """{"Pairing":"accepted"}""",
            """["accepted"]""",
            "\"accepted\"",
            "accepted",
            """{"pairing":"accepted"""",
            // Upstream's JSONSerialization keeps the last duplicate and would accept this.
            """{"pairing":"rejected","pairing":"accepted"}""",
            """{"pairing":"accepted","pairing":"accepted"}""",
            "",
        )
        for (text in nearMisses) {
            assertNull(text, decodePairingConfirmation(text.toByteArray()))
        }
        assertNull("media frame", decodePairingConfirmation(byteArrayOf(0x01) + """{"pairing":"accepted"}""".toByteArray()))
        val invalidUtf8 = """{"pairing":"accepted"}""".toByteArray().also { it[12] = 0xFF.toByte() }
        assertNull("invalid UTF-8", decodePairingConfirmation(invalidUtf8))
    }

    @Test
    fun relayRoomSecretIsCopied() {
        val secret = sampleSecret.copyOf()
        val message = PhoneMessage.RelayRoomSecret(secret)
        secret.fill(0)
        val expected = samples.single { it.name == "relay-room-secret" }
        assertEquals(expected.path, expected.json, tree(encodeEnvelope(message, 2)))
    }
}
