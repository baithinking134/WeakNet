/**
 * @author awu
 * @date 2026-05-26
 * @desc 应用信息数据模型：封装应用包名、图标和标签，用于按应用选择 VPN 流量
 */
package com.awu.weaknet.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val uid: Int = 0,
    val isSystemApp: Boolean = false,
)
