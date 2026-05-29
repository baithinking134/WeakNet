/**
 * @author awu
 * @date 2026-05-26
 * @desc VPN 服务入口：建立 TUN 虚拟网卡、管理前台通知和整体生命周期
 */
package com.awu.weaknet.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import com.awu.weaknet.MainActivity
import com.awu.weaknet.R
import com.awu.weaknet.data.model.NetworkProfile
import com.awu.weaknet.service.VpnStateHolder
import com.awu.weaknet.vpn.manipulation.ManipulationPipeline
import kotlinx.coroutines.*

/**
 * VPN 服务入口，负责建立 TUN 虚拟网卡、管理前台通知和整体生命周期。
 * 作为 Android VpnService 的子类，它是系统与应用流量拦截的唯一桥梁。
 */
class WeakNetVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var vpnHandler: VpnThread? = null
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received STOP action")
            stopVpn()
            return START_NOT_STICKY
        }

        if (isStopping.get()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 防止双重启动：如果已有 VPN 在运行，先停止再重建
        if (vpnInterface != null) {
            Log.w(TAG, "VPN already running, restarting...")
            stopVpnInternal() // 使用 resetForRestart() 保留用户配置
        }

        val profile = VpnStateHolder.activeProfile.value ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(profile.name))
        VpnStateHolder.setRunning(true)
        isStopping.set(false) // 允许后续正常 stop

        val selectedApps = VpnStateHolder.selectedApps.value

        try {
            vpnInterface = establishVpn(selectedApps)
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopVpn()
                return START_NOT_STICKY
            }

            val delayModel = VpnStateHolder.delayModel.value
            val lossModel = VpnStateHolder.lossModel.value
            val pipeline = ManipulationPipeline(profile.condition, delayModel, lossModel)
            Log.i(TAG, "Pipeline created: delay=${profile.condition.delayMs}ms, jitter=${profile.condition.jitterMs}ms, " +
                    "loss=${profile.condition.packetLossPercent}%, up=${profile.condition.uploadSpeedKbps}kbps, " +
                    "down=${profile.condition.downloadSpeedKbps}kbps, delayModel=$delayModel, lossModel=$lossModel")
            val iface = vpnInterface ?: run { stopVpn(); return START_NOT_STICKY }
            val handler = VpnThread(this, iface, pipeline)
            vpnHandler = handler

            vpnThread = Thread({
                handler.run()
            }, "WeakNet-VPN").also { it.start() }

            // Start floating window service (check overlay permission first)
            try {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    val floatIntent = Intent(this, com.awu.weaknet.service.FloatingWindowService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(floatIntent)
                    } else {
                        startService(floatIntent)
                    }
                } else {
                    Log.w(TAG, "No overlay permission, floating window disabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start floating window: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopVpn()
        }

        return START_NOT_STICKY
    }

    private fun establishVpn(selectedApps: Set<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(VpnConfig.SESSION_NAME)
            .addAddress(VpnConfig.VIRTUAL_CLIENT, VpnConfig.VIRTUAL_PREFIX_LENGTH)
            .setMtu(VpnConfig.MTU)
            .setBlocking(true)
        // setBlocking(true) 让 TUN fd 的 read/write 在当前线程阻塞，避免主线程空转消耗 CPU
        // 用两条 /1 路由代替 0.0.0.0/0：部分厂商 ROM 对 /0 路由有兼容性问题，会直接忽略

        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)

        // Add multiple DNS servers (Chinese-accessible)
        for (dns in VpnConfig.DNS_SERVERS) {
            builder.addDnsServer(dns)
        }

        if (selectedApps.isNotEmpty()) {
            for (pkg in selectedApps) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot add app $pkg: ${e.message}")
                }
            }
        } else {
            // 未选择特定应用时用 disallow 模式排除自身，让所有其他应用走 VPN
            builder.addDisallowedApplication(packageName)
        }

        return builder.establish()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopVpn()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    private fun stopVpn() {
        if (!isStopping.compareAndSet(false, true)) return
        VpnStateHolder.reset()
        stopVpnInternal()
        stopSelf()
    }

    /**
     * VPN 内部清理逻辑（停止线程、关闭 fd、重置状态）。
     * 不调用 stopSelf()，以便重启路径复用此方法后继续建立新 VPN。
     */
    private fun stopVpnInternal() {
        Log.i(TAG, "Stopping VPN...")

        vpnHandler?.stop()
        vpnHandler = null

        // 关闭 TUN fd 会使 VPN 线程的阻塞 read 抛异常退出，线程 finally 自行清理资源
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
        vpnThread = null

        VpnStateHolder.resetForRestart()

        try {
            stopService(Intent(this, com.awu.weaknet.service.FloatingWindowService::class.java))
        } catch (_: Exception) {}

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        Log.i(TAG, "VPN stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WeakNet VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Network simulation is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(profileName: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WeakNetVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WeakNet 运行中")
            .setContentText("当前配置: $profileName")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WeakNetVpnService"
        private const val CHANNEL_ID = "weaknet_vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.awu.weaknet.STOP_VPN"
    }
}
