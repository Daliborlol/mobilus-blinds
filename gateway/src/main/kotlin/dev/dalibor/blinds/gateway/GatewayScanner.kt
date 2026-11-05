package dev.dalibor.blinds.gateway

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.SocketFactory

/** Finds gateways on a local network by looking for their MQTT-over-WebSocket endpoint. */
object GatewayScanner {
    const val PORT = 8884
    private const val THREADS = 64

    /**
     * The other addresses on a subnet. Anything larger than a /24 is narrowed to the /24 around
     * [address], because home networks rarely span more and sweeping a /16 would take minutes.
     */
    fun hostsInSubnet(address: ByteArray, prefixLength: Int): List<String> {
        require(address.size == 4) { "IPv4 only" }
        val prefix = prefixLength.coerceAtLeast(24)
        if (prefix >= 31) return emptyList()
        val own = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        val hostBits = 32 - prefix
        val network = own and (-1 shl hostBits)
        return (1 until (1 shl hostBits) - 1)
            .map { network or it }
            .filter { it != own }
            .map { ip -> "${ip ushr 24}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}" }
    }

    /** Returns the hosts that accept the gateway's handshake. Blocks for a few seconds. */
    fun scan(
        hosts: List<String>,
        socketFactory: SocketFactory = SocketFactory.getDefault(),
        port: Int = PORT,
        timeoutMs: Int = 1_000,
    ): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(THREADS.coerceAtMost(hosts.size))
        try {
            val checks = hosts.map { host -> Callable { host.takeIf { isGateway(it, socketFactory, port, timeoutMs) } } }
            return pool.invokeAll(checks).mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isGateway(host: String, socketFactory: SocketFactory, port: Int, timeoutMs: Int): Boolean = try {
        socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = "GET /mqtt HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: mqtt\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            val reply = ByteArray(32)
            val size = socket.getInputStream().read(reply)
            size > 0 && String(reply, 0, size, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101")
        }
    } catch (e: IOException) {
        false
    }
}
