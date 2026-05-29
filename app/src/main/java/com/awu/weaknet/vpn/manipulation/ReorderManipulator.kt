/**
 * @author awu
 * @date 2026-05-26
 * @desc 乱序操控器：按会话缓冲数据包，用"与前一个包交换"方式模拟网络乱序
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.vpn.packet.IpHeader
import com.awu.weaknet.vpn.packet.IpPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 乱序操控器。
 * 按 5 元组（srcIp, dstIp, srcPort, dstPort, protocol）分 session 缓冲，
 * 用"与前一个包交换"代替缓冲区洗牌。
 */
class ReorderManipulator(
    bufferSize: Int = 0,
) : PacketManipulator {

    private val swapChance = if (bufferSize > 0) (bufferSize.toFloat() / 20f).coerceAtMost(1.0f) else 0f

    private val outgoingBuffers = ConcurrentHashMap<String, IpPacket>()
    private val incomingBuffers = ConcurrentHashMap<String, IpPacket>()

    private fun sessionKey(packet: IpPacket): String {
        val ip = packet.ipHeader
        val port = if (ip.isTcp) packet.tcpHeader?.let { "${it.sourcePort}:${it.destinationPort}" }
            else packet.udpHeader?.let { "${it.sourcePort}:${it.destinationPort}" } ?: ""
        return "${ip.sourceAddressString}:${ip.destinationAddressString}:$port:${ip.protocol}"
    }

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (swapChance <= 0) return listOf(packet)
        // TCP 出方向不重排：ACK 已发，乱序写入上游会损坏 TCP 字节流
        if (packet.ipHeader.isTcp) return listOf(packet)
        val key = sessionKey(packet)
        val last = outgoingBuffers.put(key, packet)
        if (last != null) {
            return if (Random.nextFloat() < swapChance) listOf(packet, last) else listOf(last, packet)
        }
        // 缓冲区溢出保护：超限时移除刚插入的条目，直接发出
        if (outgoingBuffers.size > MAX_BUFFER_ENTRIES) {
            outgoingBuffers.remove(key)
            return listOf(packet)
        }
        // 首包缓冲不发，等下一个包到达时成对发出；flush 时释放剩余缓冲包
        return emptyList()
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (swapChance <= 0) return listOf(packet)
        // TCP 入方向不重排：数据已从上游 socket 读出，缓冲会导致数据永久丢失
        if (packet.ipHeader.isTcp) return listOf(packet)
        val key = sessionKey(packet)
        val last = incomingBuffers.put(key, packet)
        if (last != null) {
            return if (Random.nextFloat() < swapChance) listOf(packet, last) else listOf(last, packet)
        }
        if (incomingBuffers.size > MAX_BUFFER_ENTRIES) {
            incomingBuffers.remove(key)
            return listOf(packet)
        }
        return emptyList()
    }

    /** 原子 drain：逐 key 移除，避免 iterate+clear 竞争丢包。 */
    fun flushOutgoing(): List<IpPacket> = drainAll(outgoingBuffers)

    fun flushIncoming(): List<IpPacket> = drainAll(incomingBuffers)

    /** 只 flush 指定 session key 的缓冲包。 */
    fun flushOutgoing(key: String): List<IpPacket> = drainKey(outgoingBuffers, key)

    fun flushIncoming(key: String): List<IpPacket> = drainKey(incomingBuffers, key)

    private fun drainAll(map: ConcurrentHashMap<String, IpPacket>): List<IpPacket> {
        val result = mutableListOf<IpPacket>()
        val entries = map.entries.toList()
        for (e in entries) {
            // 条件删除：只有值未被修改时才移除，防止新包被误刷
            if (map.remove(e.key, e.value)) {
                result.add(e.value)
            }
        }
        return result
    }

    private fun drainKey(map: ConcurrentHashMap<String, IpPacket>, key: String): List<IpPacket> {
        val packet = map.remove(key) ?: return emptyList()
        return listOf(packet)
    }

    companion object {
        private const val MAX_BUFFER_ENTRIES = 1024
    }
}
