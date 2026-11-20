package dev.bugobliterator.itermbuddy.core.relay

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * A [SocketFactory] whose sockets have `TCP_NODELAY` set (Nagle's algorithm off), made by
 * [delegate] (the platform default unless given).
 *
 * Why: Android sockets have Nagle on, and OkHttp never turns it off. The phone sends many small
 * frames on the relay socket (a typed key, a `streamAck` per media frame). With Nagle, a small
 * frame written while an earlier one is still unacknowledged waits for that TCP ACK, which the
 * relay may delay (delayed ACK, up to 40 ms on Linux and 200 ms elsewhere): typing echo then
 * waits a round trip or a delayed-ACK timer per key. OkHttp layers TLS over the socket this
 * makes, so the setting covers `wss://` too.
 *
 * OkHttp asks for an unconnected socket ([createSocket] with no arguments) and connects it
 * itself; the option is set before the connect, which both the JVM and Android allow.
 */
class NoDelaySocketFactory(private val delegate: SocketFactory = SocketFactory.getDefault()) : SocketFactory() {
    override fun createSocket(): Socket = delegate.createSocket().noDelay()

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).noDelay()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).noDelay()

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).noDelay()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).noDelay()

    private fun Socket.noDelay(): Socket = apply { tcpNoDelay = true }
}

/**
 * The OkHttp client for the relay (its WebSocket and `/register`): every socket has
 * `TCP_NODELAY` ([NoDelaySocketFactory]). [tcpNoDelay] false gives a plain client, for
 * measurements that compare the two.
 */
fun relayHttpClient(tcpNoDelay: Boolean = true, configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
    OkHttpClient.Builder()
        .apply { if (tcpNoDelay) socketFactory(NoDelaySocketFactory()) }
        .apply(configure)
        .build()
