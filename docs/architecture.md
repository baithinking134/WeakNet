# WeakNet 架构文档

## 项目概述

**WeakNet** 是一个 Android 弱网模拟应用（包名 `com.awu.weaknet`）。通过 `VpnService` 拦截所有 IP 流量，经 TUN 接口读取后送入操控管线（延迟、丢包、节流、重复、乱序、篡改、闪断、DNS 故障），最终通过 `protect()` 保护的真实 Socket 转发到实际网络。

---

## 整体架构

```
App流量 → TUN接口(VpnService) → PacketReader → PacketProcessor
    → [解析 IP/TCP/UDP] → ManipulationPipeline (节流→乱序→重发→丢包→篡改)
    → protect()'d SocketChannel/DatagramChannel → 真实网络
```

### 关键包结构

| 包 | 职责 |
|----|------|
| `vpn/` | VpnService、VpnThread、VpnConfig |
| `vpn/packet/` | IP/TCP/UDP 头解析、IP 分片重组 |
| `vpn/nat/` | TCP 会话状态机（TcpSession、TcpSessionManager）、UDP 会话管理 |
| `vpn/engine/` | PacketReader、PacketProcessor（核心调度）、UpstreamReader、DnsResponder |
| `vpn/manipulation/` | 5 个操控器 + ManipulationPipeline + DisconnectScheduler |
| `data/model/` | NetworkCondition、NetworkProfile（12 个预设）、TrafficStats、AppInfo |
| `data/repository/` | ProfileRepository（DataStore）、AppRepository（PackageManager） |
| `service/` | VpnStateHolder（全局 StateFlow 共享状态）、FloatingWindowService |
| `ui/screen/` | HomeScreen、ProfileListScreen、ProfileEditScreen、AppSelectorScreen |

---

## VPN 服务生命周期

`WeakNetVpnService` 继承 Android `VpnService`，负责创建 TUN 接口并启动 `VpnThread`。

### VPN 接口建立

```
WeakNetVpnService.onStartCommand()
  → establishVpn()
      → VpnService.Builder()
          .setSession("WeakNet")
          .addAddress("10.0.0.2", 24)         // 虚拟客户端 IP
          .addRoute("0.0.0.0", 1)             // 双路由策略（兼容国产 ROM）
          .addRoute("128.0.0.0", 1)
          .setMtu(1500)
          .addDnsServer("223.5.5.5")          // 阿里 DNS
          .addDnsServer("119.29.29.29")       // 腾讯 DNS
          .addDnsServer("114.114.114.114")    // 114 DNS
          .setBlocking(true)                  // 阻塞模式，避免 CPU 空转
          .addAllowedApplication(...)         // 或 addDisallowedApplication
          .establish()                        → ParcelFileDescriptor
  → 创建 Thread("WeakNet-VPN") { VpnThread(vpnInterface).run() }
  → startForeground(NOTIFICATION_ID, notification)
```

### 双路由策略说明

使用 `0.0.0.0/1` + `128.0.0.0/1` 而非 `0.0.0.0/0`，因为部分国产 ROM（小米、OPPO 等）对默认路由的 VPN 配置处理有 bug，双路由策略能正确覆盖所有 IP 地址（`0.0.0.0/1` 覆盖 `0-127`，`128.0.0.0/1` 覆盖 `128-255`）。

### 虚拟网络拓扑

```
┌─────────────────────────────────────────────────┐
│  Android 设备                                    │
│                                                  │
│  App (10.0.0.2) ──TUN接口──→ VPN Service         │
│       ↑                           │              │
│       │                     PacketProcessor       │
│       │                     (用户态 TCP/IP 代理)   │
│       │                           │              │
│       │                    protect()'d Socket     │
│       │                           │              │
│       └────────────────────── 真实网络            │
└─────────────────────────────────────────────────┘
```

- **虚拟网关**：`10.0.0.1`
- **虚拟客户端**：`10.0.0.2`
- **子网掩码**：`255.255.255.0`（`/24`）
- **MTU**：`1500` 字节
- 选用 `10.0.0.x` 而非 `192.168.x.x`，避免与常见路由器网段冲突

---

## 线程模型

```
WeakNetVpnService
│
└── Thread("WeakNet-VPN")
    └── VpnThread.run()
        │
        ├── Thread("NL-PacketReader")（专用阻塞读线程）
        │   └── vpnInput.read(buffer) 阻塞 → processPacket()
        │
        ├── connectExecutor（CachedThreadPool，按需创建线程）
        │   └── 每 TCP 连接：sleep(delay+jitter) → sendSynAck → connectBlocking
        │       → flush pendingData → relayUpstream（阻塞读上游循环）
        │
        ├── outgoingExecutor（FixedThreadPool(4)，per-session writeLock 保序）
        │   └── TCP 出方向管线处理 + FIN 处理
        │
        ├── udpExecutor（CachedThreadPool，按需创建线程）
        │   └── UDP 出/入方向管线
        │
        ├── icmpExecutor（SingleThreadExecutor）
        │   └── ICMP Echo Reply 构造（独立线程池，不被 UDP 流量阻塞）
        │
        ├── dnsExecutor（SingleThreadExecutor）
        │   └── DNS 故障响应构造（独立线程池，保证 DNS 及时处理）
        │
        ├── 协程: UpstreamReader.poll()
        │   └── 每 15ms 轮询所有 UDP 会话 → 非阻塞读上游响应 → handleUdpResponse
        │
        ├── 协程: StatsUpdate
        │   └── 每 500ms 计算速度，更新 VpnStateHolder
        │
        ├── 协程: SessionCleanup
        │   └── 每 10s 清理过期会话（TCP 60s，UDP 30s）
        │
        └── 协程: DisconnectTick（条件启用）
            └── 每 50ms 调用 handleDisconnectTick()，处理闪断状态机
```

### 五个线程池

| 线程池 | 类型 | 线程名 | 用途 |
|--------|------|--------|------|
| `connectExecutor` | `newCachedThreadPool`（按需创建，60s 空闲回收） | `NL-Connect` | TCP 连接建立 + relayUpstream 阻塞循环 |
| `outgoingExecutor` | `newFixedThreadPool(4)` | `NL-Outgoing` | TCP 出方向管线处理（per-session writeLock 保序）+ FIN 处理 |
| `udpExecutor` | `newCachedThreadPool`（按需创建，60s 空闲回收） | `NL-UDP` | UDP 出/入方向管线 |
| `icmpExecutor` | `newSingleThreadExecutor` | `NL-ICMP` | ICMP Echo Reply（独立线程池，保证 ping 延迟准确） |
| `dnsExecutor` | `newSingleThreadExecutor` | `NL-DNS` | DNS 故障响应构造（保证 DNS 不被其他流量延迟） |

**connectExecutor 选择 CachedThreadPool 的原因**：每个 TCP 连接在 `relayUpstream()` 中会阻塞一个线程（持续读取上游数据），使用有界队列的 `ThreadPoolExecutor` 会导致新连接排队等待空闲线程，在并发连接较多时造成长时间延迟。`CachedThreadPool` 按需创建线程，新连接立即获得线程。

**udpExecutor 选择 CachedThreadPool 的原因**：UDP 每个包都需要 `Thread.sleep(delay + jitter)` 等待延迟，固定线程池在高并发 UDP 流量下会占满所有线程，后续包在队列中堆积导致实际延迟远超设定值。`CachedThreadPool` 让每个包都能立即开始延迟计时。

### 同步机制

| 资源 | 保护方式 | 说明 |
|------|----------|------|
| TUN 写入（`tunOutput`） | `synchronized(tunLock)` | 多个 TCP relay 线程 + ICMP 线程 + UDP 入方向线程并发写 |
| TCP/UDP 会话表 | `ConcurrentHashMap` | 线程安全的读写，无需显式加锁 |
| 出方向节流 | `RateController.synchronized`（per-session） | 每个 session 独立的 RateController，避免跨连接延迟堆叠 |
| pendingData | `ConcurrentHashMap.compute()` | 原子化追加，连接线程和 outgoingExecutor 并发访问 |
| TcpSession 状态 | `AtomicReference<CAS>` | SYN_RECEIVED → ESTABLISHED → CLOSED 状态转换原子化 |
| serverSeq/clientNextSeq | `AtomicInteger` | 多线程读写序列号 |
| 乱序缓冲区 | `ConcurrentHashMap<String, IpPacket>` | 按 session key 隔离，条件删除避免竞争 |
| DisconnectScheduler 状态 | `@Volatile` | 50ms tick 协程与数据包处理线程间可见性 |
| TcpSession.writeLock | `synchronized(session.writeLock)` | outgoingExecutor 多线程写同一 session 的 SocketChannel 时互斥 |

