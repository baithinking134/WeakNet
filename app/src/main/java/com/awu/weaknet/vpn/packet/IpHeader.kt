/**
 * @author awu
 * @date 2026-05-26
 * @desc IPv4 头部解析器：从原始字节数组中解析 IP 版本、长度、协议、地址等字段
 */
package com.awu.weaknet.vpn.packet

import com.awu.weaknet.util.ByteUtils
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

class IpHeader(private val buffer: ByteArray, private val offset: Int = 0) {

    init {
        require(buffer.size >= offset + 20) { "IpHeader: buffer too small (size=${buffer.size}, offset=$offset)" }
    }

    val version: Int
        get() = (buffer[offset].toInt() shr 4) and 0x0F

    val ihl: Int
        get() = buffer[offset].toInt() and 0x0F

    val headerLength: Int
        get() = ihl * 4

    val dscp: Int
        get() = (buffer[offset + 1].toInt() shr 2) and 0x3F

    val totalLength: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 2)

    val identification: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 4)

    val flags: Int
        get() = (buffer[offset + 6].toInt() shr 5) and 0x07

    val fragmentOffset: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 6) and 0x1FFF

    val ttl: Int
        get() = buffer[offset + 8].toInt() and 0xFF

    val protocol: Int
        get() = buffer[offset + 9].toInt() and 0xFF

    val sourceAddress: ByteArray
        get() = buffer.copyOfRange(offset + 12, offset + 16)

    val destinationAddress: ByteArray
        get() = buffer.copyOfRange(offset + 16, offset + 20)

    val sourceAddressString: String
        get() = ByteUtils.ipAddressToString(buffer, offset + 12)

    val destinationAddressString: String
        get() = ByteUtils.ipAddressToString(buffer, offset + 16)

    val payloadLength: Int
        get() = (totalLength - headerLength).coerceIn(0, buffer.size - offset - headerLength)

    val isTcp: Boolean
        get() = protocol == PROTOCOL_TCP

    val isUdp: Boolean
        get() = protocol == PROTOCOL_UDP

    val isIcmp: Boolean
        get() = protocol == PROTOCOL_ICMP

    fun copyTo(dest: ByteArray, destOffset: Int = 0) {
        val len = totalLength.coerceAtMost(buffer.size - offset)
        System.arraycopy(buffer, offset, dest, destOffset, len)
    }

    fun rawBytes(): ByteArray {
        val len = totalLength.coerceAtMost(buffer.size - offset)
        return buffer.copyOfRange(offset, offset + len)
    }

    companion object {
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
    }
}
