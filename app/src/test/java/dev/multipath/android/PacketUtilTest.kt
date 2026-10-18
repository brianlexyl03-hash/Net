package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketUtilTest {
    @Test
    fun checksumMatchesKnownIpv4Header() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xc0.toByte(), 0xa8.toByte(), 0x00, 0x01, 0xc0.toByte(), 0xa8.toByte(), 0x00, 0xc7.toByte(),
        )
        assertEquals(0xb861, PacketUtil.checksum(header, 0, 20))
    }

    @Test
    fun tcpPacketHasValidChecksumsAndFields() {
        val src = 0x0A000001
        val dst = 0x08080808
        val payload = "hello".toByteArray()
        val p = PacketUtil.tcpPacket(dst, 443, src, 50_000, 1000L, 2000L, PacketUtil.ACK or PacketUtil.PSH, 65535, payload, 0, payload.size)
        assertEquals(20 + 20 + payload.size, p.size)
        assertEquals(0, PacketUtil.checksum(p, 0, 20))
        val tcpLen = p.size - 20
        assertEquals(0, PacketUtil.checksum(p, 20, tcpLen, PacketUtil.pseudoSum(dst, src, 6, tcpLen)))
        assertEquals(443, PacketUtil.readU16(p, 20))
        assertEquals(1000L, PacketUtil.readU32(p, 24))
        assertEquals(2000L, PacketUtil.readU32(p, 28))
        assertEquals("hello", String(p, 40, 5))
    }

    @Test
    fun synAckCarriesMssOption() {
        val p = PacketUtil.tcpPacket(1, 80, 2, 1234, 5L, 6L, PacketUtil.SYN or PacketUtil.ACK, 65535, null, 0, 0, PacketUtil.MSS)
        assertEquals(44, p.size)
        assertEquals(PacketUtil.MSS, PacketUtil.readU16(p, 42))
        val tcpLen = p.size - 20
        assertEquals(0, PacketUtil.checksum(p, 20, tcpLen, PacketUtil.pseudoSum(1, 2, 6, tcpLen)))
    }

    @Test
    fun udpPacketIsWellFormed() {
        val payload = byteArrayOf(1, 2, 3)
        val p = PacketUtil.udpPacket(0x01010101, 53, 0x0A000001, 4000, payload, 0, 3)
        assertEquals(31, p.size)
        assertEquals(0, PacketUtil.checksum(p, 0, 20))
        assertEquals(11, PacketUtil.readU16(p, 24))
        assertEquals(3, p[30].toInt())
    }

    @Test
    fun sequenceComparisonHandlesWraparound() {
        assertTrue(PacketUtil.seqAfter(5L, 0xFFFFFFF0L))
        assertFalse(PacketUtil.seqAfter(0xFFFFFFF0L, 5L))
        assertFalse(PacketUtil.seqAfter(7L, 7L))
    }
}
