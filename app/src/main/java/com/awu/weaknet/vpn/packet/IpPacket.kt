/**
 * @author awu
 * @date 2026-05-26
 * @desc IP 数据包封装：组合 IP 头部与原始字节，提供 TCP/UDP 头部懒加载解析和载荷提取
 */
package com.awu.weaknet.vpn.packet

data class IpPacket(
    val rawBytes: ByteArray,
    val ipHeader: IpHeader,
) {
    val tcpHeader: TcpHeader?
        get() = if (ipHeader.isTcp) TcpHeader(rawBytes, ipHeader.headerLength, ipHeader.headerLength) else null

    val udpHeader: UdpHeader?
        get() = if (ipHeader.isUdp) UdpHeader(rawBytes, ipHeader.headerLength) else null

    val payload: ByteArray
        get() {
            val transportHeaderLength = when {
                ipHeader.isTcp -> tcpHeader!!.headerLength
                ipHeader.isUdp -> 8
                else -> 0
            }
            val payloadStart = ipHeader.headerLength + transportHeaderLength
            return if (payloadStart < ipHeader.totalLength.coerceAtMost(rawBytes.size)) {
                rawBytes.copyOfRange(payloadStart, ipHeader.totalLength.coerceAtMost(rawBytes.size))
            } else {
                ByteArray(0)
            }
        }

    fun withRawBytes(newBytes: ByteArray): IpPacket {
        val header = IpHeader(newBytes)
        require(header.totalLength <= newBytes.size) { "withRawBytes: totalLength ${header.totalLength} exceeds array ${newBytes.size}" }
        return IpPacket(newBytes, header)
    }

    fun copy(): IpPacket {
        val bytes = rawBytes.copyOf()
        return IpPacket(bytes, IpHeader(bytes))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpPacket) return false
        return rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int = rawBytes.contentHashCode()

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): IpPacket? {
            if (length < 20) return null
            val bytes = if (length < data.size) data.copyOf(length) else data
            val header = IpHeader(bytes)
            if (header.version != 4) return null
            if (header.headerLength < 20) return null
            if (header.totalLength < header.headerLength) return null
            if (header.totalLength > bytes.size) return null
            // Transport layer boundary check
            if (header.isTcp && bytes.size < header.headerLength + 20) return null
            if (header.isUdp && bytes.size < header.headerLength + 8) return null
            return IpPacket(bytes, header)
        }
    }
}