### 关键设计：管线不阻塞主线程

出方向的操控管线（含节流 sleep）**不**在 PacketReader 线程执行：
- **TCP 数据**：提交到 `outgoingExecutor`（4 线程池，per-session writeLock 保序）
- **UDP 数据**：提交到 `udpExecutor`（CachedThreadPool）

入方向的操控管线**不**在 UpstreamReader 的 poll 协程执行：
- **UDP 入方向**：提交到 `udpExecutor`（管线 + applyDelay + writeToTun）

这样 PacketReader 线程可以持续读包，poll 协程可以持续轮询，不会因节流/延迟而阻塞。节流的 `RateController` 仅在 `synchronized` 块内做时间计算（纳秒级），sleep 在块外执行，不持有锁。

---

## 数据包解析

### IpHeader（IP 头，20 字节起）

| 字段 | 偏移 | 长度 | 说明 |
|------|------|------|------|
| version | 0 (高4位) | 4bit | IP 版本，必须为 4 |
| ihl | 0 (低4位) | 4bit | 头长度，单位 32bit 字 |
| headerLength | - | - | `ihl * 4`，字节数 |
| dscp | 1 | 6bit | 差分服务代码点 |
| totalLength | 2-3 | 16bit | IP 包总长度（含头部和载荷） |
| identification | 4-5 | 16bit | 分片标识 |
| flags | 6 (高3位) | 3bit | DF/MF/Reserved |
| fragmentOffset | 6-7 (低13位) | 13bit | 分片偏移，单位 8 字节 |
| ttl | 8 | 8bit | 生存时间 |
| protocol | 9 | 8bit | 传输层协议（1=ICMP, 6=TCP, 17=UDP） |
| sourceAddress | 12-15 | 32bit | 源 IP 地址 |
| destinationAddress | 16-19 | 32bit | 目的 IP 地址 |

### TcpHeader（TCP 头，20 字节起）

| 字段 | 偏移 | 长度 | 说明 |
|------|------|------|------|
| sourcePort | 0-1 | 16bit | 源端口 |
| destinationPort | 2-3 | 16bit | 目的端口 |
| sequenceNumber | 4-7 | 32bit | 序列号 |
| ackNumber | 8-11 | 32bit | 确认号 |
| dataOffset | 12 (高4位) | 4bit | TCP 头长度，单位 32bit 字 |
| flags | 13 | 8bit | 控制标志 |
| windowSize | 14-15 | 16bit | 窗口大小 |

**TCP 标志位**：
- `FIN = 0x01`：发送方完成数据传输
- `SYN = 0x02`：请求建立连接
- `RST = 0x04`：重置连接
- `PSH = 0x08`：推送数据（接收方应立即交付应用层）
- `ACK = 0x10`：确认号有效

### UdpHeader（UDP 头，固定 8 字节）

| 字段 | 偏移 | 长度 | 说明 |
|------|------|------|------|
| sourcePort | 0-1 | 16bit | 源端口 |
| destinationPort | 2-3 | 16bit | 目的端口 |
| length | 4-5 | 16bit | UDP 总长度（头部 + 载荷） |
| checksum | 6-7 | 16bit | 校验和（含伪首部，Android 必填） |

### IpPacket 结构

```
IpPacket
  ├── rawBytes: ByteArray     // 完整 IP 包（含所有头部 + 载荷）
  ├── ipHeader: IpHeader      // 解析后的 IP 头
  ├── tcpHeader: TcpHeader?   // protocol == 6 时非空
  ├── udpHeader: UdpHeader?   // protocol == 17 时非空
  └── payload: ByteArray      // 传输层载荷（不含 IP/传输层头）
```

解析流程：`IpPacket.parse(data)` → 校验长度 ≥ 20、版本 == 4、ihl ≥ 5 → 构造 `IpHeader`，根据 `protocol` 字段解析 `TcpHeader` 或 `UdpHeader`。

---

## IP 分片重组

大 UDP 报文超过 MTU 时，内核会自动按 RFC 791 分片。这些分片作为独立 IP 包逐片到达 TUN，必须先重组再处理。

### IpFragmentReassembler 工作机制

```
PacketReader 读到 IP 包
  → IpFragmentReassembler.process(data, length)
      │
      ├── 非分片包（MF=0 且 offset=0）→ 直接返回原数据
      │
      ├── 分片包：
      │   ├── 按 (srcIp, dstIp, identification, protocol) 分组
      │   ├── 缓存到 FragBuffer.chunks（按 offset 存储）
      │   ├── 检查是否所有分片到齐：
      │   │   ├── 第一个分片（offset=0）：记录 IP 头
      │   │   ├── 最后一个分片（MF=0）：记录总载荷长度
      │   │   └── 所有分片到齐：chunks 总大小 == totalPayloadLen
      │   │
      │   └── 重组完成：
      │       ├── 复制原始 IP 头
      │       ├── 更新 totalLength 字段
      │       ├── 清除 MF 标志和分片偏移
      │       ├── 重算 IP 头校验和
      │       ├── 按 offset 顺序拼接所有分片载荷
      │       └── 返回重组后的完整 IP 包
      │
      └── 分片未齐 → 返回 null，等待后续分片
```

**超时处理**：5 秒内未完成重组的缓冲区会被丢弃，防止内存泄漏。

**线程安全**：`process()` 方法使用 `synchronized(this)` 保护，因为 PacketReader 线程会并发调用。

---

## TCP 完整生命周期

### 概览时序图

```
  App                    VPN (PacketProcessor)              上游服务器
  │                           │                                │
  │──── SYN ─────────────────>│                                │
  │                     创建 TcpSession                        │
  │                     state = SYN_RECEIVED                  │
  │                     clientNextSeq = syn.seq + 1           │
  │                     serverSeq = Random(100000..MAX)       │
  │                     提交到 connectExecutor ──┐              │
  │                                            │ sleep(delay+jitter)
  │<─── SYN-ACK ──────────────────────────────┘                │
  │                     (延迟发送，模拟握手 RTT)                 │
  │                           │                                │
  │──── ACK ──────────>(payload=0, 直接丢弃, 不走管线)          │
  │                           │                                │
  │──── DATA (TLS ClientHello等) ─>                           │
  │                     更新 clientNextSeq                      │
  │                     立即 sendAck (不等待管线)                │
  │                     outgoingExecutor.submit {              │
  │                       pipeline.processOutgoing()           │
  │                       [节流→乱序→重发→丢包→篡改]             │
  │                       写 SocketChannel                     │
  │                       或缓存到 pendingData                  │
  │                     }                                      │
  │                           │                                │
  │                           │── connectBlocking() ──────────>│
  │                           │   (建立真实 TCP 连接)            │
  │                           │<── SYN-ACK ────────────────────│
  │                           │── ACK ─────────────────────────>│
  │                           │                                │
  │                           │── 发送 pendingData ────────────>│
  │                           │   启动 relayUpstream 循环       │
  │                           │                                │
  │                           │<── 响应数据 ────────────────────│
  │                           │   ch.read(buf) 阻塞读取        │
  │                           │   buildTcpDataPacket()         │
  │                           │   pipeline.processIncoming()   │
  │                           │<── 响应数据 ────────────────────│
  │<─── 响应 DATA ────────────│   writeToTun()                │
  │                     serverSeq += bytesRead                 │
  │                           │                                │
  │── FIN ───────────────────>│                                │
  │                     outgoingExecutor.submit {              │
  │                       flushPipelineForSession()            │
  │                       写 FIN 载荷到上游（如有）             │
  │                       sendFinAck() 回复 app                │
  │                       removeSession()                      │
  │                     }                                      │
```

### 各阶段详解

#### 1. SYN 处理（新建连接）

**入口**：`PacketProcessor.handleTcp()` 检测到 `tcp.isSyn && !tcp.isAck`

```kotlin
// 1. 检查是否已有同四元组的会话，防止重复 SYN
val existing = tcpSessionManager.getSession(srcIp, srcPort, dstIp, dstPort)
if (existing != null && !existing.isClosed) return

// 2. 创建新会话
val session = tcpSessionManager.createSession(srcIp, srcPort, dstIp, dstPort, syn.seq)
session.serverSeq = Random.nextInt(100000, Int.MAX_VALUE)

// 3. 提交到连接线程池（不阻塞 PacketReader）
connectExecutor.submit {
    // 3a. 延迟后发 SYN-ACK
    Thread.sleep(delay + calculateJitter(jitterMs))
    sendSynAck(session, ip, tcp)   // 构造 SYN-ACK 包写入 TUN
    session.serverSeq++            // SYN 占 1 个 seq 号（RFC 793）

    // 3b. 建立真实 TCP 连接
    if (session.connectBlocking()) {
        // 3c. flush 握手期间 app 发来的缓存数据
        pendingData.remove(session.id)?.let { data ->
            channel.write(ByteBuffer.wrap(data))
        }
        // 3d. 进入 relay 循环（阻塞在此线程上）
        relayUpstream(session)
    } else {
        pendingData.remove(session.id)
        sendRstToSession(session)           // 连接失败，通知 app
        tcpSessionManager.removeSession(session)
    }
}
```

