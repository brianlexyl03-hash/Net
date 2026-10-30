package dev.multipath.android

object Ipv6Policy {
    private var relayAvailable = false
    private var ipv6Allowed = false

    // This is intentionally conservative: if the relay is not ready, do not leak IPv6.
    fun setRelayState(connected: Boolean) {
        relayAvailable = connected
        ipv6Allowed = connected
    }

    fun ipv6RouteEnabled(): Boolean = relayAvailable && ipv6Allowed

    fun blockIpv6(): Boolean {
        ipv6Allowed = false
        return false
    }

    fun relayOffline(): Boolean = !relayAvailable
}
