/**
 * @author awu
 * @date 2026-05-26
 * @desc VPN 虚拟网络配置常量：TUN 接口地址、MTU、缓冲区和 DNS 服务器等参数集中管理
 */
package com.awu.weaknet.vpn

/**
 * VPN 虚拟网络配置常量，定义 TUN 接口地址、MTU、缓冲区和 DNS。
 * 所有参数在此集中管理，避免硬编码散落在各组件中。
 */
object VpnConfig {
    // 选用 10.0.0.x 网段而非 192.168.x.x，因为后者被大部分路由器占用容易冲突
    const val VIRTUAL_GATEWAY = "10.0.0.1"
    const val VIRTUAL_CLIENT = "10.0.0.2"
    const val VIRTUAL_PREFIX_LENGTH = 24
    const val MTU = 1500
    const val BUFFER_SIZE = 32767
    const val SESSION_NAME = "WeakNet"
    // 三个 DNS 均为国内公共服务器：阿里 223.5.5.5、腾讯 119.29.29.29、114 DNS 114.114.114.114
    val DNS_SERVERS = listOf("223.5.5.5", "119.29.29.29", "114.114.114.114")
}
