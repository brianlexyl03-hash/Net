package dev.multipath.android

import android.content.Context

/** Saved settings for an optional encrypted exit server (for example one located in the USA). */
object ExitServer {
    class Config(val host: String, val port: Int, val password: String) {
        val master: ByteArray by lazy { Shadowsocks.masterKey(password) }
    }

    private const val PREFS = "exit_server"

    @Volatile
    var active: Config? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Config? {
        val p = prefs(context)
        val host = p.getString("host", null) ?: return null
        val secret = p.getString("secret", null) ?: return null
        val password = SecretStore.decrypt(secret) ?: return null
        return Config(host, p.getInt("port", 8388), password)
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    /** Returns false when the password could not be stored securely. */
    fun save(context: Context, host: String, port: Int, password: String): Boolean {
        val secret = SecretStore.encrypt(password) ?: return false
        prefs(context).edit().putString("host", host).putInt("port", port).putString("secret", secret)
            .putBoolean("enabled", true).apply()
        refresh(context)
        return true
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        refresh(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        active = null
    }

    fun refresh(context: Context) {
        active = if (enabled(context)) load(context) else null
    }
}
