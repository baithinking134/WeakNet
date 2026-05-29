/**
 * @author awu
 * @date 2026-05-26
 * @desc 上游数据读取器：轮询所有 UDP 会话读取上游响应数据
 */
package com.awu.weaknet.vpn.engine

import android.util.Log
import com.awu.weaknet.util.ByteUtils
import com.awu.weaknet.vpn.nat.UdpSessionManager
import java.nio.ByteBuffer

/**
 * 上游数据读取器：轮询所有 UDP 会话读取上游响应。
 * 只处理 UDP，TCP 由每个连接的独立 relay 线程负责读取。
 */
class UpstreamReader(
    private val udpSessionManager: UdpSessionManager,
    private val packetProcessor: PacketProcessor,
) {
    @Volatile
    var running = false
        private set

    // 复用同一个 buffer 避免每次轮询分配内存造成 GC 压力
    private val readBuffer = ByteBuffer.allocate(16384)

    fun poll() {
        if (!running) return

        // 只轮询 UDP，TCP 由 per-connection 的 relay 线程各自阻塞读取
        for (session in udpSessionManager.allSessions()) {
            try {
                readBuffer.clear()
                val bytesRead = session.channel.read(readBuffer)
                if (bytesRead > 0) {
                    readBuffer.flip()
                    val data = ByteArray(bytesRead)
                    readBuffer.get(data)
                    packetProcessor.handleUdpResponse(session, data, bytesRead)
                    session.lastActivityTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // 读取异常说明 channel 已关闭或不可用，移除会话防止持续报错
                Log.d(TAG, "UDP read error, removing session: ${e.message}")
                udpSessionManager.removeSession(
                    ByteUtils.ipAddressToString(session.sourceIp), session.sourcePort,
                    ByteUtils.ipAddressToString(session.destIp), session.destPort)
            }
        }
    }

    fun start() { running = true }
    fun stop() { running = false }

    companion object {
        private const val TAG = "UpstreamReader"
    }
}
