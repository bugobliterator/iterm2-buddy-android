package dev.bugobliterator.itermbuddy.core.relay

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class NoDelaySocketFactoryTest {
    private lateinit var listener: ServerSocket
    private val opened = mutableListOf<Socket>()

    @Before
    fun setUp() {
        listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        listener.close()
    }

    private fun Socket.tracked(): Socket = also { opened += it }

    @Test
    fun everyKindOfSocketHasNoDelay() {
        val factory = NoDelaySocketFactory()
        val loopback = InetAddress.getLoopbackAddress()
        val port = listener.localPort
        val sockets = listOf(
            factory.createSocket(),
            factory.createSocket(loopback.hostAddress, port),
            factory.createSocket(loopback.hostAddress, port, loopback, 0),
            factory.createSocket(loopback, port),
            factory.createSocket(loopback, port, loopback, 0),
        ).map { it.tracked() }
        sockets.forEachIndexed { index, socket -> assertTrue("socket $index has TCP_NODELAY", socket.tcpNoDelay) }
    }

    @Test
    fun theUnconnectedSocketKeepsNoDelayOnceConnected() {
        val socket = NoDelaySocketFactory().createSocket().tracked()
        socket.connect(listener.localSocketAddress, CONNECT_MILLIS)
        assertTrue(socket.isConnected)
        assertTrue(socket.tcpNoDelay)
    }

    @Test
    fun thePlatformDefaultHasNaglesAlgorithmOn() {
        // The reason for the factory: a plain socket does not set TCP_NODELAY.
        val socket = javax.net.SocketFactory.getDefault().createSocket().tracked()
        assertFalse(socket.tcpNoDelay)
    }

    @Test
    fun theRelayClientConnectsWithNoDelayAndThePlainOneWithout() {
        assertEquals(listOf(true), connectionNoDelay(tcpNoDelay = true))
        assertEquals(listOf(false), connectionNoDelay(tcpNoDelay = false))
    }

    /** TCP_NODELAY of each socket a [relayHttpClient] connected for one request to a local server. */
    private fun connectionNoDelay(tcpNoDelay: Boolean): List<Boolean> {
        val seen = CopyOnWriteArrayList<Boolean>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client = relayHttpClient(tcpNoDelay) {
                callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
                eventListener(
                    object : EventListener() {
                        override fun connectionAcquired(call: Call, connection: Connection) {
                            seen += connection.socket().tcpNoDelay
                        }
                    },
                )
            }
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals(200, it.code) }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        return seen
    }

    private companion object {
        const val CONNECT_MILLIS = 2_000
        const val CALL_SECONDS = 5L
    }
}
