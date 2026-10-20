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

// Network check, Wi-Fi radar and connection log sections of the main screen.

internal fun MainActivity.levelColor(level: Int): Int = when (level) {
    1 -> accent
    0 -> amber
    2 -> blue
    else -> red
}

internal fun MainActivity.lineRow(label: String, detail: String, color: Int): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.setPadding(0, dp(2), 0, dp(2))
    row.addView(text(label, 12f, muted), LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
    row.addView(text(detail, 12f, color), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row
}

internal fun MainActivity.runNetworkCheck() {
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

internal fun MainActivity.showCheck(outcome: NetCheck.Outcome?) {
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

internal fun MainActivity.startRadar() {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), MainActivity.REQUEST_LOCATION)
        return
    }
    radarBox.removeAllViews()
    radarBox.addView(text("Scanning", 13f, muted))
    WifiRadar.scan(this) { showRadar(it) }
}

internal fun MainActivity.showRadar(results: List<ScanResult>?) {
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

internal fun MainActivity.refreshLog() {
    if (!hasLogBox) return
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