**关键点**：
- **假 SYN-ACK**：VPN 先发 SYN-ACK 让 app 认为连接已建立，同时在后台建立真实连接
- **延迟施加在 SYN-ACK 阶段**：`Thread.sleep(delay + calculateJitter(jitterMs))` 在连接线程中执行
- **SYN 占 1 个 seq 号**：RFC 793 规定 SYN 消耗一个序列号，后续数据从 `serverSeq + 1` 开始
- **连接池管理**：通过 `connectExecutor`（核心 4 线程，最大 64 线程）管理并发连接，队列满时拒绝新连接

#### 2. TcpSession 状态机

```
SYN_RECEIVED  ──(connectBlocking 成功, CAS)──>  ESTABLISHED  ──(FIN/RST/错误/超时, CAS)──>  CLOSED
```

**CAS 状态转换**：使用 `AtomicReference<TcpState>` 的 `compareAndSet` 保证状态转换原子性。如果 CAS 失败（会话已被关闭），`connectBlocking()` 会关闭刚建立的 `SocketChannel`，防止 fd 泄漏。

**TcpSession 关键字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 唯一会话 ID（AtomicLong 自增） |
| `sourceIp/Port` | `ByteArray/Int` | TUN 侧（app 侧）地址（防御性拷贝） |
| `destIp/Port` | `ByteArray/Int` | 上游服务器地址（防御性拷贝） |
| `state` | `AtomicReference<TcpState>` | SYN_RECEIVED / ESTABLISHED / CLOSED |
| `serverSeq` | `AtomicInteger` | 上游侧序列号（构造发给 app 的 TCP 包的 seq） |
| `clientNextSeq` | `AtomicInteger` | TUN 侧序列号（构造发给 app 的 TCP 包的 ack） |
| `channel` | `AtomicReference<SocketChannel?>` | `protect()` 保护的真实 Socket（阻塞模式） |
| `lastActivityTime` | `@Volatile Long` | 最后活动时间（默认 60s 超时） |
| `writeLock` | `Any` | 防止连接线程和数据线程并发写 SocketChannel |

**connectBlocking() 实现细节**：
1. `SocketChannel.open()` → 配置阻塞模式
2. `vpnService.protect(socket)` → 防止路由环路（**必须成功**，否则数据回到 VPN 形成死循环）
3. `channel.connect(address)` → 阻塞等待连接完成
4. CAS `SYN_RECEIVED` → `ESTABLISHED`；如果 CAS 失败，关闭 channel 防止 fd 泄漏
5. 返回 `true`

#### 3. ACK 处理（纯确认包）

```
payloadSize = ip.totalLength - ip.headerLength - tcp.headerLength
if (payloadSize <= 0) return  // 纯 ACK，直接丢弃
```

**设计原因**：
- 纯 ACK（payload=0）是 TCP 协议控制包，不应被延迟/丢包
- 如果 ACK 被延迟，会导致 app 侧 TCP 重传计时器超时，引发不必要的重传
- 如果 ACK 被丢包，会导致 TCP 握手/数据传输失败

#### 4. RST/FIN 处理（控制包）

```
RST → 直接清理会话（tcpSessionManager.removeSession），不发管线
FIN → 提交到 outgoingExecutor（保证在所有已排队数据之后处理）
       → flushPipelineForSession()（刷新乱序缓冲区）
       → 写 FIN 载荷到上游（如有）
       → sendFinAck()（构造 FIN-ACK 回复 app）
       → removeSession()
```

**FIN 在 outgoingExecutor 中处理**：保证 FIN 在所有已排队的数据包之后处理，避免数据包和 FIN 的乱序。同时刷新管线中该会话的乱序缓冲区。

**设计原因**：控制包应尽快传递，延迟/丢包会导致连接异常。FIN 时 `serverSeq++` 因为 FIN 也占一个序列号。

#### 5. TCP 数据包处理（出方向，app → 上游）

```kotlin
// 1. 重传检测（无符号比较）
val isRetransmit = (tcp.sequenceNumber.toLong() and 0xFFFFFFFFL) <
    (session.clientNextSeq.toLong() and 0xFFFFFFFFL)
if (isRetransmit) {
    // 重传包：仍然走管线，但重复数据只写一次上游
}

// 2. TCP 丢包模拟：在 ACK 之前决定——被"丢"的包不发 ACK，让 app TCP 自然重传
val tcpLost = pipeline.shouldDropTcpOutgoing()

if (!tcpLost) {
    session.clientNextSeq = newSeqEnd
    sendAck(session, newSeqEnd, ip, tcp)
}

// 3. 提交到 outgoingExecutor（4 线程池，per-session writeLock 保序）
outgoingExecutor.submit {
    if (tcpLost || session.isClosed) return@submit

    val manipulated = pipeline.processOutgoing(packet)
    if (manipulated.isEmpty()) return@submit

    // 统计在管线处理后更新
    totalBytesSent.addAndGet(manipulated.sumOf { it.payload.size.toLong() })
    totalPacketsSent.addAndGet(manipulated.size.toLong())

    // TCP 是流协议：重复包的数据不能重复写入上游（会损坏字节流）
    // 只写第一个包的数据，后续重复包的 payload 跳过
    var wroteData = false
    for (p in manipulated) {
        if (session.isClosed) break
        val dataToSend = p.payload
        if (dataToSend.isEmpty()) continue

        if (!wroteData) {
            val ch = session.channel
            if (ch != null && ch.isConnected) {
                synchronized(session.writeLock) { ch.write(ByteBuffer.wrap(dataToSend)) }
            } else {
                // 真实连接还没建立好，原子追加到 pendingData
                pendingData.compute(session.id) { _, existing ->
                    if (existing == null) dataToSend
                    else if (existing.size + dataToSend.size > MAX_PENDING_PER_SESSION) null
                    else existing + dataToSend
                }
            }
            wroteData = true
        }
    }
}
```

**关键设计**：
- **TCP 丢包在 ACK 前判定**：不 ACK "被丢" 的包 → app TCP 认为包丢失 → 自然重传。这比"ACK 后丢包"更符合真实弱网行为，被丢的包不会永久丢失
- **管线不阻塞 PacketReader**：提交到 `outgoingExecutor`，PacketReader 立即处理下一个包
- **pendingData 原子追加**：使用 `ConcurrentHashMap.compute()` 原子化合并数据，上限 1MB/会话
- **TCP 数据不做逐包延迟**：TCP 是流式传输，逐包延迟会累积堆叠导致连接卡死。延迟仅在 SYN-ACK 阶段施加一次
- **重传检测**：使用无符号 32 位比较检测序列号回绕
- **TCP 重复包不重复写上游**：DuplicateManipulator 可能复制 TCP 包，但 TCP 是字节流，重复写入会损坏数据。只写第一个包的数据

#### 6. TCP 响应转发（入方向，上游 → app）

```kotlin
// relayUpstream() 在连接线程中阻塞运行
while (!session.isClosed && ch.isOpen) {
    val bytesRead = ch.read(buf)   // 阻塞读取上游数据（buffer = MTU - 40 = 1460）
    if (bytesRead < 0) break       // 连接关闭

    val responsePacket = buildTcpDataPacket(session, data, bytesRead)
    val manipulated = pipeline.processIncoming(responsePacket)

    for (p in manipulated) {
        writeToTun(p.rawBytes)     // 写入 TUN → 送达 app
    }

    session.addAndGetServerSeq(bytesRead)  // 原子递增（按字节数）
    session.lastActivityTime = now
}
// relay 循环结束后，发送 FIN 通知 app
sendFinToApp(session)
tcpSessionManager.removeSession(session)
```

**buildTcpDataPacket 构造过程**：
1. 创建 `20(IP) + 20(TCP) + length` 字节数组
2. 写入 IP 头（src=上游服务器 IP，dst=app IP，protocol=6）
3. 写入 TCP 头（src=上游端口，dst=app 端口，seq=`serverSeq`，ack=`clientNextSeq`，flags=PSH|ACK）
4. 拷贝数据载荷
5. 计算 TCP 校验和（含伪首部：srcIP + dstIP + protocol + length）

