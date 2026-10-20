package dev.multipath.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerCustomListTest {
    @After
    fun cleanUp() {
        Blocker.clearCustom()
    }

    @Test
    fun normalizesPastedAddresses() {
        assertEquals("evil-site.com", Blocker.normalize("https://www.Evil-Site.com/some/path?x=1"))
        assertEquals("example.org", Blocker.normalize("  http://example.org:8080/  "))
    }

    @Test
    fun customDomainsAndTheirSubdomainsAreBlocked() {
        assertTrue(Blocker.addCustom("https://scam-example.test/login"))
        assertTrue(Blocker.isBlocked("scam-example.test"))
        assertTrue(Blocker.isBlocked("pay.scam-example.test"))
        assertFalse(Blocker.isBlocked("example.test"))
    }

    @Test
    fun rejectsEntriesThatAreNotDomains() {
        assertFalse(Blocker.addCustom(""))
        assertFalse(Blocker.addCustom("localhost"))
        assertEquals(0, Blocker.customCount())
    }

    @Test
    fun clearingTheListUnblocksEverything() {
        Blocker.addCustom("blocked-for-test.example")
        Blocker.clearCustom()
        assertFalse(Blocker.isBlocked("blocked-for-test.example"))
    }
}
