package dev.multipath.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
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
    private val palette = intArrayOf(
        Color.rgb(123, 201, 255), Color.rgb(255, 212, 121), Color.rgb(207, 160, 255),
        Color.rgb(255, 143, 143), Color.rgb(255, 170, 110),
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var monitor: NetworkMonitor
    private lateinit var scroller: ScrollView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroButton: TextView
    private lateinit var pill: TextView
    private lateinit var graph: LiveGraphView
    private lateinit var totalValue: TextView
    private lateinit var tabDown: TextView
    private lateinit var tabUp: TextView
    private lateinit var legendBox: LinearLayout
    private lateinit var sourcesBox: LinearLayout
    private lateinit var noteLine: TextView
    private lateinit var exitStatus: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var checkBox: LinearLayout
    private lateinit var radarBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private var checking = false
    private lateinit var pingBox: LinearLayout
    private lateinit var blockedLine: TextView
    private lateinit var gamesButton: TextView
    private var appsSignature = ""
    private val appDetail = HashMap<String, TextView>()
    private val pingSupplier: () -> List<LinkSnapshot> = { monitor.snapshots() }
    private lateinit var exitSwitch: Switch
    private var showDownload = true
    private var testing = false
    private val prefs: SharedPreferences by lazy { getSharedPreferences(RoutePolicy.PREFS, MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            TrafficHistory.sample()
            AppStats.sample()
            refreshHero()
            refreshGraph()
            refreshApps()
            refreshGaming()
            refreshBlocker()
            refreshLog()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        ExtraWifi.init(this)
        Bundles.init(this)
        Blocker.init(this)
        AppBlock.init(this)
        Gaming.init(this)
        NetLog.init(this)
        ExitServer.refresh(this)
        scroller = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(22))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        scroller.addView(root)
        setContentView(scroller)
        monitor = NetworkMonitor(this, keepCellularAlive = true) {
            refreshSources()
            refreshHero()
        }
        buildScreen(root)
        refreshSources()
        refreshHero()
        refreshGraph()
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
        PingMonitor.start(pingSupplier)
        mainHandler.post(ticker)
    }

    override fun onStop() {
        Bundles.flush()
        mainHandler.removeCallbacks(ticker)
        PingMonitor.stop(pingSupplier)
        monitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- layout ------------------------------------------------------------

    private fun buildScreen(root: LinearLayout) {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_mark)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        addSpace(header, 10, horizontal = true)
        header.addView(text("MultiPath", 20f, white, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pill = text("Off", 12f, muted, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(surface2, 14, line)
        }
        header.addView(pill)
        root.addView(header)
        addSpace(root, 16)

        val hero = card()
        heroTitle = text("Off", 30f, white, true)
        heroSub = text("", 14f, muted)
        hero.addView(heroTitle)
        addSpace(hero, 4)
        hero.addView(heroSub)
        addSpace(hero, 16)
        heroButton = actionButton("Start", true) { toggleVpn() }
        hero.addView(heroButton)
        root.addView(hero)
        addSpace(root, 12)

        val traffic = card()
        traffic.addView(text("Live traffic", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(traffic, 8)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabDown = tabButton("Download") { showDownload = true; refreshGraph() }
        tabUp = tabButton("Upload") { showDownload = false; refreshGraph() }
        tabs.addView(tabDown, weightParams())
        addSpace(tabs, 8, horizontal = true)
        tabs.addView(tabUp, weightParams())
        traffic.addView(tabs)
        addSpace(traffic, 12)
        totalValue = text("0.0 Mbps", 28f, accent, true)
        traffic.addView(totalValue)
        graph = LiveGraphView(this)
        traffic.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        addSpace(traffic, 6)
        legendBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        traffic.addView(legendBox)
        root.addView(traffic)
        addSpace(root, 12)

        val sources = card()
        sources.addView(text("Sources", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(sources, 6)
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sources.addView(sourcesBox)
        addSpace(sources, 10)
        sources.addView(actionButton("Add Wi-Fi or hotspot", true) { showAddWifi() })
        addSpace(sources, 8)
        sources.addView(actionButton("Test speed", false) { confirmSpeedTest() })
        addSpace(sources, 8)
        noteLine = text("", 12f, muted)
        sources.addView(noteLine)
        root.addView(sources)
        addSpace(root, 12)

        val apps = card()
        apps.addView(text("Apps using internet", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(apps, 6)
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.addView(appsBox)
        root.addView(apps)
        addSpace(root, 12)

        val gaming = card()
        gaming.addView(text("Gaming", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(gaming, 6)
        gaming.addView(switchRow("Gaming mode", "Chosen games use the fastest source", Gaming.enabled) { Gaming.setEnabled(it) })
        addSpace(gaming, 10)
        gamesButton = actionButton("Choose games", false) { showGamesDialog() }
        gaming.addView(gamesButton)
        addSpace(gaming, 10)
        pingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gaming.addView(pingBox)
        root.addView(gaming)
        addSpace(root, 12)

        val blocker = card()
        blocker.addView(text("Blocker", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(blocker, 6)
        blocker.addView(switchRow("Block ads and trackers", "", Blocker.ads) { Blocker.setAds(it) })
        addSpace(blocker, 8)
        blocker.addView(switchRow("Block adult sites", "", Blocker.adult) { Blocker.setAdult(it) })
        addSpace(blocker, 10)
        blockedLine = text("Blocked 0", 13f, accent, true)
        blocker.addView(blockedLine)
        addSpace(blocker, 10)
        blocker.addView(actionButton("Block a site", false) { showBlockSiteDialog() })
        addSpace(blocker, 8)
        blocker.addView(text("If ads still show, turn off Private DNS in Android settings.", 11f, muted))
        root.addView(blocker)
        addSpace(root, 12)

        val check = card()
        check.addView(text("Network check", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(check, 8)
        check.addView(actionButton("Check my network", true) { runNetworkCheck() })
        addSpace(check, 8)
        checkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        check.addView(checkBox)
        root.addView(check)
        addSpace(root, 12)

        val radar = card()
        radar.addView(text("Wi-Fi nearby", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(radar, 8)
        radar.addView(actionButton("Scan nearby Wi-Fi", false) { startRadar() })
        addSpace(radar, 8)
        radarBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radar.addView(radarBox)
        root.addView(radar)
        addSpace(root, 12)

        val log = card()
        log.addView(text("Connection log", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(log, 8)
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log.addView(logBox)
        addSpace(log, 8)
        log.addView(actionButton("Clear log", false) { NetLog.clear(); refreshLog() })
        root.addView(log)
        addSpace(root, 12)

        val awake = card()
        val awakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val awakeLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        awakeLabels.addView(text("Keep connections awake", 15f, white, true))
        awakeLabels.addView(text("Stops Android from putting Wi-Fi and data to sleep", 12f, muted))
        awakeRow.addView(awakeLabels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val awakeSwitch = Switch(this)
        awakeSwitch.isChecked = prefs.getBoolean("keep_awake", true)
        awakeSwitch.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("keep_awake", on).apply() }
        awakeRow.addView(awakeSwitch)
        awake.addView(awakeRow)
        addSpace(awake, 10)
        awake.addView(actionButton("Battery settings", false) { askBatteryExemption(force = true) })
        root.addView(awake)
        addSpace(root, 12)

        val exit = card()
        exit.addView(text("Exit server", 11f, muted, true).apply { letterSpacing = 0.1f })
        addSpace(exit, 6)
        val exitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val exitLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exitStatus = text("Not set up", 13f, muted)
        exitLabels.addView(text("Appear in another country", 15f, white, true))
        exitLabels.addView(exitStatus)
        exitRow.addView(exitLabels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        exitSwitch = Switch(this)
        exitSwitch.setOnCheckedChangeListener { button, on ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (ExitServer.load(this) == null) {
                button.isChecked = false
                showExitDialog()
            } else {
                ExitServer.setEnabled(this, on)
                refreshExit()
                if (MultiPathVpnService.running) toast("Stop and start again to apply")
            }
        }
        exitRow.addView(exitSwitch)
        exit.addView(exitRow)
        addSpace(exit, 10)
        exit.addView(actionButton("Set up server", false) { showExitDialog() })
        root.addView(exit)
        refreshExit()
        addSpace(root, 16)
        root.addView(text("Version ${BuildConfigVersion.NAME}", 10f, muted))
    }

    private fun tabButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        setOnClickListener { action() }
    }

    // ---- live updates --------------------------------------------------------

    private fun refreshHero() {
        if (!::heroTitle.isInitialized) return
        val on = MultiPathVpnService.running
        val active = RoutePolicy.eligible(monitor.snapshots(), prefs).count { it.validated }
        heroTitle.text = if (on) "On" else "Off"
        heroTitle.setTextColor(if (on) accent else white)
        heroSub.text = if (on) "$active source${if (active == 1) "" else "s"} in use" else "Combine your connections"
        heroButton.text = if (on) "Stop" else "Start"
        heroButton.setTextColor(if (on) white else bg)
        heroButton.background = if (on) shape(surface2, 14, line) else shape(accent, 14, accent)
        pill.text = if (on) "On" else "Off"
        pill.setTextColor(if (on) bg else muted)
        pill.background = if (on) shape(accent, 14, accent) else shape(surface2, 14, line)
    }

    private fun refreshGraph() {
        if (!::graph.isInitialized) return
        styleTab(tabDown, showDownload)
        styleTab(tabUp, !showDownload)
        val names = TrafficHistory.names()
        val items = ArrayList<LiveGraphView.Line>()
        names.forEachIndexed { index, name ->
            items.add(LiveGraphView.Line(palette[index % palette.size], TrafficHistory.values(name, showDownload), false))
        }
        val total = TrafficHistory.totalValues(showDownload)
        items.add(LiveGraphView.Line(accent, total, true))
        graph.setLines(items)
        totalValue.text = formatMbps(total.lastOrNull() ?: 0f)
        legendBox.removeAllViews()
        names.forEachIndexed { index, name ->
            val values = TrafficHistory.values(name, showDownload)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.setPadding(0, dp(4), 0, dp(4))
            row.addView(View(this).apply { background = shape(palette[index % palette.size], 4) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addSpace(row, 8, horizontal = true)
            row.addView(text(name, 13f, white), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(formatMbps(values.lastOrNull() ?: 0f), 13f, muted, true))
            legendBox.addView(row)
        }
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) bg else white)
        tab.background = if (selected) shape(accent, 12, accent) else shape(surface2, 12, line)
    }

    private fun refreshSources() {
        if (!::sourcesBox.isInitialized) return
        sourcesBox.removeAllViews()
        val links = monitor.snapshots()
        val disabled = RoutePolicy.disabled(prefs)
        val savedExtras = ExtraWifi.load(this)
        val shownExtra = HashSet<String>()
        for (link in links) {
            val name = RoutePolicy.nameOf(link, links)
            val key = RoutePolicy.keyOf(link)
            val full = Bundles.exhausted(key)
            val speed = link.measuredMbps?.let { "  ·  ${formatMbps(it.toFloat())}" } ?: ""
            val bundle = Bundles.describe(key).let { if (it.isEmpty()) "" else "  ·  $it" }
            if (link.extra) {
                shownExtra.add(link.label ?: "")
                val state = if (full) "Limit reached" else if (link.validated) "Connected" else "No internet"
                sourcesBox.addView(sourceRow(name, state + speed + bundle, true, link.validated && !full, key, { on ->
                    if (!on) ExtraWifi.disconnect(this, link.label ?: "")
                }, { removeWifi(link.label ?: "") }))
            } else {
                val state = if (full) "Limit reached" else if (link.validated) "Ready" else "No internet"
                sourcesBox.addView(sourceRow(name, state + speed + bundle, key !in disabled, link.validated && !full, key, { on ->
                    RoutePolicy.setEnabled(prefs, key, on)
                    refreshHero()
                }, null))
            }
        }
        for (saved in savedExtras) {
            if (saved.ssid in shownExtra) continue
            val live = ExtraWifi.stateOf(saved.ssid)
            val busy = live?.state == ExtraWifi.State.CONNECTING
            val detail = when {
                busy -> live?.message?.ifBlank { "Connecting" } ?: "Connecting"
                live?.state == ExtraWifi.State.FAILED -> live?.message ?: "Not connected"
                else -> "Off"
            }
            sourcesBox.addView(sourceRow("Wi-Fi: ${saved.ssid}", detail, busy, true, "wifi:" + saved.ssid, { on ->
                if (on) connectExtra(saved.ssid) else ExtraWifi.disconnect(this, saved.ssid)
            }, { removeWifi(saved.ssid) }))
        }
        if (sourcesBox.childCount == 0) {
            sourcesBox.addView(text("No sources found. Turn on Wi-Fi or mobile data.", 13f, muted))
        }
    }

    private fun sourceRow(
        name: String, detail: String, checked: Boolean, healthy: Boolean, bundleKey: String,
        onToggle: (Boolean) -> Unit, onRemove: (() -> Unit)?,
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.setPadding(0, dp(8), 0, dp(8))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(name, 15f, white, true))
        labels.addView(text(detail, 12f, if (healthy) muted else amber))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(text("Data limit", 12f, accent, true).apply {
            setPadding(0, dp(6), dp(16), dp(2))
            isClickable = true
            setOnClickListener { showBundleDialog(name, bundleKey) }
        })
        if (onRemove != null) {
            actions.addView(text("Remove", 12f, red, true).apply {
                setPadding(0, dp(6), dp(12), dp(2))
                isClickable = true
                setOnClickListener { onRemove() }
            })
        }
        labels.addView(actions)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this)
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, on -> onToggle(on) }
        row.addView(toggle)
        return row
    }

    private fun refreshExit() {
        if (!::exitStatus.isInitialized) return
        val config = ExitServer.load(this)
        val on = ExitServer.enabled(this) && config != null
        exitSwitch.isChecked = on
        exitStatus.text = when {
            config == null -> "Not set up"
            on -> "On  ·  ${config.host}"
            else -> "Off  ·  ${config.host}"
        }
        exitStatus.setTextColor(if (on) accent else muted)
    }

    // ---- actions ----------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) launchVpn() else toast("Permission needed to start")
        }
    }

    private fun toggleVpn() {
        if (MultiPathVpnService.running) {
            startService(Intent(this, MultiPathVpnService::class.java).setAction(MultiPathVpnService.ACTION_STOP))
            mainHandler.postDelayed({ refreshHero() }, 400)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            launchVpn()
        }
    }

    private fun launchVpn() {
        try {
            startForegroundService(Intent(this, MultiPathVpnService::class.java).setAction(MultiPathVpnService.ACTION_START))
        } catch (error: Exception) {
            toast("Could not start")
        }
        mainHandler.postDelayed({ refreshHero() }, 800)
        askBatteryExemption(force = false)
    }

    private fun askBatteryExemption(force: Boolean) {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            if (force) toast("Already allowed")
            return
        }
        if (!force && prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep MultiPath running")
            .setMessage("Allow it to ignore battery limits so Android does not stop it.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast("Open battery settings manually") }
                }
            }
            .show()
    }

    private fun showBundleDialog(name: String, key: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
        }
        val input = EditText(this).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            val current = Bundles.limitBytes(key)
            if (current > 0L) setText(String.format(Locale.US, "%.0f", current / 1_048_576.0))
        }
        val unit = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("MB", "GB"))
        }
        box.addView(input)
        box.addView(unit)
        box.addView(text(
            if (Bundles.limitBytes(key) > 0L) "Used so far: ${Bundles.format(Bundles.usedBytes(key))}. Counts MultiPath traffic only." else "New connections stop using this source when the limit is reached. Counts MultiPath traffic only.",
            12f, muted,
        ).apply { setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this)
            .setTitle("$name data limit")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("No limit") { _, _ -> Bundles.setLimit(key, 0L); refreshSources(); refreshHero() }
            .setPositiveButton("Set") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0.0) {
                    toast("Enter an amount")
                } else {
                    val bytes = amount * (if (unit.selectedItemPosition == 1) 1_073_741_824.0 else 1_048_576.0)
                    Bundles.setLimit(key, bytes.toLong())
                }
                refreshSources()
                refreshHero()
            }
            .show()
    }

    private fun showExitDialog() {
        val existing = ExitServer.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
        }
        val host = EditText(this).apply {
            hint = "Server address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(existing?.host ?: "")
        }
        val port = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText((existing?.port ?: 8388).toString())
        }
        val pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(existing?.password ?: "")
        }
        val result = text("", 12f, muted).apply { setPadding(0, dp(10), 0, 0) }
        box.addView(host)
        box.addView(port)
        box.addView(pass)
        box.addView(result)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exit server")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.show()
        fun read(): ExitServer.Config? {
            val h = host.text.toString().trim()
            val p = port.text.toString().trim().toIntOrNull()
            val w = pass.text.toString()
            return if (h.isEmpty() || p == null || p !in 1..65535 || w.isEmpty()) null else ExitServer.Config(h, p, w)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val config = read()
            val network = RoutePolicy.eligible(monitor.snapshots(), prefs).firstOrNull { it.validated }?.network
            if (config == null) {
                result.text = "Fill in all fields"
            } else if (network == null) {
                result.text = "No connection to test with"
            } else {
                result.text = "Testing"
                worker.execute {
                    val message = try {
                        "Works. Websites see: " + Shadowsocks.lookupExitAddress(network, config)
                    } catch (error: Exception) {
                        "Failed: " + (error.message ?: error.javaClass.simpleName).take(70)
                    }
                    mainHandler.post { result.text = message }
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val config = read()
            if (config == null) {
                result.text = "Fill in all fields"
            } else if (!ExitServer.save(this, config.host, config.port, config.password)) {
                result.text = "Could not save securely"
            } else {
                refreshExit()
                if (MultiPathVpnService.running) toast("Stop and start again to apply")
                dialog.dismiss()
            }
        }
    }

    private fun showAddWifi() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Needs Android 10 or newer")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Wi-Fi name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val types = listOf("WPA2", "WPA3", "Open")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
        }
        val support = when (ExtraWifi.support(this)) {
            ExtraWifi.Support.YES -> "This phone can stay on two Wi-Fi networks at once."
            ExtraWifi.Support.NO -> "This phone can only use one Wi-Fi at a time. Adding one will pause the current Wi-Fi."
            ExtraWifi.Support.UNKNOWN -> "Some phones pause the current Wi-Fi when another is added."
            ExtraWifi.Support.TOO_OLD -> "Needs Android 10 or newer."
        }
        box.addView(nameInput)
        box.addView(passInput)
        box.addView(typeSpinner)
        box.addView(text(support, 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this)
            .setTitle("Add Wi-Fi")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val ssid = nameInput.text.toString().trim()
                val password = passInput.text.toString()
                val security = types[typeSpinner.selectedItemPosition].lowercase(Locale.US)
                when {
                    ssid.isEmpty() -> toast("Enter the Wi-Fi name")
                    security != "open" && password.length < 8 -> toast("Password needs 8 or more characters")
                    !ExtraWifi.add(this, ssid, security, password) -> toast("Could not save securely")
                    else -> connectExtra(ssid)
                }
                refreshSources()
            }
            .show()
    }

    private fun connectExtra(ssid: String) {
        val hasMainWifi = monitor.snapshots().any { it.transport == "Wi-Fi" && !it.extra }
        val support = ExtraWifi.support(this)
        if (hasMainWifi && support != ExtraWifi.Support.YES) {
            AlertDialog.Builder(this)
                .setTitle("Use $ssid?")
                .setMessage("Your phone may pause the current Wi-Fi while this one connects. Mobile data stays on.")
                .setNegativeButton("Cancel") { _, _ -> refreshSources() }
                .setPositiveButton("Continue") { _, _ -> ExtraWifi.connect(this, ssid) }
                .show()
        } else {
            ExtraWifi.connect(this, ssid)
        }
    }

    private fun removeWifi(ssid: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $ssid?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> ExtraWifi.remove(this, ssid); refreshSources() }
            .show()
    }

    private fun confirmSpeedTest() {
        if (testing) return
        val targets = RoutePolicy.eligible(monitor.snapshots(), prefs).filter { it.validated }
        if (targets.isEmpty()) {
            toast("No source is ready")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Test speed")
            .setMessage("Uses up to 4 MB per source.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> runSpeedTest(targets) }
            .show()
    }

    private fun runSpeedTest(targets: List<LinkSnapshot>) {
        testing = true
        noteLine.text = "Testing"
        worker.execute {
            var failed = 0
            for (link in targets) {
                try {
                    monitor.recordSpeed(link.network, NetworkTester.downloadMbps(link.network))
                } catch (_: Exception) {
                    failed++
                }
            }
            mainHandler.post {
                testing = false
                noteLine.text = if (failed == 0) "Done" else "$failed source${if (failed == 1) "" else "s"} failed"
                refreshSources()
            }
        }
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 15f, white, true))
        if (subtitle.isNotEmpty()) labels.addView(text(subtitle, 12f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this)
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, on -> onChange(on) }
        row.addView(toggle)
        return row
    }

    private fun refreshApps() {
        if (!::appsBox.isInitialized) return
        val top = AppStats.top(8)
        if (top.isEmpty()) {
            if (appsSignature != "-") {
                appsBox.removeAllViews()
                appDetail.clear()
                appsBox.addView(text(if (MultiPathVpnService.running) "Waiting for traffic" else "Start to see which apps connect", 13f, muted))
                appsSignature = "-"
            }
            return
        }
        val signature = top.joinToString("|") { it.pkg }
        if (signature != appsSignature) {
            appsBox.removeAllViews()
            appDetail.clear()
            for (entry in top) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.setPadding(0, dp(6), 0, dp(6))
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(text(entry.label, 15f, white, true))
                val detail = text("", 12f, muted)
                labels.addView(detail)
                appDetail[entry.pkg] = detail
                row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (entry.pkg.isNotEmpty() && entry.pkg != "android") {
                    val toggle = Switch(this)
                    toggle.isChecked = !AppBlock.isBlocked(entry.pkg)
                    toggle.setOnCheckedChangeListener { _, allowed -> AppBlock.setBlocked(entry.pkg, !allowed) }
                    row.addView(toggle)
                }
                appsBox.addView(row)
            }
            appsSignature = signature
        }
        for (entry in top) {
            val total = entry.up.get() + entry.down.get()
            val state = if (AppBlock.isBlocked(entry.pkg)) "Blocked" else String.format(Locale.US, "Down %.1f  ·  Up %.1f Mbps", entry.rateDown, entry.rateUp)
            appDetail[entry.pkg]?.text = "$state  ·  ${Bundles.format(total)}"
        }
    }

    private fun refreshGaming() {
        if (!::pingBox.isInitialized) return
        val games = Gaming.gameSet().size
        gamesButton.text = if (games == 0) "Choose games" else "Choose games ($games)"
        pingBox.removeAllViews()
        val items = PingMonitor.stats.values.sortedBy { it.ping() ?: Long.MAX_VALUE }
        if (items.isEmpty()) {
            pingBox.addView(text("Measuring", 13f, muted))
            return
        }
        items.forEachIndexed { index, stat ->
            val ping = stat.ping()
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.setPadding(0, dp(5), 0, dp(5))
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(text(stat.label, 14f, white, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(text(if (ping == null) "No reply" else "$ping ms", 14f, if (index == 0 && ping != null) accent else white, true))
            row.addView(head)
            val jitter = stat.jitter()
            row.addView(text("Jitter ${jitter ?: 0} ms  ·  Loss ${stat.lossPercent()}%" + if (index == 0 && ping != null && items.size > 1) "  ·  Fastest" else "", 12f, muted))
            pingBox.addView(row)
        }
    }

    private fun refreshBlocker() {
        if (!::blockedLine.isInitialized) return
        val recent = Blocker.recentBlocked()
        blockedLine.text = "Blocked ${Blocker.blockedCount.get()}" + if (recent.isNotEmpty()) "  ·  last: ${recent.last()}" else ""
    }

    private fun showBlockSiteDialog() {
        val input = EditText(this).apply {
            hint = "example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
        }
        box.addView(input)
        box.addView(text("${Blocker.customCount()} site(s) in your list", 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this)
            .setTitle("Block a site")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear my list") { _, _ -> Blocker.clearCustom() }
            .setPositiveButton("Block") { _, _ ->
                if (!Blocker.addCustom(input.text.toString())) toast("Enter a site like example.com")
            }
            .show()
    }

    private fun showGamesDialog() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase(Locale.US) }
        if (apps.isEmpty()) {
            toast("No apps found")
            return
        }
        val chosen = Gaming.gameSet().toMutableSet()
        val labels = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle("Choose games")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen.add(apps[which].first) else chosen.remove(apps[which].first)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ -> Gaming.setGames(chosen) }
            .show()
    }

    private fun levelColor(level: Int): Int = when (level) {
        1 -> accent
        0 -> amber
        2 -> blue
        else -> red
    }

    private fun lineRow(label: String, detail: String, color: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.setPadding(0, dp(2), 0, dp(2))
        row.addView(text(label, 12f, muted), LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(text(detail, 12f, color), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun runNetworkCheck() {
        if (checking) return
        val links = monitor.snapshots()
        if (links.isEmpty()) {
            toast("No source found")
            return
        }
        checking = true
        checkBox.removeAllViews()
        checkBox.addView(text("Checking", 13f, muted))
        worker.execute {
            val outcome = try { NetCheck.run(links) } catch (_: Exception) { null }
            mainHandler.post {
                checking = false
                showCheck(outcome)
            }
        }
    }

    private fun showCheck(outcome: NetCheck.Outcome?) {
        checkBox.removeAllViews()
        if (outcome == null) {
            checkBox.addView(text("Check failed. Try again.", 13f, red))
            return
        }
        for (report in outcome.reports) {
            checkBox.addView(text(report.name, 15f, white, true).apply { setPadding(0, dp(8), 0, 0) })
            checkBox.addView(text(report.verdict, 13f, levelColor(report.level), true))
            report.items.forEach { checkBox.addView(lineRow(it.label, it.detail, levelColor(it.state))) }
        }
        outcome.safety?.let {
            checkBox.addView(text("Safety", 15f, white, true).apply { setPadding(0, dp(12), 0, 0) })
            checkBox.addView(text(it.detail, 13f, levelColor(it.state), true))
        }
        if (outcome.safety == null && outcome.reports.size == 1) {
            checkBox.addView(text("Add a second source to compare and check safety.", 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
        }
    }

    private fun startRadar() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        radarBox.removeAllViews()
        radarBox.addView(text("Scanning", 13f, muted))
        WifiRadar.scan(this) { showRadar(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRadar()
            else toast("Location is needed to scan Wi-Fi")
        }
    }

    private fun showRadar(results: List<ScanResult>?) {
        radarBox.removeAllViews()
        if (results.isNullOrEmpty()) {
            radarBox.addView(text("Nothing found. Turn on Location and Wi-Fi, then try again.", 13f, amber))
            return
        }
        val analysis = WifiRadar.analyze(results)
        val best = ArrayList<String>()
        analysis.best24?.let { best.add("2.4 GHz channel $it") }
        if (analysis.nets.any { it.band == "5 GHz" }) analysis.best5?.let { best.add("5 GHz channel $it") }
        if (best.isNotEmpty()) {
            radarBox.addView(text("Quietest channel for your router", 12f, muted))
            radarBox.addView(text(best.joinToString("  ·  "), 15f, accent, true))
            addSpace(radarBox, 8)
        }
        for (net in analysis.nets.take(8)) {
            val strength = when {
                net.level >= -55 -> "Strong"
                net.level >= -67 -> "Good"
                net.level >= -80 -> "Weak"
                else -> "Very weak"
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.setPadding(0, dp(5), 0, dp(5))
            row.addView(text(net.ssid, 14f, white, true))
            row.addView(text("$strength (${net.level} dBm)  ·  ch ${net.channel}  ·  ${net.band}  ·  ${net.security}", 12f, if (net.security == "Open") amber else muted))
            radarBox.addView(row)
        }
    }

    private fun refreshLog() {
        if (!::logBox.isInitialized) return
        logBox.removeAllViews()
        val uptime = NetLog.uptime()
        val events = NetLog.recent(6)
        if (uptime.isEmpty() && events.isEmpty()) {
            logBox.addView(text("Nothing yet", 13f, muted))
            return
        }
        for ((name, permille) in uptime) {
            logBox.addView(text("$name  ·  ${String.format(Locale.US, "%.1f", permille / 10.0)}% online", 14f, white, true).apply { setPadding(0, dp(2), 0, dp(2)) })
        }
        if (events.isNotEmpty()) addSpace(logBox, 8)
        events.forEach { logBox.addView(text(it, 12f, muted).apply { setPadding(0, dp(2), 0, dp(2)) }) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun formatMbps(value: Float): String = String.format(Locale.US, "%.1f Mbps", value)

    companion object {
        private const val REQUEST_VPN = 41
        private const val REQUEST_NOTIFICATIONS = 42
        private const val REQUEST_LOCATION = 43
    }

    private fun card(fill: Int = surface, outline: Int = line): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(fill, 20, outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun shape(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
