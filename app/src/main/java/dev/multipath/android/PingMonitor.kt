package dev.multipath.android

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

/** Measures real delay, jitter and loss on every source every two seconds. */
object PingMonitor {
    class Stat(@Volatile var label: String) {
        private val samples = ArrayDeque<Long>()

        @Synchronized
        fun add(ms: Long) {
            samples.addLast(ms)
            while (samples.size > 12) samples.removeFirst()
        }

        @Synchronized
        fun ping(): Long? {
            val ok = samples.filter { it >= 0L }
            return if (ok.isEmpty()) null else ok.average().toLong()
        }

        @Synchronized
        fun jitter(): Long? {
            val ok = samples.filter { it >= 0L }
            if (ok.size < 2) return null
            var sum = 0L
            for (i in 1 until ok.size) sum += abs(ok[i] - ok[i - 1])
            return sum / (ok.size - 1)
        }

        @Synchronized
        fun lossPercent(): Int = if (samples.isEmpty()) 0 else samples.count { it < 0L } * 100 / samples.size
    }

    val stats = ConcurrentHashMap<String, Stat>()
    private val suppliers = CopyOnWriteArrayList<() -> List<LinkSnapshot>>()
    private var thread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "multipath-ping").apply { isDaemon = true } }

    @Synchronized
    fun start(supplier: () -> List<LinkSnapshot>) {
        suppliers.add(supplier)
        if (thread == null) {
            thread = Thread({ loop() }, "multipath-ping-loop").also { it.isDaemon = true; it.start() }
        }
    }

    @Synchronized
    fun stop(supplier: () -> List<LinkSnapshot>) {
        suppliers.remove(supplier)
        if (suppliers.isEmpty()) {
            thread?.interrupt()
            thread = null
            stats.clear()
        }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val links = suppliers.flatMap { it() }.distinctBy { it.id }
            for (link in links) {
                if (!link.validated) continue
                val stat = stats.getOrPut(link.id) { Stat(RoutePolicy.nameOf(link, links)) }
                stat.label = RoutePolicy.nameOf(link, links)
                pool.execute { stat.add(probe(link.network)) }
            }
            stats.keys.retainAll(links.map { it.id }.toSet())
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun probe(network: Network): Long {
        val socket = Socket()
        return try {
            network.bindSocket(socket)
            val start = System.nanoTime()
            socket.connect(InetSocketAddress("1.1.1.1", 443), 1_500)
            (System.nanoTime() - start) / 1_000_000L
        } catch (_: Exception) {
            -1L
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }
}
