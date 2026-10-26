package com.timelock.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TimeLockVpnService : VpnService() {

    companion object {
        private const val TAG = "TimeLockVpn"
        const val ACTION_START_LOCK = "com.timelock.blocker.START_LOCK"
        const val ACTION_STOP_LOCK_EXPIRED = "com.timelock.blocker.STOP_EXPIRED"
        private const val NOTIFICATION_CHANNEL_ID = "timelock_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isLoopRunning = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private lateinit var lockManager: LockManager
    private lateinit var blockedRepo: BlockedWebsitesRepository
    private lateinit var dnsHandler: DnsPacketHandler

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!lockManager.isLockActive()) {
                Log.d(TAG, "Lock time expired! Stopping VPN service.")
                lockManager.checkAndExpireIfTimePassed()
                stopVpnService()
                return
            }
            updateNotification()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lockManager = LockManager(this)
        blockedRepo = BlockedWebsitesRepository(this)
        dnsHandler = DnsPacketHandler(blockedRepo)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_LOCK_EXPIRED) {
            stopVpnService()
            return START_NOT_STICKY
        }

        if (!lockManager.isLockActive()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startVpnLoop()
        mainHandler.post(timerRunnable)
        isRunning = true

        return START_STICKY
    }

    private fun startVpnLoop() {
        if (isLoopRunning.get()) return

        try {
            val builder = Builder()
                .setSession("TimeLock Blocker")
                .addAddress("10.10.10.2", 32)
                .addDnsServer("10.10.10.2")
                .addRoute("10.10.10.2", 32)
                .setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isLoopRunning.set(true)
            executor = Executors.newFixedThreadPool(2)
            executor?.execute { processTunTraffic() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN interface", e)
        }
    }

    private fun processTunTraffic() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteBuffer.allocate(32767)

        val protectedSocket = DatagramSocket()
        protect(protectedSocket)

        while (isLoopRunning.get()) {
            try {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    packet.position(0)
                    handleIpPacket(packet, length, outputStream, protectedSocket)
                    packet.clear()
                }
            } catch (e: Exception) {
                if (isLoopRunning.get()) {
                    Log.e(TAG, "Error processing TUN packet", e)
                }
                break
            }
        }

        try {
            protectedSocket.close()
        } catch (_: Exception) {}
    }

    private fun handleIpPacket(
        packet: ByteBuffer,
        length: Int,
        outputStream: FileOutputStream,
        protectedSocket: DatagramSocket
    ) {
        if (length < 28) return

        val versionAndIhl = packet.get(0).toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return

        val ihl = (versionAndIhl and 0x0F) * 4
        val protocol = packet.get(9).toInt() and 0xFF
        if (protocol != 17) return // UDP

        val srcPort = packet.getShort(ihl).toInt() and 0xFFFF
        val destPort = packet.getShort(ihl + 2).toInt() and 0xFFFF
        if (destPort != 53) return // DNS Port

        val udpHeaderLength = 8
        val dnsPayloadOffset = ihl + udpHeaderLength
        val dnsPayloadLength = length - dnsPayloadOffset
        if (dnsPayloadLength <= 0) return

        val dnsPayload = ByteArray(dnsPayloadLength)
        System.arraycopy(packet.array(), dnsPayloadOffset, dnsPayload, 0, dnsPayloadLength)

        val parsedQuery = dnsHandler.parseQuery(dnsPayload) ?: return
        val isBlocked = blockedRepo.isDomainBlocked(parsedQuery.domain)

        val responseDnsPayload = if (isBlocked) {
            Log.i(TAG, "🚫 BLOCKED DOMAIN: ${parsedQuery.domain}")
            dnsHandler.buildSinkholeDnsResponse(parsedQuery)
        } else {
            dnsHandler.forwardUpstream(protectedSocket, dnsPayload)
        }

        if (responseDnsPayload != null) {
            val responseIpPacket = buildIpUdpPacket(
                srcIp = packet.array().copyOfRange(16, 20),
                dstIp = packet.array().copyOfRange(12, 16),
                srcPort = destPort,
                dstPort = srcPort,
                payload = responseDnsPayload
            )
            outputStream.write(responseIpPacket)
        }
    }

    private fun buildIpUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteBuffer.allocate(totalLength)

        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalLength.toShort())
        packet.putShort(0x0000.toShort())
        packet.putShort(0x4000.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte())
        packet.putShort(0.toShort())
        packet.put(srcIp)
        packet.put(dstIp)

        val ipChecksum = computeChecksum(packet.array(), 0, 20)
        packet.putShort(10, ipChecksum.toShort())

        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort((8 + payload.size).toShort())
        packet.putShort(0.toShort())
        packet.put(payload)

        return packet.array()
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = if (i + 1 < offset + length) data[i + 1].toInt() and 0xFF else 0
            val word = (b1 shl 8) or b2
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        return sum.inv() and 0xFFFF
    }

    private fun buildNotification(): Notification {
        val remainingMillis = lockManager.getRemainingMillis()
        val hours = remainingMillis / (1000 * 60 * 60)
        val minutes = (remainingMillis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (remainingMillis % (1000 * 60)) / 1000
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TimeLock Blocker: লক সক্রিয় (Active)")
            .setContentText("বাকি সময়: $timeString • ওয়েবসাইট ব্লক চলছে")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TimeLock Blocker Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live remaining lock timer and active blocking status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopVpnService() {
        isLoopRunning.set(false)
        mainHandler.removeCallbacks(timerRunnable)
        executor?.shutdownNow()
        executor = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isLoopRunning.set(false)
        mainHandler.removeCallbacks(timerRunnable)
        isRunning = false
        super.onDestroy()
    }
}