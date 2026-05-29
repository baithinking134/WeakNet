/**
 * @author awu
 * @date 2026-05-26
 * @desc 应用仓库：通过 PackageManager 查询设备已安装应用列表，支持按名称过滤搜索
 */
package com.awu.weaknet.data.repository

import android.content.pm.PackageManager
import com.awu.weaknet.data.model.AppInfo

class AppRepository(private val packageManager: PackageManager) {

    fun getInstalledApps(): List<AppInfo> {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps
            .filter { appInfo ->
                packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = packageManager.getApplicationLabel(appInfo).toString(),
                    icon = appInfo.loadIcon(packageManager),
                    uid = appInfo.uid,
                    isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.appName.lowercase() }
    }
}
