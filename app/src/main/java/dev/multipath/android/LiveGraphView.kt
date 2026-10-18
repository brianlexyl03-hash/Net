package dev.multipath.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.util.Locale

/** Smooth multi-line chart. Draws what it is given; it never invents data. */
class LiveGraphView(context: Context) : View(context) {
    class Line(val color: Int, val values: List<Float>, val thick: Boolean)

    private var lines: List<Line> = emptyList()
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 158, 180, 168)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(158, 180, 168)
        textSize = 10f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setLines(next: List<Line>) {
        lines = next
        invalidate()
    }

    private fun niceMax(value: Float): Float {
        val steps = floatArrayOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f)
        for (s in steps) if (value <= s) return s
        return value
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 4f * density
        val right = w - 4f * density
        val top = 14f * density
        val bottom = h - 16f * density
        val peak = niceMax(lines.maxOfOrNull { l -> l.values.maxOrNull() ?: 0f } ?: 0f)

        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
            val value = peak * i / 2f
            val label = if (value >= 10f || value == 0f) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)
            canvas.drawText(if (i == 2) "$label Mbps" else label, left, y - 3f * density, labelPaint)
        }
        canvas.drawText("60 s ago", left, h - 3f * density, labelPaint)
        val nowWidth = labelPaint.measureText("now")
        canvas.drawText("now", right - nowWidth, h - 3f * density, labelPaint)

        for (line in lines) {
            val n = line.values.size
            if (n < 2) continue
            val step = (right - left) / (TrafficHistory.POINTS - 1)
            val startX = right - step * (n - 1)
            val path = Path()
            fun px(i: Int) = startX + step * i
            fun py(i: Int) = bottom - (bottom - top) * (line.values[i] / peak).coerceIn(0f, 1f)
            path.moveTo(px(0), py(0))
            for (i in 1 until n) {
                val midX = (px(i - 1) + px(i)) / 2f
                val midY = (py(i - 1) + py(i)) / 2f
                path.quadTo(px(i - 1), py(i - 1), midX, midY)
            }
            path.lineTo(px(n - 1), py(n - 1))
            if (line.thick) {
                val fill = Path(path)
                fill.lineTo(px(n - 1), bottom)
                fill.lineTo(px(0), bottom)
                fill.close()
                fillPaint.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    Color.argb(90, Color.red(line.color), Color.green(line.color), Color.blue(line.color)),
                    Color.argb(0, Color.red(line.color), Color.green(line.color), Color.blue(line.color)),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawPath(fill, fillPaint)
            }
            linePaint.color = line.color
            linePaint.strokeWidth = (if (line.thick) 2.6f else 1.8f) * density
            canvas.drawPath(path, linePaint)
        }
    }
}
