package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStatsTest {
    @Test
    fun appsAreListedByTotalTraffic() {
        AppStats.reset()
        val small = AppStats.entry(AppResolver.App("small.app", "Small"))
        val big = AppStats.entry(AppResolver.App("big.app", "Big"))
        small.down.addAndGet(1_000L)
        big.down.addAndGet(9_000L)
        big.up.addAndGet(500L)
        assertEquals(listOf("big.app", "small.app"), AppStats.top(5).map { it.pkg })
    }

    @Test
    fun appsWithNoTrafficAreHidden() {
        AppStats.reset()
        AppStats.entry(AppResolver.App("idle.app", "Idle"))
        assertTrue(AppStats.top(5).isEmpty())
    }

    @Test
    fun sameAppSharesOneEntry() {
        AppStats.reset()
        val a = AppStats.entry(AppResolver.App("same.app", "Same"))
        val b = AppStats.entry(AppResolver.App("same.app", "Same"))
        assertTrue(a === b)
    }

    @Test
    fun sampleComputesRatesFromByteDeltas() {
        AppStats.reset()
        val app = AppStats.entry(AppResolver.App("rate.app", "Rate"))
        app.down.addAndGet(1_000_000L)
        AppStats.sample()
        assertEquals(8.0f, app.rateDown, 0.01f)
    }

    @Test
    fun unknownAppsAreNotBlocked() {
        assertTrue(!AppBlock.isBlocked("not.blocked"))
        assertTrue(!AppBlock.isBlocked(""))
    }
}
