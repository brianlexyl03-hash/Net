package dev.multipath.android

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

/** Answers DNS lookups for ad, tracker and unwanted domains with "does not exist". */
object Blocker {
    private const val PREFS = "blocker"

    @Volatile
    var ads = true
        private set

    @Volatile
    var adult = false
        private set

    @Volatile
    private var custom: Set<String> = emptySet()
    private var prefs: SharedPreferences? = null
    val blockedCount = AtomicLong()
    private val recent = ArrayDeque<String>()

    class Query(val name: String, val length: Int)

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        ads = p.getBoolean("ads", true)
        adult = p.getBoolean("adult", false)
        custom = p.getStringSet("custom", emptySet())?.toSet() ?: emptySet()
    }

    fun setAds(on: Boolean) { ads = on; prefs?.edit()?.putBoolean("ads", on)?.apply() }
    fun setAdult(on: Boolean) { adult = on; prefs?.edit()?.putBoolean("adult", on)?.apply() }

    fun normalize(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return s.substringBefore('/').substringBefore(':').trim('.')
    }

    fun addCustom(input: String): Boolean {
        val domain = normalize(input)
        if (domain.isEmpty() || !domain.contains('.')) return false
        custom = custom + domain
        prefs?.edit()?.putStringSet("custom", custom)?.apply()
        return true
    }

    fun clearCustom() {
        custom = emptySet()
        prefs?.edit()?.putStringSet("custom", emptySet())?.apply()
    }

    fun customCount(): Int = custom.size

    fun active(): Boolean = ads || adult || custom.isNotEmpty()

    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }

    fun record(name: String) {
        blockedCount.incrementAndGet()
        synchronized(recent) {
            if (recent.lastOrNull() == name) return
            recent.addLast(name)
            while (recent.size > 8) recent.removeFirst()
        }
    }

    /** Reads the first question of a DNS query. Returns null for anything else. */
    fun parse(b: ByteArray, off: Int, len: Int): Query? {
        if (len < 17) return null
        if ((PacketUtil.readU16(b, off + 2) and 0x8000) != 0) return null
        if (PacketUtil.readU16(b, off + 4) < 1) return null
        var p = off + 12
        val end = off + len
        val name = StringBuilder()
        while (true) {
            if (p >= end) return null
            val l = b[p].toInt() and 0xFF
            if (l == 0) { p++; break }
            if ((l and 0xC0) != 0 || p + 1 + l > end) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 1..l) name.append((b[p + i].toInt() and 0xFF).toChar().lowercaseChar())
            p += 1 + l
        }
        if (p + 4 > end) return null
        return Query(name.toString(), p + 4 - off)
    }

    fun isBlocked(name: String): Boolean {
        var n = name
        while (true) {
            if (ads && n in AD_DOMAINS) return true
            if (adult && n in ADULT_DOMAINS) return true
            if (n in custom) return true
            val dot = n.indexOf('.')
            if (dot < 0) return false
            n = n.substring(dot + 1)
            if (!n.contains('.')) return false
        }
    }

    /** Builds a "name does not exist" reply from the query's header and question. */
    fun nxdomain(b: ByteArray, off: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        System.arraycopy(b, off, out, 0, size)
        val flags = PacketUtil.readU16(b, off + 2)
        PacketUtil.writeU16(out, 2, 0x8000 or (flags and 0x0100) or 0x0080 or 3)
        PacketUtil.writeU16(out, 4, 1)
        PacketUtil.writeU16(out, 6, 0)
        PacketUtil.writeU16(out, 8, 0)
        PacketUtil.writeU16(out, 10, 0)
        return out
    }

    private val AD_DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "google-analytics.com", "googletagmanager.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "admob.com", "adsense.com", "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com", "moatads.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "advertising.com", "adcolony.com", "applovin.com", "unityads.unity3d.com",
        "vungle.com", "chartboost.com", "inmobi.com", "mopub.com", "ironsrc.com", "supersonicads.com", "startapp.com",
        "flurry.com", "smaato.net", "tapjoy.com", "fyber.com", "adform.net", "casalemedia.com", "contextweb.com",
        "yieldmo.com", "sharethrough.com", "teads.tv", "mathtag.com", "bluekai.com", "krxd.net", "demdex.net",
        "everesttech.net", "serving-sys.com", "2mdn.net", "adtechus.com", "zedo.com", "popads.net", "popcash.net",
        "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.net", "hotjar.com", "mouseflow.com",
        "crazyegg.com", "fullstory.com", "segment.io", "mixpanel.com", "amplitude.com", "branch.io", "appsflyer.com",
        "adjust.com", "kochava.com", "singular.net", "app-measurement.com", "ads.yahoo.com", "analytics.yahoo.com",
        "ads.twitter.com", "analytics.twitter.com", "ads-api.twitter.com", "ads.tiktok.com", "analytics.tiktok.com",
        "business-api.tiktok.com", "ads.linkedin.com", "px.ads.linkedin.com", "an.facebook.com", "pixel.facebook.com",
        "adsmoloco.com", "liftoff.io", "digitalturbine.com", "bidswitch.net", "lijit.com", "sovrn.com", "33across.com",
        "adskeeper.com", "mgid.com", "revcontent.com", "zemanta.com", "gumgum.com", "media.net", "adroll.com",
    )

    private val ADULT_DOMAINS = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "tube8.com",
        "spankbang.com", "eporner.com", "txxx.com", "beeg.com", "porn.com", "brazzers.com", "chaturbate.com",
        "stripchat.com", "bongacams.com", "livejasmin.com", "onlyfans.com", "fapello.com", "rule34.xxx", "e-hentai.org",
        "nhentai.net", "hentaihaven.xxx", "motherless.com", "thumbzilla.com", "tnaflix.com", "4tube.com",
        "youjizz.com", "sex.com", "cam4.com", "camsoda.com", "myfreecams.com", "fansly.com", "xtube.com",
    )
}
