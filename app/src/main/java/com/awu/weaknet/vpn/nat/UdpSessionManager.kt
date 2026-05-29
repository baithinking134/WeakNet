/**
 * @author awu
 * @date 2026-05-26
 * @desc UDP 会话管理器：基于四元组管理无状态的 UDP 会话，配合 UpstreamReader 轮询读取响应
 */
package com.awu.weaknet.vpn.nat

import android.net.VpnService
import android.util.Log
import com.awu.weaknet.util.ByteUtils
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 会话管理器：基于四元组管理无状态的 UDP 会话，配合 UpstreamReader 轮询读取响应。
 */
class UdpSessionManager(private val vpnService: VpnService) {

    class UdpSession(
        val channel: DatagramChannel,
        sourceIp: ByteArray,
        val sourcePort: Int,
        destIp: ByteArray,
        val destPort: Int,
        @Volatile var lastActivityTime: Long = System.currentTimeMillis(),
    ) {
        val sourceIp: ByteArray = sourceIp.copyOf()
        val destIp: ByteArray = destIp.copyOf()
    }

    private val sessions = ConcurrentHashMap<String, UdpSession>()
    private val timeoutMs = 30000L

    fun getOrCreateSession(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int
    ): UdpSession? {
        val srcIpStr = ByteUtils.ipAddressToString(srcIp)
        val dstIpStr = ByteUtils.ipAddressToString(dstIp)
        val key = buildKey(srcIpStr, srcPort, dstIpStr, dstPort)

        sessions[key]?.let {
            it.lastActivityTime = System.currentTimeMillis()
            return it
        }

        return synchronized(this) {
            // Double-check after acquiring lock
            sessions[key]?.let {
                it.lastActivityTime = System.currentTimeMillis()
                return it
            }

            var channel: DatagramChannel? = null
            try {
                channel = DatagramChannel.open()
                channel.configureBlocking(false)
                if (!vpnService.protect(channel.socket())) {
                    Log.e(TAG, "protect() FAILED for UDP $dstIpStr:$dstPort")
                    channel.close()
                    return null
                }
                channel.connect(InetSocketAddress(dstIpStr, dstPort))
                Log.d(TAG, "UDP session created: $srcIpStr:$srcPort -> $dstIpStr:$dstPort")
                val session = UdpSession(channel, srcIp, srcPort, dstIp, dstPort)
                sessions[key] = session
                session
            } catch (e: Exception) {
                try { channel?.close() } catch (_: Exception) {}
                Log.e(TAG, "Failed to create UDP session: ${e.message}")
                null
            }
        }
    }

    fun removeSession(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) {
        synchronized(this) {
            sessions.remove(buildKey(srcIp, srcPort, dstIp, dstPort))?.channel?.close()
        }
    }

    fun cleanupExpired() {
        synchronized(this) {
            sessions.entries.removeIf { entry ->
                val expired = System.currentTimeMillis() - entry.value.lastActivityTime > timeoutMs
                if (expired) try { entry.value.channel.close() } catch (_: Exception) {}
                expired
            }
        }
    }

    fun allSessions(): List<UdpSession> = sessions.values.toList()
    val activeCount: Int get() = sessions.size

    private fun buildKey(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) =
        "$srcIp:$srcPort->$dstIp:$dstPort"

    companion object {
        private const val TAG = "NL-Udp"
    }
}
