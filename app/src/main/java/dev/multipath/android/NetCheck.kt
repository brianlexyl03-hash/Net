package dev.multipath.android

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One-tap health and safety check of every source. Blocking: run it off the main thread. */
object NetCheck {
    /** state: 1 good, 0 warning, -1 bad, 2 information */
    class Item(val label: String, val state: Int, val detail: String)

    class Report(val name: String, val items: List<Item>, val verdict: String, val level: Int)

    class Outcome(val reports: List<Report>, val safety: Item?)

    private val HOSTS = listOf("www.google.com", "www.wikipedia.org", "www.cloudflare.com")

    fun run(links: List<LinkSnapshot>): Outcome {
        if (links.isEmpty()) return Outcome(emptyList(), null)
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "multipath-check").apply { isDaemon = true } }
        try {
            val reports = links.map { link -> pool.submit(Callable { checkOne(link, links) }) }
            val certs = links.map { link -> pool.submit(Callable { HOSTS.map { host -> tls(link, host) } }) }
            val done = reports.map { it.get() }
            val safety = if (links.size >= 2) compare(links, certs.map { it.get() }) else null
            return Outcome(done, safety)
        } finally {
            pool.shutdown()
        }
    }

    private fun millis(start: Long): Long = (System.nanoTime() - start) / 1_000_000L

    private fun checkOne(link: LinkSnapshot, all: List<LinkSnapshot>): Report {
        val items = ArrayList<Item>()
        var dnsOk = false
        var hijack = false
        try {
            val start = System.nanoTime()
            val answers = link.network.getAllByName("www.google.com")
            val ms = millis(start)
            if (answers.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isAnyLocalAddress }) {
                hijack = true
                items.add(Item("Names", -1, "Points to a local address"))
            } else {
                dnsOk = true
                items.add(Item("Names", 1, "$ms ms"))
            }
        } catch (_: Exception) {
            items.add(Item("Names", -1, "Lookup failed"))
        }

        var reachMs: Long? = null
        val socket = Socket()
        try {
            link.network.bindSocket(socket)
            val start = System.nanoTime()
            socket.connect(InetSocketAddress("1.1.1.1", 443), 3_000)
            reachMs = millis(start)
            items.add(Item("Reach", if (reachMs > 300L) 0 else 1, "$reachMs ms"))
        } catch (_: Exception) {
            items.add(Item("Reach", -1, "No reply"))
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }

        var captive = false
        var webOk = false
        try {
            val connection = link.network.openConnection(URL("http://connectivitycheck.gstatic.com/generate_204")) as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            if (code == 204) {
                webOk = true
                items.add(Item("Web", 1, "Open"))
            } else if (code in 200..399) {
                captive = true
                items.add(Item("Web", -1, "Sign-in page in the way"))
            } else {
                items.add(Item("Web", -1, "Blocked ($code)"))
            }
        } catch (_: Exception) {
            items.add(Item("Web", -1, "Did not load"))
        }

        try {
            val connection = link.network.openConnection(URL("https://api.ipify.org")) as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            val text = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            connection.disconnect()
            if (text.isNotEmpty() && text.length <= 45) items.add(Item("Address", 2, text))
        } catch (_: Exception) {
            // optional line
        }

        val reachOk = reachMs != null
        val level: Int
        val verdict: String
        when {
            captive -> { level = -1; verdict = "A sign-in page is blocking the internet" }
            hijack -> { level = -1; verdict = "Lookups are being redirected" }
            !reachOk && !dnsOk -> { level = -1; verdict = "No internet" }
            reachOk && !dnsOk -> { level = -1; verdict = "Connected, but names do not resolve" }
            reachOk && !webOk -> { level = 0; verdict = "Connected, but websites do not load" }
            (reachMs ?: 0L) > 300L -> { level = 0; verdict = "Working, but slow to reply" }
            else -> { level = 1; verdict = "Working" }
        }
        return Report(RoutePolicy.nameOf(link, all), items, verdict, level)
    }

    private class Tls(val issuer: String?, val rejected: Boolean)

    private fun tls(link: LinkSnapshot, host: String): Tls {
        val raw = Socket()
        try {
            link.network.bindSocket(raw)
            raw.connect(InetSocketAddress(link.network.getByName(host), 443), 4_000)
            raw.soTimeout = 4_000
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            val params: SSLParameters = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()
            val leaf = ssl.session.peerCertificates[0] as X509Certificate
            return Tls(leaf.issuerX500Principal.name, false)
        } catch (_: SSLException) {
            return Tls(null, true)
        } catch (_: Exception) {
            return Tls(null, false)
        } finally {
            try { raw.close() } catch (_: Exception) { }
        }
    }

    private fun compare(links: List<LinkSnapshot>, results: List<List<Tls>>): Item {
        val names = links.map { RoutePolicy.nameOf(it, links) }
        val rejected = names.filterIndexed { i, _ -> results[i].any { it.rejected } }
        if (rejected.isNotEmpty()) {
            return Item("Safety", -1, "Fake certificate seen on ${rejected.joinToString(" and ")}. Do not log in or pay there.")
        }
        var compared = 0
        for (h in HOSTS.indices) {
            val issuers = results.mapNotNull { it[h].issuer }
            if (issuers.size >= 2) {
                compared++
                if (issuers.toSet().size > 1) {
                    return Item("Safety", -1, "Sources disagree on ${HOSTS[h]}. One may be tampered with. Prefer mobile data.")
                }
            }
        }
        return if (compared == 0) Item("Safety", 2, "Could not compare sources")
        else Item("Safety", 1, "No signs of tampering. This is a quick check, not a guarantee.")
    }
}
