/**
 * @author awu
 * @date 2026-05-26
 * @desc TCP 头部解析器：从原始字节数组中解析端口号、序列号、标志位等字段
 */
package com.awu.weaknet.vpn.packet

import com.awu.weaknet.util.ByteUtils

class TcpHeader(private val buffer: ByteArray, private val offset: Int = 0, ipHeaderLength: Int = 20) {

    init {
        require(buffer.size >= offset + 20) { "TcpHeader: buffer too small (size=${buffer.size}, offset=$offset)" }
    }

    val sourcePort: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset)

    val destinationPort: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 2)

    val sequenceNumber: Int
        get() = ByteUtils.byteArrayToInt(buffer, offset + 4)

    val ackNumber: Int
        get() = ByteUtils.byteArrayToInt(buffer, offset + 8)

    val dataOffset: Int
        get() = (((buffer[offset + 12].toInt() and 0xFF) shr 4) * 4).coerceAtLeast(20)

    val flags: Int
        get() = buffer[offset + 13].toInt() and 0xFF

    val isSyn: Boolean
        get() = (flags and FLAG_SYN) != 0

    val isAck: Boolean
        get() = (flags and FLAG_ACK) != 0

    val isFin: Boolean
        get() = (flags and FLAG_FIN) != 0

    val isRst: Boolean
        get() = (flags and FLAG_RST) != 0

    val isPsh: Boolean
        get() = (flags and FLAG_PSH) != 0

    val windowSize: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 14)

    val headerLength: Int
        get() = dataOffset

    private val ipHeaderLen: Int = ipHeaderLength

    val payloadLength: Int
        get() {
            val ipTotalLength = ByteUtils.byteArrayToShort(buffer, offset - ipHeaderLen + 2)
            return (ipTotalLength - ipHeaderLen - headerLength).coerceAtLeast(0)
        }

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
    }
}
