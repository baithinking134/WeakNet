/**
 * @author awu
 * @date 2026-05-26
 * @desc 配置列表页面：展示用户自定义配置文件，支持新增、编辑和删除操作
 */
package com.awu.weaknet.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awu.weaknet.data.model.NetworkProfile
import com.awu.weaknet.data.repository.ProfileRepository
import com.awu.weaknet.service.VpnStateHolder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    repository: ProfileRepository,
    onNavigateBack: () -> Unit,
    onNavigateEdit: (String?) -> Unit,
) {
    val activeProfile by VpnStateHolder.activeProfile.collectAsState()
    val customProfiles by repository.customProfiles.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // Top bar
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
                "网络配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onNavigateEdit(null) }) {
                Text("+ 新建")
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Presets section
            item {
                Text(
                    "预设配置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(NetworkProfile.presets, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = activeProfile?.id == profile.id,
                    onClick = { VpnStateHolder.setActiveProfile(profile) },
                )
            }

            // Custom profiles section
            if (customProfiles.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "自定义配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                items(customProfiles, key = { it.id }) { profile ->
                    CustomProfileCard(
                        profile = profile,
                        isActive = activeProfile?.id == profile.id,
                        onClick = { VpnStateHolder.setActiveProfile(profile) },
                        onEdit = { onNavigateEdit(profile.id) },
                        onDelete = {
                            scope.launch {
                                if (activeProfile?.id == profile.id) {
                                    VpnStateHolder.setActiveProfile(null)
                                }
                                repository.deleteProfile(profile.id)
                            }
                        },
                    )
                }
            }

            // Empty state hint when no custom profiles
            if (customProfiles.isEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "点击右上角 \"+ 新建\" 创建自定义弱网配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: NetworkProfile,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ProfileConditionSummary(profile)
            }
            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "活跃",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomProfileCard(
    profile: NetworkProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ProfileConditionSummary(profile)
            }
            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "活跃",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
            TextButton(onClick = onDelete) {
                Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ProfileConditionSummary(profile: NetworkProfile) {
    val c = profile.condition
    val parts = mutableListOf<String>()
    if (c.delayMs > 0) parts.add("延迟${c.delayMs}ms")
    if (c.jitterMs > 0) parts.add("抖动${c.jitterMs}ms")
    if (c.packetLossPercent > 0) parts.add("丢包${c.packetLossPercent}%")
    if (c.uploadSpeedKbps > 0) parts.add("↑${c.uploadSpeedKbps}kbps")
    if (c.downloadSpeedKbps > 0) parts.add("↓${c.downloadSpeedKbps}kbps")
    if (c.duplicatePercent > 0) parts.add("重发${c.duplicatePercent}%")
    if (c.reorderBufferSize > 0) parts.add("乱序×${c.reorderBufferSize}")
    if (c.tamperPercent > 0) parts.add("篡改${c.tamperPercent}%")
    if (c.disconnectEnabled) parts.add("闪断${c.disconnectIntervalMs / 1000}s/${c.disconnectDurationMs / 1000}s")
    if (c.dnsFaultType != com.awu.weaknet.data.model.DnsFaultType.NONE)
        parts.add("DNS:${c.dnsFaultType.label}")

    if (parts.isEmpty()) {
        Text("无限制", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
    } else {
        Text(
            parts.joinToString(" | "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
