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
    private class Sample(val path: String, val direction: String, val json: JsonObject) {
        val name: String get() = path.substringAfterLast('/').removeSuffix(".json")
        val bytes: ByteArray get() = json.toString().toByteArray(Charsets.UTF_8)
    }

    private val samples: List<Sample> by lazy {
        val paths = Fixtures.list("messages/session")
        assertTrue("messages/session is empty", paths.isNotEmpty())
        paths.map { path ->
            val file = Json.parseToJsonElement(Fixtures.read(path)).jsonObject
            Sample(path, file.getValue("direction").jsonPrimitive.content, file.getValue("json").jsonObject)
        }
    }

    private fun samples(direction: String): List<Sample> =
        samples.filter { it.direction == direction }.also { assertTrue("no $direction samples", it.isNotEmpty()) }

    /** The 32-byte secret in `relay-room-secret.json`: 0xF0, 0xF3, 0xF6, ... */
    private val sampleSecret = ByteArray(32) { (0xF0 + 3 * it).toByte() }

    /** Every phoneToMac sample, with the message and request ID the Android client sends. */
    private val phoneMessages: Map<String, Pair<PhoneMessage, Long?>> = mapOf(
        "hello-phone" to (PhoneMessage.Hello() to 1L),
        "relay-room-secret" to (PhoneMessage.RelayRoomSecret(sampleSecret) to 2L),
        "ping" to (PhoneMessage.Ping to 3L),
        "unpairing" to (PhoneMessage.Unpairing to null),
    )

    private fun body(sample: Sample, case: String): JsonObject =
        sample.json.getValue("payload").jsonObject.getValue(case).jsonObject

    /** Every macToPhone sample, with the request ID and message it decodes to. */
    private fun expectedMacMessage(sample: Sample): Pair<Long?, MacMessage>? = when (sample.name) {
        "hello-mac" -> 1L to MacMessage.HelloReply(15, 11, body(sample, "hello"))
        "hello-mac-with-alerts" -> 1L to MacMessage.HelloReply(15, 11, body(sample, "hello"))
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
        for (sample in phoneSamples) {
            val (message, requestID) = phoneMessages.getValue(sample.name)
            assertEquals(sample.path, sample.json, tree(encodeEnvelope(message, requestID)))
        }
    }

    @Test
    fun phoneSampleRequestIdsFollowAndroidOrder() {
        for (sample in samples("phoneToMac")) {
            val expected = phoneMessages.getValue(sample.name).second
            val actual = sample.json["requestID"]?.jsonPrimitive?.content?.toLong()
            assertEquals(sample.path, expected, actual)
        }
    }

    @Test
    fun phoneHelloAdvertisesProtocolRevision() {
        assertEquals(11, ProtocolRevision.REVISION)
        assertEquals(11, ProtocolRevision.MINIMUM_PEER)
        assertEquals(PhoneMessage.Hello(11, 11), PhoneMessage.Hello())
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
        for (sample in samples("macToPhone")) {
            val expected = expectedMacMessage(sample) ?: throw AssertionError("${sample.path}: no expectation")
            assertEquals(sample.path, expected, decodeEnvelope(sample.bytes))
        }
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
            """{"requestID":1,"payload":{"hello":{"revision":4294967296,"minimumPeer":11}}}""",
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
            // Upstream's synthesized enum decode wants exactly one key; with several, its
            // outcome depends on hash order, so Android always throws.
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
