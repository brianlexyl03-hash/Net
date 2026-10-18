package dev.multipath.android

import java.net.InetAddress

/** Minimal IPv4/TCP/UDP packet helpers used by the VPN engine. */
object PacketUtil {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val MSS = 1400
    const val MASK = 0xFFFFFFFFL

    private var ipId = 0
    private val ipIdLock = Any()

    fun u8(b: Byte): Int = b.toInt() and 0xFF
    fun readU16(d: ByteArray, o: Int): Int = (u8(d[o]) shl 8) or u8(d[o + 1])
    fun readInt(d: ByteArray, o: Int): Int =
        (u8(d[o]) shl 24) or (u8(d[o + 1]) shl 16) or (u8(d[o + 2]) shl 8) or u8(d[o + 3])
    fun readU32(d: ByteArray, o: Int): Long = readInt(d, o).toLong() and MASK

    fun writeU16(d: ByteArray, o: Int, v: Int) {
        d[o] = (v ushr 8).toByte()
        d[o + 1] = v.toByte()
    }

    fun writeU32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v ushr 24).toByte()
        d[o + 1] = (v ushr 16).toByte()
        d[o + 2] = (v ushr 8).toByte()
        d[o + 3] = v.toByte()
    }

    fun writeInt(d: ByteArray, o: Int, v: Int) = writeU32(d, o, v.toLong() and MASK)

    /** True when sequence number [a] is after [b] (modulo 2^32). */
    fun seqAfter(a: Long, b: Long): Boolean {
        val diff = (a - b) and MASK
        return diff in 1L..0x7FFFFFFFL
    }

    /** One's-complement checksum. Pass an [initial] partial sum for pseudo headers. */
    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun pseudoSum(src: Int, dst: Int, protocol: Int, length: Int): Long =
        ((src ushr 16) + (src and 0xFFFF) + (dst ushr 16) + (dst and 0xFFFF) + protocol + length).toLong()

    private fun nextId(): Int = synchronized(ipIdLock) {
        ipId = (ipId + 1) and 0xFFFF
        ipId
    }

    private fun writeIpHeader(p: ByteArray, total: Int, protocol: Int, src: Int, dst: Int) {
        p[0] = 0x45
        p[1] = 0
        writeU16(p, 2, total)
        writeU16(p, 4, nextId())
        writeU16(p, 6, 0x4000)
        p[8] = 64
        p[9] = protocol.toByte()
        writeU16(p, 10, 0)
        writeInt(p, 12, src)
        writeInt(p, 16, dst)
        writeU16(p, 10, checksum(p, 0, 20))
    }

    fun tcpPacket(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray?, payloadOffset: Int, payloadLength: Int, mss: Int = 0,
    ): ByteArray {
        val tcpHeader = if (mss > 0) 24 else 20
        val tcpLen = tcpHeader + payloadLength
        val total = 20 + tcpLen
        val p = ByteArray(total)
        writeIpHeader(p, total, 6, srcIp, dstIp)
        writeU16(p, 20, srcPort)
        writeU16(p, 22, dstPort)
        writeU32(p, 24, seq and MASK)
        writeU32(p, 28, ack and MASK)
        p[32] = ((tcpHeader / 4) shl 4).toByte()
        p[33] = flags.toByte()
        writeU16(p, 34, window.coerceIn(0, 65535))
        writeU16(p, 36, 0)
        writeU16(p, 38, 0)
        if (mss > 0) {
            p[40] = 2
            p[41] = 4
            writeU16(p, 42, mss)
        }
        if (payload != null && payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, p, 20 + tcpHeader, payloadLength)
        }
        writeU16(p, 36, checksum(p, 20, tcpLen, pseudoSum(srcIp, dstIp, 6, tcpLen)))
        return p
    }

    fun udpPacket(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        payload: ByteArray, payloadOffset: Int, payloadLength: Int,
    ): ByteArray {
        val udpLen = 8 + payloadLength
        val total = 20 + udpLen
        val p = ByteArray(total)
        writeIpHeader(p, total, 17, srcIp, dstIp)
        writeU16(p, 20, srcPort)
        writeU16(p, 22, dstPort)
        writeU16(p, 24, udpLen)
        writeU16(p, 26, 0) // UDP checksum is optional over IPv4
        System.arraycopy(payload, payloadOffset, p, 28, payloadLength)
        return p
    }

    fun toAddress(ip: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    )
}
