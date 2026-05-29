/**
 * @author awu
 * @date 2026-05-26
 * @desc 悬浮窗服务：使用 WindowManager + ComposeView 实现可拖动的 VPN 状态控制面板
 */
package com.awu.weaknet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.awu.weaknet.MainActivity
import com.awu.weaknet.R
import com.awu.weaknet.ui.theme.WeakNetTheme
import com.awu.weaknet.vpn.WeakNetVpnService

/**
 * 支持拖动的 FrameLayout。
 * 通过 onInterceptTouchEvent 在检测到拖动手势时拦截触摸事件，
 * 避免 Compose 子视图消费事件导致容器无法拖动。
 */
class DragFrameLayout(context: android.content.Context) : FrameLayout(context) {
    var layoutParamsProvider: (() -> WindowManager.LayoutParams?)? = null
    var windowManagerProvider: (() -> WindowManager?)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParamsProvider?.invoke()?.x ?: 0
                initialY = layoutParamsProvider?.invoke()?.y ?: 0
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - initialTouchX
                val dy = ev.rawY - initialTouchY
                if (dx * dx + dy * dy > 25) {
                    isDragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = layoutParamsProvider?.invoke() ?: return false
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                p.x = initialX + (event.rawX - initialTouchX).toInt()
                p.y = initialY + (event.rawY - initialTouchY).toInt()
                try {
                    windowManagerProvider?.invoke()?.updateViewLayout(this, p)
                } catch (_: Exception) {}
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) performClick()
                isDragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

class FloatingWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            return START_NOT_STICKY
        }
        if (floatingView != null) return START_NOT_STICKY

        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w(TAG, "No overlay permission, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        showFloatingWindow()
        return START_NOT_STICKY
    }

    private fun showFloatingWindow() {
        if (floatingView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = DragFrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
        }

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(composeView)
        floatingView = container

        composeView.setContent {
            WeakNetTheme {
                FloatingWindowContent(
                    onStop = {
                        val stopIntent = Intent(this, WeakNetVpnService::class.java).apply {
                            action = WeakNetVpnService.ACTION_STOP
                        }
                        startService(stopIntent)
                        stopSelf()
                    },
                    onPauseResume = { paused ->
                        VpnStateHolder.setPaused(paused)
                    }
                )
            }
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2 - (90 * resources.displayMetrics.density).toInt()  // Center horizontally
            y = 100
        }

        // Drag handling: 由 DragFrameLayout.onInterceptTouchEvent 处理
        container.layoutParamsProvider = { params }
        container.windowManagerProvider = { windowManager }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to add floating view: ${e.message}")
        }
    }

    private fun removeFloatingWindow() {
        floatingView?.let { container ->
            if (container is android.view.ViewGroup) {
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is ComposeView) {
                        child.disposeComposition()
                    }
                }
            }
            try {
                windowManager?.removeView(container)
            } catch (_: Exception) {}
        }
        floatingView = null
    }

    override fun onDestroy() {
        removeFloatingWindow()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Floating Window",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WeakNet 悬浮窗")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NL-Float"
        private const val CHANNEL_ID = "weaknet_floating"
        private const val NOTIFICATION_ID = 2
    }
}

@Composable
fun FloatingWindowContent(
    onStop: () -> Unit,
    onPauseResume: (Boolean) -> Unit,
) {
    val stats by VpnStateHolder.stats.collectAsState()
    val isPaused by VpnStateHolder.isPaused.collectAsState()
    val profile by VpnStateHolder.activeProfile.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .widthIn(max = if (expanded) 240.dp else 160.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xCC1A1A2E),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(min = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: profile name + expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile?.name ?: "未选择",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(20.dp),
                ) {
                    Text(
                        if (expanded) "▲" else "▼",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            // Speed row: upload | divider | download
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("↑", fontSize = 10.sp, color = Color(0xFF4FC3F7))
                    Text(
                        formatSpeed(stats.uploadSpeedBps),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.2f))
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("↓", fontSize = 10.sp, color = Color(0xFFFF7043))
                    Text(
                        formatSpeed(stats.downloadSpeedBps),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }

            // Expanded controls
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Pause/Resume button
                    Surface(
                        onClick = { onPauseResume(!isPaused) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isPaused) Color(0xFF4FC3F7).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (isPaused) "恢复" else "暂停",
                                fontSize = 11.sp,
                                color = Color.White,
                            )
                        }
                    }

                    // Stop button
                    Surface(
                        onClick = onStop,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFF5252).copy(alpha = 0.3f),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("停止", fontSize = 11.sp, color = Color(0xFFFF5252))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "TCP: ${stats.activeTcpSessions}  UDP: ${stats.activeUdpSessions}",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}

private fun formatSpeed(bps: Long): String {
    return when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }
}
