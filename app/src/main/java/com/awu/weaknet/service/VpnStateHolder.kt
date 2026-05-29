/**
 * @author awu
 * @date 2026-05-26
 * @desc VPN 全局状态持有者：进程级单例，通过 StateFlow 桥接 Activity、VPN 服务和悬浮窗
 */
package com.awu.weaknet.service

import com.awu.weaknet.data.model.DelayModel
import com.awu.weaknet.data.model.LossModel
import com.awu.weaknet.data.model.NetworkProfile
import com.awu.weaknet.data.model.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnStateHolder {

    private val _activeProfile = MutableStateFlow<NetworkProfile?>(null)
    val activeProfile: StateFlow<NetworkProfile?> = _activeProfile.asStateFlow()

    private val _selectedApps = MutableStateFlow<Set<String>>(emptySet())
    val selectedApps: StateFlow<Set<String>> = _selectedApps.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _delayModel = MutableStateFlow(DelayModel.UNIFORM)
    val delayModel: StateFlow<DelayModel> = _delayModel.asStateFlow()

    private val _lossModel = MutableStateFlow(LossModel.RANDOM)
    val lossModel: StateFlow<LossModel> = _lossModel.asStateFlow()

    fun setActiveProfile(profile: NetworkProfile?) {
        _activeProfile.value = profile
    }

    fun setSelectedApps(apps: Set<String>) {
        _selectedApps.value = apps
    }

    fun updateStats(stats: TrafficStats) {
        _stats.value = stats
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun setDelayModel(model: DelayModel) {
        _delayModel.value = model
    }

    fun setLossModel(model: LossModel) {
        _lossModel.value = model
    }

    fun reset() {
        _isRunning.value = false
        _isPaused.value = false
        _activeProfile.value = null
        _selectedApps.value = emptySet()
        _stats.value = TrafficStats()
        _delayModel.value = DelayModel.UNIFORM
        _lossModel.value = LossModel.RANDOM
    }

    /** 只重置运行状态，保留 activeProfile/selectedApps/models（用于 VPN 重启）。 */
    fun resetForRestart() {
        _isRunning.value = false
        _isPaused.value = false
        _stats.value = TrafficStats()
    }
}
