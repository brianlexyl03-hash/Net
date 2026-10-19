package dev.multipath.android

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remembers when each source connected, lost internet or came back, and how long it was up. */
object NetLog {
    class Event(val at: Long, val text: String)

    private class Track(var name: String, var ok: Boolean, var since: Long) {
        var upMs = 0L
        var totalMs = 0L
    }

    private val tracks = LinkedHashMap<String, Track>()
    private val events = ArrayList<Event>()
    private var primed = false
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("netlog", Context.MODE_PRIVATE)
        prefs = p
        p.getString("events", "")?.lines()?.forEach { line ->
            val parts = line.split('\t', limit = 2)
            val at = parts.firstOrNull()?.toLongOrNull()
            if (at != null && parts.size == 2) events.add(Event(at, parts[1]))
        }
    }

    private fun add(now: Long, text: String) {
        events.add(Event(now, text))
        while (events.size > 40) events.removeAt(0)
        prefs?.edit()?.putString("events", events.joinToString("\n") { "${it.at}\t${it.text}" })?.apply()
    }

    @Synchronized
    fun observe(links: List<LinkSnapshot>) {
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        for (link in links) {
            val id = link.id
            seen.add(id)
            val name = RoutePolicy.nameOf(link, links)
            val ok = link.validated
            val track = tracks[id]
            if (track == null) {
                tracks[id] = Track(name, ok, now)
                if (primed) add(now, if (ok) "$name connected" else "$name connected, no internet")
            } else {
                close(track, now)
                track.name = name
                if (track.ok != ok) {
                    track.ok = ok
                    add(now, if (ok) "$name back online" else "$name lost internet")
                }
            }
        }
        for ((id, track) in tracks) {
            if (id !in seen && track.since >= 0L) {
                close(track, now)
                if (track.ok) add(now, "${track.name} disconnected")
                track.ok = false
            }
        }
        primed = true
    }

    private fun close(track: Track, now: Long) {
        val span = (now - track.since).coerceAtLeast(0L)
        track.totalMs += span
        if (track.ok) track.upMs += span
        track.since = now
    }

    /** Returns lines such as "Wi-Fi  98.7% online". */
    @Synchronized
    fun uptime(): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        return tracks.values.mapNotNull { t ->
            val total = t.totalMs + (now - t.since).coerceAtLeast(0L)
            val up = t.upMs + if (t.ok) (now - t.since).coerceAtLeast(0L) else 0L
            if (total < 5_000L) null else t.name to (up * 1000L / total).toInt()
        }
    }

    @Synchronized
    fun recent(limit: Int): List<String> {
        val format = SimpleDateFormat("HH:mm", Locale.US)
        return events.takeLast(limit).reversed().map { "${format.format(Date(it.at))}  ${it.text}" }
    }

    @Synchronized
    fun clear() {
        events.clear()
        prefs?.edit()?.remove("events")?.apply()
    }
}
