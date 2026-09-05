package com.vlesscardvpn.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.R
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.VlessConfig

/**
 * Stub VLESS VPN Service.
 * TODO: Integrate real sing-box binary or Go lib.
 * Current: Placeholder that starts VPN interface and logs generated config.
 * For real: Use sing-box native or libv2ray / sing-box-go.
 */
class VlessVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.vlesscardvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vlesscardvpn.DISCONNECT"
        const val EXTRA_CONFIG = "config"

        @Volatile
        var isRunning = false

        fun startVpn(context: Context, config: VlessConfig) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, config.id)
            }
            context.startService(intent)
        }

        fun stopVpn(context: Context) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private var currentConfig: VlessConfig? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG)
                // In real app load from DB. For skeleton use hard-coded or passed.
                // Stub: assume passed via intent or global
                currentConfig = VlessConfig(
                    name = "Active", address = "1.1.1.1", port = 443, uuid = "stub"
                )
                startVpnSession(currentConfig!!)
            }
            ACTION_DISCONNECT -> {
                stopVpnSession()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpnSession(config: VlessConfig) {
        if (isRunning) return

        val builder = Builder()
            .setSession("VLESS Card VPN")
            .addAddress("10.0.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(1500)

        // Protect the tun
        val vpnInterface = builder.establish() ?: return

        isRunning = true

        // Generate sing-box config with network-aware SNI
        val singBoxJson = SingBoxManager.generateConfig(this, config)
        // TODO: Write to file /data and exec sing-box binary or use embedded
        // For skeleton, just print/log
        println("=== SING-BOX CONFIG (stub) ===\n$singBoxJson")

        startForegroundNotification(config)
    }

    private fun stopVpnSession() {
        isRunning = false
        // TODO: Kill sing-box process / tun close
    }

    private fun startForegroundNotification(config: VlessConfig) {
        val channelId = "vless_vpn_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "VLESS VPN", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VLESS Card VPN Connected")
            .setContentText("${config.name} • ${config.address}")
            .setSmallIcon(android.R.drawable.ic_lock_lock) // placeholder
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopVpnSession()
        super.onDestroy()
    }
}