**seq 号管理**：
- `serverSeq`：使用 `AtomicInteger.addAndGet()` 按**字节数**原子递增。TCP seq 是字节流偏移量
- `clientNextSeq`：在收到 app 数据时更新，用于构造 ack 号

---

## 网络闪断（DisconnectScheduler）

### 工作原理

`DisconnectScheduler` 使用基于时间的周期性窗口法模拟网络闪断：

```
时间轴（每个 intervalMs 为一个周期）：
├──────────────────────────────────┤├──────┤
│        连接正常（app 可通信）        │ 闪断  │
│                                    │ 窗口  │
├─────────────── intervalMs ─────────┤ durMs │
0                                  phase    intervalMs
```

- 在每个 `intervalMs` 周期中，最后 `durationMs` 是闪断窗口
- `tick()` 方法每 50ms 被调用一次，计算 `phase = elapsed % intervalMs`
- 当 `phase >= intervalMs - durationMs` 时进入闪断状态

### 状态转换

```kotlin
enum class State { CONNECTED, DISCONNECTED, JUST_RECONNECTED }

fun tick(): State {
    val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
    val phase = elapsed % intervalMs
    val shouldDisconnect = phase >= intervalMs - durationMs

    return when {
        !wasDisconnected && shouldDisconnect → DISCONNECTED（进入闪断）
        wasDisconnected && !shouldDisconnect → JUST_RECONNECTED（恢复连接）
        else → 当前状态
    }
}
```

### 闪断处理流程

当 `JUST_RECONNECTED` 状态返回时，`PacketProcessor.handleDisconnectTick()` 执行：

```
1. 快照所有活跃会话的 serverSeq（在 close 之前）
2. 关闭所有 TCP 会话（close SocketChannel + CAS 状态为 CLOSED）
3. 向 app 发送 RST 包（使用快照的 serverSeq 作为 seq）
4. 清除所有会话记录
5. 乱序缓冲区中的包被丢弃
```

**为什么先快照再关闭**：`close()` 会修改 session 状态，先快照保证 RST 包的 seq 在 app 的接收窗口内（RFC 793 要求）。

### 数据包层面的闪断效果

- `isDisconnected = true` 期间，所有新到达的数据包（TCP/UDP/ICMP）被静默丢弃
- TCP relay 线程在 `ch.read()` 上阻塞，数据不会丢失（仍在内核缓冲区中），恢复后继续读取
- UDP 数据丢失（UDP 不可靠），与真实断网行为一致

---

## DNS 故障模拟

### DnsResponder

`DnsResponder` 是无状态单例对象，负责构造 DNS 响应包。

**三种 DNS 故障模式**：

| 模式 | 行为 | DnsResponder 调用 |
|------|------|-------------------|
| `TIMEOUT` | 静默丢弃 DNS 查询 | 不调用（直接 return） |
| `FAILURE` | 立即返回 SERVFAIL | `buildErrorResponse(query, RCODE_SERVFAIL)` |
| `HIJACK` | 返回伪造 IP 的 A 记录 | `buildHijackResponse(query, hijackIp)` |

### DNS 故障拦截位置

```
handleUdp(packet)
  → 创建/获取 UDP 会话
  → 检查 udp.destinationPort == 53 && dnsFaultType != NONE
      ├── TIMEOUT: return（静默丢弃）
      ├── FAILURE: DnsResponder.buildErrorResponse() → writeToTun → return
      └── HIJACK:  DnsResponder.buildHijackResponse() → writeToTun → return
```

### DNS 响应构造

**buildErrorResponse**：
1. 复制查询的事务 ID（前 2 字节）
2. 设置 QR=1, RD=1, RA=1 标志
3. 复制查询段（question section）
4. 无应答记录（ANCOUNT=0）

**buildHijackResponse**：
1. 复制查询的事务 ID 和查询段
2. 对于 AAAA 查询（type=28）：返回空应答（rcode=0，无记录）
3. 对于 A 查询（type=1）：
   - 构造 Answer 段：NAME 指针（compression）→ TYPE=A → CLASS=IN → TTL=60 → RDLENGTH=4 → rdata=hijackIp
   - QDCOUNT=1, ANCOUNT=1

---

## UDP 生命周期

### 出方向（app → 上游）

```
  App                    VPN                                 上游
  │                       │                                   │
  │──── UDP 数据报 ──────>│                                   │
  │                getOrCreateSession()                        │
  │                  (四元组查表/创建)                           │
  │                检查 DNS 故障（port 53）                      │
  │                更新统计计数                                  │
  │                提交到 udpExecutor ──┐                        │
  │                                    │ pipeline.processOutgoing()
  │                                    │   [节流→乱序→重发→丢包→篡改]
  │                                    │ applyDelay()            │
  │                                    │   sleep(delay + jitter) │
  │                                    │── 数据报 ─────────────>│
```

**代码流程**：
```kotlin
private fun handleUdp(packet: IpPacket) {
    val session = udpSessionManager.getOrCreateSession(...) ?: return

    // DNS 故障拦截
    if (udp.destinationPort == 53 && dnsFaultType != NONE) {
        if (handleDnsFault(udpPayload, session)) return
    }

    totalBytesSent += packet.payload.size
    totalPacketsSent++

    udpExecutor.submit {
        val manipulated = pipeline.processOutgoing(packet)
        applyDelay()  // sleep(delay + calculateJitter(jitterMs))
        for (p in manipulated) {
            channel.write(ByteBuffer.wrap(p.payload))
        }
    }
}
```

**如果管线丢包**：`manipulated` 为空列表，for 循环不执行，数据报被丢弃。

### 入方向（上游 → app）

```
  App                    VPN                                 上游
  │                       │                                   │
  │                       │<── 响应数据报 ─────────────────────│
  │                UpstreamReader.poll()                       │
  │                  (每 15ms 轮询所有 UDP 会话)                  │
  │                channel.read(buffer)                        │
  │                handleUdpResponse()                         │
  │                提交到 udpExecutor ──┐                        │
  │                                    │ pipeline.processIncoming()
  │                                    │   [节流→乱序→重发→丢包→篡改]
  │                                    │ applyDelay()            │
  │                                    │   sleep(delay + jitter) │
  │<── 响应数据报 ─────────────────────│ writeToTunFragmented()  │
```

**代码流程**：
```kotlin
fun handleUdpResponse(session: UdpSession, data: ByteArray, length: Int) {
    val responsePacket = buildUdpResponsePacket(session, data, length)

    udpExecutor.submit {
        val manipulated = pipeline.processIncoming(responsePacket)
        applyDelay()
        for (p in manipulated) {
            writeToTunFragmented(p.rawBytes)
        }
    }
}
```

### UDP 会话管理

**UdpSession 结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `channel` | `DatagramChannel` | `protect()` 保护的非阻塞 UDP Socket |
| `sourceIp/Port` | `ByteArray/Int` | TUN 侧地址（防御性拷贝） |
| `destIp/Port` | `ByteArray/Int` | 上游地址（防御性拷贝） |
| `lastActivityTime` | `@Volatile Long` | 最后活动时间 |

**会话创建流程**（含 fd 泄露防护）：
1. `DatagramChannel.open()` → 打开 UDP 通道
2. `configureBlocking(false)` → 非阻塞模式（配合 UpstreamReader 轮询）
3. `vpnService.protect(socket)` → **必须成功**，否则数据路由回 VPN 形成环路
4. `connect(address)` → 连接到远程端点
5. 存入 `ConcurrentHashMap`
6. 如果创建失败，在 catch 中关闭已打开的 `DatagramChannel`，防止 fd 泄露

**超时**：30 秒无活动自动清理（SessionCleanup 协程每 10s 检查一次）。

### UDP RTT 计算

```
UDP RTT = 出方向 delay + 入方向 delay = 2 × delayMs
```

延迟在出/入方向各施加一次：
- 出方向：在 `udpExecutor` 中 `Thread.sleep(delay + calculateJitter(jitterMs))`
- 入方向：在 `udpExecutor` 中 `applyDelay()` → `Thread.sleep(delay + calculateJitter(jitterMs))`

加上真实网络延迟，总 RTT ≈ `2 × delayMs + 真实网络 RTT`。

### writeToTunFragmented（自动 IP 分片）

UDP 响应可能超过 MTU（1500 字节），写入 TUN 前自动按 RFC 791 分片：

