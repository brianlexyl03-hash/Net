package dev.multipath.android

import android.net.Network

data class LinkSnapshot(
    val network: Network,
    val transport: String,
    val validated: Boolean,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val interfaceName: String?,
    val latencyMs: Long? = null,
    val measuredMbps: Double? = null,
    val lastError: String? = null,
) {
    val id: String get() = network.toString()
}

data class FlowCandidate(
    val id: String,
    val validated: Boolean,
    val measuredMbps: Double?,
    val advertisedDownKbps: Int,
    val activeFlows: Int = 0,
)
