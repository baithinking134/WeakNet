/**
 * @author awu
 * @date 2026-05-26
 * @desc IP 分片重组器：收集同一原始包的所有分片，重组为完整 IP 包后返回给处理器
 */
package com.awu.weaknet.vpn.packet

import com.awu.weaknet.util.ByteUtils

/**
 * IP 分片重组器。
 *
 * 当 app 发送超过 MTU（1500 字节）的 UDP 报文时，内核 IP 层会将其分片，
 * 每个分片作为独立的 IP 包到达 TUN 接口。本类收集同一原始包的所有分片，
 * 重组为完整的 IP 包后返回给 PacketProcessor 处理。
 *
 * 重组 key：源IP + 目的IP + identification + protocol（RFC 791 规定的分片标识方式）。
 * 超时未齐的分片会被丢弃，防止内存泄漏。
 */
class IpFragmentReassembler {

    private class FragKey(
        private val srcIp: ByteArray,
        private val dstIp: ByteArray,
        private val identification: Int,
        private val protocol: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FragKey) return false
            return identification == other.identification &&
                    protocol == other.protocol &&
                    srcIp.contentEquals(other.srcIp) &&
                    dstIp.contentEquals(other.dstIp)
        }

        override fun hashCode(): Int {
            var result = identification
            result = 31 * result + protocol
            result = 31 * result + srcIp.contentHashCode()
            result = 31 * result + dstIp.contentHashCode()
            return result
        }
    }

    private class FragBuffer(
        var ipHeader: ByteArray = ByteArray(0),
        var ipHeaderLength: Int = 0,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var totalPayloadLen: Int = -1,
        val createTime: Long = System.currentTimeMillis(),
    )

    private val buffers = mutableMapOf<FragKey, FragBuffer>()
    private var lastCleanupTime = 0L

    /**
     * 处理一个从 TUN 读取的原始 IP 包。
     * - 非分片包：直接返回原始数据
     * - 分片包：缓存，所有分片收齐后返回重组数据
     * - 分片未齐：返回 null
     */
    fun process(data: ByteArray, length: Int): ByteArray? {
        if (length < 20) return data.copyOf(length)

        // 解析 flags + fragment offset（bytes 6-7）
        val flagsAndOffset = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val mf = (flagsAndOffset and 0x2000) != 0   // More Fragments 标志
        val fragOffset = (flagsAndOffset and 0x1FFF) * 8  // 分片偏移，单位转为字节

        // 非分片包直接返回
        if (!mf && fragOffset == 0) return data.copyOf(length)

        val ihl = (data[0].toInt() and 0x0F) * 4
        val rawTotalLength = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val totalLength = rawTotalLength.coerceAtMost(length)
        val payloadLen = (totalLength - ihl).coerceAtLeast(0)
        val protocol = data[9].toInt() and 0xFF
        val identification = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val srcIp = data.copyOfRange(12, 16)
        val dstIp = data.copyOfRange(16, 20)

        val key = FragKey(srcIp, dstIp, identification, protocol)

        synchronized(this) {
            var buffer = buffers[key]

            if (buffer == null) {
                if (buffers.size >= MAX_ENTRIES) {
                    // 逐出最旧的条目以释放空间
                    buffers.entries.maxByOrNull { it.value.createTime }?.let { buffers.remove(it.key) }
                }
                buffer = FragBuffer()
                buffers[key] = buffer
            }

            // 首个分片（offset=0）携带原始 IP 头，保存用于重组
            if (fragOffset == 0) {
                buffer.ipHeader = data.copyOfRange(0, ihl)
                buffer.ipHeaderLength = ihl
            }

            // 检测分片重叠：已有相同偏移的分片则丢弃新分片（保留先到的）
            if (buffer.chunks.containsKey(fragOffset)) return null
            // 存储分片载荷
            buffer.chunks[fragOffset] = data.copyOfRange(ihl, ihl + payloadLen)

            // 最后一个分片（MF=0）确定了总载荷长度
            if (!mf) {
                val total = fragOffset + payloadLen
                if (total > MAX_REASSEMBLY_SIZE) return null
                buffer.totalPayloadLen = total
            }

            // 超时清理：每秒最多执行一次，避免高频流量下的 O(n) 开销
            val now = System.currentTimeMillis()
            if (now - lastCleanupTime > 1000L) {
                lastCleanupTime = now
                buffers.entries.removeIf { now - it.value.createTime > TIMEOUT_MS }
            }

            // 检查重组条件：IP 头已收 + 总长度已知 + 所有分片连续无间隙
            if (buffer.ipHeader.isEmpty() || buffer.totalPayloadLen < 0) return null

            val sorted = buffer.chunks.toSortedMap()
            var expectedOffset = 0
            for ((fragOff, chunk) in sorted) {
                if (fragOff != expectedOffset) return null // 间隙或重叠
                expectedOffset = fragOff + chunk.size
            }
            if (expectedOffset != buffer.totalPayloadLen) return null

            // ---- 重组 ----
            val result = ByteArray(buffer.ipHeaderLength + buffer.totalPayloadLen)

            // 复制原始 IP 头
            System.arraycopy(buffer.ipHeader, 0, result, 0, buffer.ipHeaderLength)

            // 更新 total length
            result[2] = (result.size shr 8).toByte()
            result[3] = result.size.toByte()

            // 清除 MF 标志和分片偏移，保留 Reserved(0x80) 和 DF(0x40)
            result[6] = (result[6].toInt() and 0xC0).toByte()
            result[7] = 0

            // 重算 IP 头校验和
            result[10] = 0
            result[11] = 0
            ByteUtils.updateChecksum(result, 10, 0, buffer.ipHeaderLength)

            // 按 offset 排列复制各分片载荷（sorted 已在上面的校验中创建）
            for ((offset, chunk) in sorted) {
                System.arraycopy(chunk, 0, result, buffer.ipHeaderLength + offset, chunk.size)
            }

            buffers.remove(key)
            return result
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000L
        private const val MAX_ENTRIES = 256
        private const val MAX_REASSEMBLY_SIZE = 65535
    }
}
