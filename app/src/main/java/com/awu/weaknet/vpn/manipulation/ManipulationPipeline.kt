/**
 * @author awu
 * @date 2026-05-26
 * @desc 操控管线：将多个包操控器（节流、重排、重发、丢包、篡改）串联执行
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.data.model.DelayModel
import com.awu.weaknet.data.model.DnsFaultType
import com.awu.weaknet.data.model.LossModel
import com.awu.weaknet.data.model.NetworkCondition
import com.awu.weaknet.vpn.packet.IpPacket
import kotlin.random.Random
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.exp

/**
 * 操控管线：将多个包操控器串联执行。
 * 返回 List<IpPacket> 而非单个 IpPacket，因为重发、分片等操控会增减包数量。
 */
class ManipulationPipeline(
    condition: NetworkCondition,
    delayModel: DelayModel = DelayModel.UNIFORM,
    lossModel: LossModel = LossModel.RANDOM,
) {

    val delayMs: Int = condition.delayMs
    val jitterMs: Int = condition.jitterMs
    val packetLossPercent: Int = condition.packetLossPercent
    private val delayModel: DelayModel = delayModel

    // Disconnect parameters (used by VpnThread to create DisconnectScheduler)
    val hasDisconnect: Boolean = condition.disconnectEnabled
    val disconnectIntervalMs: Int = condition.disconnectIntervalMs
    val disconnectDurationMs: Int = condition.safeDisconnectDurationMs

    // DNS fault parameters (used by VpnThread to pass to PacketProcessor)
    val dnsFaultType: DnsFaultType = condition.dnsFaultType
    val dnsHijackIp: String = condition.dnsHijackIp

    // 顺序很重要：节流→重排→重发→丢包→篡改
    // 延迟由 PacketProcessor 在 SYN-ACK、ICMP 回复和入方向数据中单独施加
    private val manipulators: List<PacketManipulator> = listOf(
        ThrottleManipulator(
            uploadSpeedKbps = condition.uploadSpeedKbps,
            downloadSpeedKbps = condition.downloadSpeedKbps,
        ),
        ReorderManipulator(bufferSize = condition.reorderBufferSize),
        DuplicateManipulator(condition.duplicatePercent),
        PacketLossManipulator(condition.packetLossPercent, lossModel),
        TamperManipulator(condition.tamperPercent),
    )

    private val lossManipulator = manipulators.filterIsInstance<PacketLossManipulator>().firstOrNull()

    /** TCP 出方向丢包判定：委托给 PacketLossManipulator 以支持 RANDOM 和 BURST 模型 */
    fun shouldDropTcpOutgoing(): Boolean = lossManipulator?.shouldDropForTcp() ?: false

    /**
     * 根据延迟模型计算抖动值。
     * UNIFORM: 均匀分布在 [-jitterMs, +jitterMs] 内取值
     * GAUSSIAN: 高斯分布，jitterMs 作为标准差(σ)，3σ 裁剪
     * LONG_TAIL: 对数正态分布，模拟真实弱网长尾效应——大部分包延迟小，偶尔出现远超均值的尖峰
     */
    fun calculateJitter(jitterMs: Int): Int {
        if (jitterMs <= 0) return 0
        return when (delayModel) {
            DelayModel.UNIFORM -> Random.nextInt(-jitterMs, jitterMs + 1)
            DelayModel.GAUSSIAN -> {
                // Box-Muller 变换生成标准正态分布
                val u1 = Random.nextDouble(1e-10, 1.0)
                val u2 = Random.nextDouble()
                val g = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
                (g * jitterMs).toInt().coerceIn(-3 * jitterMs, 3 * jitterMs)
            }
            DelayModel.LONG_TAIL -> {
                // 对数正态分布: e^N(0,1) 得到 LogNormal(0,1)
                // 中位数=1, 均值≈1.65, P(X>5)≈5.4%, P(X>10)≈1.1%
                // 乘以 jitterMs 得到实际抖动值，截断上限防止极端值
                val u1 = Random.nextDouble(1e-10, 1.0)
                val u2 = Random.nextDouble()
                val normal = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
                val logNormal = exp(normal)
                (logNormal * jitterMs).toInt().coerceIn(0, 5 * jitterMs)
            }
        }
    }

    fun processOutgoing(packet: IpPacket): List<IpPacket> {
        var packets = listOf(packet)
        for (m in manipulators) {
            packets = packets.flatMap { m.manipulateOutgoing(it) }
        }
        return packets
    }

    fun processIncoming(packet: IpPacket): List<IpPacket> {
        var packets = listOf(packet)
        for (m in manipulators) {
            packets = packets.flatMap { m.manipulateIncoming(it) }
        }
        return packets
    }

    data class FlushResult(val outgoing: List<IpPacket>, val incoming: List<IpPacket>)

    /** 释放所有操纵器中缓存的残余包，按方向区分。 */
    fun flush(): FlushResult {
        val out = mutableListOf<IpPacket>()
        val inc = mutableListOf<IpPacket>()
        for (m in manipulators) {
            if (m is ReorderManipulator) {
                out.addAll(m.flushOutgoing())
                inc.addAll(m.flushIncoming())
            }
        }
        return FlushResult(out, inc)
    }

    /** 只释放指定 session key 的缓冲包，并将结果通过后续操控器处理。 */
    fun flushForSession(sessionKey: String): FlushResult {
        var outPackets = mutableListOf<IpPacket>()
        var incPackets = mutableListOf<IpPacket>()
        for (m in manipulators) {
            // ReorderManipulator 释放缓冲包
            if (m is ReorderManipulator) {
                outPackets.addAll(m.flushOutgoing(sessionKey))
                incPackets.addAll(m.flushIncoming(sessionKey))
            } else {
                // 其他操控器处理前面释放出的包
                outPackets = outPackets.flatMap { m.manipulateOutgoing(it) }.toMutableList()
                incPackets = incPackets.flatMap { m.manipulateIncoming(it) }.toMutableList()
            }
        }
        return FlushResult(outPackets, incPackets)
    }
}
