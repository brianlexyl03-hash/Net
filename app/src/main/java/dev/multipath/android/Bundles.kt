package dev.multipath.android

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional data limit per source (a SIM bundle, a hotspot allowance). Counts only traffic
 * relayed by MultiPath, so it can differ from the carrier's own meter.
 */
object Bundles {
    private const val PREFS = "bundles"
    private val used = ConcurrentHashMap<String, AtomicLong>()
    private val limit = ConcurrentHashMap<String, Long>()
    private var prefs: SharedPreferences? = null

    @Volatile
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) {
            if (v !is Long) continue
            if (k.startsWith("used:")) used[k.removePrefix("used:")] = AtomicLong(v)
            else if (k.startsWith("limit:")) limit[k.removePrefix("limit:")] = v
        }
        loaded = true
    }

    fun add(key: String, bytes: Long) {
        if (key.isEmpty()) return
        used.getOrPut(key) { AtomicLong() }.addAndGet(bytes)
    }

    fun usedBytes(key: String): Long = used[key]?.get() ?: 0L
    fun limitBytes(key: String): Long = limit[key] ?: 0L
    fun exhausted(key: String): Boolean = limitBytes(key) > 0L && usedBytes(key) >= limitBytes(key)

    fun setLimit(key: String, bytes: Long) {
        if (bytes <= 0L) limit.remove(key) else limit[key] = bytes
        used[key] = AtomicLong(0L)
        flush()
    }

    fun resetUsed(key: String) {
        used[key] = AtomicLong(0L)
        flush()
    }

    fun flush() {
        val p = prefs ?: return
        val editor = p.edit().clear()
        for ((k, v) in used) editor.putLong("used:$k", v.get())
        for ((k, v) in limit) editor.putLong("limit:$k", v)
        editor.apply()
    }

    fun format(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun describe(key: String): String {
        val l = limitBytes(key)
        return if (l > 0L) "${format(usedBytes(key))} of ${format(l)}" else ""
    }
}
