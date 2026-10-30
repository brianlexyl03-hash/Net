package dev.multipath.android

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Relay transport contract for a relay-backed multipath VPN.
 *
 * This is intentionally transport-agnostic: the production Android implementation can use a
 * native QUIC transport or another approved tunnel, while the rest of the app uses this API.
 */
data class RelayConfig(
    val host: String,
    val port: Int,
    val token: String,
    val enableTls: Boolean = true,
)

data class RelayPacket(
    val sessionId: String,
    val flowId: String,
    val pathId: String,
    val sequence: Long,
    val payload: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
)

class RelayClient(
    private val config: RelayConfig,
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var connected = false

    @Volatile
    private var sessionId: String = "android-${System.currentTimeMillis()}"

    private val packetCounter = AtomicLong(0)

    fun connect(): Boolean {
        return try {
            val s = Socket()
            if (config.enableTls) {
                // Production implementation would use a TLS-protected QUIC tunnel or a vetted
                // Android-native socket stack. This skeleton keeps the contract explicit and
                // avoids pretending that a plain socket is the final solution.
            }
            s.connect(InetSocketAddress(config.host, config.port), 5_000)
            socket = s
            output = s.getOutputStream()
            connected = true
            true
        } catch (t: Throwable) {
            connected = false
            false
        }
    }

    fun send(packet: RelayPacket): Boolean {
        if (!connected || output == null) return false
        val header = buildHeader(packet)
        return try {
            output?.write(header)
            output?.write(packet.payload)
            output?.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun nextPacketId(): Long = packetCounter.incrementAndGet()

    fun close() {
        connected = false
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        output = null
    }

    fun sessionId(): String = sessionId

    private fun buildHeader(packet: RelayPacket): ByteArray {
        val sb = StringBuilder()
        sb.append("MULTIPATH\n")
        sb.append("session=").append(packet.sessionId).append('\n')
        sb.append("flow=").append(packet.flowId).append('\n')
        sb.append("path=").append(packet.pathId).append('\n')
        sb.append("seq=").append(packet.sequence).append('\n')
        sb.append("ts=").append(packet.timestampMs).append('\n')
        sb.append("len=").append(packet.payload.size).append('\n')
        sb.append("token=").append(config.token).append('\n')
        sb.append("---\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