```
原始 IP 包（> MTU）
  → 计算最大分片载荷：((MTU - IP头长) / 8) * 8（必须是 8 的整数倍）
  → 按序分片：
      ├── 分片 1：MF=1, offset=0
      ├── 分片 2：MF=1, offset=maxFragPayload
      └── 分片 N：MF=0, offset=(N-1)*maxFragPayload
  → 每片重算 IP 头校验和
  → 逐片 writeToTun()
```

TCP 响应不需要分片，因为 `relayUpstream()` 每次最多读取 `MTU - 40 = 1460` 字节。

---

## ICMP (Ping)

```
App ── Echo Request ──> VPN
                        │
                        提交到 udpExecutor
                        └── {
                              pipeline.processOutgoing()（丢包检查）
                              applyDelay()
                              复制请求数据
                              reply[0] = 0  (type: echo reply)
                              reply[1] = 0  (code: 0)
                              重算 ICMP 校验和
                              构造 IP 包（交换 src/dst IP）
                              writeToTun()
                            }
App <── Echo Reply ──── VPN
                        (不走真实网络，纯本地构造)
```

**为什么不走真实 ICMP**：Android 上 raw ICMP socket 行为不可靠，部分设备不支持。改为直接构造 fake echo reply，延迟和丢包效果完全等价。

**构造过程**：
1. 复制原始 ICMP 数据（保留 identifier、sequence、payload）
2. 修改 `type` 字段从 8（echo request）改为 0（echo reply）
3. 清空校验和字段，重新计算 ICMP 校验和
4. 构造 IP 包：源 IP = 原 dst IP，目的 IP = 原 src IP，protocol = 1
5. 写入 TUN

---

## 操控管线

### 管线执行顺序

**throttle → reorder → duplicate → loss → tamper**

延迟由 PacketProcessor 在各协议处理中单独施加（见下节"延迟施加位置"），不在管线内。

### ManipulationPipeline 实现

```kotlin
class ManipulationPipeline(
    condition: NetworkCondition,
    delayModel: DelayModel = DelayModel.UNIFORM,
    lossModel: LossModel = LossModel.RANDOM,
) {
    val delayMs = condition.delayMs
    val jitterMs = condition.jitterMs
    val hasDisconnect = condition.disconnectEnabled
    val disconnectIntervalMs = condition.disconnectIntervalMs
    val disconnectDurationMs = condition.safeDisconnectDurationMs
    val dnsFaultType = condition.dnsFaultType
    val dnsHijackIp = condition.dnsHijackIp

    private val manipulators = listOf(
        ThrottleManipulator(uploadSpeedKbps, downloadSpeedKbps),
        ReorderManipulator(bufferSize = reorderBufferSize),
        DuplicateManipulator(duplicatePercent),
        PacketLossManipulator(packetLossPercent, lossModel),
        TamperManipulator(tamperPercent),
    )

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

    fun calculateJitter(jitterMs: Int): Long { ... }
}
```

**为什么返回 `List<IpPacket>`**：管线可能增减包数量——丢包返回空列表，重发返回 `[原包, 副本]`，正常返回 `[包]`。使用 `flatMap` 将每个操控器的结果展平后传给下一个。

### 控制包不走管线

以下类型的包**不经过**操控管线，直接处理：

| 包类型 | 原因 |
|--------|------|
| TCP SYN | 延迟在 SYN-ACK 线程中单独施加 |
| TCP ACK（payload=0） | 延迟/丢包会导致连接建立失败 |
| TCP FIN | 应用层主动关闭，在 outgoingExecutor 中保序处理 |
| TCP RST | 连接异常，应立即清理 |
| ICMP | 延迟和丢包在 handleIcmp 中单独处理 |

只有**有载荷的 TCP 数据包**和 **UDP 数据包**经过管线。

### TCP 数据在管线中的特殊处理

TCP 数据包虽然经过管线，但部分操控器对 TCP 有特殊行为：

| 操控器 | TCP 出方向 | TCP 入方向 | UDP |
|--------|-----------|-----------|-----|
| Throttle | 正常节流 | 正常节流 | 正常节流 |
| Reorder | 跳过 | 跳过 | 正常乱序 |
| Duplicate | 生成副本但不重复写上游 | 正常重复 | 正常重复 |
| Loss | 管线中跳过（由 PacketProcessor pre-ACK 判定） | 跳过 | 正常丢包 |
| Tamper | 跳过 | 跳过 | 正常篡改 |

**核心原则**：VPN 代理模型中，TCP 数据在到达管线时已被确认（出方向已 ACK，入方向已从 socket 读出）。任何会导致数据永久丢失或损坏的操控（丢包、篡改、缓冲重排）都必须跳过。仅 Throttle（延迟不丢数据）和 Duplicate（副本可安全忽略）可作用于 TCP。

### 5 种操控器详解

#### 1. ThrottleManipulator（节流）

**原理**：per-session 时间调度法——每个 session 独立的 `RateController`，跟踪 `nextSendTimeNanos`，每个包必须等到自己的发送时刻才能通过。

```kotlin
class RateController(speedKbps: Int) {
    private val nanosPerByte = 1_000_000_000.0 / (speedKbps * 1000L / 8L)
    private var nextSendTimeNanos = System.nanoTime()

    fun acquire(bytes: Int) {
        val waitNs = synchronized(this) {
            val now = System.nanoTime()
            val sendAt = maxOf(nextSendTimeNanos, now)
            nextSendTimeNanos = sendAt + (bytes * nanosPerByte).toLong()
            sendAt - now
        }
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    }
}
```
```

**节流速率计算示例**：
- `30 kbps` = `3750 字节/秒` → 每字节 `266,667 ns` → 1500 字节包需 `400ms`
- `250 kbps` = `31250 字节/秒` → 每字节 `32,000 ns` → 1500 字节包需 `48ms`

**关键设计**：
- **per-session 隔离**：使用 `ConcurrentHashMap<String, RateController>` 按 5 元组 session key 隔离。不同连接的节流互不影响，避免一个慢速连接阻塞其他连接
- **累计 `nextSendTime`**：不是每包独立计时，而是累计。如果前一个包的发送还没到时间，下一个包必须等待
- **锁外 sleep**：`synchronized` 仅保护时间计算（纳秒级），`Thread.sleep()` 在锁外执行。多线程并发调用时，各自计算等待时间后并行 sleep，互不阻塞
- **上传/下载分开控制**：`uploadControllers` 和 `downloadControllers` 独立工作
- **0 速度 = 不节流**：`nanosPerByte = 0` 时直接跳过

#### 2. ReorderManipulator（乱序）

**原理**：按会话隔离的相邻包交换法——以一定概率将当前包与前一个包交换位置。

**TCP 跳过**：两个方向都跳过 TCP。
- 出方向：VPN 已 ACK 数据，重排会破坏字节流顺序
- 入方向：数据已从上游 socket 读出，缓冲首包会导致永久丢失（无人重传）

```kotlin
class ReorderManipulator(bufferSize: Int) {
    private val swapChance = (bufferSize / 20f).coerceAtMost(1.0f)
    private val outgoingBuffers = ConcurrentHashMap<String, IpPacket>()
    private val incomingBuffers = ConcurrentHashMap<String, IpPacket>()

