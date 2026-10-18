package dev.multipath.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 17, 15)
    private val surface = Color.rgb(17, 31, 27)
    private val surface2 = Color.rgb(23, 41, 35)
    private val line = Color.rgb(42, 65, 55)
    private val accent = Color.rgb(124, 243, 169)
    private val white = Color.rgb(240, 247, 242)
    private val muted = Color.rgb(158, 180, 168)
    private val amber = Color.rgb(255, 212, 121)
    private val blue = Color.rgb(123, 201, 255)
    private val red = Color.rgb(255, 143, 143)

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var monitor: NetworkMonitor
    private lateinit var scroller: ScrollView
    private lateinit var content: LinearLayout
    private var status = "Ready. Scan to inspect routes available to Android."
    private var running = false
    private var lastParallelAggregateMbps: Double? = null
    private var lastParallelRouteCount = 0
    private var lastParallelDurationSeconds: Double? = null
    private val prefs by lazy { getSharedPreferences("route_slots", MODE_PRIVATE) }
    private var simSlots = 2
    private var wifiSlots = 2
    private var hotspotSlots = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        scroller = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(22))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        scroller.addView(content)
        setContentView(scroller)

        simSlots = prefs.getInt("sim_slots", 2).coerceIn(0, 2)
        wifiSlots = prefs.getInt("wifi_slots", 2).coerceIn(0, 4)
        hotspotSlots = prefs.getInt("hotspot_slots", 2).coerceIn(0, 4)
        monitor = NetworkMonitor(this) { render() }
        render()
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        if (!::content.isInitialized || isFinishing) return
        val previousScroll = scroller.scrollY
        content.removeAllViews()
        val routes = monitor.snapshots()
        val candidates = routes.map { FlowBalancer.candidate(it) }
        val best = FlowBalancer.chooseNext(candidates)?.id
        val validatedCount = routes.count { it.validated }

        content.addView(buildHeader())
        addSpace(content, 22)
        addText(content, "YOUR NETWORK, AT A GLANCE", 10f, accent, bold = true, letterSpacing = 1.55f)
        addSpace(content, 8)
        addText(content, "Know your links.\nChoose with data.", 30f, white, bold = true)
        addSpace(content, 9)
        addText(content, "Discover the connections Android exposes, compare latency and speed, and see which route is performing best.", 14f, muted)
        addSpace(content, 19)

        content.addView(buildOverview(routes.size, validatedCount, routes.firstOrNull { it.id == best }))
        addSpace(content, 12)
        content.addView(buildSlotSettings(routes))
        addSpace(content, 12)
        content.addView(buildCombinedSpeed(routes))
        addSpace(content, 12)
        content.addView(buildActions())
        addSpace(content, 12)
        content.addView(buildRoutes(routes, best))
        addSpace(content, 12)
        content.addView(buildCaveat())
        addSpace(content, 12)
        content.addView(buildTips())
        addSpace(content, 22)
        addText(content, "MULTIPATH  ·  NETWORK DIAGNOSTICS", 10f, muted, bold = true, letterSpacing = 0.8f)
        content.post { scroller.scrollTo(0, previousScroll) }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_mark)
            contentDescription = "MultiPath app logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        row.addView(logo)
        val nameBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        nameBlock.addView(text("MultiPath", 17f, white, true))
        nameBlock.addView(text("NETWORK INTELLIGENCE", 9f, muted, true).apply { letterSpacing = 1.05f })
        row.addView(nameBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = text(if (running) "● TESTING" else "● READY", 10f, if (running) amber else accent, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(surface2, 24, line)
            contentDescription = if (running) "Network tests are running" else "App ready"
        }
        row.addView(badge)
        return row
    }

    private fun buildOverview(total: Int, validated: Int, best: LinkSnapshot?): View {
        val card = card()
        addText(card, "LINK OVERVIEW", 11f, muted, true, 1.3f)
        addSpace(card, 12)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metric("$total", "ROUTES SEEN", accent), weightParams())
        addVerticalDivider(metrics)
        metrics.addView(metric("$validated", "VALIDATED", blue), weightParams())
        addVerticalDivider(metrics)
        val latency = best?.latencyMs?.let { "$it ms" } ?: "—"
        metrics.addView(metric(latency, "LEADER RTT", amber), weightParams())
        card.addView(metrics)
        addSpace(card, 13)
        val message = when {
            total == 0 -> "No internet-capable routes surfaced yet. Turn on Wi-Fi or mobile data and scan again."
            validated == 0 -> "Android sees route(s), but none are validated for internet access yet."
            best != null && best.measuredMbps != null -> "Current leader: ${best.transport}, measured at ${formatMbps(best.measuredMbps)} download."
            best != null -> "Current leader: ${best.transport}, based on Android's advertised link estimate. Run a speed test for a real measurement."
            else -> "Routes found. Run latency checks to see which ones can reach the test endpoint."
        }
        addText(card, message, 13f, muted)
        return card
    }

    private fun buildSlotSettings(routes: List<LinkSnapshot>): View {
        val card = card()
        addText(card, "CONNECTION SLOT SETTINGS", 11f, muted, true, 1.3f)
        addSpace(card, 6)
        addText(card, "Choose how many paths you want the manager to consider. Android can expose fewer real paths than the configured slots.", 12f, muted)
        addSpace(card, 12)
        slotRow(card, "Cellular routes", "Up to 2 phone SIM routes; carrier name may not be exposed", simSlots, 0, 2) { simSlots = it; prefs.edit().putInt("sim_slots", it).apply() }
        addSpace(card, 8)
        slotRow(card, "Wi-Fi route slots", "Includes ordinary Wi-Fi and Wi-Fi hotspots", wifiSlots, 0, 4) { wifiSlots = it; prefs.edit().putInt("wifi_slots", it).apply() }
        addSpace(card, 8)
        slotRow(card, "Extra hotspot slots", "Only useful when Android exposes extra routes", hotspotSlots, 0, 4) { hotspotSlots = it; prefs.edit().putInt("hotspot_slots", it).apply() }
        addSpace(card, 10)
        val cellFound = routes.count { it.transport == "Mobile data" }
        val wifiFound = routes.count { it.transport == "Wi-Fi" }
        addText(card, "Currently exposed by Android: $cellFound cellular route(s), $wifiFound Wi-Fi route(s).", 12f, accent)
        addSpace(card, 4)
        addText(card, "The slot limit is the maximum number of Android-exposed routes considered, not a way to create extra radios. A hotspot is usually reported as Wi-Fi, and this app cannot reliably label a route Safaricom, Airtel or Telkom without carrier information Android makes available.", 11f, muted)
        return card
    }

    private fun slotRow(parent: LinearLayout, title: String, subtitle: String, value: Int, min: Int, max: Int, update: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 14f, white, true))
        labels.addView(text(subtitle, 11f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton("−", false) { if (value > min) { update(value - 1); render() } }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(42)) })
        addSpace(row, 8, horizontal = true)
        row.addView(text(value.toString(), 17f, accent, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(28), dp(42)) })
        addSpace(row, 8, horizontal = true)
        row.addView(actionButton("+", false) { if (value < max) { update(value + 1); render() } }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(42)) })
        parent.addView(row)
    }

    private fun eligibleRoutes(routes: List<LinkSnapshot>): List<LinkSnapshot> {
        val cellular = routes.filter { it.transport == "Mobile data" }.take(simSlots)
        val wifi = routes.filter { it.transport == "Wi-Fi" }.take(wifiSlots + hotspotSlots)
        val other = routes.filter { it.transport != "Mobile data" && it.transport != "Wi-Fi" }
        return (cellular + wifi + other).distinctBy { it.id }
    }

    private fun buildCombinedSpeed(routes: List<LinkSnapshot>): View {
        val card = card(fill = surface2, outline = line)
        val selected = eligibleRoutes(routes).filter { it.validated }
        val measured = selected.mapNotNull { it.measuredMbps }
        val total = measured.sum()
        addText(card, "SPEED SUMMARY", 11f, muted, true, 1.3f)
        addSpace(card, 9)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val metric = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        metric.addView(text(lastParallelAggregateMbps?.let { formatMbps(it) } ?: "—", 27f, accent, true))
        metric.addView(text("SIMULTANEOUS TEST RESULT", 9f, muted, true))
        row.addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("${measured.size} tested", 12f, blue, true))
        card.addView(row)
        addSpace(card, 8)
        val best = selected.mapNotNull { it.measuredMbps }.maxOrNull()
        addText(card, "Fastest individual route: ${best?.let { formatMbps(it) } ?: "not tested yet"}.", 12f, white)
        addSpace(card, 5)
        addText(card, "Separately tested route rates sum to ${if (measured.isEmpty()) "not available" else formatMbps(total)}. This is a comparison, not a verified bonded speed. The simultaneous result above measures concurrent test downloads over the selected routes; normal phone traffic is still not bonded by this prototype.", 11f, muted)
        if (lastParallelRouteCount > 0 && lastParallelDurationSeconds != null) {
            addSpace(card, 5)
            addText(card, "Last concurrent test: $lastParallelRouteCount route(s) over ${lastParallelDurationSeconds?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} seconds.", 11f, blue)
        }
        return card
    }

    private fun buildActions(): View {
        val card = card()
        addText(card, "TEST YOUR CONNECTIONS", 11f, muted, true, 1.3f)
        addSpace(card, 12)
        card.addView(actionButton(if (running) "TEST RUNNING…" else "Measure latency on every route", true) {
            runLatencyTests()
        }.apply { isEnabled = !running })
        addSpace(card, 8)
        card.addView(actionButton("Download test  ·  up to 4 MB per route", false) {
            confirmSpeedTest()
        }.apply { isEnabled = !running })
        addSpace(card, 8)
        card.addView(actionButton("Concurrent aggregate test · up to 2 MB per route", false) {
            confirmParallelSpeedTest()
        }.apply { isEnabled = !running })
        addSpace(card, 8)
        card.addView(actionButton("Clear measurements", false) {
            lastParallelAggregateMbps = null
            lastParallelRouteCount = 0
            lastParallelDurationSeconds = null
            monitor.clearMeasurements()
            status = "Measurements cleared."
            render()
        }.apply { isEnabled = !running })
        addSpace(card, 11)
        addText(card, status, 12f, if (status.contains("fail", true) || status.contains("error", true)) red else muted)
        addSpace(card, 7)
        addText(card, "The ordinary test transfers up to 4 MB per route. The concurrent test transfers up to 2 MB per route (up to 20 MB if ten routes are available). Both may consume mobile data; test only when you accept those charges.", 11f, muted)
        return card
    }

    private fun buildRoutes(routes: List<LinkSnapshot>, bestId: String?): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("Available routes", 19f, white, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("${routes.size} found", 12f, muted))
        wrap.addView(head)
        addSpace(wrap, 10)
        if (routes.isEmpty()) {
            val empty = card()
            addText(empty, "Waiting for Android to expose a route", 15f, white, true)
            addSpace(empty, 5)
            addText(empty, "Check your Wi-Fi/mobile-data toggle, then press Scan links below.", 13f, muted)
            addSpace(empty, 10)
            empty.addView(actionButton("Scan links", false) {
                status = "Scanning available Android routes…"
                render()
                monitor.scanNow()
                Toast.makeText(this, "Network scan refreshed", Toast.LENGTH_SHORT).show()
            })
            wrap.addView(empty)
        } else {
            val sorted = routes.sortedWith(
                compareByDescending<LinkSnapshot> { it.id == bestId }
                    .thenByDescending { it.validated }
                    .thenByDescending { it.measuredMbps ?: 0.0 }
            )
            sorted.forEach { link ->
                wrap.addView(buildRouteCard(link, link.id == bestId))
                addSpace(wrap, 9)
            }
        }
        return wrap
    }

    private fun buildRouteCard(link: LinkSnapshot, isBest: Boolean): View {
        val card = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = when (link.transport) {
                "Wi-Fi" -> "W"
                "Mobile data" -> "4G"
                "Ethernet" -> "E"
                else -> "N"
            }
            gravity = Gravity.CENTER
            textSize = if (link.transport == "Mobile data") 12f else 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isBest) bg else accent)
            background = shape(if (isBest) accent else surface2, 13, if (isBest) accent else line)
            layoutParams = LinearLayout.LayoutParams(dp(43), dp(43))
        }
        top.addView(icon)
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titleBlock.addView(text(link.transport, 16f, white, true))
        titleBlock.addView(text(link.interfaceName?.let { "Interface $it" } ?: "Interface not reported", 11f, muted))
        top.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = if (link.validated) "INTERNET OK" else "NOT VERIFIED"
        top.addView(text(state, 9f, if (link.validated) accent else amber, true).apply {
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = shape(surface2, 18, line)
        })
        card.addView(top)
        if (isBest) {
            addSpace(card, 12)
            addText(card, "RECOMMENDED BY CURRENT METRICS", 10f, accent, true, 0.6f)
        }
        addSpace(card, 13)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(smallStat("Latency", link.latencyMs?.let { "$it ms" } ?: "Not tested", blue), weightParams())
        stats.addView(smallStat("Measured download", link.measuredMbps?.let { formatMbps(it) } ?: "Not tested", accent), weightParams())
        card.addView(stats)
        addSpace(card, 11)
        addText(card, "Android estimate: ↓ ${formatLinkRate(link.downstreamKbps)}   ↑ ${formatLinkRate(link.upstreamKbps)}", 12f, muted)
        if (link.lastError != null) {
            addSpace(card, 5)
            addText(card, "Last test: ${link.lastError}", 11f, red)
        }
        return card
    }

    private fun buildCaveat(): View {
        val card = card(fill = surface2, outline = line)
        addText(card, "IMPORTANT · ANDROID PORT STATUS", 11f, amber, true, 1.0f)
        addSpace(card, 8)
        addText(card, "This build discovers routes, lets you configure slot limits, measures individual routes and runs a simultaneous multi-route benchmark. It does NOT yet merge Wi-Fi + mobile data into one connection for every app. A real system-wide engine needs a user-approved VPNService/TUN, per-flow routing, DNS/IPv6 handling, route failover and extensive device testing.", 13f, white)
        addSpace(card, 8)
        addText(card, "One network cannot magically exceed its ISP/router capacity. Combining links mainly helps workloads that open multiple independent connections, and results depend on both links and the destination.", 12f, muted)
        return card
    }

    private fun buildTips(): View {
        val card = card()
        addText(card, "QUICK WI-FI IMPROVEMENTS", 11f, muted, true, 1.2f)
        addSpace(card, 9)
        tip(card, "01", "Near the router", "Try 5 GHz or 6 GHz if supported; 2.4 GHz usually reaches farther through walls.")
        addSpace(card, 10)
        tip(card, "02", "Compare like for like", "Run tests two or three times. If every route is slow, the bottleneck may be your ISP or the destination, not Wi-Fi strength.")
        addSpace(card, 10)
        tip(card, "03", "Switch network safely", "Use Android's own settings to pick a known Wi-Fi network; this app never reads or stores Wi-Fi passwords.")
        addSpace(card, 13)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Wi-Fi settings", false) { openSettings(Settings.ACTION_WIFI_SETTINGS) }, weightParams())
        addSpace(buttons, 8, horizontal = true)
        buttons.addView(actionButton("Internet settings", false) { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }, weightParams())
        card.addView(buttons)
        addSpace(card, 10)
        card.addView(actionButton("Scan links again", true) {
            status = "Refreshing route list…"
            monitor.scanNow()
            render()
        })
        return card
    }

    private fun runLatencyTests() {
        val routes = eligibleRoutes(monitor.snapshots())
        if (routes.isEmpty()) {
            status = "No internet-capable route is available within your selected slots."
            render()
            return
        }
        running = true
        status = "Testing HTTPS response time on ${routes.size} route(s)…"
        render()
        worker.execute {
            var succeeded = 0
            routes.forEachIndexed { index, route ->
                try {
                    val latency = NetworkTester.latencyMs(route.network)
                    monitor.recordLatency(route.network, latency)
                    succeeded++
                } catch (error: Exception) {
                    monitor.recordError(route.network, shortError(error))
                }
                mainHandler.post {
                    status = "Latency checks: ${index + 1}/${routes.size} route(s) complete."
                    render()
                }
            }
            mainHandler.post {
                running = false
                status = "Latency test finished: $succeeded/${routes.size} routes responded."
                render()
            }
        }
    }

    private fun confirmSpeedTest() {
        val routes = eligibleRoutes(monitor.snapshots()).filter { it.validated }
        if (routes.isEmpty()) {
            status = "No validated route is ready for a download test. Run latency checks first."
            render()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Run download tests?")
            .setMessage("This downloads up to 4 MB over each of ${routes.size} validated route(s). It can consume mobile data. Continue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Test routes") { _, _ -> runSpeedTests(routes.map { it.network }) }
            .show()
    }

    private fun runSpeedTests(networks: List<Network>) {
        running = true
        status = "Measuring download rate over ${networks.size} route(s)…"
        render()
        worker.execute {
            var succeeded = 0
            networks.forEachIndexed { index, network ->
                try {
                    val mbps = NetworkTester.downloadMbps(network)
                    monitor.recordSpeed(network, mbps)
                    succeeded++
                } catch (error: Exception) {
                    monitor.recordError(network, shortError(error))
                }
                mainHandler.post {
                    status = "Download tests: ${index + 1}/${networks.size} route(s) complete."
                    render()
                }
            }
            mainHandler.post {
                running = false
                status = "Download test finished: $succeeded/${networks.size} routes measured."
                render()
            }
        }
    }

    private fun confirmParallelSpeedTest() {
        val routes = eligibleRoutes(monitor.snapshots()).filter { it.validated }
        if (routes.isEmpty()) {
            status = "No validated routes are ready for a concurrent test."
            render()
            return
        }
        val maximumMb = routes.size * 2
        AlertDialog.Builder(this)
            .setTitle("Run concurrent aggregate test?")
            .setMessage("This downloads up to 2 MB on each of ${routes.size} validated route(s), up to $maximumMb MB total. It measures simultaneous test throughput, not bonded phone traffic. Mobile-data charges may apply.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run test") { _, _ -> runParallelSpeedTest(routes.map { it.network }) }
            .show()
    }

    private fun runParallelSpeedTest(networks: List<Network>) {
        running = true
        status = "Starting simultaneous downloads across ${networks.size} route(s)…"
        render()
        worker.execute {
            try {
                val result = NetworkTester.parallelDownloadMbps(networks)
                result.routes.forEach { route -> monitor.recordSpeed(route.network, route.mbps) }
                mainHandler.post {
                    lastParallelAggregateMbps = result.aggregateMbps
                    lastParallelRouteCount = result.routes.size
                    lastParallelDurationSeconds = result.elapsedSeconds
                    running = false
                    status = "Concurrent test complete: ${formatMbps(result.aggregateMbps)} across ${result.routes.size} route(s)."
                    render()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    running = false
                    status = "Concurrent test failed: ${shortError(error)}"
                    render()
                }
            }
        }
    }

    private fun shareDiagnostics(routes: List<LinkSnapshot>) {
        val report = buildString {
            appendLine("MultiPath Mobile diagnostics")
            appendLine("App version: 0.4.0")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Configured slots: cellular=$simSlots, Wi-Fi=$wifiSlots, extra hotspots=$hotspotSlots")
            appendLine("Routes exposed: ${routes.size}")
            routes.forEachIndexed { index, route ->
                appendLine("${index + 1}. ${route.transport}; interface=${route.interfaceName ?: "unknown"}; validated=${route.validated}; latency=${route.latencyMs?.let { "$it ms" } ?: "untested"}; measured=${route.measuredMbps?.let { formatMbps(it) } ?: "untested"}; Android estimate down=${formatLinkRate(route.downstreamKbps)}, up=${formatLinkRate(route.upstreamKbps)}")
                route.lastError?.let { appendLine("   last error=$it") }
            }
            appendLine("Concurrent aggregate benchmark: ${lastParallelAggregateMbps?.let { formatMbps(it) } ?: "not run"}")
            appendLine("Note: this prototype does not yet route all device traffic through a bonding engine.")
            appendLine("No Wi-Fi passwords, account credentials or browsing history are included.")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MultiPath Mobile diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share diagnostic report"))
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun buildHeaderSpacer() = View(this)

    private fun card(fill: Int = surface, outline: Int = line): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(fill, 20, outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun metric(value: String, label: String, valueColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(value, 21f, valueColor, true))
        addSpace(this, 4)
        addView(text(label, 8.5f, muted, true).apply { letterSpacing = 0.45f })
    }

    private fun smallStat(label: String, value: String, valueColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 10f, muted, true))
        addSpace(this, 4)
        addView(text(value, 15f, valueColor, true))
    }

    private fun tip(parent: LinearLayout, number: String, title: String, body: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(text(number, 11f, accent, true).apply {
            gravity = Gravity.CENTER
            background = shape(surface2, 9)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        content.addView(text(title, 14f, white, true))
        addSpace(content, 3)
        content.addView(text(body, 12f, muted))
        row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) bg else white)
        setPadding(dp(14), dp(15), dp(14), dp(15))
        background = shape(if (primary) accent else surface2, 14, if (primary) accent else line)
        alpha = 1f
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) {
        parent.addView(text(value, size, color, bold).apply { letterSpacing = spacing / 16f })
    }

    private fun addSpace(parent: LinearLayout, size: Int, horizontal: Boolean = false) {
        parent.addView(View(this), if (horizontal) {
            LinearLayout.LayoutParams(dp(size), 1)
        } else {
            LinearLayout.LayoutParams(1, dp(size))
        })
    }

    private fun addVerticalDivider(parent: LinearLayout) {
        val divider = View(this).apply { setBackgroundColor(line) }
        parent.addView(divider, LinearLayout.LayoutParams(dp(1), dp(43)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun shape(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun formatLinkRate(kbps: Int): String = if (kbps <= 0) "—" else {
        val mbps = kbps / 1000.0
        String.format(Locale.US, "%.1f Mbps", mbps)
    }

    private fun formatMbps(mbps: Double): String = String.format(Locale.US, "%.1f Mbps", mbps)

    private fun shortError(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return message.replace('\n', ' ').take(70)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
