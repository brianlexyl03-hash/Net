package dev.multipath.android

/** Rolling per-second throughput history built from real bytes relayed by the VPN engine. */
object TrafficHistory {
    const val POINTS = 60

    class Series(val name: String, prefill: Int) {
        val down = ArrayDeque<Float>()
        val up = ArrayDeque<Float>()

        init {
            repeat(prefill) { down.addLast(0f); up.addLast(0f) }
        }
    }

    private val series = LinkedHashMap<String, Series>()
    private val total = Series("Total", 0)
    private val lastDown = HashMap<String, Long>()
    private val lastUp = HashMap<String, Long>()
    private var lastAt = 0L

    private fun push(queue: ArrayDeque<Float>, value: Float) {
        queue.addLast(value)
        while (queue.size > POINTS) queue.removeFirst()
    }

    @Synchronized
    fun sample() {
        val now = System.nanoTime()
        val seconds = if (lastAt == 0L) 1.0 else ((now - lastAt) / 1e9).coerceIn(0.2, 5.0)
        lastAt = now
        val names = LinkedHashSet<String>()
        names.addAll(series.keys)
        names.addAll(VpnStats.downByRoute.keys)
        names.addAll(VpnStats.upByRoute.keys)
        var sumDown = 0f
        var sumUp = 0f
        for (name in names) {
            val s = series.getOrPut(name) { Series(name, total.down.size) }
            val curDown = VpnStats.downByRoute[name]?.get() ?: 0L
            val curUp = VpnStats.upByRoute[name]?.get() ?: 0L
            val prevDown = lastDown[name] ?: 0L
            val prevUp = lastUp[name] ?: 0L
            lastDown[name] = curDown
            lastUp[name] = curUp
            val deltaDown = if (curDown >= prevDown) curDown - prevDown else curDown
            val deltaUp = if (curUp >= prevUp) curUp - prevUp else curUp
            val mbpsDown = (deltaDown * 8.0 / seconds / 1_000_000.0).toFloat()
            val mbpsUp = (deltaUp * 8.0 / seconds / 1_000_000.0).toFloat()
            push(s.down, mbpsDown)
            push(s.up, mbpsUp)
            sumDown += mbpsDown
            sumUp += mbpsUp
        }
        push(total.down, sumDown)
        push(total.up, sumUp)
    }

    @Synchronized
    fun names(): List<String> = series.keys.toList()

    @Synchronized
    fun values(name: String, download: Boolean): List<Float> {
        val s = series[name] ?: return emptyList()
        return (if (download) s.down else s.up).toList()
    }

    @Synchronized
    fun totalValues(download: Boolean): List<Float> = (if (download) total.down else total.up).toList()

    @Synchronized
    fun clear() {
        series.clear()
        total.down.clear()
        total.up.clear()
        lastDown.clear()
        lastUp.clear()
        lastAt = 0L
    }
}
