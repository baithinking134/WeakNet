/**
 * @author awu
 * @date 2026-05-26
 * @desc VPN 主线程编排器：协调 TUN 读写、TCP/UDP 会话管理、流量统计和断线调度
 */
package com.awu.weaknet.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.awu.weaknet.data.model.TrafficStats
import com.awu.weaknet.service.VpnStateHolder
import com.awu.weaknet.vpn.engine.PacketProcessor
import com.awu.weaknet.vpn.engine.PacketReader
import com.awu.weaknet.vpn.engine.UpstreamReader
import com.awu.weaknet.vpn.manipulation.DisconnectScheduler
import com.awu.weaknet.vpn.manipulation.ManipulationPipeline
import com.awu.weaknet.vpn.nat.TcpSessionManager
import com.awu.weaknet.vpn.nat.UdpSessionManager
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * VPN 主线程编排器：协调 TUN 读写、TCP/UDP 会话管理和流量统计。
 * 在独立线程中运行阻塞式 TUN 读取，同时通过协程处理 UDP 轮询、统计和会话清理。
 */
class VpnThread(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val pipeline: ManipulationPipeline,
) {
    private val tcpSessionManager = TcpSessionManager(vpnService)
    private val udpSessionManager = UdpSessionManager(vpnService)

    @Volatile
    private var stopped = false

    @Volatile
    private var lastSpeedCalcTime = 0L
    @Volatile
    private var lastBytesSent = 0L
    @Volatile
    private var lastBytesReceived = 0L

    fun run() {
        val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
        val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val disconnectScheduler = if (pipeline.hasDisconnect) {
            DisconnectScheduler(
                intervalMs = pipeline.disconnectIntervalMs.toLong(),
                durationMs = pipeline.disconnectDurationMs.toLong(),
            )
        } else null

        val packetProcessor = PacketProcessor(
            tcpSessionManager = tcpSessionManager,
            udpSessionManager = udpSessionManager,
            pipeline = pipeline,
            tunOutput = vpnOutput,
            vpnService = vpnService,
            disconnectScheduler = disconnectScheduler,
            dnsFaultType = pipeline.dnsFaultType,
            dnsHijackIp = pipeline.dnsHijackIp,
        )

        val packetReader = PacketReader(vpnInput) { data, length ->
            if (!stopped) {
                packetProcessor.processPacket(data, length)
            }
        }

        val upstreamReader = UpstreamReader(udpSessionManager, packetProcessor)
        upstreamReader.start()
        lastSpeedCalcTime = System.currentTimeMillis()

        // UDP 上游用 5ms 间隔轮询，因为 DatagramChannel 无法像 TCP 那样按连接阻塞读
        val udpJob = coroutineScope.launch {
            while (isActive && !stopped) {
                upstreamReader.poll()
                delay(15)
            }
        }

        // Stats update
        // 500ms 统计间隔：太短会造成频繁状态更新浪费 UI 开销，太长则速度曲线不平滑
        val statsJob = coroutineScope.launch {
            while (isActive && !stopped) {
                updateStats(packetProcessor)
                delay(500)
            }
        }

        // 10s 清理一次过期会话：频繁清理浪费 CPU，间隔太长则僵尸会话占用端口和内存
        val cleanupJob = coroutineScope.launch {
            while (isActive && !stopped) {
                tcpSessionManager.cleanupExpired()
                udpSessionManager.cleanupExpired()
                delay(10000)
            }
        }

        // 网络闪断调度器：50ms 精度足够覆盖 2s 的最短断开时长
        val disconnectJob = if (disconnectScheduler != null) {
            coroutineScope.launch {
                while (isActive && !stopped) {
                    packetProcessor.handleDisconnectTick()
                    delay(50)
                }
            }
        } else null

        // TUN 读取在独立线程中阻塞运行：每个 TCP 连接需要独立线程做 blocking read（SocketChannel 无非阻塞回调机制）
        try {
            packetReader.start()
        } catch (e: Exception) {
            Log.e(TAG, "VPN thread error: ${e.message}")
        } finally {
            Log.i(TAG, "VPN thread cleaning up...")
            stopped = true
            packetReader.stop()
            packetProcessor.shutdown()
            upstreamReader.stop()
            udpJob.cancel()
            statsJob.cancel()
            cleanupJob.cancel()
            disconnectJob?.cancel()
            coroutineScope.cancel()
            tcpSessionManager.allSessions().forEach { it.close() }
            udpSessionManager.allSessions().forEach {
                try { it.channel.close() } catch (_: Exception) {}
            }
            try { vpnInput.close() } catch (_: Exception) {}
            try { vpnOutput.close() } catch (_: Exception) {}
            Log.i(TAG, "VPN thread finished")
        }
    }

    private fun updateStats(processor: PacketProcessor) {
        val stats = processor.getStats()
        val now = System.currentTimeMillis()
        val elapsed = (now - lastSpeedCalcTime).coerceAtLeast(1)

        VpnStateHolder.updateStats(
            TrafficStats(
                totalBytesSent = stats.bytesSent,
                totalBytesReceived = stats.bytesReceived,
                totalPacketsSent = stats.packetsSent,
                totalPacketsReceived = stats.packetsReceived,
                uploadSpeedBps = maxOf(0L, (stats.bytesSent - lastBytesSent) * 1000 / elapsed),
                downloadSpeedBps = maxOf(0L, (stats.bytesReceived - lastBytesReceived) * 1000 / elapsed),
                activeTcpSessions = tcpSessionManager.activeCount,
                activeUdpSessions = udpSessionManager.activeCount,
            )
        )
        lastSpeedCalcTime = now
        lastBytesSent = stats.bytesSent
        lastBytesReceived = stats.bytesReceived
    }

    fun stop() {
        Log.i(TAG, "VpnThread.stop() called")
        stopped = true
    }

    companion object {
        private const val TAG = "VpnThread"
    }
}
