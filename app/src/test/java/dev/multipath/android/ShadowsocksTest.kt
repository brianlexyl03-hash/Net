package dev.multipath.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ShadowsocksTest {
    @Test
    fun masterKeyStartsWithMd5OfPassword() {
        val key = Shadowsocks.masterKey("password")
        assertEquals(32, key.size)
        val firstBlock = key.copyOf(16).joinToString("") { "%02x".format(it) }
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", firstBlock)
    }

    @Test
    fun framedStreamRoundTripsAcrossChunkBoundaries() {
        val master = Shadowsocks.masterKey("secret")
        val wire = ByteArrayOutputStream()
        val out = Shadowsocks.Output(wire, master)
        val data = ByteArray(40_000) { (it * 31).toByte() }
        out.write(data)
        out.write(byteArrayOf(1, 2, 3))

        val input = Shadowsocks.Input(ByteArrayInputStream(wire.toByteArray()), master)
        val back = input.readExact(data.size + 3)
        assertArrayEquals(data, back.copyOf(data.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), back.copyOfRange(data.size, data.size + 3))
        val tail = ByteArray(8)
        assertEquals(-1, input.read(tail, 0, tail.size))
    }

    @Test(expected = java.io.IOException::class)
    fun wrongPasswordIsRejected() {
        val wire = ByteArrayOutputStream()
        Shadowsocks.Output(wire, Shadowsocks.masterKey("right")).write(byteArrayOf(9, 9, 9))
        Shadowsocks.Input(ByteArrayInputStream(wire.toByteArray()), Shadowsocks.masterKey("wrong")).readExact(3)
    }
}
