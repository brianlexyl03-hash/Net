package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiRadarTest {
    @Test
    fun mapsFrequenciesToChannels() {
        assertEquals(1, WifiRadar.channelOf(2412))
        assertEquals(6, WifiRadar.channelOf(2437))
        assertEquals(11, WifiRadar.channelOf(2462))
        assertEquals(36, WifiRadar.channelOf(5180))
        assertEquals(149, WifiRadar.channelOf(5745))
        assertEquals("2.4 GHz", WifiRadar.bandOf(2437))
        assertEquals("5 GHz", WifiRadar.bandOf(5180))
    }

    @Test
    fun readsSecurityFromCapabilities() {
        assertEquals("WPA3", WifiRadar.securityOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2", WifiRadar.securityOf("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("Open", WifiRadar.securityOf("[ESS]"))
        assertEquals("WEP", WifiRadar.securityOf("[WEP][ESS]"))
    }
}
