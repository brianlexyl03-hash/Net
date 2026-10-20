package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficHistoryTest {
    @Test
    fun firstSampleConvertsBytesToMegabitsPerSecond() {
        TrafficHistory.clear()
        VpnStats.reset()
        VpnStats.addDown("Wi-Fi", 1_000_000L)
        VpnStats.addUp("Wi-Fi", 250_000L)
        TrafficHistory.sample()
        assertEquals(8.0f, TrafficHistory.totalValues(true).last(), 0.01f)
        assertEquals(2.0f, TrafficHistory.totalValues(false).last(), 0.01f)
        assertEquals(listOf("Wi-Fi"), TrafficHistory.names())
    }

    @Test
    fun totalIsTheSumOfEverySource() {
        TrafficHistory.clear()
        VpnStats.reset()
        VpnStats.addDown("Wi-Fi", 500_000L)
        VpnStats.addDown("Mobile data", 500_000L)
        TrafficHistory.sample()
        assertEquals(8.0f, TrafficHistory.totalValues(true).last(), 0.01f)
        assertEquals(2, TrafficHistory.names().size)
    }

    @Test
    fun historyIsCappedAtOneWindow() {
        TrafficHistory.clear()
        VpnStats.reset()
        repeat(TrafficHistory.POINTS + 10) { TrafficHistory.sample() }
        assertTrue(TrafficHistory.totalValues(true).size <= TrafficHistory.POINTS)
    }

    @Test
    fun counterResetDoesNotProduceNegativeSpeed() {
        TrafficHistory.clear()
        VpnStats.reset()
        VpnStats.addDown("Wi-Fi", 2_000_000L)
        TrafficHistory.sample()
        VpnStats.reset()
        VpnStats.addDown("Wi-Fi", 100_000L)
        TrafficHistory.sample()
        assertTrue(TrafficHistory.totalValues(true).all { it >= 0f })
    }
}
