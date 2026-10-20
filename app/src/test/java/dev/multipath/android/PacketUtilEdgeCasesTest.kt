package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketUtilEdgeCasesTest {
    @Test
    fun convertsPackedAddressToText() {
        assertEquals("10.77.0.2", PacketUtil.toAddress(0x0A4D0002).hostAddress)
        assertEquals("255.255.255.255", PacketUtil.toAddress(-1).hostAddress)
    }

    @Test
    fun readsAndWritesUnsigned32BitValues() {
        val buffer = ByteArray(8)
        PacketUtil.writeU32(buffer, 2, 0xFFFFFFFEL)
        assertEquals(0xFFFFFFFEL, PacketUtil.readU32(buffer, 2))
        assertEquals(-2, PacketUtil.readInt(buffer, 2))
    }

    @Test
    fun checksumHandlesOddLengthData() {
        val even = PacketUtil.checksum(byteArrayOf(1, 2, 3, 0), 0, 4)
        val odd = PacketUtil.checksum(byteArrayOf(1, 2, 3), 0, 3)
        assertEquals(even, odd)
    }

    @Test
    fun checksumOfDataPlusItsChecksumIsZero() {
        val data = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
        val sum = PacketUtil.checksum(data, 0, data.size)
        val withSum = data + byteArrayOf((sum shr 8).toByte(), sum.toByte())
        assertEquals(0, PacketUtil.checksum(withSum, 0, withSum.size))
    }

    @Test
    fun sequenceOrderingIgnoresWraparound() {
        assertTrue(PacketUtil.seqAfter(0L, 0xFFFFFFFFL))
        assertFalse(PacketUtil.seqAfter(0xFFFFFFFFL, 0L))
    }

    @Test
    fun synAckCarriesTheAdvertisedSegmentSize() {
        val p = PacketUtil.tcpPacket(1, 80, 2, 3000, 7L, 8L, PacketUtil.SYN or PacketUtil.ACK, 65_535, null, 0, 0, PacketUtil.MSS)
        assertEquals(PacketUtil.SYN or PacketUtil.ACK, PacketUtil.u8(p[33]))
        assertEquals(PacketUtil.MSS, PacketUtil.readU16(p, 42))
    }

    @Test
    fun windowIsClampedToSixteenBits() {
        val p = PacketUtil.tcpPacket(1, 80, 2, 3000, 0L, 0L, PacketUtil.ACK, 1_000_000, null, 0, 0)
        assertEquals(65_535, PacketUtil.readU16(p, 34))
    }
}
