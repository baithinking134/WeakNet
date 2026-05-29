/**
 * @author awu
 * @date 2026-05-26
 * @desc UDP 头部解析器：从原始字节数组中解析端口号、长度等字段
 */
package com.awu.weaknet.vpn.packet

import com.awu.weaknet.util.ByteUtils

class UdpHeader(private val buffer: ByteArray, private val offset: Int = 0) {

    init {
        require(buffer.size >= offset + 8) { "UdpHeader: buffer too small (size=${buffer.size}, offset=$offset)" }
    }

    val sourcePort: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset)

    val destinationPort: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 2)

    val length: Int
        get() = ByteUtils.byteArrayToShort(buffer, offset + 4).coerceAtMost(buffer.size - offset)

    val headerLength: Int
        get() = 8

    val payloadLength: Int
        get() = (length - 8).coerceAtLeast(0)
}
