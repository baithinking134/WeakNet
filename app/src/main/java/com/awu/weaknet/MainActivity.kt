/**
 * @author awu
 * @date 2026-05-26
 * @desc 应用主 Activity：管理 VPN 权限、通知权限、悬浮窗权限的申请流程和 Compose 导航
 */
package com.awu.weaknet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.awu.weaknet.ui.navigation.AppNavigation
import com.awu.weaknet.ui.theme.WeakNetTheme
import com.awu.weaknet.vpn.WeakNetVpnService

class MainActivity : ComponentActivity() {

    // Permission state observable for Compose
    private val permissionState = mutableStateOf<PermissionUiState>(PermissionUiState.Idle)
    private val composePermissionState: State<PermissionUiState> get() = permissionState

    // VPN permission
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能工作", Toast.LENGTH_SHORT).show()
        }
    }

    // Runtime permissions (POST_NOTIFICATIONS on Android 13+)
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.all { it.value }
        if (allGranted) {
            permissionState.value = PermissionUiState.Granted
        } else {
            val denied = grants.filter { !it.value }.keys.toList()
            permissionState.value = PermissionUiState.Denied(denied)
        }
    }

    // Track if VPN start was pending after permission grant
    private var vpnStartPending = false

    // Overlay permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkAndStartVpn()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示控制面板", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        vpnStartPending = savedInstanceState?.getBoolean(KEY_VPN_PENDING) ?: false

        setContent {
            WeakNetTheme {
                AppNavigation(
                    onRequestVpn = { checkAndStartVpn() },
                    permissionState = composePermissionState,
                    onRequestOverlay = { requestOverlayPermission() },
                )
            }
        }

        // Request runtime permissions on startup
        requestNeededPermissions()
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VPN_PENDING, vpnStartPending)
    }

    override fun onResume() {
        super.onResume()
        if (vpnStartPending && permissionState.value == PermissionUiState.Granted) {
            vpnStartPending = false
            checkOverlayAndStartVpn()
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        // POST_NOTIFICATIONS required on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionState.value = PermissionUiState.Requesting(needed.toList())
            runtimePermissionLauncher.launch(needed.toTypedArray())
        } else {
            permissionState.value = PermissionUiState.Granted
        }
    }

    private fun checkAndStartVpn() {
        // Step 1: Check runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                vpnStartPending = true
                runtimePermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return
            }
        }

        // Step 2: Check overlay permission
        checkOverlayAndStartVpn()
    }

    private fun checkOverlayAndStartVpn() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        // Step 3: Request VPN permission
        requestVpnPermission()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already prepared
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, WeakNetVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val KEY_VPN_PENDING = "vpn_start_pending"
    }
}

/**
 * Permission UI state exposed to Compose layer.
 */
sealed class PermissionUiState {
    /** No permission request in progress */
    data object Idle : PermissionUiState()
    /** Currently requesting these permissions */
    data class Requesting(val permissions: List<String>) : PermissionUiState()
    /** All runtime permissions granted */
    data object Granted : PermissionUiState()
    /** Some permissions denied */
    data class Denied(val permissions: List<String>) : PermissionUiState()
}
