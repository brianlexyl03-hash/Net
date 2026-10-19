package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerTest {
    private fun query(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    @Test
    fun parsesQueryName() {
        val packet = query("Ads.DoubleClick.net")
        val parsed = Blocker.parse(packet, 0, packet.size)
        assertNotNull(parsed)
        assertEquals("ads.doubleclick.net", parsed!!.name)
        assertEquals(packet.size, parsed.length)
    }

    @Test
    fun blocksSubdomainsOfListedDomains() {
        assertTrue(Blocker.isBlocked("pagead.doubleclick.net"))
        assertTrue(Blocker.isBlocked("doubleclick.net"))
        assertFalse(Blocker.isBlocked("example.com"))
        assertFalse(Blocker.isBlocked("net"))
    }

    @Test
    fun replyIsNxdomainWithSameId() {
        val packet = query("doubleclick.net")
        val parsed = Blocker.parse(packet, 0, packet.size)!!
        val reply = Blocker.nxdomain(packet, 0, parsed.length)
        assertEquals(0x1234, PacketUtil.readU16(reply, 0))
        val flags = PacketUtil.readU16(reply, 2)
        assertTrue((flags and 0x8000) != 0)
        assertEquals(3, flags and 0x000F)
        assertEquals(0, PacketUtil.readU16(reply, 6))
    }

    @Test
    fun ignoresResponsesAndShortPackets() {
        val packet = query("doubleclick.net")
        packet[2] = 0x81.toByte()
        assertNull(Blocker.parse(packet, 0, packet.size))
        assertNull(Blocker.parse(ByteArray(5), 0, 5))
    }
}
