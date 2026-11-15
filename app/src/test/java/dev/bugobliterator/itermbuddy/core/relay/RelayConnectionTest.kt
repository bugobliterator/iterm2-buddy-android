package dev.bugobliterator.itermbuddy.core.relay

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Admission, framing and /register against MockWebServer playing the relay (room.js at b8aa2f9).
 * Admission runs under runBlocking, not runTest: admit() has a real-time timeout and OkHttp
 * completes it from its own threads, which runTest's virtual clock would race past.
 */
class RelayConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val connections = mutableListOf<RelayConnection>()
    private val heldSockets = mutableListOf<Socket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        connections.forEach { it.close() }
        heldSockets.forEach { it.close() }
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun endpoints(): RelayEndpoints = RelayEndpoints(
        origin = ORIGIN,
        webSocketUrl = "ws://${server.hostName}:${server.port}/",
        httpBaseUrl = "http://${server.hostName}:${server.port}",
    )

    private fun connection(
        relayClient: OkHttpClient = client,
        relayEndpoints: RelayEndpoints = endpoints(),
        admissionTimeoutMillis: Long = 5_000,
    ): RelayConnection =
        RelayConnection(relayClient, relayEndpoints, ROOM, admissionTimeoutMillis = admissionTimeoutMillis)
            .also { connections += it }

    private fun relay(fake: FakeRelay): FakeRelay {
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        return fake
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("no request recorded")

    // --- Admission ---

    @Test
    fun noOriginHeaderSent() = runBlocking {
        relay(FakeRelay())
        val result = connection().admit(Proof.Fresh)
        assertEquals(AdmissionResult.Admitted(TOKEN), result)
        val request = takeRequest()
        assertNull("Origin header: ${request.headers}", request.getHeader("Origin"))
        assertEquals(ROOM, request.getHeader("x-relay-room"))
        assertEquals("iTermBuddyAndroid", request.getHeader("User-Agent"))
        assertEquals("/", request.path)
    }

    @Test
    fun noOriginHeaderSentEvenWhenClientAddsOne() = runBlocking {
        relay(FakeRelay())
        val adding = client.newBuilder().addInterceptor(AddOrigin).build()
        assertEquals(AdmissionResult.Admitted(TOKEN), connection(relayClient = adding).admit(Proof.Fresh))
        val request = takeRequest()
        assertNull("Origin header: ${request.headers}", request.getHeader("Origin"))
    }

    @Test
    fun freshProofSendsEmptyObject() = runBlocking {
        val fake = relay(FakeRelay())
        assertEquals(AdmissionResult.Admitted(TOKEN), connection().admit(Proof.Fresh))
        assertEquals("""{"v":1,"role":"phone"}""", fake.nextText())
        assertEquals("{}", fake.nextText())
    }

    @Test
    fun signedProofSendsSig() = runBlocking {
        val fake = relay(FakeRelay())
        val signer = JoinSigner(ByteArray(32) { 7 })
        assertEquals(AdmissionResult.Admitted(TOKEN), connection().admit(Proof.Signed(signer)))
        assertEquals("""{"v":1,"role":"phone"}""", fake.nextText())
        val proof = Json.parseToJsonElement(fake.nextText()).jsonObject
        assertEquals(setOf("sig"), proof.keys)
        val sig = Base64.getDecoder().decode(proof.getValue("sig").jsonPrimitive.content)
        // Signed over the canonical origin, never the test URL the socket actually reached.
        assertArrayEquals(signer.sign(NONCE, ROOM, ORIGIN), sig)
    }

    @Test
    fun rejectionReportsRelayError() = runBlocking {
        for (error in listOf("mac offline", "bad signature", "signature required", "ticket required")) {
            relay(FakeRelay(reply = """{"ok":false,"error":"$error"}""", closeAfterReply = 1008 to error))
            assertEquals(error, AdmissionResult.Rejected(error), connection().admit(Proof.Fresh))
        }
    }

    @Test
    fun closeBeforeResultIsClosed() = runBlocking {
        relay(FakeRelay(reply = null, closeAfterReply = 1008 to "admission timeout"))
        assertEquals(AdmissionResult.Closed(1008, "admission timeout"), connection().admit(Proof.Fresh))
    }

    @Test
    fun successWithoutTokenIsProtocolError() = runBlocking {
        relay(FakeRelay(reply = """{"ok":true}"""))
        assertTrue(connection().admit(Proof.Fresh) is AdmissionResult.ProtocolError)
    }

    @Test
    fun nonceWithWrongLengthFailsAdmission() = runBlocking {
        val badNonces = listOf(
            "16 bytes" to b64(ByteArray(16) { 1 }),
            "33 bytes" to b64(ByteArray(33) { 1 }),
            "not base64" to "not base64!",
            "url alphabet" to b64(ByteArray(32) { -1 }).replace('/', '_'),
        )
        for ((label, nonce) in badNonces) {
            val fake = relay(FakeRelay(nonce = nonce))
            val result = connection().admit(Proof.Signed(JoinSigner(ByteArray(32))))
            assertTrue("$label: $result", result is AdmissionResult.ProtocolError)
            fake.awaitClosed()
            assertEquals(label, """{"v":1,"role":"phone"}""", fake.nextText())
            assertNull("$label: no proof after a bad challenge", fake.received.poll())
        }
    }

    @Test
    fun upgradeRefusalIsHttpFailure() = runBlocking {
        for (status in listOf(403, 413, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"nope $status"}"""))
            assertEquals(
                AdmissionResult.HttpFailure(status, """{"error":"nope $status"}"""),
                connection().admit(Proof.Fresh),
            )
        }
    }

    @Test
    fun unreachableHostIsUnreachable() = runBlocking {
        val result = connection(relayEndpoints = refusingEndpoints()).admit(Proof.Fresh)
        assertEquals("$result", UnreachableKind.CONNECT, (result as AdmissionResult.Unreachable).kind)
    }

    @Test
    fun unresolvableHostIsUnreachableDns() = runBlocking {
        val noDns = client.newBuilder().dns(failingDns {}).build()
        val endpoints = RelayEndpoints(ORIGIN, "ws://relay.invalid/", "http://relay.invalid")
        val result = connection(relayClient = noDns, relayEndpoints = endpoints).admit(Proof.Fresh)
        assertEquals("$result", UnreachableKind.DNS, (result as AdmissionResult.Unreachable).kind)
    }

    @Test
    fun connectStillPendingAtTimeoutIsUnreachableTimeout() = runBlocking {
        val release = CountDownLatch(1)
        val stuckDns = client.newBuilder().dns(failingDns { release.await(5, TimeUnit.SECONDS) }).build()
        try {
            val endpoints = RelayEndpoints(ORIGIN, "ws://relay.invalid/", "http://relay.invalid")
            val result = connection(relayClient = stuckDns, relayEndpoints = endpoints, admissionTimeoutMillis = 150)
                .admit(Proof.Fresh)
            assertEquals("$result", UnreachableKind.TIMEOUT, (result as AdmissionResult.Unreachable).kind)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun untrustedCertificateIsUnreachableTls() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeRelay()))
        val endpoints = RelayEndpoints(ORIGIN, "wss://localhost:${server.port}/", "https://localhost:${server.port}")
        val result = connection(relayEndpoints = endpoints).admit(Proof.Fresh)
        assertEquals("$result", UnreachableKind.TLS, (result as AdmissionResult.Unreachable).kind)
    }

    @Test
    fun unreachableKindsClassifyCauseChains() {
        val cases = listOf(
            UnknownHostException("relay.invalid") to UnreachableKind.DNS,
            ConnectException("refused") to UnreachableKind.CONNECT,
            NoRouteToHostException("no route") to UnreachableKind.CONNECT,
            SocketTimeoutException("connect timed out") to UnreachableKind.TIMEOUT,
            InterruptedIOException("timeout") to UnreachableKind.TIMEOUT,
            SSLHandshakeException("PKIX path building failed") to UnreachableKind.TLS,
            SSLPeerUnverifiedException("Hostname not verified") to UnreachableKind.TLS,
            IOException("wrapped", CertificateException("expired")) to UnreachableKind.TLS,
            IOException("wrapped", UnknownHostException("x")) to UnreachableKind.DNS,
            IOException("something else") to UnreachableKind.OTHER,
        )
        for ((error, kind) in cases) assertEquals("$error", kind, classifyUnreachable(error))
    }

    @Test
    fun silentRelayTimesOut() = runBlocking {
        relay(FakeRelay(silent = true))
        val started = System.nanoTime()
        val result = connection(admissionTimeoutMillis = 200).admit(Proof.Fresh)
        assertEquals(AdmissionResult.Closed(1006, "admission timeout"), result)
        assertTrue("took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    }

    @Test
    fun resultArrivingAsTimeoutFiresStillAdmits() = runBlocking {
        // The relay withholds its result until the timeout has fired; the hook then delivers it
        // and waits for it to land before the timeout path takes the lock.
        val fake = relay(FakeRelay(reply = null))
        val connection = connection(admissionTimeoutMillis = 300)
        connection.beforeTimeoutSettles = {
            fake.nextText()
            fake.nextText()
            fake.socket().send("""{"ok":true,"registrationToken":"$TOKEN"}""")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!connection.admissionSettled && System.nanoTime() < deadline) Thread.sleep(1)
        }
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        connection.send(byteArrayOf(4, 2))
        assertEquals(byteArrayOf(4, 2).toByteString(), fake.next())
        fake.socket().send(byteArrayOf(7).toByteString())
        assertArrayEquals(byteArrayOf(7), withTimeout(5_000) { connection.incoming.receive() })
    }

    @Test
    fun timeoutFailsIncomingWithAbnormalClose() = runBlocking {
        relay(FakeRelay(silent = true))
        val connection = connection(admissionTimeoutMillis = 100)
        assertEquals(AdmissionResult.Closed(1006, "admission timeout"), connection.admit(Proof.Fresh))
        val closed = assertThrows(RelayClosedException::class.java) { runBlocking { connection.incoming.receive() } }
        assertEquals(1006, closed.code)
    }

    @Test
    fun pairingCycleCapBeforeAdmissionRequiresRescan() = runBlocking {
        // room.js:491-497, 761-763: the room is torn down with 1000 and no admission reply.
        relay(FakeRelay(reply = null, closeAfterReply = 1000 to "pairing cycle cap"))
        val result = connection().admit(Proof.Fresh)
        assertEquals(AdmissionResult.Closed(1000, "pairing cycle cap"), result)
        assertTrue(requiresRescan((result as AdmissionResult.Closed).close))
    }

    @Test
    fun pairingCycleCapAfterAdmissionRequiresRescan() = runBlocking {
        val fake = relay(FakeRelay())
        val connection = connection()
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        fake.socket().close(1000, "pairing cycle cap")
        val closed = assertThrows(RelayClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.incoming.receive() } }
        }
        assertEquals(RelayClose(1000, "pairing cycle cap"), closed.close)
        assertTrue(requiresRescan(closed.close))
    }

    @Test
    fun admittedToStringRedactsToken() {
        val text = AdmissionResult.Admitted(TOKEN).toString()
        assertFalse(text, text.contains(TOKEN))
        assertTrue(text, text.contains("redacted"))
    }

    @Test
    fun admitTwiceIsRefused() = runBlocking {
        relay(FakeRelay())
        val connection = connection()
        connection.admit(Proof.Fresh)
        assertThrows(IllegalStateException::class.java) { runBlocking { connection.admit(Proof.Fresh) } }
        Unit
    }

    // --- After admission ---

    @Test
    fun binaryFramesFlowBothWays() = runBlocking {
        val fake = relay(FakeRelay())
        val connection = connection()
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        connection.send(byteArrayOf(1, 2, 3))
        fake.nextText()
        fake.nextText()
        assertEquals(byteArrayOf(1, 2, 3).toByteString(), fake.next())
        fake.socket().send(byteArrayOf(9, 8).toByteString())
        assertArrayEquals(byteArrayOf(9, 8), withTimeout(5_000) { connection.incoming.receive() })
    }

    @Test
    fun textFrameAfterAdmissionIsFatal() = runBlocking {
        val fake = relay(FakeRelay())
        val connection = connection()
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        fake.socket().send("""{"ok":true}""")
        val error = assertThrows(RelayProtocolException::class.java) {
            runBlocking { withTimeout(5_000) { connection.incoming.receive() } }
        }
        assertTrue(error.message!!, error.message!!.contains("text frame"))
        assertEquals(1002, fake.awaitClosed())
        assertThrows(RelaySendException::class.java) { runBlocking { connection.send(byteArrayOf(1)) } }
        Unit
    }

    @Test
    fun displacedCloseIsVisibleToCaller() = runBlocking {
        for ((code, reason) in listOf(1000 to "displaced", 1001 to "peer gone", 1008 to "daily quota exceeded")) {
            val fake = relay(FakeRelay())
            val connection = connection()
            assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
            fake.socket().close(code, reason)
            val closed = assertThrows(RelayClosedException::class.java) {
                runBlocking { withTimeout(5_000) { connection.incoming.receive() } }
            }
            assertEquals(code, closed.code)
            assertEquals(reason, closed.reason)
        }
    }

    @Test
    fun sendReportsFailure() = runBlocking {
        relay(FakeRelay())
        val connection = connection()
        assertThrows("before admission", RelaySendException::class.java) {
            runBlocking { connection.send(byteArrayOf(1)) }
        }
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        assertThrows("oversized frame", IllegalArgumentException::class.java) {
            runBlocking { connection.send(ByteArray(256 * 1024 + 1)) }
        }
        connection.close()
        assertThrows("after close", RelaySendException::class.java) {
            runBlocking { connection.send(byteArrayOf(1)) }
        }
        Unit
    }

    @Test
    fun closeIsIdempotent() = runBlocking {
        val fake = relay(FakeRelay())
        val connection = connection()
        assertEquals(AdmissionResult.Admitted(TOKEN), connection.admit(Proof.Fresh))
        connection.close()
        connection.close()
        assertEquals(1000, fake.awaitClosed())
        assertThrows(Exception::class.java) { runBlocking { connection.incoming.receive() } }
        Unit
    }

    @Test
    fun closeBeforeAdmitResolvesAdmission() = runBlocking {
        relay(FakeRelay(silent = true))
        val connection = connection()
        connection.close()
        assertTrue(connection.admit(Proof.Fresh) is AdmissionResult.Closed)
    }

    // --- Endpoints ---

    @Test
    fun forOriginDerivesUrlsAndKeepsOrigin() {
        for (origin in listOf(ORIGIN, "$ORIGIN:443", "https://Relay.Example:8443", "https://127.0.0.1:9", "https://[::1]:8443")) {
            val endpoints = RelayEndpoints.forOrigin(origin) ?: throw AssertionError("refused $origin")
            assertEquals(origin, endpoints.origin)
            assertEquals("wss://" + origin.removePrefix("https://") + "/", endpoints.webSocketUrl)
            assertEquals(origin, endpoints.httpBaseUrl)
        }
    }

    @Test
    fun forOriginRefusesNonCanonicalOrigins() {
        val refused = listOf(
            "http://relay.example", "https://relay.example/", "https://relay.example/x", "https://u@relay.example",
            "https://relay.example?q", "https://relay.example#f", "https://", "wss://relay.example", "relay.example",
            "https://relay.example:", "https://relay.example:99999999999", "https://relay.example:0",
            "https://relay.example:65536", "https://re lay.example",
        )
        for (origin in refused) assertNull(origin, RelayEndpoints.forOrigin(origin))
    }

    @Test
    fun forOriginRejectsMismatchedHostParse() {
        // These pass the plain-origin pattern, but OkHttp would connect to a different spelling
        // than the one signed, so they are refused rather than silently altered.
        for (origin in listOf("https://[::0001]", "https://[::ffff:1.2.3.4]", "https://[1:2:3:4:5:6:7:8:9]")) {
            assertNull(origin, RelayEndpoints.forOrigin(origin))
        }
    }

    // --- /register ---

    @Test
    fun registerPostsToHttpBaseUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val verifier = ByteArray(32) { it.toByte() }
        val adding = client.newBuilder().addInterceptor(AddOrigin).build()
        val result = RelayRegistrar(adding, endpoints()).register(ROOM, TOKEN, verifier)
        assertEquals(RegisterResult.Registered, result)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/register", request.path)
        assertEquals(ROOM, request.getHeader("x-relay-room"))
        assertEquals("iTermBuddyAndroid", request.getHeader("User-Agent"))
        assertNull("Origin header: ${request.headers}", request.getHeader("Origin"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("registrationToken", "verifier"), body.keys)
        assertEquals(TOKEN, body.getValue("registrationToken").jsonPrimitive.content)
        assertEquals(b64(verifier), body.getValue("verifier").jsonPrimitive.content)
    }

    @Test
    fun registerTreatsAlreadyRegisteredAsSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"already registered"}"""))
        assertEquals(RegisterResult.Registered, RelayRegistrar(client, endpoints()).register(ROOM, TOKEN, ByteArray(32)))
    }

    @Test
    fun registerReportsRefusal() = runTest {
        val refusals = listOf(
            403 to """{"ok":false,"error":"bad token"}""",
            403 to """{"ok":false,"error":"token required"}""",
            400 to """{"ok":false,"error":"missing verifier"}""",
            403 to """{"ok":false,"error":"Already registered"}""",
            403 to "forbidden",
            413 to "payload too large",
            500 to "internal error",
            502 to "bad gateway",
        )
        for ((status, body) in refusals) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            assertEquals(
                body,
                RegisterResult.Refused(status, body),
                RelayRegistrar(client, endpoints()).register(ROOM, TOKEN, ByteArray(32)),
            )
        }
    }

    @Test
    fun registerReportsTransportError() = runTest {
        val result = RelayRegistrar(client, refusingEndpoints()).register(ROOM, TOKEN, ByteArray(32))
        assertTrue("$result", result is RegisterResult.TransportError)
    }

    @Test
    fun registerRequiresThirtyTwoByteVerifier() = runTest {
        val registrar = RelayRegistrar(client, endpoints())
        for (size in listOf(0, 31, 33, 64)) {
            assertThrows("$size", IllegalArgumentException::class.java) {
                runBlocking { registrar.register(ROOM, TOKEN, ByteArray(size)) }
            }
        }
        assertEquals(0, server.requestCount)
    }

    /**
     * Endpoints on a port held by a bound socket that never listens, so connects are refused and
     * no other test can take the port meanwhile.
     */
    private fun refusingEndpoints(): RelayEndpoints {
        val holder = Socket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        heldSockets += holder
        val port = holder.localPort
        return RelayEndpoints(ORIGIN, "ws://127.0.0.1:$port/", "http://127.0.0.1:$port")
    }

    /** Plays room.js's admission: Hello -> Challenge, Proof -> reply, then splices frames. */
    private class FakeRelay(
        private val nonce: String = b64(NONCE),
        private val reply: String? = """{"ok":true,"registrationToken":"$TOKEN"}""",
        private val closeAfterReply: Pair<Int, String>? = null,
        private val silent: Boolean = false,
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<Any>()
        private val opened = CompletableFuture<WebSocket>()
        private val closed = CompletableFuture<Int>()
        private var textCount = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (silent) return
            when (textCount++) {
                0 -> webSocket.send("""{"nonce":"$nonce"}""")
                1 -> {
                    reply?.let(webSocket::send)
                    closeAfterReply?.let { (code, reason) -> webSocket.close(code, reason) }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.add(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(code)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(-1)
        }

        fun socket(): WebSocket = opened.get(5, TimeUnit.SECONDS)

        fun awaitClosed(): Int = closed.get(5, TimeUnit.SECONDS)

        fun next(): Any = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("relay received nothing")

        fun nextText(): String = next() as String
    }

    /** A resolver that runs [first] (to stall, say) and then fails every lookup. */
    private fun failingDns(first: () -> Unit): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            first()
            throw UnknownHostException(hostname)
        }
    }

    private object AddOrigin : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("Origin", "https://evil.example").build())
    }

    private companion object {
        const val ORIGIN = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"
        val ROOM = "0123456789abcdef".repeat(4)
        const val TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX"
        val NONCE = ByteArray(32) { (it * 3).toByte() }

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
