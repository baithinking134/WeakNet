/**
 * @author awu
 * @date 2026-05-26
 * @desc 字节工具类：提供 IP 地址转换、整数/短整数序列化、校验和计算等网络协议辅助方法
 */
package com.awu.weaknet.util

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

object ByteUtils {

    fun byteArrayToInt(bytes: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) { "byteArrayToInt: offset=$offset out of bounds for size=${bytes.size}" }
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    fun shortToByteArray(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "shortToByteArray: value $value out of unsigned 16-bit range" }
        return byteArrayOf(
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    fun byteArrayToShort(bytes: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "byteArrayToShort: offset=$offset out of bounds for size=${bytes.size}" }
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
    }

    fun ipAddressToString(bytes: ByteArray, offset: Int = 0): String {
        require(offset >= 0 && offset + 4 <= bytes.size) { "ipAddressToString: offset out of bounds" }
        return "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}.${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
    }

    fun stringToIpAddress(ip: String): ByteArray {
        val parts = ip.split(".")
        require(parts.size == 4) { "Invalid IP address: $ip" }
        return byteArrayOf(
            (parts[0].toIntOrNull()?.coerceIn(0, 255)
                ?: throw IllegalArgumentException("Invalid IP octet: ${parts[0]}")).toByte(),
            (parts[1].toIntOrNull()?.coerceIn(0, 255)
                ?: throw IllegalArgumentException("Invalid IP octet: ${parts[1]}")).toByte(),
            (parts[2].toIntOrNull()?.coerceIn(0, 255)
                ?: throw IllegalArgumentException("Invalid IP octet: ${parts[2]}")).toByte(),
            (parts[3].toIntOrNull()?.coerceIn(0, 255)
                ?: throw IllegalArgumentException("Invalid IP octet: ${parts[3]}")).toByte(),
        )
    }

    fun calculateChecksum(buffer: ByteBuffer, offset: Int, length: Int): Int {
        var sum = 0L
        val savedPosition = buffer.position()
        try {
            buffer.position(offset)

            var i = 0
            while (i < length) {
                val word = if (i + 1 < length) {
                    ((buffer.get().toInt() and 0xFF) shl 8) or (buffer.get().toInt() and 0xFF)
                } else {
                    (buffer.get().toInt() and 0xFF) shl 8
                }
                sum += word.toLong()
                i += 2
            }

            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }

            return (sum.inv() and 0xFFFF).toInt()
        } finally {
            buffer.position(savedPosition)
        }
    }

    fun calculateChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length) {
            val word = if (i + 1 < length) {
                ((bytes[offset + i].toInt() and 0xFF) shl 8) or (bytes[offset + i + 1].toInt() and 0xFF)
            } else {
                (bytes[offset + i].toInt() and 0xFF) shl 8
            }
            sum += word.toLong()
            i += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun updateChecksum(bytes: ByteArray, checksumOffset: Int, dataOffset: Int, dataLength: Int) {
        bytes[checksumOffset] = 0
        bytes[checksumOffset + 1] = 0
        val checksum = calculateChecksum(bytes, dataOffset, dataLength)
        bytes[checksumOffset] = (checksum shr 8).toByte()
        bytes[checksumOffset + 1] = checksum.toByte()
    }
}
