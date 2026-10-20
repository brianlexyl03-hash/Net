package dev.multipath.android

import android.util.Log

/** Small tagged logger. Safe to call from unit tests, where android.util.Log is stubbed. */
object Logger {
    private const val TAG = "MultiPath"

    fun d(area: String, message: String) {
        try { Log.d(TAG, "[$area] $message") } catch (_: Throwable) { }
    }

    fun w(area: String, message: String) {
        try { Log.w(TAG, "[$area] $message") } catch (_: Throwable) { }
    }

    fun e(area: String, message: String) {
        try { Log.e(TAG, "[$area] $message") } catch (_: Throwable) { }
    }
}
