/**
 * @author awu
 * @date 2026-05-26
 * @desc 丢包操控器：支持随机丢包（伯努利）和突发丢包（Gilbert 模型）两种模式
 */
package com.awu.weaknet.vpn.manipulation

import com.awu.weaknet.data.model.LossModel
import com.awu.weaknet.vpn.packet.IpPacket
import kotlin.random.Random

/**
 * 丢包操控器。
 * RANDOM: 独立随机丢包，每个包以 lossPercent 概率丢弃（伯努利模型）
 * BURST: Gilbert 模型突发丢包——在"好"和"坏"两个状态间切换，
 *        好状态几乎不丢包，坏状态高概率丢包，模拟真实弱网的突发丢包特征
 *
 * 入方向 TCP 不丢包：代理已从上游 socket 读出数据，丢弃会导致永久丢失。
 */
class PacketLossManipulator(
    private val lossPercent: Int = 0,
    private val lossModel: LossModel = LossModel.RANDOM,
) : PacketManipulator {

    private val lock = Any()

    // ---- Gilbert 模型状态 ----
    private var inBadState = false

    private val p: Double // GOOD → BAD
    private val r: Double // BAD → GOOD

    init {
        if (lossPercent in 1..99) {
            r = 1.0 / 3.0
            p = r * lossPercent / (100.0 - lossPercent)
        } else {
            p = 0.0
            r = 1.0
        }
    }

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (lossPercent <= 0) return listOf(packet)
        // TCP 数据不走丢包：VPN 已提前 ACK，丢包会导致数据永久丢失、连接卡死直到超时
        if (packet.ipHeader.isTcp) return listOf(packet)
        if (lossPercent >= 100) return emptyList()
        return if (shouldDrop()) emptyList() else listOf(packet)
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (lossPercent <= 0) return listOf(packet)
        if (packet.ipHeader.isTcp) return listOf(packet)
        if (lossPercent >= 100) return emptyList()
        return if (shouldDrop()) emptyList() else listOf(packet)
    }

    /** TCP 出方向丢包判定：供 PacketProcessor 在 ACK 前调用，支持 RANDOM 和 BURST 模型 */
    fun shouldDropForTcp(): Boolean {
        if (lossPercent <= 0) return false
        val dropped = shouldDrop()
        if (dropped) {
            android.util.Log.i("NL-Loss", "TCP DROP (${lossModel.name}) loss=$lossPercent% badState=$inBadState")
        }
        return dropped
    }

    private fun shouldDrop(): Boolean {
        return when (lossModel) {
            LossModel.RANDOM -> Random.nextInt(100) < lossPercent
            LossModel.BURST -> synchronized(lock) {
                val drop = inBadState
                if (inBadState) {
                    if (Random.nextDouble() < r) inBadState = false
                } else {
                    if (Random.nextDouble() < p) inBadState = true
                }
                drop
            }
        }
    }
}
