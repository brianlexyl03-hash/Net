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

// Small view-building helpers shared by every section of the main screen.

internal fun MainActivity.card(fill: Int = surface, outline: Int = line): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = shape(fill, 20, outline)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun MainActivity.actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
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

internal fun MainActivity.text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = true
}

internal fun MainActivity.addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) {
    parent.addView(text(value, size, color, bold).apply { letterSpacing = spacing / 16f })
}

internal fun MainActivity.addSpace(parent: LinearLayout, size: Int, horizontal: Boolean = false) {
    parent.addView(View(this), if (horizontal) {
        LinearLayout.LayoutParams(dp(size), 1)
    } else {
        LinearLayout.LayoutParams(1, dp(size))
    })
}

internal fun MainActivity.weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

internal fun MainActivity.shape(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

internal fun MainActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
