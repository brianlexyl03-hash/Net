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

// Dialogs and actions started from the main screen. Extension functions keep MainActivity small.

internal fun MainActivity.askBatteryExemption(force: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
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

internal fun MainActivity.showBundleDialog(name: String, key: String) {
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
        adapter = ArrayAdapter(this@showBundleDialog, android.R.layout.simple_spinner_dropdown_item, listOf("MB", "GB"))
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

internal fun MainActivity.showExitDialog() {
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

internal fun MainActivity.showAddWifi() {
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
        adapter = ArrayAdapter(this@showAddWifi, android.R.layout.simple_spinner_dropdown_item, types)
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

internal fun MainActivity.connectExtra(ssid: String) {
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

internal fun MainActivity.removeWifi(ssid: String) {
    AlertDialog.Builder(this)
        .setTitle("Remove $ssid?")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Remove") { _, _ -> ExtraWifi.remove(this, ssid); refreshSources() }
        .show()
}

internal fun MainActivity.confirmSpeedTest() {
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

internal fun MainActivity.runSpeedTest(targets: List<LinkSnapshot>) {
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

internal fun MainActivity.showBlockSiteDialog() {
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

internal fun MainActivity.showGamesDialog() {
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
