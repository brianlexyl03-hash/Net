package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TcpSessionTest {
    private val noRoutes = object : RouteProvider {
        override fun acquire(exclude: Set<String>, lowLatency: Boolean): Route? = null
        override fun release(route: Route) {}
    }

    @Test
    fun resetsTheClientWhenNoRouteIsAvailable() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val sent = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val app = AppStats.entry(AppResolver.App("test.tcp.session", "Test"))
        val clientIp = 0x0A4D0002
        val serverIp = 0x08080808
        val session = TcpSession(
            "test-key", clientIp, 40_000, serverIp, 443, app, false, 1000L,
            PacketSink { packets.add(it); sent.countDown() }, noRoutes, pool,
        ) { closed.countDown() }
        session.start()
        assertTrue(sent.await(3, TimeUnit.SECONDS))
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        val packet = packets[0]
        assertEquals(PacketUtil.RST or PacketUtil.ACK, PacketUtil.u8(packet[33]))
        assertEquals(443, PacketUtil.readU16(packet, 20))
        assertEquals(40_000, PacketUtil.readU16(packet, 22))
        assertEquals(1001L, PacketUtil.readU32(packet, 28))
        assertEquals(0, PacketUtil.checksum(packet, 0, 20))
        pool.shutdownNow()
    }

    @Test
    fun clientResetClosesAConnectingSession() {
        val closed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val app = AppStats.entry(AppResolver.App("test.tcp.reset", "Test"))
        val session = TcpSession(
            "reset-key", 0x0A4D0002, 40_001, 0x08080808, 443, app, false, 5L,
            PacketSink { }, noRoutes, pool,
        ) { closed.countDown() }
        session.shutdown(false)
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        pool.shutdownNow()
    }
}
