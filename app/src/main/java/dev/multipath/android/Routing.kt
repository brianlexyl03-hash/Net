package dev.multipath.android

import android.content.SharedPreferences
import android.net.Network
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

fun interface PacketSink {
    fun write(packet: ByteArray)
}

class Route(val network: Network, val id: String, val label: String, val key: String = "")

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
    val upByRoute = ConcurrentHashMap<String, AtomicLong>()
    val downByRoute = ConcurrentHashMap<String, AtomicLong>()

    fun addUp(route: String, bytes: Long) {
        bytesUp.addAndGet(bytes)
        upByRoute.getOrPut(route) { AtomicLong() }.addAndGet(bytes)
    }

    fun addDown(route: String, bytes: Long) {
        bytesDown.addAndGet(bytes)
        downByRoute.getOrPut(route) { AtomicLong() }.addAndGet(bytes)
    }

    fun reset() {
        tcpOpened.set(0); udpOpened.set(0); activeTcp.set(0)
        bytesUp.set(0); bytesDown.set(0); flowsByRoute.clear()
        upByRoute.clear(); downByRoute.clear()
    }
}

object RoutePolicy {
    const val PREFS = "route_slots"
    private const val DISABLED = "disabled_sources"

    /** Stable key used to switch a kind of source on or off. */
    fun keyOf(link: LinkSnapshot): String = when {
        link.extra -> "wifi:" + (link.label ?: "")
        link.transport == "Mobile data" -> "cell"
        link.transport == "Wi-Fi" -> "wifi"
        link.transport == "Ethernet" -> "cable"
        else -> link.transport
    }

    /** Short, plain name shown to the user. */
    fun nameOf(link: LinkSnapshot, all: List<LinkSnapshot>): String {
        if (link.extra) return "Wi-Fi: " + (link.label ?: "")
        val base = if (link.transport == "Ethernet") "USB or cable" else link.transport
        val same = all.count { it.transport == link.transport && !it.extra }
        val iface = link.interfaceName
        return if (same > 1 && iface != null) "$base ($iface)" else base
    }

    fun disabled(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(DISABLED, emptySet())?.toSet() ?: emptySet()

    fun setEnabled(prefs: SharedPreferences, key: String, enabled: Boolean) {
        val next = disabled(prefs).toMutableSet()
        if (enabled) next.remove(key) else next.add(key)
        prefs.edit().putStringSet(DISABLED, next).apply()
    }

    fun eligible(routes: List<LinkSnapshot>, disabled: Set<String>): List<LinkSnapshot> =
        routes.filter { keyOf(it) !in disabled && !Bundles.exhausted(keyOf(it)) }.distinctBy { it.id }

    fun eligible(routes: List<LinkSnapshot>, prefs: SharedPreferences): List<LinkSnapshot> =
        eligible(routes, disabled(prefs))
}

/** Picks the Android Network that should carry each new flow, using [FlowBalancer]. */
class RouteSelector(
    private val monitor: NetworkMonitor,
    private val prefs: SharedPreferences,
) : RouteProvider {
    private val active = ConcurrentHashMap<String, AtomicInteger>()

    @Synchronized
    override fun acquire(exclude: Set<String>): Route? {
        val all = monitor.snapshots()
        val pool = RoutePolicy.eligible(all, prefs).filter { it.id !in exclude }
        if (pool.isEmpty()) return null
        val candidates = pool.map { FlowBalancer.candidate(it, active[it.id]?.get() ?: 0) }
        val chosenId = FlowBalancer.chooseNext(candidates)?.id
        val link = pool.firstOrNull { it.id == chosenId } ?: pool.first()
        active.getOrPut(link.id) { AtomicInteger() }.incrementAndGet()
        val label = RoutePolicy.nameOf(link, all)
        VpnStats.flowsByRoute.getOrPut(label) { AtomicLong() }.incrementAndGet()
        return Route(link.network, link.id, label, RoutePolicy.keyOf(link))
    }

    @Synchronized
    override fun release(route: Route) {
        active[route.id]?.decrementAndGet()
    }
}