    override fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
        if (swapChance <= 0) return listOf(packet)
        if (packet.ipHeader.isTcp) return listOf(packet)  // TCP 跳过
        // ... 交换逻辑
    }

    override fun manipulateIncoming(packet: IpPacket): List<IpPacket> {
        if (swapChance <= 0) return listOf(packet)
        if (packet.ipHeader.isTcp) return listOf(packet)  // TCP 跳过
        // ... 交换逻辑
    }
}
```

**交换逻辑**：
1. `put` 当前包到 per-session 缓冲区，获取前一个包
2. 如果有前一个包：以 `swapChance` 概率返回 `[当前包, 前一个包]`（交换），否则 `[前一个包, 当前包]`（正常）
3. 如果没有前一个包（首个包）：缓冲当前包，返回空列表（等待下一个包）
4. 溢出保护：缓冲区超过 1024 条目时，直接放行

**会话级隔离**：使用五元组作为 key，不同 TCP/UDP 连接的乱序互不影响。这样当某个会话的 FIN 包被提交到 outgoingExecutor 时，可以精确 flush 该会话的缓冲区。

**flush 方法**：
- `flushOutgoing()` / `flushIncoming()` — 全量刷新（VPN 关闭时）
- `flushOutgoing(key)` / `flushIncoming(key)` — 单会话刷新（FIN 处理时）

使用条件删除 `map.remove(key, value)` 避免 race condition。

#### 3. DuplicateManipulator（重复）

**原理**：以一定概率返回包的深拷贝，管线后续处理和发送两次。

```kotlin
fun manipulateOutgoing(packet: IpPacket): List<IpPacket> {
    return if (Random.nextInt(100) < duplicatePercent) {
        listOf(packet, packet.copy())  // 深拷贝字节数组
    } else {
        listOf(packet)
    }
}
```

**无状态**，不维护任何内部变量。

#### 4. PacketLossManipulator（丢包）

**原理**：支持随机丢包（伯努利）和突发丢包（Gilbert 模型）两种模式。

**RANDOM 模式（伯努利）**：
```kotlin
Random.nextInt(100) < lossPercent  // 独立随机，每包以 lossPercent 概率丢弃
```

**BURST 模式（Gilbert 模型）**：
```kotlin
// 两状态马尔可夫链：好状态（几乎不丢包）↔ 坏状态（高概率丢包）
r = 1.0 / 3.0                                    // BAD → GOOD 转换概率
p = r * lossPercent / (100.0 - lossPercent)       // GOOD → BAD 转换概率
// 在坏状态时所有包被丢弃
// 模拟真实弱网的突发丢包特征
```

**TCP 丢包的特殊处理**：

TCP 数据的丢包不能简单丢弃（VPN 已 ACK 或已从上游读出），因此分两种情况：

| 方向 | 处理方式 | 原因 |
|------|----------|------|
| TCP 出方向 | 管线中跳过丢包；由 `PacketProcessor` 在 ACK 前通过 `shouldDropTcpOutgoing()` 判定 | 如果先 ACK 再丢包，数据永久丢失；不 ACK 则 app TCP 自然重传 |
| TCP 入方向 | 直接跳过丢包 | 数据已从上游 socket 读出（内核已 ACK），丢弃 = 永久丢失 |
| UDP 双方向 | 正常走丢包逻辑 | UDP 不可靠，丢包是预期行为 |

`shouldDropForTcp()` 方法将丢包判定委托给 `PacketLossManipulator`，支持 RANDOM 和 BURST 两种模型，保持与 UDP 丢包行为一致。

**LossModel 由 VpnStateHolder 管理**，通过 `StateFlow` 传递给 `ManipulationPipeline`。

#### 5. TamperManipulator（篡改）

**原理**：随机选载荷中一字节，XOR 随机值（1-255），模拟数据损坏。

**TCP 跳过**：两个方向都跳过 TCP。
- 出方向：VPN 已 ACK 数据，篡改 = 不可恢复的数据损坏
- 入方向：数据已从上游 socket 读出，篡改 = 永久损坏

```
篡改过程：
1. 深拷贝包字节
2. 计算 payload 起始偏移 = IP头长 + 传输层头长
3. 随机选 payload 中一个字节
4. bytes[offset] = bytes[offset] xor Random(1, 256)
5. 重算 IP 头校验和（offset 10-11）
6. 重算传输层校验和：
   - TCP: offset 16-17，使用伪首部（srcIP + dstIP + protocol + TCP length）
   - UDP: offset 6-7，使用伪首部（srcIP + dstIP + protocol + UDP length）
```

**必须重算校验和**：不重算的话接收方会因校验和不匹配而丢弃包，达不到"数据损坏但仍然传递"的效果。

**无状态**。

### 延迟模型

延迟抖动的计算方式由 `DelayModel` 枚举控制：

| 模型 | 分布 | 计算方法 |
|------|------|----------|
| `UNIFORM` | 均匀分布 | `Random.nextInt(-jitterMs, jitterMs + 1)` |
| `GAUSSIAN` | 正态分布（Box-Muller 变换） | 以 `jitterMs` 为标准差 σ，截断到 `[-3σ, +3σ]` |
| `LONG_TAIL` | 对数正态分布 | `exp(normal)`，截断到 `[0, 5×jitterMs]`。中位数≈1，P(X>5)≈1.6% |

**UNIFORM**：经典模型，所有抖动值等概率出现。
**GAUSSIAN**：更接近真实网络抖动分布，大部分抖动集中在均值附近，偶尔出现较大偏差。
**LONG_TAIL**：大部分时候抖动很小，偶尔出现极大值（长尾），模拟突发延迟毛刺。

### 管线顺序选择原因

```
throttle → reorder → duplicate → loss → tamper
```

| 顺序 | 操控器 | 原因 |
|------|--------|------|
| 1 | 节流 | 首先控制发送速率，后续操控在速率限制内处理 |
| 2 | 乱序 | 在节流之后交换，保持节流的时间调度语义 |
| 3 | 重复 | 在丢包之前复制，使丢包能同时影响原始和副本 |
| 4 | 丢包 | 在篡改之前决定是否丢弃，避免无意义的篡改计算 |
| 5 | 篡改 | 最后修改数据，保证篡改后的内容就是最终发送的内容 |

---

## 延迟施加位置汇总

延迟不在管线内，由 PacketProcessor 在各协议路径中单独施加。

| 场景 | 施加位置 | 线程 | 说明 |
|------|----------|------|------|
| TCP SYN-ACK | 连接线程 `Thread.sleep(delay+jitter)` → `sendSynAck()` | connectExecutor | 模拟握手 RTT |
| TCP 数据（出） | **不施加** | — | 逐包延迟会累积堆叠，导致流式传输卡死 |
| TCP 数据（入） | **不施加** | — | 同上 |
| UDP 出方向 | 发送线程 `applyDelay()` → `Thread.sleep(delay+jitter)` | udpExecutor | 每包独立延迟 |
| UDP 入方向 | 响应处理线程 `applyDelay()` → `Thread.sleep(delay+jitter)` | udpExecutor | 每包独立延迟 |
| ICMP Echo Reply | 后台线程 `applyDelay()` → `Thread.sleep(delay+jitter)` | udpExecutor | 模拟 ping RTT |

**抖动（jitter）计算**：
```kotlin
fun calculateJitter(jitterMs: Int): Long {
    val jitter = when (delayModel) {
        UNIFORM   -> Random.nextInt(-jitterMs, jitterMs + 1)
        GAUSSIAN  -> (Random.nextGaussian() * jitterMs).toInt()
                      .coerceIn(-3 * jitterMs, 3 * jitterMs)
        LONG_TAIL -> exp(abs(Random.nextGaussian()) * 0.5).toInt()
                      .coerceAtMost(5 * jitterMs)
    }
    return (delayMs + jitter).coerceAtLeast(0).toLong()
}
```

---

## 状态管理

### VpnStateHolder（进程级单例）

使用 Kotlin `object` + `StateFlow` 实现进程内全局状态共享，连接 `MainActivity`、`WeakNetVpnService`、`FloatingWindowService`。

```
VpnStateHolder (object)
  │
  ├── MutableStateFlow<Boolean> isRunning        ← VPN Service 设置
  ├── MutableStateFlow<Boolean> isPaused          ← UI/FloatingWindow 设置 → PacketProcessor 读取
  ├── MutableStateFlow<NetworkProfile?> activeProfile  ← UI 设置 → VPN Service 读取（构建 Pipeline）
  ├── MutableStateFlow<Set<String>> selectedApps       ← UI 设置 → VPN Service 读取（路由配置）
  ├── MutableStateFlow<TrafficStats> stats             ← VpnThread 每 500ms 更新 → UI/FloatingWindow 收集
  │     ├── totalBytesSent / totalBytesReceived
  │     ├── uploadSpeedBps / downloadSpeedBps
  │     ├── activeTcpSessions / activeUdpSessions
  │     └── totalPacketsSent
  ├── MutableStateFlow<DelayModel> delayModel     ← UI 设置 → VPN Service 读取（构建 Pipeline）
  ├── MutableStateFlow<LossModel> lossModel        ← UI 设置 → VPN Service 读取（构建 Pipeline）
  │
  ├── reset()              → 清空所有状态（VPN 完全停止时调用）
  └── resetForRestart()    → 仅清空 running/paused/stats，保留 profile/apps/models（VPN 重启时调用）
