package dev.multipath.android

import android.content.SharedPreferences
import android.net.Network
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

fun interface PacketSink {
    fun write(packet: ByteArray)
}

class Route(val network: Network, val id: String, val label: String)

interface RouteProvider {
    fun acquire(exclude: Set<String>): Route?
    fun release(route: Route)
}

/** Live counters shown in the app while the VPN engine runs. */
object VpnStats {
    val tcpOpened = AtomicLong()
    val udpOpened = AtomicLong()
    val activeTcp = AtomicInteger()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val flowsByRoute = ConcurrentHashMap<String, AtomicLong>()

    fun reset() {
        tcpOpened.set(0); udpOpened.set(0); activeTcp.set(0)
        bytesUp.set(0); bytesDown.set(0); flowsByRoute.clear()
    }
}

object RoutePolicy {
    const val PREFS = "route_slots"

    fun eligible(routes: List<LinkSnapshot>, sim: Int, wifi: Int, hotspot: Int): List<LinkSnapshot> {
        val cellular = routes.filter { it.transport == "Mobile data" }.take(sim)
        val wifiRoutes = routes.filter { it.transport == "Wi-Fi" }.take(wifi + hotspot)
        val other = routes.filter { it.transport != "Mobile data" && it.transport != "Wi-Fi" }
        return (cellular + wifiRoutes + other).distinctBy { it.id }
    }

    fun eligible(routes: List<LinkSnapshot>, prefs: SharedPreferences): List<LinkSnapshot> = eligible(
        routes,
        prefs.getInt("sim_slots", 2).coerceIn(0, 2),
        prefs.getInt("wifi_slots", 2).coerceIn(0, 4),
        prefs.getInt("hotspot_slots", 2).coerceIn(0, 4),
    )
}

/** Picks the Android Network that should carry each new flow, using [FlowBalancer]. */
class RouteSelector(
    private val monitor: NetworkMonitor,
    private val prefs: SharedPreferences,
) : RouteProvider {
    private val active = ConcurrentHashMap<String, AtomicInteger>()

    @Synchronized
    override fun acquire(exclude: Set<String>): Route? {
        val pool = RoutePolicy.eligible(monitor.snapshots(), prefs).filter { it.id !in exclude }
        if (pool.isEmpty()) return null
        val candidates = pool.map { FlowBalancer.candidate(it, active[it.id]?.get() ?: 0) }
        val chosenId = FlowBalancer.chooseNext(candidates)?.id
        val link = pool.firstOrNull { it.id == chosenId } ?: pool.first()
        active.getOrPut(link.id) { AtomicInteger() }.incrementAndGet()
        val label = link.interfaceName?.let { "${link.transport} ($it)" } ?: link.transport
        VpnStats.flowsByRoute.getOrPut(label) { AtomicLong() }.incrementAndGet()
        return Route(link.network, link.id, label)
    }

    @Synchronized
    override fun release(route: Route) {
        active[route.id]?.decrementAndGet()
    }
}
