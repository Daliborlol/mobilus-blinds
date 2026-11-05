package dev.dalibor.blinds.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.SocketFactory
import kotlin.concurrent.thread

class GatewayScannerTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test fun listsOtherHostsInSlash24() {
        val hosts = GatewayScanner.hostsInSubnet(ip(192, 168, 1, 20), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue("192.168.1.20" !in hosts)
    }

    @Test fun narrowsLargeSubnetsToSlash24() {
        val hosts = GatewayScanner.hostsInSubnet(ip(10, 1, 2, 3), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.1.2.") })
    }

    @Test fun handlesSmallSubnets() {
        assertEquals(listOf("192.168.1.130"), GatewayScanner.hostsInSubnet(ip(192, 168, 1, 129), 30))
        assertEquals(emptyList<String>(), GatewayScanner.hostsInSubnet(ip(10, 0, 0, 4), 32))
    }

    @Test fun acceptsWebSocketUpgrade() =
        assertEquals(listOf("127.0.0.1"), scanServerReplying("HTTP/1.1 101 Switching Protocols\r\n\r\n"))

    @Test fun rejectsOtherHttpServers() =
        assertEquals(emptyList<String>(), scanServerReplying("HTTP/1.1 404 Not Found\r\n\r\n"))

    @Test fun closedPortIsNotAGateway() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(emptyList<String>(), GatewayScanner.scan(listOf("127.0.0.1"), SocketFactory.getDefault(), port))
    }

    private fun scanServerReplying(reply: String): List<String> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread {
                server.accept().use { client ->
                    client.getInputStream().read(ByteArray(1024))
                    client.getOutputStream().write(reply.toByteArray())
                }
            }
            GatewayScanner.scan(listOf("127.0.0.1"), SocketFactory.getDefault(), server.localPort)
        }
}
