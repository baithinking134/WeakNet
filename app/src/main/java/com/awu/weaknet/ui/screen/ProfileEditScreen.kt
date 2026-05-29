/**
 * @author awu
 * @date 2026-05-26
 * @desc 配置编辑页面：新建或编辑自定义网络配置文件，支持全部网络参数的滑块调节
 */
package com.awu.weaknet.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.awu.weaknet.data.model.DnsFaultType
import com.awu.weaknet.data.model.NetworkCondition
import com.awu.weaknet.data.model.NetworkProfile
import androidx.compose.ui.unit.sp

@Composable
fun ProfileEditScreen(
    profileId: String?,
    existingProfile: NetworkProfile?,
    onSave: suspend (NetworkProfile) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = existingProfile?.condition
    var name by rememberSaveable { mutableStateOf(existingProfile?.name ?: "") }
    var delayMs by rememberSaveable { mutableStateOf((c?.delayMs ?: 0).toFloat()) }
    var jitterMs by rememberSaveable { mutableStateOf((c?.jitterMs ?: 0).toFloat()) }
    var packetLossPercent by rememberSaveable { mutableStateOf((c?.packetLossPercent ?: 0).toFloat()) }
    var uploadSpeedKbps by rememberSaveable { mutableStateOf((c?.uploadSpeedKbps ?: 0).toFloat()) }
    var downloadSpeedKbps by rememberSaveable { mutableStateOf((c?.downloadSpeedKbps ?: 0).toFloat()) }
    var duplicatePercent by rememberSaveable { mutableStateOf((c?.duplicatePercent ?: 0).toFloat()) }
    var reorderBufferSize by rememberSaveable { mutableStateOf((c?.reorderBufferSize ?: 0).toFloat()) }
    var tamperPercent by rememberSaveable { mutableStateOf((c?.tamperPercent ?: 0).toFloat()) }
    var disconnectEnabled by rememberSaveable { mutableStateOf(c?.disconnectEnabled ?: false) }
    var disconnectInterval by rememberSaveable { mutableStateOf((c?.disconnectIntervalMs ?: 30000) / 1000f) }
    var disconnectDuration by rememberSaveable { mutableStateOf((c?.disconnectDurationMs ?: 2000) / 1000f) }
    var dnsFaultType by rememberSaveable(
        stateSaver = Saver<DnsFaultType, String>(
            save = { it.name },
            restore = { try { DnsFaultType.valueOf(it) } catch (_: Exception) { DnsFaultType.NONE } }
        )
    ) { mutableStateOf(c?.dnsFaultType ?: DnsFaultType.NONE) }
    var dnsHijackIp by rememberSaveable { mutableStateOf(c?.dnsHijackIp ?: "1.2.3.4") }

    val ipValid = dnsFaultType != DnsFaultType.HIJACK ||
        (dnsHijackIp.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) &&
         dnsHijackIp.split(".").all { it.toIntOrNull() in 0..255 })

    Column(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onNavigateBack) {
                Text("← 返回")
            }
            Text(
                if (profileId == null) "新建配置" else "编辑配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("配置名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text("延迟设置", style = MaterialTheme.typography.titleMedium)
            SliderControl(label = "延迟", value = delayMs, valueRange = 0f..5000f, unit = "ms", onValueChange = { delayMs = it })
            SliderControl(label = "抖动", value = jitterMs, valueRange = 0f..1000f, unit = "ms", onValueChange = { jitterMs = it })

            Text("丢包 & 重发", style = MaterialTheme.typography.titleMedium)
            SliderControl(label = "丢包率", value = packetLossPercent, valueRange = 0f..100f, unit = "%", onValueChange = { packetLossPercent = it })
            SliderControl(label = "重发率", value = duplicatePercent, valueRange = 0f..50f, unit = "%", onValueChange = { duplicatePercent = it })

            Text("速率限制", style = MaterialTheme.typography.titleMedium)
            SliderControl(label = "上传速率", value = uploadSpeedKbps, valueRange = 0f..10000f, unit = "kbps", onValueChange = { uploadSpeedKbps = it })
            SliderControl(label = "下载速率", value = downloadSpeedKbps, valueRange = 0f..100000f, unit = "kbps", onValueChange = { downloadSpeedKbps = it })

            Text("高级", style = MaterialTheme.typography.titleMedium)
            SliderControl(label = "乱序缓冲", value = reorderBufferSize, valueRange = 0f..20f, unit = "个", onValueChange = { reorderBufferSize = it })
            SliderControl(label = "篡改率", value = tamperPercent, valueRange = 0f..50f, unit = "%", onValueChange = { tamperPercent = it })

            Text("网络闪断", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用闪断", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = disconnectEnabled, onCheckedChange = { disconnectEnabled = it })
            }
            if (disconnectEnabled) {
                SliderControl(label = "闪断间隔", value = disconnectInterval, valueRange = 5f..120f, unit = "秒", onValueChange = { disconnectInterval = it })
                SliderControl(label = "闪断时长", value = disconnectDuration, valueRange = 0.5f..10f, unit = "秒", onValueChange = { disconnectDuration = it })
            }

            Text("DNS 故障", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("故障类型", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DnsFaultType.entries.forEach { type ->
                        FilterChip(
                            selected = dnsFaultType == type,
                            onClick = { dnsFaultType = type },
                            label = { Text(type.label, fontSize = 11.sp) },
                        )
                    }
                }
            }
            if (dnsFaultType == DnsFaultType.HIJACK) {
                OutlinedTextField(
                    value = dnsHijackIp,
                    onValueChange = { dnsHijackIp = it },
                    label = { Text("劫持 IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = dnsHijackIp.isNotBlank() && !ipValid,
                    supportingText = if (dnsHijackIp.isNotBlank() && !ipValid)
                        {{ Text("请输入有效的 IPv4 地址") }} else null,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val condition = NetworkCondition(
                        delayMs = delayMs.toInt(),
                        jitterMs = jitterMs.toInt(),
                        packetLossPercent = packetLossPercent.toInt(),
                        uploadSpeedKbps = uploadSpeedKbps.toInt(),
                        downloadSpeedKbps = downloadSpeedKbps.toInt(),
                        duplicatePercent = duplicatePercent.toInt(),
                        reorderBufferSize = reorderBufferSize.toInt(),
                        tamperPercent = tamperPercent.toInt(),
                        disconnectEnabled = disconnectEnabled,
                        disconnectIntervalMs = kotlin.math.round(disconnectInterval * 1000).toInt(),
                        disconnectDurationMs = kotlin.math.round(disconnectDuration * 1000).toInt(),
                        dnsFaultType = dnsFaultType,
                        dnsHijackIp = dnsHijackIp,
                    )
                    val profile = if (existingProfile != null) {
                        existingProfile.copy(name = name, condition = condition)
                    } else {
                        NetworkProfile(name = name, condition = condition)
                    }
                    scope.launch {
                        try {
                            onSave(profile)
                            onNavigateBack()
                        } catch (e: Exception) {
                            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = name.isNotBlank() && ipValid,
            ) {
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun SliderControl(
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
                "${value.toInt()} $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
