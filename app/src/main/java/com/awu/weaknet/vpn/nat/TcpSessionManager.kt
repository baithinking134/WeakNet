/**
 * @author awu
 * @date 2026-05-26
 * @desc TCP 会话管理器：基于四元组管理所有活跃的 TCP 会话，负责创建、查找和过期清理
 */
package com.awu.weaknet.vpn.nat

import android.net.VpnService
import com.awu.weaknet.util.ByteUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP 会话管理器：基于四元组（srcIp:srcPort -> dstIp:dstPort）管理所有活跃的 TCP 会话。
 */
class TcpSessionManager(private val vpnService: VpnService) {

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val idGen = AtomicLong(0)

    private val lock = Any()

    fun getSession(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): TcpSession? {
        return sessions[buildKey(srcIp, srcPort, dstIp, dstPort)]
    }

    fun createSession(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int,
                      clientSeq: Int): TcpSession {
        val key = buildKey(ByteUtils.ipAddressToString(srcIp), srcPort,
            ByteUtils.ipAddressToString(dstIp), dstPort)
        synchronized(lock) {
            sessions.remove(key)?.close()
            val session = TcpSession(
                id = idGen.incrementAndGet(),
                sourceIp = srcIp, sourcePort = srcPort,
                destIp = dstIp, destPort = dstPort,
                vpnService = vpnService,
            )
            session.clientNextSeq = clientSeq + 1
            sessions[key] = session
            return session
        }
    }

    fun removeSession(session: TcpSession) {
        synchronized(lock) {
            session.close()
            val key = buildKey(ByteUtils.ipAddressToString(session.sourceIp), session.sourcePort,
                ByteUtils.ipAddressToString(session.destIp), session.destPort)
            sessions.remove(key)
        }
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { e ->
            if (now - e.value.lastActivityTime > 60000) {
                e.value.close()
                true
            } else false
        }
    }

    fun allSessions(): List<TcpSession> = sessions.values.toList()
    val activeCount: Int get() = sessions.size

    private fun buildKey(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) =
        "$srcIp:$srcPort->$dstIp:$dstPort"
}
