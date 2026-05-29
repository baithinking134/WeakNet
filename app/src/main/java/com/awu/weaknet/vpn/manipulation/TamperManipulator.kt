/**
 * @author awu
 * @date 2026-05-26
 * @desc 篡改操控器：随机修改负载字节并重算校验和，模拟网络传输中的数据损坏
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.util.ByteUtils
import com.awu.weaknet.vpn.packet.IpPacket
import com.awu.weaknet.vpn.packet.TcpHeader
import kotlin.random.Random

/**
 * 篡改操控器：随机修改负载字节并重算校验和，模拟网络传输中的数据损坏。
 */
class TamperManipulator(
    private val tamperPercent: Int = 0,
) : PacketManipulator {

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (tamperPercent <= 0) return listOf(packet)
        // TCP 数据不走篡改：VPN 已提前 ACK，篡改会导致数据损坏且不可恢复
        if (packet.ipHeader.isTcp) return listOf(packet)
        return if (Random.nextInt(100) < tamperPercent) {
            listOf(tamperPacket(packet))
        } else {
            listOf(packet)
        }
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (tamperPercent <= 0) return listOf(packet)
        // TCP 入方向同样不篡改：数据已从上游 socket 读出，篡改等于永久损坏
        if (packet.ipHeader.isTcp) return listOf(packet)
        return if (Random.nextInt(100) < tamperPercent) {
            listOf(tamperPacket(packet))
        } else {
            listOf(packet)
        }
    }

    private fun tamperPacket(packet: IpPacket): IpPacket {
        val bytes = packet.rawBytes.copyOf()
        val ipHeaderLen = packet.ipHeader.headerLength
        val transportHeaderLen = when {
            packet.ipHeader.isTcp -> packet.tcpHeader?.headerLength ?: 20
            packet.ipHeader.isUdp -> 8
            else -> return packet
        }
        val payloadStart = ipHeaderLen + transportHeaderLen
        val payloadEnd = packet.ipHeader.totalLength

        if (payloadEnd <= payloadStart) return packet

        val tamperOffset = payloadStart + Random.nextInt(payloadEnd - payloadStart)
        bytes[tamperOffset] = (bytes[tamperOffset].toInt() xor Random.nextInt(1, 256)).toByte()

        // 篡改后必须重算 IP + 传输层校验和，否则接收端会直接丢弃
        updateChecksums(bytes, packet)
        return packet.withRawBytes(bytes)
    }

    private fun updateChecksums(bytes: ByteArray, original: IpPacket) {
        ByteUtils.updateChecksum(bytes, 10, 0, original.ipHeader.headerLength)
        when {
            original.ipHeader.isTcp -> {
                val tcpOffset = original.ipHeader.headerLength
                val tcpPayloadLen = original.ipHeader.totalLength - tcpOffset
                // 清零旧 TCP checksum 再复制，否则校验和计算错误
                bytes[tcpOffset + 16] = 0
                bytes[tcpOffset + 17] = 0
                val srcIp = ByteArray(4)
                val dstIp = ByteArray(4)
                System.arraycopy(bytes, 12, srcIp, 0, 4)
                System.arraycopy(bytes, 16, dstIp, 0, 4)
                val pseudoHeader = ByteArray(12)
                System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
                System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
                pseudoHeader[9] = 6 // TCP protocol
                pseudoHeader[10] = (tcpPayloadLen shr 8).toByte()
                pseudoHeader[11] = tcpPayloadLen.toByte()
                val combined = ByteArray(12 + tcpPayloadLen)
                System.arraycopy(pseudoHeader, 0, combined, 0, 12)
                System.arraycopy(bytes, tcpOffset, combined, 12, tcpPayloadLen)
                val checksum = ByteUtils.calculateChecksum(combined, 0, combined.size)
                bytes[tcpOffset + 16] = (checksum shr 8).toByte()
                bytes[tcpOffset + 17] = checksum.toByte()
            }
            original.ipHeader.isUdp -> {
                val udpOffset = original.ipHeader.headerLength
                val udpLen = original.ipHeader.totalLength - udpOffset
                // 清零旧 UDP checksum 再复制
                bytes[udpOffset + 6] = 0
                bytes[udpOffset + 7] = 0
                val srcIp = ByteArray(4)
                val dstIp = ByteArray(4)
                System.arraycopy(bytes, 12, srcIp, 0, 4)
                System.arraycopy(bytes, 16, dstIp, 0, 4)
                val pseudoHeader = ByteArray(12)
                System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
                System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
                pseudoHeader[9] = 17 // UDP protocol
                pseudoHeader[10] = (udpLen shr 8).toByte()
                pseudoHeader[11] = udpLen.toByte()
                val combined = ByteArray(12 + udpLen)
                System.arraycopy(pseudoHeader, 0, combined, 0, 12)
                System.arraycopy(bytes, udpOffset, combined, 12, udpLen)
                val checksum = ByteUtils.calculateChecksum(combined, 0, combined.size)
                bytes[udpOffset + 6] = (checksum shr 8).toByte()
                bytes[udpOffset + 7] = checksum.toByte()
            }
        }
    }
}
