package dev.multipath.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Lets the app join extra Wi-Fi networks next to the one Android already uses, via the
 * Android 10+ network-request API. Whether a phone can really hold two Wi-Fi links at once
 * depends on its Wi-Fi chip; see [support].
 */
object ExtraWifi {
    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }
    enum class Support { YES, NO, UNKNOWN, TOO_OLD }

    class Saved(val ssid: String, val security: String, val secret: String)

    class Live {
        @Volatile var state = State.IDLE
        @Volatile var network: Network? = null
        @Volatile var validated = false
        @Volatile var latencyMs: Long? = null
        @Volatile var message = ""
        @Volatile var callback: ConnectivityManager.NetworkCallback? = null
    }

    private const val PREFS = "extra_wifi"
    private const val KEY = "list"

    @Volatile
    private var app: Context? = null
    private val live = ConcurrentHashMap<String, Live>()
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "extra-wifi").apply { isDaemon = true } }

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun addListener(listener: Runnable) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Runnable) { listeners.remove(listener) }
    private fun changed() { listeners.forEach { it.run() } }

    // ---- saved list -----------------------------------------------------------

    fun load(context: Context): List<Saved> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Saved(o.getString("ssid"), o.getString("security"), o.optString("secret", ""))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun store(context: Context, list: List<Saved>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("ssid", it.ssid).put("security", it.security).put("secret", it.secret))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    /** Returns false when the password could not be stored securely. */
    fun add(context: Context, ssid: String, security: String, password: String): Boolean {
        init(context)
        val secret = if (security == "open") "" else (SecretStore.encrypt(password) ?: return false)
        val next = load(context).filter { it.ssid != ssid } + Saved(ssid, security, secret)
        store(context, next)
        changed()
        return true
    }

    fun remove(context: Context, ssid: String) {
        disconnect(context, ssid)
        store(context, load(context).filter { it.ssid != ssid })
        live.remove(ssid)
        changed()
    }

    fun stateOf(ssid: String): Live? = live[ssid]

    // ---- capability -----------------------------------------------------------

    fun support(context: Context): Support {
        if (Build.VERSION.SDK_INT < 29) return Support.TOO_OLD
        if (Build.VERSION.SDK_INT < 31) return Support.UNKNOWN
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.isStaConcurrencyForLocalOnlyConnectionsSupported()) Support.YES else Support.NO
        } catch (_: Throwable) {
            Support.UNKNOWN
        }
    }

    // ---- connect / disconnect ---------------------------------------------------

    fun connect(context: Context, ssid: String) {
        init(context)
        val st = live.getOrPut(ssid) { Live() }
        if (Build.VERSION.SDK_INT < 29) {
            fail(st, "Needs Android 10 or newer")
            return
        }
        val saved = load(context).firstOrNull { it.ssid == ssid }
        if (saved == null) {
            fail(st, "Not saved")
            return
        }
        val password = if (saved.security == "open") "" else SecretStore.decrypt(saved.secret)
        if (password == null) {
            fail(st, "Password unreadable. Add it again.")
            return
        }
        release(context, st)
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        when (saved.security) {
            "wpa3" -> builder.setWpa3Passphrase(password)
            "wpa2" -> builder.setWpa2Passphrase(password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(builder.build())
            .build()
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                st.network = network
                st.validated = false
                st.state = State.CONNECTING
                st.message = "Checking internet"
                changed()
                io.execute {
                    try {
                        st.latencyMs = NetworkTester.latencyMs(network)
                        st.validated = true
                        st.state = State.CONNECTED
                        st.message = ""
                    } catch (_: Exception) {
                        st.validated = false
                        st.state = State.FAILED
                        st.message = "No internet on this Wi-Fi"
                    }
                    changed()
                }
            }

            override fun onUnavailable() {
                st.network = null
                st.validated = false
                st.state = State.FAILED
                st.message = "Could not connect"
                changed()
            }

            override fun onLost(network: Network) {
                if (st.network == network) {
                    st.network = null
                    st.validated = false
                    st.state = State.IDLE
                    st.message = ""
                    changed()
                }
            }
        }
        st.callback = callback
        st.state = State.CONNECTING
        st.message = "Waiting for approval"
        changed()
        try {
            manager.requestNetwork(request, callback, 60_000)
        } catch (error: Exception) {
            st.callback = null
            fail(st, error.message?.take(60) ?: "Could not start")
        }
    }

    fun disconnect(context: Context, ssid: String) {
        val st = live[ssid] ?: return
        release(context, st)
        st.network = null
        st.validated = false
        st.state = State.IDLE
        st.message = ""
        changed()
    }

    private fun release(context: Context, st: Live) {
        val callback = st.callback ?: return
        st.callback = null
        try {
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // already released
        }
    }

    private fun fail(st: Live, message: String) {
        st.state = State.FAILED
        st.message = message
        changed()
    }

    // ---- route snapshots for the balancer ----------------------------------------

    fun snapshots(): List<LinkSnapshot> {
        val context = app ?: return emptyList()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = ArrayList<LinkSnapshot>()
        for ((ssid, st) in live) {
            val network = st.network ?: continue
            val caps = try { manager.getNetworkCapabilities(network) } catch (_: RuntimeException) { null }
            val props = try { manager.getLinkProperties(network) } catch (_: RuntimeException) { null }
            out.add(
                LinkSnapshot(
                    network = network,
                    transport = "Wi-Fi",
                    validated = st.validated,
                    downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
                    upstreamKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
                    interfaceName = props?.interfaceName,
                    latencyMs = st.latencyMs,
                    measuredMbps = NetworkMonitor.sharedSpeed[network.toString()],
                    lastError = null,
                    label = ssid,
                    extra = true,
                )
            )
        }
        return out
    }
}
