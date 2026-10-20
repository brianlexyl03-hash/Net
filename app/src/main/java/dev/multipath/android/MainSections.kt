package dev.multipath.android

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.ScanResult
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

// Live sections of the main screen: hero, graph, sources, apps, gaming and blocker.

internal fun MainActivity.refreshHero() {
    if (!hasHeroTitle) return
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

internal fun MainActivity.refreshGraph() {
    if (!hasGraph) return
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

internal fun MainActivity.styleTab(tab: TextView, selected: Boolean) {
    tab.setTextColor(if (selected) bg else white)
    tab.background = if (selected) shape(accent, 12, accent) else shape(surface2, 12, line)
}

internal fun MainActivity.tabButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 13f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dp(10), dp(10), dp(10), dp(10))
    isClickable = true
    setOnClickListener { action() }
}

internal fun MainActivity.refreshSources() {
    if (!hasSourcesBox) return
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

internal fun MainActivity.sourceRow(
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

internal fun MainActivity.refreshExit() {
    if (!hasExitStatus) return
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

internal fun MainActivity.switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
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

internal fun MainActivity.refreshApps() {
    if (!hasAppsBox) return
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

internal fun MainActivity.refreshGaming() {
    if (!hasPingBox) return
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

internal fun MainActivity.refreshBlocker() {
    if (!hasBlockedLine) return
    val recent = Blocker.recentBlocked()
    blockedLine.text = "Blocked ${Blocker.blockedCount.get()}" + if (recent.isNotEmpty()) "  ·  last: ${recent.last()}" else ""
}
