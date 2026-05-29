/**
 * @author awu
 * @date 2026-05-26
 * @desc 网络配置文件数据模型：包含 12 种预设场景（2G/3G/4G弱/地铁/电梯等）及用户自定义配置
 */
package com.awu.weaknet.data.model

import java.util.UUID

data class NetworkProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "network",
    val isPreset: Boolean = false,
    val condition: NetworkCondition,
    val description: String = "",
) {
    companion object {
        val presets = listOf(
            NetworkProfile(
                id = "preset_none",
                name = "无限制",
                icon = "check",
                isPreset = true,
                condition = NetworkCondition(),
                description = "不施加任何网络限制"
            ),
            NetworkProfile(
                id = "preset_2g",
                name = "2G (EDGE)",
                icon = "2g",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 300, jitterMs = 100, packetLossPercent = 5,
                    uploadSpeedKbps = 30, downloadSpeedKbps = 50
                ),
                description = "经典 2G 网络，极慢"
            ),
            NetworkProfile(
                id = "preset_3g",
                name = "3G (HSPA)",
                icon = "3g",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 100, jitterMs = 40, packetLossPercent = 2,
                    uploadSpeedKbps = 250, downloadSpeedKbps = 750
                ),
                description = "3G 网络，慢但可用"
            ),
            NetworkProfile(
                id = "preset_4g_poor",
                name = "4G (弱信号)",
                icon = "4g",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 50, jitterMs = 20, packetLossPercent = 1,
                    uploadSpeedKbps = 1000, downloadSpeedKbps = 3000
                ),
                description = "4G 弱信号区域"
            ),
            NetworkProfile(
                id = "preset_wifi_poor",
                name = "WiFi (弱)",
                icon = "wifi",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 30, jitterMs = 15, packetLossPercent = 0,
                    uploadSpeedKbps = 2000, downloadSpeedKbps = 5000
                ),
                description = "距离路由器较远的 WiFi"
            ),
            NetworkProfile(
                id = "preset_subway",
                name = "地铁",
                icon = "subway",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 200, jitterMs = 150, packetLossPercent = 8,
                    uploadSpeedKbps = 500, downloadSpeedKbps = 1000,
                    disconnectEnabled = true, disconnectIntervalMs = 30000, disconnectDurationMs = 2000,
                ),
                description = "地铁中网络不稳定，进隧道时周期性断连"
            ),
            NetworkProfile(
                id = "preset_elevator",
                name = "电梯",
                icon = "elevator",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 500, jitterMs = 200, packetLossPercent = 15,
                    uploadSpeedKbps = 50, downloadSpeedKbps = 100,
                    disconnectEnabled = true, disconnectIntervalMs = 20000, disconnectDurationMs = 3000,
                ),
                description = "电梯中信号极差，频繁短暂断连"
            ),
            NetworkProfile(
                id = "preset_high_latency",
                name = "高延迟",
                icon = "latency",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 2000, jitterMs = 500, packetLossPercent = 0
                ),
                description = "模拟卫星网络级别的延迟"
            ),
            NetworkProfile(
                id = "preset_packet_hell",
                name = "丢包地狱",
                icon = "hell",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 100, jitterMs = 50, packetLossPercent = 30,
                    duplicatePercent = 5, reorderBufferSize = 4
                ),
                description = "极端丢包环境，用于测试重连逻辑"
            ),
            NetworkProfile(
                id = "preset_high_speed_rail",
                name = "高铁",
                icon = "rail",
                isPreset = true,
                condition = NetworkCondition(
                    delayMs = 150, jitterMs = 100, packetLossPercent = 5,
                    uploadSpeedKbps = 300, downloadSpeedKbps = 800,
                    disconnectEnabled = true, disconnectIntervalMs = 60000, disconnectDurationMs = 4000,
                ),
                description = "高铁经过偏远地区时周期性断网"
            ),
            NetworkProfile(
                id = "preset_dns_timeout",
                name = "DNS 超时",
                icon = "dns",
                isPreset = true,
                condition = NetworkCondition(
                    dnsFaultType = DnsFaultType.TIMEOUT,
                ),
                description = "DNS 解析超时，模拟 DNS 服务器不可达"
            ),
            NetworkProfile(
                id = "preset_data_corrupt",
                name = "数据校验测试",
                icon = "corrupt",
                isPreset = true,
                condition = NetworkCondition(
                    tamperPercent = 10,
                ),
                description = "10% 数据篡改，用于安全与容错测试"
            ),
        )
    }
}
