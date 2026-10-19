package dev.multipath.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/** Scans nearby Wi-Fi and finds the quietest channel for your router. */
object WifiRadar {
    class Net(val ssid: String, val level: Int, val channel: Int, val band: String, val security: String)

    class Result(val nets: List<Net>, val best24: Int?, val best5: Int?)

    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5000..5900 -> (freq - 5000) / 5
        freq >= 5955 -> (freq - 5950) / 5
        else -> 0
    }

    fun bandOf(freq: Int): String = when {
        freq < 3000 -> "2.4 GHz"
        freq < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    fun securityOf(capabilities: String): String = when {
        capabilities.contains("SAE") || capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") || capabilities.contains("RSN") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> "Open"
    }

    @Suppress("DEPRECATION")
    fun analyze(results: List<ScanResult>): Result {
        val nets = results.map {
            Net(it.SSID.ifBlank { "(hidden)" }, it.level, channelOf(it.frequency), bandOf(it.frequency), securityOf(it.capabilities))
        }
            .groupBy { it.ssid + "|" + it.band }
            .map { entry -> entry.value.maxByOrNull { it.level }!! }
            .sortedByDescending { it.level }

        fun weight(level: Int) = (level + 100).coerceIn(0, 60) / 60.0
        val band24 = nets.filter { it.band == "2.4 GHz" }
        val best24 = listOf(1, 6, 11).minByOrNull { c ->
            band24.sumOf { n ->
                val gap = abs(n.channel - c)
                weight(n.level) * (if (gap == 0) 1.0 else if (gap <= 4) 0.6 else 0.0)
            }
        }
        val band5 = nets.filter { it.band == "5 GHz" }
        val best5 = listOf(36, 40, 44, 48, 149, 153, 157, 161).minByOrNull { c ->
            band5.sumOf { n -> if (n.channel == c) weight(n.level) else 0.0 }
        }
        return Result(nets, best24, best5)
    }

    /** Starts a scan and calls [done] on the main thread with the results, or null if unavailable. */
    @Suppress("DEPRECATION")
    fun scan(context: Context, done: (List<ScanResult>?) -> Unit) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var receiver: BroadcastReceiver? = null

        fun finish() {
            if (finished) return
            finished = true
            receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) { } }
            val results = try { wifi.scanResults } catch (_: SecurityException) { null }
            done(results)
        }

        val listener = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { finish() }
        }
        receiver = listener
        try {
            app.registerReceiver(listener, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } catch (_: Exception) {
            finish()
            return
        }
        val started = try { wifi.startScan() } catch (_: SecurityException) { false }
        if (!started) handler.postDelayed({ finish() }, 300) else handler.postDelayed({ finish() }, 8_000)
    }
}
