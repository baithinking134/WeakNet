/**
 * @author awu
 * @date 2026-05-26
 * @desc 重复包操控器：按概率复制数据包，模拟网络中常见的重复传输现象
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.vpn.packet.IpPacket
import kotlin.random.Random

/**
 * 重复包操控器：按概率复制数据包，模拟网络中常见的重复传输现象。
 */
class DuplicateManipulator(
    private val duplicatePercent: Int = 0,
) : PacketManipulator {

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (duplicatePercent <= 0) return listOf(packet)
        return if (Random.nextInt(100) < duplicatePercent) {
            listOf(packet, packet.copy())
        } else {
            listOf(packet)
        }
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (duplicatePercent <= 0) return listOf(packet)
        return if (Random.nextInt(100) < duplicatePercent) {
            listOf(packet, packet.copy())
        } else {
            listOf(packet)
        }
    }
}
