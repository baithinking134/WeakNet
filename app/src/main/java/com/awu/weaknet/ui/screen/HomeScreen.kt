/**
 * @author awu
 * @date 2026-05-26
 * @desc 主页面：场景预设选择、自定义参数配置（延迟/丢包/节流/闪断/DNS故障）和 VPN 启停控制
 */
package com.awu.weaknet.ui.screen

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awu.weaknet.PermissionUiState
import com.awu.weaknet.data.model.DelayModel
import com.awu.weaknet.data.model.DnsFaultType
import com.awu.weaknet.data.model.LossModel
import com.awu.weaknet.data.model.NetworkCondition
import com.awu.weaknet.data.model.NetworkProfile
import com.awu.weaknet.service.VpnStateHolder
import com.awu.weaknet.vpn.WeakNetVpnService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun HomeScreen(
    onNavigateProfiles: () -> Unit,
    onNavigateAppSelector: () -> Unit,
    onNavigateProfileEdit: (String?) -> Unit,
    onRequestVpn: () -> Unit,
    permissionState: State<PermissionUiState>,
    onRequestOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val isRunning by VpnStateHolder.isRunning.collectAsState()
    val isPaused by VpnStateHolder.isPaused.collectAsState()
    val activeProfile by VpnStateHolder.activeProfile.collectAsState()
    val stats by VpnStateHolder.stats.collectAsState()
    val selectedApps by VpnStateHolder.selectedApps.collectAsState()

    // Mode: "preset" or "custom"
    var mode by rememberSaveable { mutableStateOf("preset") }
    // Custom condition sliders
    var customDelay by rememberSaveable { mutableStateOf(0f) }
    var customJitter by rememberSaveable { mutableStateOf(0f) }
    var customLoss by rememberSaveable { mutableStateOf(0f) }
    var customUploadKbps by rememberSaveable { mutableStateOf(0f) }
    var customDownloadKbps by rememberSaveable { mutableStateOf(0f) }
    var customDuplicate by rememberSaveable { mutableStateOf(0f) }
    var customReorder by rememberSaveable { mutableStateOf(0f) }
    var customTamper by rememberSaveable { mutableStateOf(0f) }
    // Disconnect params
    var customDisconnectEnabled by rememberSaveable { mutableStateOf(false) }
    var customDisconnectInterval by rememberSaveable { mutableStateOf(30f) }
    var customDisconnectDuration by rememberSaveable { mutableStateOf(2f) }
    // DNS fault params
    var customDnsFaultType by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.name },
            restore = { try { DnsFaultType.valueOf(it) } catch (_: Exception) { DnsFaultType.NONE } }
        )
    ) { mutableStateOf(DnsFaultType.NONE) }
    var customDnsHijackIp by rememberSaveable { mutableStateOf("1.2.3.4") }
    val globalDelayModel by VpnStateHolder.delayModel.collectAsState()
    val globalLossModel by VpnStateHolder.lossModel.collectAsState()

    // 切换到 custom 模式时立即推送配置，避免 debounce 延迟导致启动时使用旧 preset
    LaunchedEffect(mode) {
        if (mode == "custom") {
            VpnStateHolder.setActiveProfile(
                NetworkProfile(
                    id = "custom",
                    name = "自定义",
                    isPreset = false,
                    condition = NetworkCondition(
                        delayMs = customDelay.toInt(),
                        jitterMs = customJitter.toInt(),
                        packetLossPercent = customLoss.toInt(),
                        uploadSpeedKbps = customUploadKbps.toInt(),
                        downloadSpeedKbps = customDownloadKbps.toInt(),
                        duplicatePercent = customDuplicate.toInt(),
                        reorderBufferSize = customReorder.toInt(),
                        tamperPercent = customTamper.toInt(),
                        disconnectEnabled = customDisconnectEnabled,
                        disconnectIntervalMs = kotlin.math.round(customDisconnectInterval * 1000).toInt(),
                        disconnectDurationMs = kotlin.math.round(customDisconnectDuration * 1000).toInt(),
                        dnsFaultType = customDnsFaultType,
                        dnsHijackIp = customDnsHijackIp,
                    ),
                    description = "自定义网络参数",
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            if (mode == "custom") Pair(
                mode,
                NetworkCondition(
                    delayMs = customDelay.toInt(),
                    jitterMs = customJitter.toInt(),
                    packetLossPercent = customLoss.toInt(),
                    uploadSpeedKbps = customUploadKbps.toInt(),
                    downloadSpeedKbps = customDownloadKbps.toInt(),
                    duplicatePercent = customDuplicate.toInt(),
                    reorderBufferSize = customReorder.toInt(),
                    tamperPercent = customTamper.toInt(),
                    disconnectEnabled = customDisconnectEnabled,
                    disconnectIntervalMs = kotlin.math.round(customDisconnectInterval * 1000).toInt(),
                    disconnectDurationMs = kotlin.math.round(customDisconnectDuration * 1000).toInt(),
                    dnsFaultType = customDnsFaultType,
                    dnsHijackIp = customDnsHijackIp,
                )
            ) else null
        }.debounce(300).collect { pair ->
            if (pair != null && pair.first == "custom" && mode == "custom") {
                VpnStateHolder.setActiveProfile(
                    NetworkProfile(
                        id = "custom",
                        name = "自定义",
                        isPreset = false,
                        condition = pair.second,
                        description = "自定义网络参数",
                    )
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text("WeakNet", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("弱网模拟工具", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(12.dp))

        // ---- Permission banner ----
        val permState by permissionState
        when (val ps = permState) {
            is PermissionUiState.Denied -> {
                val ctx = LocalContext.current
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("权限未授予", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                            Text(
                                if (ps.permissions.contains("android.permission.POST_NOTIFICATIONS"))
                                    "需要通知权限以保持后台运行"
                                else
                                    "部分功能需要额外权限",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:com.awu.weaknet")
                            )
                            ctx.startActivity(intent)
                        }) {
                            Text("去设置", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            is PermissionUiState.Requesting -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Spacer(modifier = Modifier.height(8.dp))
            }
            else -> { /* Granted or Idle, no banner */ }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- Mode tabs ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == "preset",
                onClick = { mode = "preset" },
                label = { Text("场景预设", fontSize = 13.sp) },
                modifier = Modifier.weight(1f).height(40.dp),
            )
            FilterChip(
                selected = mode == "custom",
                onClick = { mode = "custom" },
                label = { Text("自定义配置", fontSize = 13.sp) },
                modifier = Modifier.weight(1f).height(40.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 延迟模型（全局设置，影响所有配置） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("延迟模型", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = globalDelayModel == DelayModel.UNIFORM,
                    onClick = { VpnStateHolder.setDelayModel(DelayModel.UNIFORM) },
                    label = { Text("均匀", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = globalDelayModel == DelayModel.GAUSSIAN,
                    onClick = { VpnStateHolder.setDelayModel(DelayModel.GAUSSIAN) },
                    label = { Text("高斯", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = globalDelayModel == DelayModel.LONG_TAIL,
                    onClick = { VpnStateHolder.setDelayModel(DelayModel.LONG_TAIL) },
                    label = { Text("长尾", fontSize = 12.sp) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- 丢包模型（全局设置） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("丢包模型", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = globalLossModel == LossModel.RANDOM,
                    onClick = { VpnStateHolder.setLossModel(LossModel.RANDOM) },
                    label = { Text("随机", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = globalLossModel == LossModel.BURST,
                    onClick = { VpnStateHolder.setLossModel(LossModel.BURST) },
                    label = { Text("突发", fontSize = 12.sp) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Preset mode ----
        AnimatedVisibility(visible = mode == "preset") {
            Column {
                Text("选择弱网场景", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))

                // Grid of preset cards (2 columns)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkProfile.presets.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { profile ->
                                PresetCard(
                                    profile = profile,
                                    isSelected = activeProfile?.id == profile.id,
                                    onClick = {
                                        mode = "preset"
                                        VpnStateHolder.setActiveProfile(profile)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Fill remaining space if odd number
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ---- Custom mode ----
        AnimatedVisibility(visible = mode == "custom") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自定义网络参数", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                CustomSlider("延迟", customDelay, 0f..5000f, "ms") { customDelay = it }
                CustomSlider("抖动", customJitter, 0f..1000f, "ms") { customJitter = it }
                CustomSlider("丢包率", customLoss, 0f..100f, "%") { customLoss = it }
                CustomSlider("上传速率", customUploadKbps, 0f..10000f, "kbps") { customUploadKbps = it }
                CustomSlider("下载速率", customDownloadKbps, 0f..100000f, "kbps") { customDownloadKbps = it }
                CustomSlider("重发率", customDuplicate, 0f..50f, "%") { customDuplicate = it }
                CustomSlider("乱序缓冲", customReorder, 0f..20f, "个") { customReorder = it }
                CustomSlider("篡改率", customTamper, 0f..50f, "%") { customTamper = it }

                // ---- 网络闪断 ----
                Text("网络闪断", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用闪断", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = customDisconnectEnabled,
                        onCheckedChange = { customDisconnectEnabled = it },
                    )
                }
                if (customDisconnectEnabled) {
                    CustomSlider("闪断间隔", customDisconnectInterval, 5f..120f, "秒") { customDisconnectInterval = it }
                    CustomSlider("闪断时长", customDisconnectDuration, 0.5f..10f, "秒") { customDisconnectDuration = it }
                }

                // ---- DNS 故障 ----
                Text("DNS 故障", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("故障类型", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DnsFaultType.entries.forEach { type ->
                            FilterChip(
                                selected = customDnsFaultType == type,
                                onClick = { customDnsFaultType = type },
                                label = { Text(type.label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
                if (customDnsFaultType == DnsFaultType.HIJACK) {
                    val ipValid = customDnsHijackIp.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) &&
                            customDnsHijackIp.split(".").all { it.toIntOrNull() in 0..255 }
                    OutlinedTextField(
                        value = customDnsHijackIp,
                        onValueChange = { customDnsHijackIp = it },
                        label = { Text("劫持 IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = customDnsHijackIp.isNotBlank() && !ipValid,
                        supportingText = if (customDnsHijackIp.isNotBlank() && !ipValid)
                            {{ Text("请输入有效的 IPv4 地址") }} else null,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Active config summary ----
        val currentProfile = activeProfile
        if (currentProfile != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentProfile.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        val c = currentProfile.condition
                        val tags = buildList {
                            if (c.delayMs > 0) add("延迟${c.delayMs}ms")
                            if (c.packetLossPercent > 0) add("丢包${c.packetLossPercent}%")
                            if (c.uploadSpeedKbps > 0) add("↑${c.uploadSpeedKbps}k")
                            if (c.downloadSpeedKbps > 0) add("↓${c.downloadSpeedKbps}k")
                            if (c.disconnectEnabled) add("闪断${c.disconnectIntervalMs/1000}s/${c.disconnectDurationMs/1000}s")
                            if (c.dnsFaultType != DnsFaultType.NONE) add("DNS:${c.dnsFaultType.name}")
                        }
                        if (tags.isEmpty()) {
                            Text("无限制", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        } else {
                            Text(tags.joinToString(" "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (isRunning) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.error,
                        ) {
                            Text("运行中", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Main toggle button ----
        Button(
            onClick = {
                if (isRunning) {
                    val stopIntent = Intent(context, WeakNetVpnService::class.java).apply {
                        action = WeakNetVpnService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    if (activeProfile != null) onRequestVpn()
                }
            },
            modifier = Modifier.size(130.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isRunning && isPaused -> MaterialTheme.colorScheme.tertiary
                    isRunning -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            ),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    when {
                        isRunning && isPaused -> "▶"
                        isRunning -> "⏸"
                        else -> "▶"
                    }, fontSize = 26.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    when {
                        isRunning && isPaused -> "已暂停"
                        isRunning -> "运行中"
                        else -> "启动"
                    }, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
        }

        // ---- Running status ----
        if (isRunning) {
            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SpeedColumn("上传 ↑", stats.uploadSpeedBps, MaterialTheme.colorScheme.primary)
                    Box(modifier = Modifier.width(1.dp).height(36.dp)
                        .align(Alignment.CenterVertically))
                    SpeedColumn("下载 ↓", stats.downloadSpeedBps, MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { VpnStateHolder.setPaused(!isPaused) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (isPaused) "▶ 恢复" else "⏸ 暂停", fontSize = 14.sp) }
                Button(
                    onClick = {
                        val stopIntent = Intent(context, WeakNetVpnService::class.java).apply {
                            action = WeakNetVpnService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("■ 停止", fontSize = 14.sp) }
            }

            Text(
                "TCP: ${stats.activeTcpSessions} | UDP: ${stats.activeUdpSessions} | 包: ${stats.totalPacketsSent + stats.totalPacketsReceived}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Bottom nav ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onNavigateProfiles, modifier = Modifier.weight(1f)) {
                Text("配置列表", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onNavigateAppSelector, modifier = Modifier.weight(1f)) {
                Text(
                    if (selectedApps.isNotEmpty()) "应用(${selectedApps.size})" else "应用选择",
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    profile: NetworkProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .wrapContentHeight()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                profile.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                profile.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CustomSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value <= 0) "关闭" else "${value.toInt()} $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (value <= 0) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

@Composable
private fun SpeedColumn(label: String, bps: Long, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatSpeed(bps), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun formatSpeed(bps: Long): String {
    return when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }
}
