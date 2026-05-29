/**
 * @author awu
 * @date 2026-05-26
 * @desc TUN 接口数据包读取器：从 VPN 虚拟网卡循环读取 IP 数据包并分发处理
 */
package com.awu.weaknet.vpn.engine

import android.util.Log
import com.awu.weaknet.vpn.VpnConfig
import java.io.FileInputStream
import java.io.InterruptedIOException

/**
 * TUN 接口数据包读取器：从 VPN 的 TUN 文件描述符循环读取 IP 数据包。
 */
class PacketReader(
    private val vpnInput: FileInputStream,
    private val onPacket: (ByteArray, Int) -> Unit,
) {
    @Volatile
    var running = false
        private set

    fun start() {
        running = true
        val buffer = ByteArray(VpnConfig.BUFFER_SIZE)
        while (running) {
            try {
                val length = vpnInput.read(buffer)
                if (length < 0) {
                    Log.i(TAG, "TUN read returned -1, exiting")
                    break
                }
                if (length > 0) {
                    // buffer 被复用，必须复制有效数据，否则下一轮 read 会覆盖
                    val copy = buffer.copyOf(length)
                    onPacket(copy, length)
                }
            } catch (e: InterruptedIOException) {
                Log.i(TAG, "PacketReader interrupted, exiting")
                break
            } catch (e: java.io.IOException) {
                // This is expected when the VPN interface is closed during stop
                if (running) {
                    Log.w(TAG, "TUN read error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Error reading from TUN: ${e.message}")
                }
                break
            }
        }
        running = false
        Log.i(TAG, "PacketReader stopped")
    }

    fun stop() {
        running = false
        // TUN fd 的 read() 不可被 Thread.interrupt() 中断，只能通过关闭 fd 解除阻塞
        try {
            vpnInput.close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "PacketReader"
    }
}
