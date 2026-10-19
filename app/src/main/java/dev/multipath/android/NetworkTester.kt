package dev.multipath.android

import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NetworkTester {
    private const val LATENCY_URL = "https://connectivitycheck.gstatic.com/generate_204"
    private const val SPEED_TEST_BASE = "https://speed.cloudflare.com/__down?bytes="

    data class RouteSpeed(val network: Network, val mbps: Double, val bytes: Long, val elapsedSeconds: Double)
    data class ParallelSpeedResult(val routes: List<RouteSpeed>, val aggregateMbps: Double, val elapsedSeconds: Double)

    /** HTTP response time over the supplied Android Network, not the default route. */
    @Throws(IOException::class)
    fun latencyMs(network: Network): Long {
        val connection = network.openConnection(URL(LATENCY_URL)) as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "MultiPath-Mobile/0.9.0")
        val started = System.nanoTime()
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Endpoint returned HTTP $code")
            return ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } finally {
            connection.disconnect()
        }
    }

    /** Measures one HTTPS download over a specific Android Network. */
    @Throws(IOException::class)
    fun downloadMbps(network: Network, requestedBytes: Int = 4_000_000): Double =
        downloadMeasurement(network, requestedBytes).mbps

    /**
     * Downloads over all supplied routes at once and measures aggregate delivered
     * throughput using total bytes divided by shared wall-clock time. This is a
     * benchmark of concurrent paths, not proof that normal phone traffic is bonded.
     */
    @Throws(IOException::class)
    fun parallelDownloadMbps(networks: List<Network>, requestedBytesPerRoute: Int = 2_000_000): ParallelSpeedResult {
        val unique = networks.distinctBy { it.toString() }.take(10)
        if (unique.isEmpty()) throw IOException("No routes selected")
        val bytesPerRoute = requestedBytesPerRoute.coerceIn(500_000, 2_000_000)
        val pool = Executors.newFixedThreadPool(unique.size)
        val startGate = CountDownLatch(1)
        val readyGate = CountDownLatch(unique.size)
        try {
            val tasks = unique.map { network ->
                pool.submit<RouteSpeed> {
                    readyGate.countDown()
                    if (!startGate.await(10, TimeUnit.SECONDS)) throw IOException("Timed out waiting to start test")
                    downloadMeasurement(network, bytesPerRoute, network)
                }
            }
            if (!readyGate.await(10, TimeUnit.SECONDS)) throw IOException("Routes did not become ready in time")
            val start = System.nanoTime()
            startGate.countDown()
            val results = tasks.map { future ->
                try {
                    future.get(25, TimeUnit.SECONDS)
                } catch (error: Exception) {
                    tasks.forEach { it.cancel(true) }
                    throw IOException("One or more parallel route tests failed: ${error.cause?.message ?: error.message}", error)
                }
            }
            val elapsed = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.001)
            val totalBytes = results.sumOf { it.bytes }
            return ParallelSpeedResult(
                routes = results,
                aggregateMbps = totalBytes * 8.0 / elapsed / 1_000_000.0,
                elapsedSeconds = elapsed,
            )
        } finally {
            startGate.countDown()
            pool.shutdownNow()
        }
    }

    private fun downloadMeasurement(network: Network, requestedBytes: Int, resultNetwork: Network = network): RouteSpeed {
        val bytesToRead = requestedBytes.coerceIn(500_000, 4_000_000)
        val connection = network.openConnection(URL("$SPEED_TEST_BASE$bytesToRead")) as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "MultiPath-Mobile/0.9.0")
        val started = System.nanoTime()
        var total = 0L
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Speed-test endpoint returned HTTP $code")
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (total < bytesToRead) {
                    val wanted = minOf(buffer.size.toLong(), bytesToRead.toLong() - total).toInt()
                    val count = input.read(buffer, 0, wanted)
                    if (count < 0) break
                    total += count
                }
            }
        } finally {
            connection.disconnect()
        }
        val seconds = ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(0.001)
        if (total <= 0L) throw IOException("No data received")
        return RouteSpeed(resultNetwork, total * 8.0 / seconds / 1_000_000.0, total, seconds)
    }
}
