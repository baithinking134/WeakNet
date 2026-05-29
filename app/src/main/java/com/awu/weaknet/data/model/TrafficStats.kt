/**
 * @author awu
 * @date 2026-05-26
 * @desc 流量统计数据模型：记录上下行速率、包数量和活跃会话数
 */
package com.awu.weaknet.data.model

data class TrafficStats(
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val totalPacketsSent: Long = 0,
    val totalPacketsReceived: Long = 0,
    val uploadSpeedBps: Long = 0,
    val downloadSpeedBps: Long = 0,
    val activeTcpSessions: Int = 0,
    val activeUdpSessions: Int = 0,
)
