[English](README_EN.md) | 中文

<div align="center">

# WeakNet

**Android 弱网模拟工具**

基于 `VpnService` 在用户态拦截所有 IP 流量，对数据包进行延迟、丢包、限速、重复、乱序、篡改等操控。<br>
支持网络闪断和 DNS 故障模拟。无需 root，无需代理配置，安装即用。

<p>

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android)
![Compose](https://img.shields.io/badge/Jetpack_Compose-BOM%202024.09-4285F4?logo=jetpackcompose)
![License](https://img.shields.io/badge/License-MIT-blue)

</p>

</div>

---

## 为什么需要 WeakNet

移动应用在弱网环境下的表现直接影响用户体验——加载超时、图片模糊、数据丢失、重复请求。开发和测试时需要**可复现的弱网环境**来验证应用的容错能力。

**WeakNet** 通过 Android `VpnService` 在用户态拦截**所有 IP 流量**（TCP/UDP/ICMP），在数据包层面实施操控。无需 root、不限于 HTTP、支持 12 个真实场景预设。

## 功能

- **全协议覆盖** — TCP、UDP、ICMP（Ping），不限于 HTTP 层
- **12 个预设场景** — 2G/3G/4G 弱信号/WiFi 弱/地铁/电梯/高铁/高延迟/丢包地狱/DNS 超时/数据校验
- **5 种数据包操控** — 节流、乱序、重复、丢包、篡改，管线串联可任意组合
- **3 种延迟分布** — 均匀、高斯（Box-Muller）、长尾（对数正态）
- **2 种丢包模型** — 随机（伯努利）、突发（Gilbert 马尔可夫链）
- **网络闪断** — 周期性模拟断连/恢复，恢复时自动 RST 清理所有 TCP 会话
- **DNS 故障模拟** — 超时（静默丢弃）/ 失败（SERVFAIL）/ 劫持（伪造 A 记录）
- **按应用过滤** — 选择特定 App 生效，不影响其他应用
- **浮窗监控** — 实时显示上传/下载速度、会话数，支持暂停/停止
- **自定义配置** — 所有参数均可自由调节，支持保存为自定义配置

## 快速开始

### 环境要求

- Android Studio
- Android 设备或模拟器（VPN 服务无法在宿主机测试）
- minSdk 23+

### 构建

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew compileDebugKotlin     # 仅编译（快速检查）
./gradlew test                   # 运行单元测试
```

### Release 构建

在 `local.properties`（不被 git 跟踪）中添加签名配置：

```properties
RELEASE_STORE_FILE=../your_keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

## 预设场景

| 预设 | 延迟 | 丢包 | 限速 | 特殊 |
|:-----|:----:|:----:|:----:|:-----|
| 无限制 | — | — | — | — |
| 2G (EDGE) | 300ms | 5% | 50kbps | — |
| 3G (HSPA) | 100ms | 2% | 750kbps | — |
| 4G 弱信号 | 50ms | 1% | 3Mbps | — |
| WiFi 弱 | 30ms | — | 5Mbps | 抖动 15ms |
| 地铁 | 200ms | 8% | 1Mbps | 闪断 30s/2s |
| 电梯 | 500ms | 15% | 100kbps | 闪断 20s/3s |
| 高延迟 | 2000ms | — | — | 抖动 500ms |
| 丢包地狱 | 100ms | 30% | — | 重发 5% + 乱序 |
| 高铁 | 150ms | 5% | 800kbps | 闪断 60s/4s |
| DNS 超时 | — | — | — | DNS 查询丢弃 |
| 数据校验 | — | — | — | 篡改 10% |

## 架构

```
App 流量 → TUN 接口 (VpnService)
    → PacketReader (阻塞读 TUN fd)
    → PacketProcessor (解析 IP/TCP/UDP, TCP 状态机, DNS 拦截, 闪断守卫)
        → ManipulationPipeline (Throttle → Reorder → Duplicate → Loss → Tamper)
        → protect()'d SocketChannel / DatagramChannel → 真实网络
```

WeakNet 作为**用户态 TCP/IP 代理**运行：App → TUN → VPN（解析+操控+转发）→ protect()'d Socket → 真实网络。TCP 连接由 VPN 代替 app 建立，app 只与 VPN 的虚拟 TCP 端点通信。

<details>
<summary><b>TCP 代理模型</b></summary>

VPN 代理模型中存在**两条 TCP 连接**：

```
App ←── TCP ──→ VPN(虚拟端点) ←── TCP ──→ 真实服务器
     VPN 内部 ACK              VPN 连真实服务器
```

这意味着：
- **出方向**：VPN 收到 app 数据后立即 ACK，然后转发到上游。如果管线丢包，数据已 ACK，无法恢复 → 因此 TCP 丢包在 ACK **之前**判定（不 ACK = 让 app TCP 自然重传）
- **入方向**：VPN 从上游 socket 读出数据（内核已 ACK），然后构造 TCP 包写入 TUN。如果管线丢弃/缓冲/篡改，数据永久丢失 → 因此 TCP 入方向跳过 Reorder/Loss/Tamper

</details>

<details>
<summary><b>核心组件</b></summary>

| 组件 | 职责 |
|------|------|
| `WeakNetVpnService` | VpnService 实现，建立 TUN 接口 |
| `VpnThread` | 编排器，协调 TUN 读写、UDP 轮询、统计、会话清理 |
| `PacketProcessor` | 核心：IP/TCP/UDP 解析、TCP 状态机、DNS 拦截 |
| `ManipulationPipeline` | 串联 5 个操纵器（`flatMap` 实现 1→N 包变换） |
| `DisconnectScheduler` | 闪断计时器，取模运算实现周期性状态切换 |
| `DnsResponder` | DNS 响应构造（SERVFAIL / 劫持 IP） |

</details>

<details>
<summary><b>线程模型</b></summary>

| 线程池 | 配置 | 用途 |
|--------|------|------|
| `connectExecutor` | CachedThreadPool（按需创建） | TCP 连接建立 + relayUpstream 阻塞循环 |
| `outgoingExecutor` | FixedThreadPool(4) | TCP 出方向管线（per-session writeLock 保序） |
| `udpExecutor` | CachedThreadPool（按需创建） | UDP 出/入方向管线 |
| `icmpExecutor` | SingleThreadExecutor | ICMP Echo Reply |
| `dnsExecutor` | SingleThreadExecutor | DNS 故障响应 |

</details>

<details>
<summary><b>项目结构</b></summary>

```
app/src/main/java/com/awu/weaknet/
├── vpn/                          # VPN 核心
│   ├── WeakNetVpnService.kt      # VpnService 实现
│   ├── VpnThread.kt              # 编排器
│   ├── VpnConfig.kt              # 常量配置
│   ├── packet/                   # IP/TCP/UDP 头解析、IP 分片重组
│   ├── nat/                      # TCP 会话状态机、UDP 会话管理
│   ├── engine/                   # PacketReader, PacketProcessor, UpstreamReader, DnsResponder
│   └── manipulation/             # 5 个操纵器 + Pipeline + DisconnectScheduler
├── data/
│   ├── model/                    # NetworkCondition, NetworkProfile, TrafficStats, AppInfo
│   └── repository/               # ProfileRepository (DataStore), AppRepository
├── service/                      # VpnStateHolder (全局状态), FloatingWindowService
└── ui/screen/                    # HomeScreen, ProfileListScreen, ProfileEditScreen, AppSelectorScreen
```

</details>

详细架构文档：[docs/architecture.md](docs/architecture.md)

## 技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.09.00) + Material 3 |
| 构建 | AGP 8.13.2, Gradle 8.13 |
| 目标 | compileSdk / targetSdk 36, minSdk 23 |
| 导航 | Navigation Compose |
| 持久化 | DataStore Preferences |
| 架构 | MVVM + Singleton StateFlow |
