package dev.multipath.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowBalancerTest {
    @Test
    fun balancesIndependentFlowsAcrossEqualRoutes() {
        val links = listOf(
            FlowCandidate("wifi", true, 10.0, 10_000),
            FlowCandidate("mobile", true, 10.0, 10_000),
        )
        assertEquals(mapOf("wifi" to 5, "mobile" to 5), FlowBalancer.distribute(links, 10))
    }

    @Test
    fun skipsRoutesAndroidHasNotValidated() {
        val links = listOf(
            FlowCandidate("captive-portal", false, 100.0, 100_000),
            FlowCandidate("usable", true, 10.0, 10_000),
        )
        assertEquals("usable", FlowBalancer.chooseNext(links)?.id)
    }

    @Test
    fun returnsNullWhenNoValidatedInternetRouteExists() {
        assertNull(FlowBalancer.chooseNext(listOf(FlowCandidate("offline", false, null, 0))))
    }

    @Test
    fun fasterRoutesReceiveMoreFlows() {
        val links = listOf(
            FlowCandidate("fast", true, 20.0, 20_000),
            FlowCandidate("slow", true, 10.0, 10_000),
        )
        val result = FlowBalancer.distribute(links, 12)
        assertEquals(12, result.values.sum())
        assertTrue(result.getValue("fast") > result.getValue("slow"))
    }
}
