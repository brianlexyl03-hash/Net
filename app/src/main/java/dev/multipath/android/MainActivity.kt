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
    internal val bg = Color.rgb(9, 17, 15)
    internal val surface = Color.rgb(17, 31, 27)
    internal val surface2 = Color.rgb(23, 41, 35)
    internal val line = Color.rgb(42, 65, 55)
    internal val accent = Color.rgb(124, 243, 169)
    internal val white = Color.rgb(240, 247, 242)
    internal val muted = Color.rgb(158, 180, 168)
    internal val amber = Color.rgb(255, 212, 121)
    internal val blue = Color.rgb(123, 201, 255)
    internal val red = Color.rgb(255, 143, 143)
    internal val palette = intArrayOf(
        Color.rgb(123, 201, 255), Color.rgb(255, 212, 121), Color.rgb(207, 160, 255),
        Color.rgb(255, 143, 143), Color.rgb(255, 170, 110),
    )

    internal val worker = Executors.newSingleThreadExecutor()
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal lateinit var monitor: NetworkMonitor
    internal lateinit var scroller: ScrollView
    internal lateinit var heroTitle: TextView
    internal lateinit var heroSub: TextView
    internal lateinit var heroButton: TextView
    internal lateinit var pill: TextView
    internal lateinit var graph: LiveGraphView
    internal lateinit var totalValue: TextView
    internal lateinit var tabDown: TextView
    internal lateinit var tabUp: TextView
    internal lateinit var legendBox: LinearLayout
    internal lateinit var sourcesBox: LinearLayout
    internal lateinit var noteLine: TextView
    internal lateinit var exitStatus: TextView
    internal lateinit var appsBox: LinearLayout
    internal lateinit var checkBox: LinearLayout
    internal lateinit var radarBox: LinearLayout
    internal lateinit var logBox: LinearLayout
    internal var checking = false
    internal lateinit var pingBox: LinearLayout
    internal lateinit var blockedLine: TextView
    internal lateinit var gamesButton: TextView
    internal var appsSignature = ""
    internal val appDetail = HashMap<String, TextView>()
    internal val pingSupplier: () -> List<LinkSnapshot> = { monitor.snapshots() }
    internal lateinit var exitSwitch: Switch
    internal var showDownload = true
    internal var testing = false
    internal val prefs: SharedPreferences by lazy { getSharedPreferences(RoutePolicy.PREFS, MODE_PRIVATE) }

    internal val ticker = object : Runnable {
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

    internal fun buildScreen(root: LinearLayout) {
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

    // ---- live updates --------------------------------------------------------

    // ---- actions ----------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) launchVpn() else toast("Permission needed to start")
        }
    }

    internal fun toggleVpn() {
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

    internal fun launchVpn() {
        try {
            startForegroundService(Intent(this, MultiPathVpnService::class.java).setAction(MultiPathVpnService.ACTION_START))
        } catch (error: Exception) {
            toast("Could not start")
        }
        mainHandler.postDelayed({ refreshHero() }, 800)
        askBatteryExemption(force = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRadar()
            else toast("Location is needed to scan Wi-Fi")
        }
    }

    // lateinit checks must live in this class; extension files read these instead
    internal val hasLogBox: Boolean get() = ::logBox.isInitialized
    internal val hasHeroTitle: Boolean get() = ::heroTitle.isInitialized
    internal val hasGraph: Boolean get() = ::graph.isInitialized
    internal val hasSourcesBox: Boolean get() = ::sourcesBox.isInitialized
    internal val hasExitStatus: Boolean get() = ::exitStatus.isInitialized
    internal val hasAppsBox: Boolean get() = ::appsBox.isInitialized
    internal val hasPingBox: Boolean get() = ::pingBox.isInitialized
    internal val hasBlockedLine: Boolean get() = ::blockedLine.isInitialized

    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    internal fun formatMbps(value: Float): String = String.format(Locale.US, "%.1f Mbps", value)

    companion object {
        internal const val REQUEST_VPN = 41
        internal const val REQUEST_NOTIFICATIONS = 42
        internal const val REQUEST_LOCATION = 43
    }

}