```

**线程安全**：`MutableStateFlow` 内部保证原子性，暴露为只读 `StateFlow` 供外部收集。

### TrafficStats 数据结构

```kotlin
data class TrafficStats(
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val totalPacketsSent: Long = 0,
    val uploadSpeedBps: Long = 0,       // 字节/秒
    val downloadSpeedBps: Long = 0,     // 字节/秒
    val activeTcpSessions: Int = 0,
    val activeUdpSessions: Int = 0,
)
```

### 速度计算

```kotlin
// VpnThread.updateStats()，每 500ms 调用一次
val elapsed = (now - lastSpeedCalcTime).coerceAtLeast(1)
uploadSpeedBps = (bytesSent - lastBytesSent) * 1000 / elapsed
downloadSpeedBps = (bytesReceived - lastBytesReceived) * 1000 / elapsed
```

---

## 网络预设

### NetworkCondition 所有参数

```kotlin
data class NetworkCondition(
    val delayMs: Int = 0,               // 固定延迟（毫秒）
    val jitterMs: Int = 0,              // 抖动范围（毫秒）
    val packetLossPercent: Int = 0,     // 丢包概率（0-100）
    val uploadSpeedKbps: Int = 0,       // 上传限速（千比特/秒，0=不限）
    val downloadSpeedKbps: Int = 0,     // 下载限速（千比特/秒，0=不限）
    val duplicatePercent: Int = 0,      // 重复概率（0-100）
    val reorderBufferSize: Int = 0,     // 乱序强度（映射为交换概率 bufferSize/20）
    val tamperPercent: Int = 0,         // 篡改概率（0-100）
    val disconnectEnabled: Boolean = false,       // 是否启用网络闪断
    val disconnectIntervalMs: Int = 30000,        // 闪断周期（毫秒）
    val disconnectDurationMs: Int = 2000,         // 每次断开时长（毫秒）
    val dnsFaultType: DnsFaultType = DnsFaultType.NONE,  // DNS 故障类型
    val dnsHijackIp: String = "1.2.3.4",                 // DNS 劫持目标 IP
)
```

**safeDisconnectDurationMs**：计算属性，保证 `duration < interval - 1000`（至少 1 秒连接窗口），防止 `DisconnectScheduler` 的 `require` 断言失败。

### 12 个预设详情

| 预设 | 延迟 | 抖动 | 丢包 | 上传 | 下载 | 重复 | 乱序 | 篡改 | 闪断 | DNS | 说明 |
|------|------|------|------|------|------|------|------|------|------|-----|------|
| 无限制 | 0ms | 0 | 0% | 0 | 0 | 0 | 0 | 0% | - | - | 不施加任何限制 |
| 2G (EDGE) | 300ms | 100 | 5% | 30kbps | 50kbps | 0 | 0 | 0% | - | - | 极慢 |
| 3G (HSPA) | 100ms | 40 | 2% | 250kbps | 750kbps | 0 | 0 | 0% | - | - | 慢但可用 |
| 4G 弱信号 | 50ms | 20 | 1% | 1000kbps | 3000kbps | 0 | 0 | 0% | - | - | 弱信号区域 |
| WiFi 弱 | 30ms | 15 | 0% | 2000kbps | 5000kbps | 0 | 0 | 0% | - | - | 距离路由器远 |
| 地铁 | 200ms | 150 | 8% | 500kbps | 1000kbps | 0 | 0 | 0% | 周期30s/持续2s | - | 不稳定，进隧道时周期性断连 |
| 电梯 | 500ms | 200 | 15% | 50kbps | 100kbps | 0 | 0 | 0% | 周期20s/持续3s | - | 极差，频繁短暂断连 |
| 高延迟 | 2000ms | 500 | 0% | 0 | 0 | 0 | 0 | 0% | - | - | 卫星网络级别 |
| 丢包地狱 | 100ms | 50 | 30% | 0 | 0 | 5% | 4 | 0% | - | - | 极端丢包，测试重连逻辑 |
| 高铁 | 150ms | 100 | 5% | 300kbps | 800kbps | 0 | 0 | 0% | 周期60s/持续4s | - | 经过偏远地区时周期性断网 |
| DNS 超时 | 0ms | 0 | 0% | 0 | 0 | 0 | 0 | 0% | - | TIMEOUT | DNS 解析超时 |
| 数据校验测试 | 0ms | 0 | 0% | 0 | 0 | 0 | 0 | 10% | - | - | 10% 数据篡改，安全与容错测试 |

---

## 关键设计决策

| 决策 | 原因 |
|------|------|
| 先发假 SYN-ACK 再连真实服务器 | 让 app 快速建立连接，不阻塞等握手 |
| 纯 ACK (payload=0) 直接丢弃 | 控制包不走管线，避免延迟导致连接失败 |
| TCP 数据不做逐包延迟 | TCP 是流式传输，逐包延迟会累积堆叠 |
| TCP 丢包在 ACK 前判定（不 ACK = 让 app 重传） | 先 ACK 再丢包会导致数据永久丢失；不 ACK 则 app TCP 自然重传，模拟真实丢包 |
| TCP 出方向管线跳过 Reorder/Tamper/Loss | VPN 已 ACK 数据，重排/篡改/丢弃会破坏已确认的数据 |
| TCP 入方向管线跳过 Reorder/Tamper/Loss | 数据已从上游 socket 读出（内核已 ACK），缓冲/篡改/丢弃 = 永久损坏 |
| TCP 重复包不重复写上游 | TCP 是字节流，重复写入会损坏数据；DuplicateManipulator 的副本只走统计 |
| ACK 在管线处理前发送 | 不让管线处理时间延迟 ACK，app 侧更快收到确认 |
| protect() 每个 socket | 不 protect 数据会回到 VPN 形成死循环 |
| protect() 失败直接中止 | 路由环路比连接失败更严重 |
| TUN fd 关闭才能中断 PacketReader | FileInputStream.read() 不可中断，只能 close fd 解除阻塞 |
| SupervisorJob | 单个协程异常不会取消其他协程 |
| ConcurrentHashMap | 线程安全的会话访问，无需显式加锁 |
| outgoingExecutor 4 线程 + per-session writeLock | 并行处理多连接，writeLock 保证同一 session 的写顺序 |
| connectExecutor CachedThreadPool | relayUpstream 阻塞占用线程，按需创建避免队列等待 |
| udpExecutor CachedThreadPool | 每个 UDP 包需要 sleep(delay)，按需创建避免固定线程池队列堆积 |
| icmpExecutor 独立线程池 | ICMP ping 延迟不被 UDP 流量阻塞，保证延迟准确 |
| dnsExecutor 独立线程池 | DNS 故障响应不被其他流量延迟 |
| FIN 在 outgoingExecutor 中处理 | 保证 FIN 在所有已排队数据之后，避免乱序 |
| RateController 锁外 sleep | synchronized 仅保护时间计算，sleep 不阻塞其他线程 |
| per-session RateController | 避免跨连接速率限制导致某个连接独占全部带宽 |
| IP 分片重组 | 大 UDP 报文被内核分片后逐片到达 TUN，需重组才能提取完整载荷 |
| ICMP 不走真实网络 | Android 上 raw ICMP socket 行为不可靠，改为构造 fake echo reply |
| 双路由策略 | 0.0.0.0/1 + 128.0.0.0/1 兼容国产 ROM |
| UDP 校验和必填 | Android 网络栈会校验 UDP checksum，填 0 会被丢弃 |
| 10.0.0.x 虚拟网段 | 避免 192.168.x.x 与常见路由器冲突 |
| 浮窗 ComposeView 手动实现 LifecycleOwner | Service 没有 lifecycle，需手动管理 |
| RST 包携带 ACK 标志 | RFC 793：如果 ackNumber 非零，RST 必须携带 ACK |
| RST seq 在接收窗口内 | RFC 793：RST 的 seq 必须落在接收方的窗口范围内 |
| TCP 重传无符号比较 | 序列号是 unsigned 32-bit，需转为 Long 再比较 |
| CAS 状态转换 | TcpSession 的 SYN_RECEIVED→ESTABLISHED→CLOSED 使用 CAS 防止竞态 |
| pendingData.compute() | 原子化追加数据到缓冲区，避免连接线程和 outgoingExecutor 并发问题 |
| DisconnectScheduler 先快照再关闭 | 在关闭 session 前快照 serverSeq，保证 RST 的 seq 有效 |
| DatagramChannel 创建异常保护 | catch 中关闭已打开的 channel，防止 fd 泄露 |
| TCP 连接超时 5s | 防止对不可达服务器阻塞线程 indefinitely |

---

## VPN 关停流程

```
WeakNetVpnService.stopVpn()
  │
  ├── AtomicBoolean CAS（防止重复 stop）
  │
  ├── vpnHandler?.stop()              // 设置 stopped = true 标志
  │
  ├── vpnThread?.interrupt()          // 中断线程
  ├── vpnThread?.join(10_000)         // 等待线程结束（最多 10 秒）
  │
  ├── vpnInterface?.close()           // 关闭 TUN fd
  │   └── FileInputStream.read() 抛出 IOException
  │       └── PacketReader 线程退出
  │
  ├── VpnStateHolder.resetForRestart()  // 保留 profile/config，仅清空运行状态
  ├── stopFloatingWindow()
  └── stopSelf()
