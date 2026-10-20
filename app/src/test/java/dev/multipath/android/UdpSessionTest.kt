package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UdpSessionTest {
    private val noRoutes = object : RouteProvider {
        override fun acquire(exclude: Set<String>, lowLatency: Boolean): Route? = null
        override fun release(route: Route) {}
    }

    private fun newSession(port: Int, packets: CopyOnWriteArrayList<ByteArray>, closed: CountDownLatch): Pair<UdpSession, java.util.concurrent.ExecutorService> {
        val pool = Executors.newSingleThreadExecutor()
        val app = AppStats.entry(AppResolver.App("test.udp.session", "Test"))
        val session = UdpSession(
            "udp-key", 0x0A4D0002, 50_000, 0x01010101, port, app, false,
            PacketSink { packets.add(it) }, noRoutes, pool,
        ) { closed.countDown() }
        return session to pool
    }

    @Test
    fun closesWhenNoRouteIsAvailable() {
        val closed = CountDownLatch(1)
        val (session, pool) = newSession(5000, CopyOnWriteArrayList(), closed)
        session.send(byteArrayOf(1, 2, 3), 0, 3)
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun dropsNonDnsUdpWhileAnExitServerIsOn() {
        val closed = CountDownLatch(1)
        val packets = CopyOnWriteArrayList<ByteArray>()
        val (session, pool) = newSession(443, packets, closed)
        ExitServer.active = ExitServer.Config("exit.example.test", 8388, "secret")
        try {
            session.send(byteArrayOf(9, 9), 0, 2)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertEquals(0, packets.size)
        } finally {
            ExitServer.active = null
            pool.shutdownNow()
        }
    }

    @Test
    fun closingTwiceReportsOnce() {
        val closed = CountDownLatch(2)
        val (session, pool) = newSession(5001, CopyOnWriteArrayList(), closed)
        session.close()
        session.close()
        assertEquals(1L, closed.count)
        pool.shutdownNow()
    }
}
