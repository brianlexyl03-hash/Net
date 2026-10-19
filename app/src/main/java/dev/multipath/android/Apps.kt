package dev.multipath.android

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Finds which app owns a connection that enters the tunnel (Android 10 and newer). */
object AppResolver {
    class App(val pkg: String, val label: String)

    private val unknown = App("", "Other")
    private val cache = ConcurrentHashMap<Int, App>()

    fun lookup(context: Context, protocol: Int, src: Int, srcPort: Int, dst: Int, dstPort: Int): App {
        if (Build.VERSION.SDK_INT < 29) return unknown
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = manager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(PacketUtil.toAddress(src), srcPort),
                InetSocketAddress(PacketUtil.toAddress(dst), dstPort),
            )
            if (uid < 0) unknown else cache.getOrPut(uid) { resolve(context, uid) }
        } catch (_: Exception) {
            unknown
        }
    }

    private fun resolve(context: Context, uid: Int): App {
        if (uid == 1000) return App("android", "Android system")
        val manager = context.packageManager
        val packages = manager.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) return App("uid:$uid", "System service")
        val pkg = packages[0]
        val label = try {
            manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        return App(pkg, label)
    }
}

/** Live per-app traffic counters, filled in by the tunnel. */
object AppStats {
    class Entry(val pkg: String, val label: String) {
        val up = AtomicLong()
        val down = AtomicLong()
        val flows = AtomicInteger()

        @Volatile var lastUp = 0L
        @Volatile var lastDown = 0L
        @Volatile var rateUp = 0f
        @Volatile var rateDown = 0f
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var lastAt = 0L

    fun entry(app: AppResolver.App): Entry = entries.getOrPut(app.pkg) { Entry(app.pkg, app.label) }

    fun reset() {
        entries.clear()
        lastAt = 0L
    }

    @Synchronized
    fun sample() {
        val now = System.nanoTime()
        val seconds = if (lastAt == 0L) 1.0 else ((now - lastAt) / 1e9).coerceIn(0.2, 5.0)
        lastAt = now
        for (e in entries.values) {
            val up = e.up.get()
            val down = e.down.get()
            e.rateUp = ((up - e.lastUp) * 8.0 / seconds / 1_000_000.0).toFloat().coerceAtLeast(0f)
            e.rateDown = ((down - e.lastDown) * 8.0 / seconds / 1_000_000.0).toFloat().coerceAtLeast(0f)
            e.lastUp = up
            e.lastDown = down
        }
    }

    fun top(limit: Int): List<Entry> = entries.values
        .filter { it.up.get() + it.down.get() > 0L || it.flows.get() > 0 }
        .sortedByDescending { it.up.get() + it.down.get() }
        .take(limit)
}

/** Apps the user has cut off from the internet while MultiPath is on. */
object AppBlock {
    private var prefs: SharedPreferences? = null

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    var listener: (() -> Unit)? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("app_block", Context.MODE_PRIVATE)
        prefs = p
        blocked = p.getStringSet("blocked", emptySet())?.toSet() ?: emptySet()
    }

    fun isBlocked(pkg: String): Boolean = pkg.isNotEmpty() && pkg in blocked

    fun setBlocked(pkg: String, on: Boolean) {
        blocked = if (on) blocked + pkg else blocked - pkg
        prefs?.edit()?.putStringSet("blocked", blocked)?.apply()
        if (on) listener?.invoke()
    }
}

/** Gaming mode: chosen games use the source with the lowest delay. */
object Gaming {
    private var prefs: SharedPreferences? = null

    @Volatile
    var enabled = false
        private set

    @Volatile
    private var games: Set<String> = emptySet()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("gaming", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", false)
        games = p.getStringSet("games", emptySet())?.toSet() ?: emptySet()
    }

    fun setEnabled(on: Boolean) { enabled = on; prefs?.edit()?.putBoolean("enabled", on)?.apply() }
    fun gameSet(): Set<String> = games
    fun setGames(next: Set<String>) { games = next; prefs?.edit()?.putStringSet("games", next)?.apply() }
    fun isGame(pkg: String): Boolean = pkg.isNotEmpty() && pkg in games
}
