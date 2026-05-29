/**
 * @author awu
 * @date 2026-05-26
 * @desc 应用选择器页面：展示已安装应用列表，支持搜索过滤和多选，确定哪些应用走 VPN
 */
package com.awu.weaknet.ui.screen

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awu.weaknet.data.model.AppInfo
import com.awu.weaknet.service.VpnStateHolder

@Composable
fun AppSelectorScreen(
    onNavigateBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedApps by VpnStateHolder.selectedApps.collectAsState()
    var usePerApp by remember { mutableStateOf(selectedApps.isNotEmpty()) }
    // VpnStateHolder 重置后 selectedApps 变空，同步 usePerApp
    LaunchedEffect(selectedApps) {
        if (!selectedApps.isNotEmpty() && usePerApp) usePerApp = false
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    AppInfo(
                        packageName = it.packageName,
                        appName = pm.getApplicationLabel(it).toString(),
                        icon = it.loadIcon(pm),
                        uid = it.uid,
                    )
                }
                .sortedBy { it.appName.lowercase() }
            apps = installed
        }
    }

    var showClearDialog by remember { mutableStateOf(false) }

    val filteredApps = if (searchQuery.isBlank()) apps else {
        apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val allFilteredSelected = filteredApps.isNotEmpty() &&
        filteredApps.all { it.packageName in selectedApps }

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
                "应用选择",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (allFilteredSelected) {
                        VpnStateHolder.setSelectedApps(selectedApps - filteredApps.map { it.packageName }.toSet())
                    } else {
                        VpnStateHolder.setSelectedApps(selectedApps + filteredApps.map { it.packageName }.toSet())
                    }
                }
            ) {
                Text(if (allFilteredSelected) "取消全选" else "全选")
            }
        }

        // Mode toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("仅限制选中的应用", modifier = Modifier.weight(1f))
            Switch(
                checked = usePerApp,
                onCheckedChange = { checked ->
                    if (!checked && selectedApps.isNotEmpty()) {
                        showClearDialog = true
                    } else {
                        usePerApp = checked
                    }
                },
            )
        }

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("搜索应用") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            enabled = usePerApp,
        )

        if (!usePerApp) {
            // All apps mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前模式：全部应用", fontWeight = FontWeight.Bold)
                    Text(
                        "所有应用的网络都将被限制",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    Text(
                        "已选择 ${selectedApps.size} 个应用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selectedApps
                    AppItem(
                        app = app,
                        isSelected = isSelected,
                        onToggle = {
                            val newSet = if (isSelected) {
                                selectedApps - app.packageName
                            } else {
                                selectedApps + app.packageName
                            }
                            VpnStateHolder.setSelectedApps(newSet)
                        },
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清除") },
            text = { Text("关闭应用选择将清除已选的 ${selectedApps.size} 个应用，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    usePerApp = false
                    VpnStateHolder.setSelectedApps(emptySet())
                    showClearDialog = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(8.dp))
        // Icon
        app.icon?.let { drawable ->
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.ImageView(ctx).apply {
                        setImageDrawable(drawable)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (36 * resources.displayMetrics.density).toInt(),
                            (36 * resources.displayMetrics.density).toInt(),
                        )
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
