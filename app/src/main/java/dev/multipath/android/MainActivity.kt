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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var showDownload = true
    private var testing = false
    private val prefs: SharedPreferences by lazy { getSharedPreferences(RoutePolicy.PREFS, MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            TrafficHistory.sample()
            refreshHero()
            refreshGraph()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        ExtraWifi.init(this)
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
        mainHandler.post(ticker)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(ticker)
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
        sources.addView(actionButton("Add Wi-Fi", true) { showAddWifi() })
        addSpace(sources, 8)
        sources.addView(actionButton("Test speed", false) { confirmSpeedTest() })
        addSpace(sources, 8)
        noteLine = text("", 12f, muted)
        sources.addView(noteLine)
        root.addView(sources)
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
            val speed = link.measuredMbps?.let { "  ·  ${formatMbps(it.toFloat())}" } ?: ""
            if (link.extra) {
                shownExtra.add(link.label ?: "")
                val state = if (link.validated) "Connected" else "No internet"
                sourcesBox.addView(sourceRow(name, state + speed, true, link.validated, { on ->
                    if (!on) ExtraWifi.disconnect(this, link.label ?: "")
                }, { removeWifi(link.label ?: "") }))
            } else {
                val key = RoutePolicy.keyOf(link)
                val state = if (link.validated) "Ready" else "No internet"
                sourcesBox.addView(sourceRow(name, state + speed, key !in disabled, link.validated, { on ->
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
            sourcesBox.addView(sourceRow("Wi-Fi: ${saved.ssid}", detail, busy, true, { on ->
                if (on) connectExtra(saved.ssid) else ExtraWifi.disconnect(this, saved.ssid)
            }, { removeWifi(saved.ssid) }))
        }
        if (sourcesBox.childCount == 0) {
            sourcesBox.addView(text("No sources found. Turn on Wi-Fi or mobile data.", 13f, muted))
        }
    }

    private fun sourceRow(
        name: String, detail: String, checked: Boolean, healthy: Boolean,
        onToggle: (Boolean) -> Unit, onRemove: (() -> Unit)?,
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.setPadding(0, dp(8), 0, dp(8))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(name, 15f, white, true))
        labels.addView(text(detail, 12f, if (healthy) muted else amber))
        if (onRemove != null) {
            labels.addView(text("Remove", 12f, red, true).apply {
                setPadding(0, dp(6), dp(12), dp(2))
                isClickable = true
                setOnClickListener { onRemove() }
            })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this)
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, on -> onToggle(on) }
        row.addView(toggle)
        return row
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun formatMbps(value: Float): String = String.format(Locale.US, "%.1f Mbps", value)

    companion object {
        private const val REQUEST_VPN = 41
        private const val REQUEST_NOTIFICATIONS = 42
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
