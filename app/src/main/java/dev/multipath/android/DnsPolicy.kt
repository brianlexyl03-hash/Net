package dev.multipath.android

object DnsPolicy {
    private val dohBlocklist = mutableSetOf(
        "dns.google",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "one.one.one.one",
    )

    private val dotBlocklist = mutableSetOf(
        "dns.google",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
    )

    fun refreshBlocklist(blockedHosts: Collection<String>) {
        dohBlocklist.clear()
        dohBlocklist.addAll(blockedHosts)
        dotBlocklist.clear()
        dotBlocklist.addAll(blockedHosts)
    }

    fun shouldBlockPlainDns(host: String): Boolean = false

    fun shouldBlockDot(host: String): Boolean = host.lowercase() in dotBlocklist

    fun shouldBlockDoh(host: String): Boolean = host.lowercase() in dohBlocklist

    fun shouldInterceptPort53(): Boolean = true

    fun shouldInterceptPort853(): Boolean = true
}
