package dev.multipath.android

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

/** One UDP flow (DNS, QUIC, VoIP...) relayed over a socket bound to a chosen Network. */
class UdpSession(
    val key: String,
    private val clientIp: Int,
    private val clientPort: Int,
    private val serverIp: Int,
    private val serverPort: Int,
    private val sink: PacketSink,
    private val routes: RouteProvider,
    private val pool: ExecutorService,
    private val onClosed: (UdpSession) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var route: Route? = null
    @Volatile
    private var routeName = ""
    @Volatile
    private var routeKey = ""
    private var closed = false

    @Volatile
    var lastActive: Long = System.currentTimeMillis()
        private set

    val idleLimitMs: Long get() = if (serverPort == 53) 10_000L else 60_000L

    @Synchronized
    private fun open(): DatagramSocket? {
        socket?.let { return it }
        if (closed) return null
        val tried = HashSet<String>()
        for (attempt in 0 until 3) {
            val candidate = routes.acquire(tried) ?: return null
            tried.add(candidate.id)
            val s = DatagramSocket(null)
            try {
                candidate.network.bindSocket(s)
                s.connect(InetSocketAddress(PacketUtil.toAddress(serverIp), serverPort))
                socket = s
                route = candidate
                routeName = candidate.label
                routeKey = candidate.key
                VpnStats.udpOpened.incrementAndGet()
                pool.execute { receiveLoop(s) }
                return s
            } catch (_: Exception) {
                try { s.close() } catch (_: Exception) { }
                routes.release(candidate)
            }
        }
        return null
    }

    private fun dnsViaTunnel(config: ExitServer.Config, query: ByteArray) {
        pool.execute {
            val tried = HashSet<String>()
            for (attempt in 0 until 2) {
                val route = routes.acquire(tried) ?: break
                tried.add(route.id)
                var ok = false
                try {
                    val tunnel = Shadowsocks.open(route.network, config, serverIp, null, 53)
                    try {
                        tunnel.socket.soTimeout = 6_000
                        tunnel.output.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()) + query)
                        val size = tunnel.input.readExact(2)
                        val n = ((size[0].toInt() and 0xFF) shl 8) or (size[1].toInt() and 0xFF)
                        val answer = tunnel.input.readExact(n)
                        sink.write(PacketUtil.udpPacket(serverIp, 53, clientIp, clientPort, answer, 0, n))
                        VpnStats.addUp(route.label, query.size.toLong())
                        VpnStats.addDown(route.label, n.toLong())
                        Bundles.add(route.key, (query.size + n).toLong())
                        ok = true
                    } finally {
                        try { tunnel.socket.close() } catch (_: IOException) { }
                    }
                } catch (_: Exception) {
                    // try the next route
                } finally {
                    routes.release(route)
                }
                if (ok) break
            }
            close()
        }
    }

    fun send(buf: ByteArray, offset: Int, length: Int) {
        lastActive = System.currentTimeMillis()
        val exit = ExitServer.active
        if (exit != null) {
            // With an exit server on, only DNS is carried (inside the encrypted tunnel).
            // Other UDP is dropped so nothing leaks outside it; apps fall back to TCP.
            if (serverPort == 53) dnsViaTunnel(exit, buf.copyOfRange(offset, offset + length)) else close()
            return
        }
        val s = open()
        if (s == null) {
            close()
            return
        }
        try {
            s.send(DatagramPacket(buf.copyOfRange(offset, offset + length), length))
            VpnStats.addUp(routeName, length.toLong())
            Bundles.add(routeKey, length.toLong())
        } catch (_: IOException) {
            close()
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(65_535)
        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                lastActive = System.currentTimeMillis()
                VpnStats.addDown(routeName, packet.length.toLong())
                Bundles.add(routeKey, packet.length.toLong())
                sink.write(PacketUtil.udpPacket(serverIp, serverPort, clientIp, clientPort, buffer, 0, packet.length))
            }
        } catch (_: IOException) {
            // closed or network lost
        } finally {
            close()
        }
    }

    fun close() {
        var s: DatagramSocket? = null
        var used: Route? = null
        synchronized(this) {
            if (closed) return
            closed = true
            s = socket
            used = route
            route = null
        }
        try { s?.close() } catch (_: Exception) { }
        used?.let { routes.release(it) }
        onClosed(this)
    }
}
