package dev.multipath.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/** Watches network routes supplied by Android; it never changes Wi-Fi settings. */
class NetworkMonitor(
    context: Context,
    private val keepCellularAlive: Boolean = false,
    private val onChanged: (List<LinkSnapshot>) -> Unit,
) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private val entries = linkedMapOf<Network, LinkSnapshot>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private val extraListener = Runnable { publish() }
    private val keepAliveRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()
    private val keepAliveCallback = object : ConnectivityManager.NetworkCallback() {}
    private var keepAliveRegistered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            refresh(network, networkCapabilities)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh(network)
        override fun onLost(network: Network) {
            synchronized(lock) { entries.remove(network) }
            publish()
        }
    }

    fun start() {
        synchronized(lock) {
            if (registered) return
            registered = true
        }
        ExtraWifi.addListener(extraListener)
        try {
            manager.registerNetworkCallback(request, callback)
            manager.allNetworks.forEach { refresh(it) }
            if (keepCellularAlive) {
                // Asking for a cellular network keeps the mobile radio connected while Wi-Fi is
                // the default route, which is what lets both links carry traffic at once.
                try {
                    manager.requestNetwork(keepAliveRequest, keepAliveCallback)
                    keepAliveRegistered = true
                } catch (_: RuntimeException) {
                    keepAliveRegistered = false
                }
            }
        } catch (error: RuntimeException) {
            synchronized(lock) { registered = false }
            publish()
        }
    }

    fun stop() {
        val shouldUnregister = synchronized(lock) {
            if (!registered) false else {
                registered = false
                true
            }
        }
        if (shouldUnregister) {
            ExtraWifi.removeListener(extraListener)
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: RuntimeException) {
                // The callback may already have been removed by Android.
            }
            if (keepAliveRegistered) {
                keepAliveRegistered = false
                try {
                    manager.unregisterNetworkCallback(keepAliveCallback)
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    fun scanNow() {
        manager.allNetworks.forEach { refresh(it) }
        publish()
    }

    fun snapshots(): List<LinkSnapshot> {
        val own = synchronized(lock) { entries.values.toList() }
        val extras = ExtraWifi.snapshots()
        val extraIds = extras.map { it.id }.toSet()
        return (own.filter { it.id !in extraIds } + extras).sortedWith(
            compareByDescending<LinkSnapshot> { it.validated }
                .thenByDescending { it.measuredMbps ?: 0.0 }
                .thenBy { it.transport }
        )
    }

    fun recordLatency(network: Network, latencyMs: Long) {
        synchronized(lock) {
            val old = entries[network] ?: return
            entries[network] = old.copy(latencyMs = latencyMs, lastError = null)
        }
        publish()
    }

    fun recordSpeed(network: Network, mbps: Double) {
        sharedSpeed[network.toString()] = mbps
        synchronized(lock) {
            val old = entries[network] ?: return
            entries[network] = old.copy(measuredMbps = mbps, lastError = null)
        }
        publish()
    }

    fun recordError(network: Network, message: String) {
        synchronized(lock) {
            val old = entries[network] ?: return
            entries[network] = old.copy(lastError = message)
        }
        publish()
    }

    fun clearMeasurements() {
        sharedSpeed.clear()
        synchronized(lock) {
            entries.replaceAll { _, old -> old.copy(latencyMs = null, measuredMbps = null, lastError = null) }
        }
        publish()
    }

    private fun refresh(network: Network, suppliedCapabilities: NetworkCapabilities? = null) {
        val capabilities = suppliedCapabilities ?: manager.getNetworkCapabilities(network)
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            synchronized(lock) { entries.remove(network) }
            publish()
            return
        }
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val transport = when {
            isVpn -> "VPN tunnel"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other network"
        }
        // VPN routes are not candidates for the future uplink balancer; their
        // underlying routes are listed separately by Android when available.
        if (isVpn || transport == "Other network" || transport == "Bluetooth") {
            synchronized(lock) { entries.remove(network) }
            publish()
            return
        }
        val properties = manager.getLinkProperties(network)
        synchronized(lock) {
            val old = entries[network]
            entries[network] = LinkSnapshot(
                network = network,
                transport = transport,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                downstreamKbps = capabilities.linkDownstreamBandwidthKbps,
                upstreamKbps = capabilities.linkUpstreamBandwidthKbps,
                interfaceName = properties?.interfaceName,
                latencyMs = old?.latencyMs,
                measuredMbps = old?.measuredMbps ?: sharedSpeed[network.toString()],
                lastError = old?.lastError,
            )
        }
        publish()
    }

    companion object {
        /** Speed results survive across monitor instances so the VPN can reuse app tests. */
        val sharedSpeed = java.util.concurrent.ConcurrentHashMap<String, Double>()
    }

    private fun publish() {
        val copy = snapshots()
        NetLog.observe(copy)
        mainHandler.post { onChanged(copy) }
    }
}