```

**VpnThread.run() 的 finally 块执行清理**：

```
finally {
    stopped = true
    packetReader.stop()              // 设置 running = false, close vpnInput
    packetProcessor.shutdown()       // 关闭所有 TCP 会话，shutdownNow 三个线程池
    upstreamReader.stop()            // 设置 running = false
    udpJob.cancel()                  // 取消 UDP 轮询协程
    statsJob.cancel()                // 取消统计更新协程
    cleanupJob.cancel()              // 取消会话清理协程
    disconnectJob?.cancel()          // 取消闪断协程
    coroutineScope.cancel()          // 取消所有协程

    tcpSessionManager.allSessions().forEach { it.close() }
    udpSessionManager.allSessions().forEach { it.channel.close() }

    vpnInput.close()
    vpnOutput.close()
}
```

**PacketProcessor.shutdown()**：

```
1. 关闭所有 TCP 会话（close SocketChannel + CAS CLOSED）
2. shutdownNow() 五个线程池（interrupt 正在执行的任务）
3. awaitTermination(2s) 等待每个线程池关闭
```

**关键顺序**：先 interrupt + join 等待 VpnThread 退出（其 finally 会关闭所有资源），再关闭 TUN fd。`resetForRestart()` 保留 profile/apps/models 配置，方便用户快速重启 VPN。

---

## TCP 包构造详解

### IP 头构造（writeIpHeader）

```
字节偏移：  0    1    2-3   4-5   6-7   8    9   10-11  12-15  16-19
         ┌────┬────┬─────┬─────┬─────┬────┬────┬─────┬──────┬──────┐
         │0x45│ 0  │总长 │随机 │0x4000│TTL │协议│CS=0 │ srcIP│dstIP│
         └────┴────┴─────┴─────┴─────┴────┴────┴─────┴──────┴──────┘
         │    │              │                 │    │
         │版本=4            │DF=1             64   6=TCP
         │IHL=5(20字节)   随机identification       17=UDP
                                                      1=ICMP
```

### SYN-ACK 构造（sendSynAck）

```
44 字节 = 20(IP) + 24(TCP, 含选项)
  IP: src=原dstIP, dst=原srcIP, protocol=6
  TCP: srcPort=原dstPort, dstPort=原srcPort
       seq=serverSeq, ack=clientNextSeq
       flags=SYN|ACK, window=65535
  TCP 选项:
       MSS=1460 (最大段大小)
       Window Scale=4 (窗口扩大因子，实际窗口可达 ~1MB)
  → serverSeq++  (SYN 占 1 个 seq)
```

### ACK 构造（sendAck）

```
40 字节 = 20(IP) + 20(TCP)
  IP: src=原dstIP, dst=原srcIP, protocol=6
  TCP: srcPort=原dstPort, dstPort=原srcPort
       seq=serverSeq, ack=参数ackNum
       flags=ACK, window=65535
```

### FIN-ACK 构造（sendFinAck / sendFinToApp）

```
40 字节 = 20(IP) + 20(TCP)
  TCP: seq=serverSeq, ack=clientNextSeq
       flags=FIN|ACK, window=65535
  → serverSeq++  (FIN 占 1 个 seq)
```

### RST 构造（sendRst / sendRstToSession）

**sendRstToSession（会话存在时）**：
```
40 字节 = 20(IP) + 20(TCP)
  TCP: seq=serverSeqSnapshot, ack=clientNextSeq
       flags=RST|ACK, window=0
  // seq 使用快照值（在 session.close() 之前捕获），保证在 app 的接收窗口内
  // ack 确认所有 app 数据
```

**sendRst（无会话时）**：
```
40 字节 = 20(IP) + 20(TCP)
  TCP: seq=tcp.ackNumber, ack=tcp.sequenceNumber
       flags=RST|ACK, window=0
  // seq = app 期望的服务器 seq（即 app 的 ackNumber）
  // ack = app 的下一个 seq
```

**RFC 793 合规**：
- RST 包必须携带 ACK 标志（当 ackNumber 非零时）
- RST 的 seq 必须落在接收方的窗口范围内，否则会被忽略

### TCP 数据包构造（buildTcpDataPacket）

```
20 + 20 + data.length 字节
  IP: src=destIp(上游), dst=sourceIp(app), totalLength=20+20+length
  TCP: srcPort=destPort(上游), dstPort=sourcePort(app)
       seq=serverSeq, ack=clientNextSeq
       flags=PSH|ACK, window=65535
  payload: data[0..length]
  → 重算 TCP 校验和
```

### UDP 响应包构造（buildUdpResponsePacket）

```
20 + 8 + data.length 字节
  IP: src=destIp(上游), dst=sourceIp(app), protocol=17
  UDP: srcPort=destPort(上游), dstPort=sourcePort(app)
       length=8+data.length
  payload: data[0..length]
  → 重算 UDP 校验和（含伪首部）
```

**UDP 校验和不能省略**：Android 网络栈会严格校验 UDP checksum，填 0 会导致包被内核丢弃。

---

## 校验和计算

### IP 头校验和

```
清零校验和字段（offset 10-11）
将 IP 头视为 16bit 序列
累加所有 16bit 字（进位回卷）
取反得到校验和
```

### TCP/UDP 校验和（含伪首部）

```
伪首部（12 字节）：
  ┌──────────────────┐
  │ 源 IP (4字节)     │
  ├──────────────────┤
  │ 目的 IP (4字节)   │
  ├──────┬─────┬─────┤
  │ 保留  │协议 │长度  │
  │(3字节)│(1)  │(2)  │
  └──────┴─────┴─────┘

计算过程：
1. 清零校验和字段
2. 构造伪首部（srcIP + dstIP + 0 + protocol + tcpUdpLength）
3. 拼接伪首部 + TCP/UDP 头 + 数据
4. 按标准算法计算校验和
5. 写入校验和字段
```

---

## 浮窗

`FloatingWindowService` 使用 `WindowManager.TYPE_APPLICATION_OVERLAY` + ComposeView 实现。需要 `SYSTEM_ALERT_WINDOW` 权限。

### DragFrameLayout 拖动机制

由于 ComposeView 子元素（Button、Text 等）会消费触摸事件，直接在容器上设置 `OnTouchListener` 无法接收事件。使用自定义 `DragFrameLayout` 解决：

```
DragFrameLayout (FrameLayout 子类)
  │
  ├── onInterceptTouchEvent(ev)
  │   ├── ACTION_DOWN: 记录初始位置和触摸坐标
  │   ├── ACTION_MOVE: 如果位移 > 5px（阈值），标记为拖动，拦截事件
  │   └── ACTION_UP: 重置拖动状态
  │
  ├── onTouchEvent(event)
  │   ├── ACTION_MOVE: 更新 WindowManager.LayoutParams 的 x/y
  │   │             → windowManager.updateViewLayout(this, params)
  │   └── ACTION_UP: 如果不是拖动（位移 < 5px），执行 performClick()
  │
  └── 包含 ComposeView
      └── FloatingWindowContent(@Composable)
          ├── 配置名 / 展开/收起按钮
          ├── 上传/下载速度
          └── 暂停/停止按钮、TCP/UDP 会话数（展开时显示）
```

**手动 LifecycleOwner**：Service 没有 lifecycle，需手动实现 `LifecycleOwner` / `SavedStateRegistryOwner`：

```kotlin
class FloatingWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(...) {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
```

---

## DNS 配置

VPN 配置了三个国内公共 DNS 服务器：
- `223.5.5.5`（阿里 DNS）
- `119.29.29.29`（腾讯 DNS）
- `114.114.114.114`（114 DNS）

DNS 查询以 UDP 数据报形式通过 VPN 转发。在启用 DNS 故障模拟时，DNS 查询会被拦截处理（见"DNS 故障模拟"章节）。在低速高丢包场景（如 2G: 30kbps/5% loss）下，DNS 可能受到节流和丢包影响。

---

## VPN 配置常量（VpnConfig）

| 常量 | 值 | 说明 |
|------|-----|------|
| `VIRTUAL_GATEWAY` | `"10.0.0.1"` | 虚拟网关地址 |
| `VIRTUAL_CLIENT` | `"10.0.0.2"` | 虚拟客户端地址 |
| `VIRTUAL_PREFIX_LENGTH` | `24` | 子网前缀长度 |
| `MTU` | `1500` | 最大传输单元 |
| `BUFFER_SIZE` | `32767` | TUN 读取缓冲区大小 |
| `SESSION_NAME` | `"WeakNet"` | VPN 会话名 |
| `DNS_SERVERS` | `["223.5.5.5", "119.29.29.29", "114.114.114.114"]` | DNS 服务器列表 |
