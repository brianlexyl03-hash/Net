package dev.multipath.android

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One TCP connection from an app on the phone. The VPN engine terminates the
 * client side of the connection and relays bytes over a real socket that is bound
 * to a specific Android Network (Wi-Fi or mobile data) picked per connection.
 */
class TcpSession(
    val key: String,
    private val clientIp: Int,
    private val clientPort: Int,
    private val serverIp: Int,
    private val serverPort: Int,
    clientIsn: Long,
    private val sink: PacketSink,
    private val routes: RouteProvider,
    private val pool: ExecutorService,
    private val onClosed: (TcpSession) -> Unit,
) {
    private enum class State { CONNECTING, ESTABLISHED, CLOSED }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var state = State.CONNECTING
    private val isn: Long = ThreadLocalRandom.current().nextLong() and PacketUtil.MASK
    private var mySeq: Long = isn
    private var myAck: Long = (clientIsn + 1) and PacketUtil.MASK
    private var acked: Long = isn
    private var clientWindow = 65535
    private var socket: Socket? = null
    private var route: Route? = null
    @Volatile
    private var routeName = ""
    @Volatile
    private var routeKey = ""
    @Volatile
    private var tunnel: Shadowsocks.Tunnel? = null
    private var clientFin = false
    private var serverFinSent = false
    private var zeroWindowSent = false

    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val finMark = ByteArray(0)
    private val queuedBytes = AtomicInteger()

    @Volatile
    var lastActive: Long = System.currentTimeMillis()
        private set

    fun start() {
        VpnStats.tcpOpened.incrementAndGet()
        VpnStats.activeTcp.incrementAndGet()
        pool.execute { connectAndRelay() }
    }

    // ---- outbound connection ------------------------------------------------

    private fun connectAndRelay() {
        val tried = HashSet<String>()
        var connected: Socket? = null
        var usedRoute: Route? = null
        var openedTunnel: Shadowsocks.Tunnel? = null
        val exit = ExitServer.active
        for (attempt in 0 until 3) {
            val candidate = routes.acquire(tried) ?: break
            tried.add(candidate.id)
            try {
                if (exit != null) {
                    val t = Shadowsocks.open(candidate.network, exit, serverIp, null, serverPort)
                    openedTunnel = t
                    connected = t.socket
                } else {
                    val s = Socket()
                    try {
                        candidate.network.bindSocket(s)
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(PacketUtil.toAddress(serverIp), serverPort), 8_000)
                        connected = s
                    } catch (error: Exception) {
                        try { s.close() } catch (_: IOException) { }
                        throw error
                    }
                }
                usedRoute = candidate
                break
            } catch (_: Exception) {
                routes.release(candidate)
            }
        }
        tunnel = openedTunnel
        val sock = connected
        val chosen = usedRoute
        if (sock == null || chosen == null) {
            sink.write(
                PacketUtil.tcpPacket(
                    serverIp, serverPort, clientIp, clientPort,
                    0L, myAck, PacketUtil.RST or PacketUtil.ACK, 0, null, 0, 0,
                )
            )
            shutdown(false)
            return
        }
        var abandoned = false
        lock.withLock {
            if (state == State.CLOSED) {
                abandoned = true
            } else {
                socket = sock
                route = chosen
                routeName = chosen.label
                routeKey = chosen.key
                emit(PacketUtil.SYN or PacketUtil.ACK, null, 0, 0, PacketUtil.MSS)
                mySeq = (mySeq + 1) and PacketUtil.MASK
                acked = mySeq
                state = State.ESTABLISHED
            }
        }
        if (abandoned) {
            try { sock.close() } catch (_: IOException) { }
            routes.release(chosen)
            return
        }
        pool.execute { writerLoop(sock) }
        readerLoop(sock)
    }

    private fun writerLoop(sock: Socket) {
        try {
            val out = sock.getOutputStream()
            while (true) {
                val chunk = writeQueue.take()
                if (chunk === finMark) {
                    try { sock.shutdownOutput() } catch (_: IOException) { }
                    return
                }
                val t = tunnel
                if (t != null) {
                    t.output.write(chunk)
                } else {
                    out.write(chunk)
                    out.flush()
                }
                VpnStats.addUp(routeName, chunk.size.toLong())
                Bundles.add(routeKey, chunk.size.toLong())
                lastActive = System.currentTimeMillis()
                val left = queuedBytes.addAndGet(-chunk.size)
                if (left < 32 * 1024) {
                    lock.withLock {
                        if (zeroWindowSent && state == State.ESTABLISHED) {
                            zeroWindowSent = false
                            emit(PacketUtil.ACK, null, 0, 0, 0)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // session closing
        } catch (_: IOException) {
            shutdown(true)
        }
    }

    private fun readerLoop(sock: Socket) {
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val t = tunnel
                val n = if (t != null) t.input.read(buffer, 0, buffer.size) else input.read(buffer)
                if (n < 0) break
                var offset = 0
                while (offset < n) {
                    val len = minOf(PacketUtil.MSS, n - offset)
                    var sent = false
                    lock.withLock {
                        while (state == State.ESTABLISHED && inFlight() >= clientWindow) {
                            cond.await(1, TimeUnit.SECONDS)
                            if (System.currentTimeMillis() - lastActive > IDLE_MS) break
                        }
                        if (state == State.ESTABLISHED) {
                            emit(PacketUtil.ACK or PacketUtil.PSH, buffer, offset, len, 0)
                            mySeq = (mySeq + len) and PacketUtil.MASK
                            sent = true
                        }
                    }
                    if (!sent) return
                    VpnStats.addDown(routeName, len.toLong())
                    Bundles.add(routeKey, len.toLong())
                    lastActive = System.currentTimeMillis()
                    offset += len
                }
            }
            lock.withLock {
                if (state == State.ESTABLISHED && !serverFinSent) {
                    emit(PacketUtil.FIN or PacketUtil.ACK, null, 0, 0, 0)
                    mySeq = (mySeq + 1) and PacketUtil.MASK
                    serverFinSent = true
                }
            }
            checkDone()
        } catch (_: InterruptedException) {
            // session closing
        } catch (_: IOException) {
            shutdown(true)
        }
    }

    // ---- packets from the phone (TUN thread) -------------------------------

    fun onPacket(seq: Long, ack: Long, flags: Int, window: Int, buf: ByteArray, payloadOffset: Int, payloadLength: Int) {
        lastActive = System.currentTimeMillis()
        if (flags and PacketUtil.RST != 0) {
            shutdown(false)
            return
        }
        var done = false
        lock.withLock {
            if (state == State.CONNECTING || state == State.CLOSED) return
            if (flags and PacketUtil.SYN != 0) {
                // Client retransmitted its SYN: repeat our SYN-ACK.
                sink.write(
                    PacketUtil.tcpPacket(
                        serverIp, serverPort, clientIp, clientPort,
                        isn, myAck, PacketUtil.SYN or PacketUtil.ACK, 65535, null, 0, 0, PacketUtil.MSS,
                    )
                )
                return
            }
            if (flags and PacketUtil.ACK != 0) {
                if (PacketUtil.seqAfter(ack, acked) && !PacketUtil.seqAfter(ack, mySeq)) acked = ack
                clientWindow = window
                cond.signalAll()
            }

            var dataOffset = payloadOffset
            var dataLength = payloadLength
            var dataSeq = seq
            var accepted = true
            if (dataLength > 0) {
                if (dataSeq != myAck) {
                    val behind = (myAck - dataSeq) and PacketUtil.MASK
                    if (behind in 1L..0x7FFFFFFFL && behind < dataLength) {
                        dataOffset += behind.toInt()
                        dataLength -= behind.toInt()
                        dataSeq = myAck
                    } else {
                        accepted = false
                    }
                }
                if (accepted) {
                    val chunk = buf.copyOfRange(dataOffset, dataOffset + dataLength)
                    myAck = (myAck + dataLength) and PacketUtil.MASK
                    queuedBytes.addAndGet(chunk.size)
                    writeQueue.offer(chunk)
                    emit(PacketUtil.ACK, null, 0, 0, 0)
                } else {
                    emit(PacketUtil.ACK, null, 0, 0, 0)
                    return
                }
            }
            if (flags and PacketUtil.FIN != 0) {
                val finSeq = (seq + payloadLength) and PacketUtil.MASK
                if (!clientFin && finSeq == myAck) {
                    clientFin = true
                    myAck = (myAck + 1) and PacketUtil.MASK
                    writeQueue.offer(finMark)
                }
                emit(PacketUtil.ACK, null, 0, 0, 0)
            }
            done = clientFin && serverFinSent && acked == mySeq
        }
        if (done) shutdown(false)
    }

    private fun checkDone() {
        var done = false
        lock.withLock { done = clientFin && serverFinSent && acked == mySeq }
        if (done) shutdown(false)
    }

    // ---- helpers -----------------------------------------------------------

    private fun inFlight(): Long = (mySeq - acked) and PacketUtil.MASK

    private fun advertisedWindow(): Int {
        val queued = queuedBytes.get()
        return if (queued > 128 * 1024) {
            zeroWindowSent = true
            0
        } else {
            65535
        }
    }

    /** Must be called with [lock] held so packets leave in sequence order. */
    private fun emit(flags: Int, payload: ByteArray?, offset: Int, length: Int, mss: Int) {
        sink.write(
            PacketUtil.tcpPacket(
                serverIp, serverPort, clientIp, clientPort,
                mySeq, myAck, flags, advertisedWindow(), payload, offset, length, mss,
            )
        )
    }

    fun shutdown(sendRst: Boolean) {
        var sock: Socket? = null
        var used: Route? = null
        lock.withLock {
            if (state == State.CLOSED) return
            val established = state == State.ESTABLISHED
            state = State.CLOSED
            if (sendRst && established) emit(PacketUtil.RST or PacketUtil.ACK, null, 0, 0, 0)
            sock = socket
            used = route
            route = null
            cond.signalAll()
        }
        try { sock?.close() } catch (_: IOException) { }
        used?.let { routes.release(it) }
        writeQueue.offer(finMark)
        VpnStats.activeTcp.decrementAndGet()
        onClosed(this)
    }

    companion object {
        const val IDLE_MS = 30 * 60 * 1000L
    }
}
