/**
 * @author awu
 * @date 2026-05-26
 * @desc DNS 响应构造器：根据查询报文构造伪造的 DNS 响应，用于模拟 DNS 超时、失败和劫持
 */
package com.awu.weaknet.vpn.engine

import com.awu.weaknet.util.ByteUtils

/**
 * DNS 响应构造器：根据查询报文构造伪造的 DNS 响应。
 * 用于模拟 DNS 超时（直接丢弃）、DNS 失败（SERVFAIL）和 DNS 劫持（返回指定 IP）。
 */
object DnsResponder {

    private const val TAG = "NL-DNS"
    const val RCODE_SERVFAIL = 2

    private const val DNS_HEADER_SIZE = 12
    // DNS header flags 中 QR 位：1=response, 0=query
    private const val FLAG_QR = 0x80

    fun buildErrorResponse(queryPayload: ByteArray, rcode: Int): ByteArray? {
        val querySectionEnd = parseQuerySectionEnd(queryPayload) ?: return null
        val querySection = queryPayload.copyOfRange(DNS_HEADER_SIZE, querySectionEnd)

        val response = ByteArray(DNS_HEADER_SIZE + querySection.size)
        copyTransactionId(queryPayload, response)
        response[2] = (FLAG_QR or 0x01).toByte() // QR=1, RD=1
        response[3] = (0x80 or (rcode and 0x0F)).toByte() // RA=1, RCODE
        response[4] = queryPayload[4] // QDCOUNT
        response[5] = queryPayload[5]
        System.arraycopy(querySection, 0, response, DNS_HEADER_SIZE, querySection.size)
        return response
    }

    private const val DNS_TYPE_A = 1
    private const val DNS_TYPE_AAAA = 28

    fun buildHijackResponse(queryPayload: ByteArray, hijackIp: String): ByteArray? {
        val querySectionEnd = parseQuerySectionEnd(queryPayload) ?: return null
        val querySection = queryPayload.copyOfRange(DNS_HEADER_SIZE, querySectionEnd)

        if (querySection.size >= 4) {
            val qtype = ((querySection[querySection.size - 4].toInt() and 0xFF) shl 8) or
                    (querySection[querySection.size - 3].toInt() and 0xFF)
            if (qtype == DNS_TYPE_AAAA) {
                return buildErrorResponse(queryPayload, 0)
            }
        }
        val ipBytes = try {
            ByteUtils.stringToIpAddress(hijackIp)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Invalid hijack IP: $hijackIp")
            return buildErrorResponse(queryPayload, RCODE_SERVFAIL)
        }

        // Answer: name pointer(2) + type(2) + class(2) + ttl(4) + rdlength(2) + rdata(4) = 16
        val answerSize = 16
        val response = ByteArray(DNS_HEADER_SIZE + querySection.size + answerSize)

        copyTransactionId(queryPayload, response)
        response[2] = (FLAG_QR or 0x01).toByte() // QR=1, RD=1
        response[3] = 0x80.toByte() // RA=1, RCODE=0 (NOERROR)
        response[4] = 0; response[5] = 1 // QDCOUNT=1（强制，防止畸形查询）
        response[6] = 0; response[7] = 1 // ANCOUNT=1
        System.arraycopy(querySection, 0, response, DNS_HEADER_SIZE, querySection.size)

        val a = DNS_HEADER_SIZE + querySection.size
        response[a] = 0xC0.toByte(); response[a + 1] = DNS_HEADER_SIZE.toByte() // name pointer
        response[a + 2] = 0; response[a + 3] = 1   // TYPE A
        response[a + 4] = 0; response[a + 5] = 1   // CLASS IN
        response[a + 6] = 0; response[a + 7] = 0; response[a + 8] = 0; response[a + 9] = 60 // TTL
        response[a + 10] = 0; response[a + 11] = 4  // RDLENGTH
        System.arraycopy(ipBytes, 0, response, a + 12, 4)
        return response
    }

    private fun parseQuerySectionEnd(query: ByteArray): Int? {
        if (query.size < DNS_HEADER_SIZE) return null
        if ((query[2].toInt() and FLAG_QR) != 0) return null

        val qdcount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        var offset = DNS_HEADER_SIZE
        repeat(qdcount) {
            while (offset < query.size) {
                val b = query[offset].toInt() and 0xFF
                offset++
                if (b == 0) break
                if ((b and 0xC0) == 0xC0) {
                    // 压缩指针：占 2 字节，已读 1 字节，再跳 1 字节
                    offset++
                    break
                }
                if (offset + b > query.size) return null
                offset += b
            }
            if (offset + 4 > query.size) return null
            offset += 4
        }
        return if (offset <= query.size) offset else null
    }

    private fun copyTransactionId(src: ByteArray, dst: ByteArray) {
        dst[0] = src[0]
        dst[1] = src[1]
    }
}
