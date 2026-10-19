package dev.multipath.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local VPN that sends phone traffic through a user-space packet relay. Every new
 * TCP/UDP flow is assigned to one of the real networks Android exposes (Wi-Fi,
 * mobile data) by [FlowBalancer], so many connections run over several links at once.
 */
class MultiPathVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private val outLock = Any()
    private var engineThread: Thread? = null
    private var sweeper: Thread? = null
    private var monitor: NetworkMonitor? = null
    private var selector: RouteSelector? = null
    private var pool: ExecutorService? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val pingSupplier: () -> List<LinkSnapshot> = { monitor?.snapshots().orEmpty() }
    private val tcp = ConcurrentHashMap<String, TcpSession>()
    private val udp = ConcurrentHashMap<String, UdpSession>()

    private val sink = PacketSink { packet ->
        synchronized(outLock) {
            try {
                output?.write(packet)
            } catch (_: IOException) {
                // tunnel closed
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        if (running) return START_NOT_STICKY
        if (!startEngine()) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "MultiPath connection", NotificationManager.IMPORTANCE_LOW)
            )
            val stop = PendingIntent.getService(
                this, 0,
                Intent(this, MultiPathVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val icon = Icon.createWithResource(this, R.drawable.ic_brand_mark)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_brand_mark)
                .setContentTitle("MultiPath is balancing your connections")
                .setContentText("Wi-Fi and mobile data are shared per connection. Tap to open.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // The VPN still works without the notification on most devices.
        }
    }

    private fun startEngine(): Boolean {
        ExtraWifi.init(this)
        Bundles.init(this)
        Blocker.init(this)
        AppBlock.init(this)
        Gaming.init(this)
        ExitServer.refresh(this)
        VpnStats.reset()
        AppStats.reset()
        val builder = Builder()
            .setSession("MultiPath")
            .setMtu(1500)
            .addAddress("10.77.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        if (ExitServer.active != null) {
            // Pull IPv6 into the tunnel and drop it, so the real address cannot leak around the exit server.
            try { builder.addAddress("fd00:77::2", 128).addRoute("::", 0) } catch (_: Exception) { }
        }
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 29) {
            try { builder.setMetered(false) } catch (_: Exception) { }
        }
        val descriptor = try { builder.establish() } catch (_: Exception) { null } ?: return false
        tun = descriptor
        output = FileOutputStream(descriptor.fileDescriptor)

        val prefs = getSharedPreferences(RoutePolicy.PREFS, MODE_PRIVATE)
        val networkMonitor = NetworkMonitor(this, keepCellularAlive = true) { links ->
            try {
                val nets = RoutePolicy.eligible(links, prefs).map { it.network }
                setUnderlyingNetworks(if (nets.isEmpty()) null else nets.toTypedArray())
            } catch (_: Exception) { }
        }
        monitor = networkMonitor
        networkMonitor.start()
        networkMonitor.scanNow()
        selector = RouteSelector(networkMonitor, prefs)
        pool = Executors.newCachedThreadPool { r -> Thread(r, "multipath-flow").apply { isDaemon = true } }

        AppBlock.listener = { pool?.execute { killBlocked() } }
        PingMonitor.start(pingSupplier)
        if (prefs.getBoolean("keep_awake", true)) acquireLocks()
        running = true
        engineThread = Thread({ packetLoop(descriptor) }, "multipath-tun").also { it.isDaemon = true; it.start() }
        sweeper = Thread({ sweepLoop() }, "multipath-sweeper").also { it.isDaemon = true; it.start() }
        return true
    }

    private fun packetLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32_767)
        while (running) {
            val length = try { input.read(buffer) } catch (_: IOException) { -1 }
            if (length < 0) break
            if (length == 0) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            try {
                handlePacket(buffer, length)
            } catch (_: Exception) {
                // never let one malformed packet stop the tunnel
            }
        }
        if (running) {
            stopEngine()
            stopSelf()
        }
    }

    private fun handlePacket(buf: ByteArray, length: Int) {
        if (length < 20 || (PacketUtil.u8(buf[0]) shr 4) != 4) return
        val ihl = (PacketUtil.u8(buf[0]) and 0x0F) * 4
        if (ihl < 20 || length < ihl) return
        val total = minOf(length, PacketUtil.readU16(buf, 2))
        if ((PacketUtil.readU16(buf, 6) and 0x3FFF) != 0) return // fragments are not supported
        val protocol = PacketUtil.u8(buf[9])
        val src = PacketUtil.readInt(buf, 12)
        val dst = PacketUtil.readInt(buf, 16)
        when (protocol) {
            6 -> handleTcp(buf, ihl, total, src, dst)
            17 -> handleUdp(buf, ihl, total, src, dst)
        }
    }

    private fun rejectSyn(src: Int, srcPort: Int, dst: Int, dstPort: Int, seq: Long) {
        sink.write(
            PacketUtil.tcpPacket(
                dst, dstPort, src, srcPort, 0L, (seq + 1) and PacketUtil.MASK,
                PacketUtil.RST or PacketUtil.ACK, 0, null, 0, 0,
            )
        )
    }

    private fun handleTcp(buf: ByteArray, off: Int, total: Int, src: Int, dst: Int) {
        if (total - off < 20) return
        val srcPort = PacketUtil.readU16(buf, off)
        val dstPort = PacketUtil.readU16(buf, off + 2)
        val seq = PacketUtil.readU32(buf, off + 4)
        val ack = PacketUtil.readU32(buf, off + 8)
        val headerLen = ((PacketUtil.u8(buf[off + 12]) shr 4) and 0x0F) * 4
        val flags = PacketUtil.u8(buf[off + 13])
        val window = PacketUtil.readU16(buf, off + 14)
        val payloadOffset = off + headerLen
        val payloadLength = total - payloadOffset
        if (headerLen < 20 || payloadLength < 0) return
        val key = "$src:$srcPort>$dst:$dstPort"
        val session = tcp[key]
        val isSyn = (flags and PacketUtil.SYN) != 0 && (flags and PacketUtil.ACK) == 0
        if (session == null) {
            if (isSyn) {
                val pool = this.pool ?: return
                val selector = this.selector ?: return
                if (dstPort == 853 && Blocker.active()) {
                    // Block DNS-over-TLS so lookups fall back to plain DNS that the blocker can see.
                    rejectSyn(src, srcPort, dst, dstPort, seq)
                    return
                }
                val owner = AppResolver.lookup(this, 6, src, srcPort, dst, dstPort)
                if (AppBlock.isBlocked(owner.pkg)) {
                    rejectSyn(src, srcPort, dst, dstPort, seq)
                    return
                }
                val low = Gaming.enabled && Gaming.isGame(owner.pkg)
                val created = TcpSession(
                    key, src, srcPort, dst, dstPort, AppStats.entry(owner), low, seq, sink, selector, pool,
                ) { tcp.remove(it.key, it) }
                if (tcp.putIfAbsent(key, created) == null) created.start()
            } else if ((flags and PacketUtil.RST) == 0) {
                val rstSeq = if ((flags and PacketUtil.ACK) != 0) ack else 0L
                val rstAck = (seq + payloadLength + (if ((flags and PacketUtil.SYN) != 0 || (flags and PacketUtil.FIN) != 0) 1 else 0)) and PacketUtil.MASK
                val rstFlags = if ((flags and PacketUtil.ACK) != 0) PacketUtil.RST else PacketUtil.RST or PacketUtil.ACK
                sink.write(PacketUtil.tcpPacket(dst, dstPort, src, srcPort, rstSeq, rstAck, rstFlags, 0, null, 0, 0))
            }
            return
        }
        session.onPacket(seq, ack, flags, window, buf, payloadOffset, payloadLength)
    }

    private fun handleUdp(buf: ByteArray, off: Int, total: Int, src: Int, dst: Int) {
        if (total - off < 8) return
        val srcPort = PacketUtil.readU16(buf, off)
        val dstPort = PacketUtil.readU16(buf, off + 2)
        val payloadLength = minOf(PacketUtil.readU16(buf, off + 4), total - off) - 8
        if (payloadLength < 0) return
        if (dstPort == 53 && Blocker.active()) {
            val query = Blocker.parse(buf, off + 8, payloadLength)
            if (query != null && Blocker.isBlocked(query.name)) {
                Blocker.record(query.name)
                val reply = Blocker.nxdomain(buf, off + 8, query.length)
                sink.write(PacketUtil.udpPacket(dst, 53, src, srcPort, reply, 0, reply.size))
                return
            }
        }
        val key = "$src:$srcPort>$dst:$dstPort"
        var session = udp[key]
        if (session == null) {
            val pool = this.pool ?: return
            val selector = this.selector ?: return
            val owner = AppResolver.lookup(this, 17, src, srcPort, dst, dstPort)
            if (AppBlock.isBlocked(owner.pkg)) return
            val low = Gaming.enabled && Gaming.isGame(owner.pkg)
            val created = UdpSession(
                key, src, srcPort, dst, dstPort, AppStats.entry(owner), low, sink, selector, pool,
            ) { udp.remove(it.key, it) }
            session = udp.putIfAbsent(key, created) ?: created
        }
        session.send(buf, off + 8, payloadLength)
    }

    private fun killBlocked() {
        tcp.values.forEach { if (AppBlock.isBlocked(it.app.pkg)) it.shutdown(true) }
        udp.values.forEach { if (AppBlock.isBlocked(it.app.pkg)) it.close() }
    }

    private fun sweepLoop() {
        while (running) {
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { return }
            val now = System.currentTimeMillis()
            Bundles.flush()
            tcp.values.forEach { if (now - it.lastActive > TcpSession.IDLE_MS) it.shutdown(true) }
            udp.values.forEach { if (now - it.lastActive > it.idleLimitMs) it.close() }
        }
    }

    private fun stopEngine() {
        val wasRunning = running
        running = false
        engineThread?.interrupt()
        sweeper?.interrupt()
        tcp.values.toList().forEach { it.shutdown(false) }
        udp.values.toList().forEach { it.close() }
        tcp.clear()
        udp.clear()
        Bundles.flush()
        AppBlock.listener = null
        PingMonitor.stop(pingSupplier)
        releaseLocks()
        monitor?.stop()
        monitor = null
        selector = null
        pool?.shutdownNow()
        pool = null
        synchronized(outLock) {
            try { output?.close() } catch (_: IOException) { }
            output = null
        }
        try { tun?.close() } catch (_: IOException) { }
        tun = null
        if (wasRunning || Build.VERSION.SDK_INT >= 24) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        }
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "multipath:wifi").apply { setReferenceCounted(false); acquire() }
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "multipath:engine").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
            // keep-awake is best effort
        }
    }

    private fun releaseLocks() {
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wifiLock = null
        wakeLock = null
    }

    override fun onRevoke() {
        stopEngine()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.multipath.android.START"
        const val ACTION_STOP = "dev.multipath.android.STOP"
        private const val CHANNEL = "multipath_vpn"
        private const val NOTIFICATION_ID = 7
        @Volatile
        var running = false
    }
}
