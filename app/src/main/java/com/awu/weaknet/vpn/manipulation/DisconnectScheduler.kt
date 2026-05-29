/**
 * @author awu
 * @date 2026-05-26
 * @desc 周期性网络闪断调度器：基于取模时间在每个周期的末尾产生断开窗口
 */
package com.awu.weaknet.vpn.manipulation

/**
 * 周期性网络闪断调度器。
 *
 * 在每个 intervalMs 周期的最后 durationMs 时间内处于断开状态。
 * 调用方以固定频率调用 tick() 驱动状态切换。
 */
class DisconnectScheduler(
    private val intervalMs: Long,
    private val durationMs: Long,
) {
    init {
        require(intervalMs > 0) { "DisconnectScheduler: intervalMs must be > 0" }
        require(durationMs >= 0) { "DisconnectScheduler: durationMs must be >= 0" }
    }
    enum class State { CONNECTED, DISCONNECTED, JUST_RECONNECTED }

    @Volatile
    var isDisconnected: Boolean = false
        private set

    private val startNanos: Long = System.nanoTime()

    fun tick(): State {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val phase = elapsed % intervalMs
        val shouldDisconnect = phase >= intervalMs - durationMs

        if (shouldDisconnect == isDisconnected) {
            return if (isDisconnected) State.DISCONNECTED else State.CONNECTED
        }

        isDisconnected = shouldDisconnect
        return if (shouldDisconnect) State.DISCONNECTED else State.JUST_RECONNECTED
    }
}
