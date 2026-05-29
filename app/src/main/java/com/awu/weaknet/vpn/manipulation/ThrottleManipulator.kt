/**
 * @author awu
 * @date 2026-05-26
 * @desc 节流操控器：基于令牌桶的时间调度法，每会话独立速率控制
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.vpn.packet.IpPacket

/**
 * 节流操控器（纯节流，不含延迟）。
 * 延迟由 PacketProcessor 在 SYN-ACK 和 ICMP 回复中单独施加。
 *
 * 每个会话（TCP 连接 / UDP 会话）拥有独立的速率控制器，
 * 避免多连接共享同一令牌桶导致延迟线性叠加。
 */
class ThrottleManipulator(
    uploadSpeedKbps: Int = 0,
    downloadSpeedKbps: Int = 0,
) : PacketManipulator {

    private val nanosPerByteUpload: Double = if (uploadSpeedKbps > 0)
        1_000_000_000.0 / (uploadSpeedKbps.toLong() * 1000L / 8L) else 0.0
    private val nanosPerByteDownload: Double = if (downloadSpeedKbps > 0)
        1_000_000_000.0 / (downloadSpeedKbps.toLong() * 1000L / 8L) else 0.0

    // 每会话独立速率控制器：key = "srcIp:srcPort-dstIp:dstPort"
    private val uploadControllers = java.util.concurrent.ConcurrentHashMap<String, RateController>()
    private val downloadControllers = java.util.concurrent.ConcurrentHashMap<String, RateController>()

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (nanosPerByteUpload <= 0.0) return listOf(packet)
        val key = sessionKey(packet) ?: return listOf(packet)
        uploadControllers.getOrPut(key) { RateController(nanosPerByteUpload) }
            .acquire(packet.rawBytes.size)
        return listOf(packet)
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (nanosPerByteDownload <= 0.0) return listOf(packet)
        val key = sessionKey(packet) ?: return listOf(packet)
        downloadControllers.getOrPut(key) { RateController(nanosPerByteDownload) }
            .acquire(packet.rawBytes.size)
        return listOf(packet)
    }

    private fun sessionKey(packet: IpPacket): String? {
        val ip = packet.ipHeader
        val tcp = packet.tcpHeader
        val udp = packet.udpHeader
        return when {
            tcp != null -> "${ip.sourceAddressString}:${tcp.sourcePort}-${ip.destinationAddressString}:${tcp.destinationPort}"
            udp != null -> "${ip.sourceAddressString}:${udp.sourcePort}-${ip.destinationAddressString}:${udp.destinationPort}"
            else -> null
        }
    }

    private class RateController(private val nanosPerByte: Double) {
        private var nextSendTimeNanos: Long = System.nanoTime()

        fun acquire(bytes: Int) {
            val waitNs: Long = synchronized(this) {
                val now = System.nanoTime()
                val sendAt = maxOf(nextSendTimeNanos, now)
                nextSendTimeNanos = sendAt + (bytes * nanosPerByte).toLong()
                sendAt - now
            }
            if (waitNs > 0) sleepNanos(waitNs)
        }

        private fun sleepNanos(ns: Long) {
            if (ns <= 0) return
            val ms = ns / 1_000_000L
            val extra = (ns % 1_000_000L).toInt()
            try {
                Thread.sleep(ms, extra)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
