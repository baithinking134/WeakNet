/**
 * @author awu
 * @date 2026-05-26
 * @desc 网络条件数据模型：定义延迟、丢包、节流、重发、乱序、篡改、闪断、DNS 故障等参数
 */
package com.awu.weaknet.data.model

enum class DelayModel { UNIFORM, GAUSSIAN, LONG_TAIL }
enum class LossModel { RANDOM, BURST }
enum class DnsFaultType(val label: String) {
    NONE("正常"), TIMEOUT("超时"), FAILURE("失败"), HIJACK("劫持")
}

data class NetworkCondition(
    val delayMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val downloadSpeedKbps: Int = 0,
    val duplicatePercent: Int = 0,
    val reorderBufferSize: Int = 0,
    val tamperPercent: Int = 0,
    // Network disconnect
    val disconnectEnabled: Boolean = false,
    val disconnectIntervalMs: Int = 30000,
    val disconnectDurationMs: Int = 2000,
    // DNS fault
    val dnsFaultType: DnsFaultType = DnsFaultType.NONE,
    val dnsHijackIp: String = "1.2.3.4",
) {
    // 保证 duration < interval，防止 DisconnectScheduler require 崩溃
    val safeDisconnectDurationMs: Int
        get() = if (disconnectIntervalMs > 0) disconnectDurationMs.coerceAtMost(disconnectIntervalMs - 1000).coerceAtLeast(0) else 0
}
