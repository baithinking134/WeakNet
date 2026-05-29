/**
 * @author awu
 * @date 2026-05-26
 * @desc 包操控器接口：定义出/入方向的数据包操控方法
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.vpn.packet.IpPacket

interface PacketManipulator {
    fun manipulateOutgoing(packet: IpPacket): List<IpPacket>
    fun manipulateIncoming(packet: IpPacket): List<IpPacket>
}
