package dev.multipath.android

/**
 * Small, testable connection scheduler inspired by MultiPath's desktop policy.
 * It can choose a route for a future per-flow transport engine; this Android
 * MVP does not yet intercept or reroute traffic belonging to other apps.
 */
object FlowBalancer {
    private fun capacityMbps(candidate: FlowCandidate, candidates: Collection<FlowCandidate>): Double {
        val measured = candidate.measuredMbps?.takeIf { it.isFinite() && it > 0.0 }
        val known = candidates.mapNotNull { it.measuredMbps?.takeIf { rate -> rate.isFinite() && rate > 0.0 } }
        if (measured != null) return measured
        // Once a route has a real measurement, do not compare it directly with
        // another route's optimistic Wi-Fi link-rate advertisement. Give an
        // unmeasured route the measured mean until enough evidence exists.
        if (known.isNotEmpty()) return known.average()
        return (candidate.advertisedDownKbps.coerceAtLeast(0) / 1000.0).coerceAtLeast(1.0)
    }

    /** Select the candidate with the most expected room for one new flow. */
    fun chooseNext(candidates: Collection<FlowCandidate>): FlowCandidate? {
        val usable = candidates.filter { it.validated && it.activeFlows >= 0 }
        if (usable.isEmpty()) return null
        return usable.maxWithOrNull(
            compareBy<FlowCandidate> { capacityMbps(it, usable) / (it.activeFlows + 1.0) }
                .thenBy { it.measuredMbps?.let { rate -> rate.isFinite() && rate > 0.0 } == true }
                .thenBy { capacityMbps(it, usable) }
                .thenByDescending { it.id }
        )
    }

    /**
     * Simulate placement of independent flows. Useful for verifying the policy
     * before plugging it into an Android VPN/TUN engine.
     */
    fun distribute(candidates: Collection<FlowCandidate>, flowCount: Int): Map<String, Int> {
        require(flowCount >= 0) { "flowCount must not be negative" }
        val counts = candidates.associate { it.id to 0 }.toMutableMap()
        repeat(flowCount) {
            val current = candidates.map { candidate ->
                candidate.copy(activeFlows = candidate.activeFlows + (counts[candidate.id] ?: 0))
            }
            val chosen = chooseNext(current) ?: return counts
            counts[chosen.id] = (counts[chosen.id] ?: 0) + 1
        }
        return counts
    }

    fun candidate(link: LinkSnapshot, activeFlows: Int = 0) = FlowCandidate(
        id = link.id,
        validated = link.validated,
        measuredMbps = link.measuredMbps,
        advertisedDownKbps = link.downstreamKbps,
        activeFlows = activeFlows,
    )
}
