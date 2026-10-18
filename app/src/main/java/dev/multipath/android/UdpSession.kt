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

    fun send(buf: ByteArray, offset: Int, length: Int) {
        lastActive = System.currentTimeMillis()
        val s = open()
        if (s == null) {
            close()
            return
        }
        try {
            s.send(DatagramPacket(buf.copyOfRange(offset, offset + length), length))
            VpnStats.bytesUp.addAndGet(length.toLong())
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
                VpnStats.bytesDown.addAndGet(packet.length.toLong())
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
