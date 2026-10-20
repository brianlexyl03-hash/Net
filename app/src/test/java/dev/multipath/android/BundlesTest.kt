package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundlesTest {
    @Test
    fun sourceIsExhaustedOnlyAfterItsLimitIsUsed() {
        Bundles.setLimit("test-cell", 1_000L)
        assertFalse(Bundles.exhausted("test-cell"))
        Bundles.add("test-cell", 999L)
        assertFalse(Bundles.exhausted("test-cell"))
        Bundles.add("test-cell", 1L)
        assertTrue(Bundles.exhausted("test-cell"))
    }

    @Test
    fun settingANewLimitResetsUsage() {
        Bundles.setLimit("test-reset", 10L)
        Bundles.add("test-reset", 10L)
        assertTrue(Bundles.exhausted("test-reset"))
        Bundles.setLimit("test-reset", 100L)
        assertEquals(0L, Bundles.usedBytes("test-reset"))
        assertFalse(Bundles.exhausted("test-reset"))
    }

    @Test
    fun zeroLimitMeansUnlimited() {
        Bundles.setLimit("test-open", 0L)
        Bundles.add("test-open", 5_000_000L)
        assertFalse(Bundles.exhausted("test-open"))
        assertEquals("", Bundles.describe("test-open"))
    }

    @Test
    fun formatsSizesForPeople() {
        assertEquals("512 B", Bundles.format(512L))
        assertEquals("2 KB", Bundles.format(2048L))
        assertEquals("5 MB", Bundles.format(5L * 1_048_576L))
        assertEquals("1.50 GB", Bundles.format(1_610_612_736L))
    }
}
