/**
 * @author awu
 * @date 2026-05-26
 * @desc 数据包处理核心引擎：实现用户态 TCP/IP 代理，负责协议转换、TCP 状态管理、操控管线调度
 */
package com.awu.weaknet.vpn.engine

import android.net.VpnService
import android.util.Log
import com.awu.weaknet.data.model.DnsFaultType
import com.awu.weaknet.util.ByteUtils
import com.awu.weaknet.vpn.manipulation.DisconnectScheduler
import com.awu.weaknet.vpn.manipulation.ManipulationPipeline
import com.awu.weaknet.vpn.nat.TcpSession
import com.awu.weaknet.vpn.nat.TcpSessionManager
import com.awu.weaknet.vpn.nat.TcpState
import com.awu.weaknet.vpn.nat.UdpSessionManager
import com.awu.weaknet.vpn.packet.IpFragmentReassembler
import com.awu.weaknet.vpn.packet.IpHeader
import com.awu.weaknet.vpn.packet.IpPacket
import com.awu.weaknet.vpn.packet.TcpHeader
import com.awu.weaknet.vpn.VpnConfig
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 数据包处理核心——实现用户态 TCP/IP 代理。
 *
 * 整个架构分两侧：TUN 侧看到的是 app 发出的虚拟 TCP 包，上游侧是 protect() 过的真实 SocketChannel。
 * 本类负责在两者之间做协议转换、状态管理、以及操控管线的调度。
 */
class PacketProcessor(
    private val tcpSessionManager: TcpSessionManager,
    private val udpSessionManager: UdpSessionManager,
    private val pipeline: ManipulationPipeline,
    private val tunOutput: FileOutputStream,
    private val vpnService: VpnService,
    private val disconnectScheduler: DisconnectScheduler? = null,
    private val dnsFaultType: DnsFaultType = DnsFaultType.NONE,
    private val dnsHijackIp: String = "1.2.3.4",
) {
    private val tunLock = Any()
    private val totalBytesSent = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private val totalPacketsSent = AtomicLong(0)
    private val totalPacketsReceived = AtomicLong(0)

    // TCP 出方向线程池：多线程并行写，per-session 顺序由 writeLock 保证
    private val outgoingExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "NL-Outgoing").apply { isDaemon = true }
    }

    // TCP 连接+中继线程池：每个 TCP 连接生命周期内阻塞一个线程（connect + relayUpstream），
    // 使用 CachedThreadPool 按需创建线程，避免队列排队导致新连接等待
    private val connectExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "NL-Connect").apply { isDaemon = true }
    }

    // UDP 线程池：按需创建线程，避免 per-packet delay 占满固定线程导致队列堆积
    private val udpExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "NL-UDP").apply { isDaemon = true }
    }

    // ICMP 专用线程池：不被 UDP 流量阻塞，保证 ping 延迟准确
    private val icmpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NL-ICMP").apply { isDaemon = true }
    }

    // DNS 专用单线程池：不被其他 UDP/TCP 流量占满，保证 DNS 及时处理
    private val dnsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NL-DNS").apply { isDaemon = true }
    }

    // SYN-ACK 发出后 app 立刻开始发数据（如 TLS ClientHello），但此时真实连接可能还没建立好，
    // 需要先缓存，等 connectBlocking() 成功后再一次性写入上游
    private val pendingData = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()

    // IP 分片重组器：大 UDP 报文被内核分片后逐片到达 TUN，需要重组后才能提取完整载荷
    private val fragmentReassembler = IpFragmentReassembler()

    private val isDisconnected: Boolean get() = disconnectScheduler?.isDisconnected == true
    private val isPaused: Boolean get() = com.awu.weaknet.service.VpnStateHolder.isPaused.value

    fun processPacket(data: ByteArray, length: Int) {
        // 先尝试重组 IP 分片；非分片包直接返回原数据，分片未齐返回 null
        val reassembled = fragmentReassembler.process(data, length) ?: return
        val packet = IpPacket.parse(reassembled)
        if (packet == null) {
            Log.w(TAG, "Failed to parse packet, length=$length")
            return
        }
        val ip = packet.ipHeader
        if (isDisconnected) return
        if (isPaused) return
        Log.d(TAG, "PKT ${ip.protocol}/${ip.sourceAddressString}:${if (ip.isTcp) packet.tcpHeader?.sourcePort else packet.udpHeader?.sourcePort} -> ${ip.destinationAddressString}:${if (ip.isTcp) packet.tcpHeader?.destinationPort else packet.udpHeader?.destinationPort} len=${ip.totalLength}")
        when {
            ip.isTcp -> handleTcp(packet)
            ip.isUdp -> handleUdp(packet)
            ip.protocol == 1 -> handleIcmp(packet)
        }
    }

    private fun handleTcp(packet: IpPacket) {
        val ip = packet.ipHeader
        val tcp = packet.tcpHeader ?: return

        // ---- SYN: 新连接 ----
        if (tcp.isSyn && !tcp.isAck) {
            val dst = "${ip.destinationAddressString}:${tcp.destinationPort}"
            Log.d(TAG, "TCP SYN -> $dst")
            val existing = tcpSessionManager.getSession(
                ip.sourceAddressString, tcp.sourcePort,
                ip.destinationAddressString, tcp.destinationPort)
            // 同一四元组已有未关闭会话，丢弃重复 SYN（避免状态机混乱）
            if (existing != null && !existing.isClosed) return

            val session = tcpSessionManager.createSession(
                ip.sourceAddress, tcp.sourcePort,
                ip.destinationAddress, tcp.destinationPort,
                tcp.sequenceNumber)

            // 随机初始 seq，模拟真实服务器的初始序列号
            session.serverSeq = Random.nextInt(100000, Int.MAX_VALUE)

            // 必须在后台线程处理：先给 app 发 SYN-ACK 让它认为连接已建立，
            // 然后再阻塞等待真实服务器连接完成
            try {
            connectExecutor.submit {
                try {
                    // SYN-ACK 也要经过延迟，模拟真实网络的握手往返
                    val jitter = pipeline.calculateJitter(pipeline.jitterMs)
                    val synDelay = (pipeline.delayMs + jitter).coerceAtLeast(0).toLong()
                    if (synDelay > 0) Thread.sleep(synDelay)

                    sendSynAck(session, ip, tcp)
                    Log.i(TAG, "SYN-ACK $dst delay=${synDelay}ms")

                    val t0 = System.currentTimeMillis()
                    if (session.connectBlocking()) {
                        Log.i(TAG, "CONNECT $dst ${System.currentTimeMillis() - t0}ms")
                        // 真实连接建立成功，把握手期间 app 已经发来的数据一次性发送出去
                        val pendingWriteData: ByteArray?
                        pendingWriteData = pendingData.remove(session.id)
                        if (pendingWriteData != null) {
                            synchronized(session.writeLock) {
                                try {
                                    val wbuf = ByteBuffer.wrap(pendingWriteData)
                                    while (wbuf.hasRemaining()) {
                                        session.channel?.write(wbuf)
                                    }
                                    Log.d(TAG, "Sent ${pendingWriteData.size}B pending data to ${session.key}")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to write pending data: ${e.message}")
                                }
                            }
                        }
                        // 开始循环读取上游响应，此调用会阻塞直到连接关闭
                        relayUpstream(session)
                    } else {
                        pendingData.remove(session.id)
                        sendRstToSession(session)
                        tcpSessionManager.removeSession(session)
                    }
                } catch (e: Exception) {
                    pendingData.remove(session.id)
                    Log.w(TAG, "Connection thread error: ${e.message}")
                    sendRstToSession(session)
                    tcpSessionManager.removeSession(session)
                }
            }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // 连接池满，发 RST 让 app 端快速失败
                sendRstToSession(session)
                tcpSessionManager.removeSession(session)
            }
            return
        }

        // 查找已建立的会话
        val session = tcpSessionManager.getSession(
            ip.sourceAddressString, tcp.sourcePort,
            ip.destinationAddressString, tcp.destinationPort)
        // 找不到会话或已关闭，发 RST 让 app 端立即断开
        if (session == null || session.isClosed) {
            sendRst(ip, tcp)
            return
        }

        session.lastActivityTime = System.currentTimeMillis()

        // RST 直接清理会话，不进入操控管线（控制包不应被延迟/丢包）
        if (tcp.isRst) {
            tcpSessionManager.removeSession(session)
            return
        }

        // FIN 同理，不走操控管线——应用层主动关闭应尽快通知对端
        if (tcp.isFin) {
            val finPayloadSize = (ip.totalLength - ip.headerLength - tcp.headerLength).coerceAtLeast(0)
            val finPayload = if (finPayloadSize > 0) packet.payload else null
            val sessionKey = buildSessionKey(packet)
            // 整个 FIN 处理移入 outgoingExecutor，保证：
            // 1. FIN payload 在所有排队数据之后写入（顺序正确）
            // 2. clientNextSeq 不被 executor 中的数据包覆盖
            // 3. session.close() 在 payload 写入之后执行
            outgoingExecutor.submit {
                // 写 FIN payload
                if (finPayload != null && finPayload.isNotEmpty()) {
                    val ch = session.channel
                    if (ch != null && ch.isConnected && !session.isClosed) {
                        synchronized(session.writeLock) {
                            try {
                                val wbuf = ByteBuffer.wrap(finPayload)
                                while (wbuf.hasRemaining()) ch.write(wbuf)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write FIN payload: ${e.message}")
                            }
                        }
                    } else if (!session.isClosed) {
                        // 连接尚未建立，缓冲到 pendingData 等待 connectBlocking() 后写入
                        pendingData.compute(session.id) { _, existing ->
                            val totalSize = (existing?.size ?: 0) + finPayload.size
                            if (totalSize > MAX_PENDING_PER_SESSION) {
                                Log.w(TAG, "Pending data limit exceeded for session ${session.id}")
                                return@compute existing
                            }
                            if (existing != null) {
                                val combined = ByteArray(totalSize)
                                System.arraycopy(existing, 0, combined, 0, existing.size)
                                System.arraycopy(finPayload, 0, combined, existing.size, finPayload.size)
                                combined
                            } else finPayload.copyOf()
                        }
                    }
                }
                session.clientNextSeq = tcp.sequenceNumber + finPayloadSize + 1
                flushPipelineForSession(sessionKey)
                // 先 close 停止 relayUpstream 线程，避免 serverSeq 竞争
                session.close()
                sendFinAck(session, ip, tcp)
                tcpSessionManager.removeSession(session)
            }
            return
        }

        val payloadSize = (ip.totalLength - ip.headerLength - tcp.headerLength).coerceAtLeast(0)

        // 纯 ACK（无数据）直接丢弃——控制包不走操控管线，延迟/丢包会导致连接建立失败
        if (payloadSize <= 0) return

        // ---- Data packet ----
        // 重传检测：使用无符号比较，防止 seq 回绕时误判
        val newSeqEnd = tcp.sequenceNumber + payloadSize
        if ((newSeqEnd.toLong() and 0xFFFFFFFFL) <= (session.clientNextSeq.toLong() and 0xFFFFFFFFL)) return
        Log.d(TAG, "TCP DATA ${payloadSize}B -> ${ip.destinationAddressString}:${tcp.destinationPort}")

        // TCP 丢包模拟：在 ACK 之前决定——被"丢"的包不发 ACK，让 app TCP 自然重传
        val tcpLost = pipeline.shouldDropTcpOutgoing()

        if (!tcpLost) {
            session.clientNextSeq = newSeqEnd
            sendAck(session, newSeqEnd, ip, tcp)
        }

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

                val ch = session.channel
                if (ch != null && ch.isConnected && !session.isClosed) {
                    if (!wroteData) {
                        synchronized(session.writeLock) {
                            try {
                                val buf = ByteBuffer.wrap(dataToSend)
                                while (buf.hasRemaining()) ch.write(buf)
                            } catch (e: Exception) {
                                Log.w(TAG, "Write to upstream failed: ${e.message}")
                            }
                        }
                    }
                    wroteData = true
                } else if (!wroteData) {
                    // channel 未就绪且尚未成功写入过数据，缓冲等待
                    pendingData.compute(session.id) { _, existing ->
                        val totalSize = (existing?.size ?: 0) + dataToSend.size
                        if (totalSize > MAX_PENDING_PER_SESSION) {
                            Log.w(TAG, "Pending data limit exceeded for session ${session.id}")
                            return@compute existing
                        }
                        if (existing != null) {
                            val combined = ByteArray(totalSize)
                            System.arraycopy(existing, 0, combined, 0, existing.size)
                            System.arraycopy(dataToSend, 0, combined, existing.size, dataToSend.size)
                            combined
                        } else {
                            dataToSend.copyOf()
                        }
                    }
                }
                // 后续重复包（数据已写入）跳过，防止上游收到重复字节
            }
        }
    }

    private fun relayUpstream(session: TcpSession) {
        // 1460 = MTU(1500) - IP头(20) - TCP头(20)：每次读取不超过一个 MTU 包，避免构造超大 IP 包
        val buf = ByteBuffer.allocate(VpnConfig.MTU - 40)
        val ch = session.channel ?: return
        Log.d(TAG, "relayUpstream started for ${session.key}")

        try {
            while (!session.isClosed && ch.isOpen) {
                buf.clear()
                val bytesRead = ch.read(buf)
                if (bytesRead < 0) break  // Connection closed
                if (bytesRead == 0) {
                    Thread.sleep(1)
                    continue
                }

                buf.flip()
                val data = ByteArray(bytesRead)
                buf.get(data)

                if (isDisconnected) continue
                if (isPaused) continue

                val responsePacket = buildTcpDataPacket(session, data, bytesRead)
                val manipulated = pipeline.processIncoming(responsePacket)

                // 统计在管线处理后更新
                if (manipulated.isNotEmpty()) {
                    totalBytesReceived.addAndGet(bytesRead.toLong())
                    totalPacketsReceived.incrementAndGet()
                }

                for (p in manipulated) {
                    if (session.isClosed) break
                    writeToTun(p.rawBytes)
                }

                // 按实际字节数递增而非按包递增：TCP seq 号是字节流偏移量，不是包序号
                session.addAndGetServerSeq(bytesRead)
                session.lastActivityTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            if (!session.isClosed) {
                Log.d(TAG, "Upstream relay ended for ${session.key}: ${e.message}")
            }
        } finally {
            if (!session.isClosed) {
                sendFinToApp(session)
                tcpSessionManager.removeSession(session)
            }
        }
    }

    private fun handleUdp(packet: IpPacket) {
        val ip = packet.ipHeader
        val udp = packet.udpHeader ?: return

        if (isDisconnected) return
        if (isPaused) return

        val isDns = udp.destinationPort == 53

        val session = udpSessionManager.getOrCreateSession(
            ip.sourceAddress, udp.sourcePort,
            ip.destinationAddress, udp.destinationPort) ?: run {
            if (isDns) Log.e(TAG, "DNS SESSION CREATE FAILED: ${ip.destinationAddressString}:53")
            return
        }

        if (isDns && dnsFaultType != DnsFaultType.NONE) {
            if (handleDnsFault(packet.payload, session)) return
        }

        // DNS 查询：独立线程池 + 延迟/丢包，跳过节流/重排等管线
        if (isDns) {
            if (pipeline.packetLossPercent > 0 && Random.nextInt(100) < pipeline.packetLossPercent) {
                Log.w(TAG, "DNS QUERY DROPPED (loss)")
                return
            }
            Log.i(TAG, "DNS Q -> ${ip.destinationAddressString}:53")
            dnsExecutor.submit {
                try {
                    val delayMs = pipeline.delayMs
                    if (delayMs > 0) {
                        val jitter = pipeline.calculateJitter(pipeline.jitterMs)
                        val totalDelay = (delayMs + jitter).coerceAtLeast(0).toLong()
                        if (totalDelay > 0) Thread.sleep(totalDelay)
                    }
                    val ch = session.channel
                    if (ch != null && ch.isOpen) {
                        val payload = packet.payload
                        val buf = ByteBuffer.wrap(payload)
                        while (buf.hasRemaining()) ch.write(buf)
                        totalBytesSent.addAndGet(payload.size.toLong())
                        totalPacketsSent.incrementAndGet()
                        Log.i(TAG, "DNS Q SENT ${payload.size}B -> ${ip.destinationAddressString}:53")
                    } else {
                        Log.e(TAG, "DNS Q FAIL: channel closed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DNS Q FAIL: ${e.message}")
                } catch (_: InterruptedException) {}
            }
            return
        }

        // 非 DNS 的 UDP 走操控管线
        val delayMs = pipeline.delayMs

        udpExecutor.submit {
            try {
                val manipulated = pipeline.processOutgoing(packet)
                if (manipulated.isEmpty()) return@submit
                totalBytesSent.addAndGet(manipulated.sumOf { it.payload.size.toLong() })
                totalPacketsSent.addAndGet(manipulated.size.toLong())
                if (delayMs > 0) {
                    val jitter = pipeline.calculateJitter(pipeline.jitterMs)
                    val totalDelay = (delayMs + jitter).coerceAtLeast(0).toLong()
                    if (totalDelay > 0) Thread.sleep(totalDelay)
                }
                val ch = session.channel
                if (ch == null || !ch.isOpen) return@submit
                for (p in manipulated) {
                    try {
                        val buf = ByteBuffer.wrap(p.payload)
                        while (buf.hasRemaining()) {
                            ch.write(buf)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP delayed write failed: ${e.message}")
                        break
                    }
                }
            } catch (_: InterruptedException) {}
        }
    }

    /**
     * DNS 故障拦截：根据 dnsFaultType 构造伪造响应或直接丢弃。
     * @return true 表示已处理（不应继续转发），false 表示放行。
     */
    private fun handleDnsFault(
        query: ByteArray,
        session: UdpSessionManager.UdpSession,
    ): Boolean {
        val dnsResponse = when (dnsFaultType) {
            DnsFaultType.TIMEOUT -> return true // 丢弃查询，让 app 等待超时
            DnsFaultType.FAILURE -> DnsResponder.buildErrorResponse(query, DnsResponder.RCODE_SERVFAIL)
            DnsFaultType.HIJACK -> DnsResponder.buildHijackResponse(query, dnsHijackIp)
            DnsFaultType.NONE -> return false
        }

        if (dnsResponse != null) {
            val responsePacket = buildUdpResponsePacket(session, dnsResponse, dnsResponse.size)
            udpExecutor.submit {
                try { writeToTunFragmented(responsePacket.rawBytes) }
                catch (e: Exception) { Log.w(TAG, "DNS response write failed: ${e.message}") }
            }
        }
        return true
    }

    fun handleUdpResponse(session: UdpSessionManager.UdpSession, data: ByteArray, length: Int) {
        if (length <= 0) return
        if (isDisconnected) return
        if (isPaused) return

        val isDns = session.destPort == 53

        val responsePacket = buildUdpResponsePacket(session, data, length)

        // DNS 响应：独立线程池 + 延迟/丢包，跳过节流/重排等管线
        if (isDns) {
            if (pipeline.packetLossPercent > 0 && Random.nextInt(100) < pipeline.packetLossPercent) {
                Log.w(TAG, "DNS RESP DROPPED (loss)")
                return
            }
            Log.i(TAG, "DNS R <- ${ByteUtils.ipAddressToString(session.destIp)}:53 ${length}B")
            dnsExecutor.submit {
                try {
                    val delayMs = pipeline.delayMs
                    if (delayMs > 0) {
                        val jitter = pipeline.calculateJitter(pipeline.jitterMs)
                        val totalDelay = (delayMs + jitter).coerceAtLeast(0).toLong()
                        if (totalDelay > 0) Thread.sleep(totalDelay)
                    }
                    writeToTunFragmented(responsePacket.rawBytes)
                    totalBytesReceived.addAndGet(length.toLong())
                    totalPacketsReceived.incrementAndGet()
                    Log.i(TAG, "DNS R WRITTEN TO TUN ${length}B")
                } catch (e: Exception) {
                    Log.e(TAG, "DNS R FAIL: ${e.message}")
                } catch (_: InterruptedException) {}
            }
            return
        }

        // 非 DNS 的 UDP 响应走操控管线
        udpExecutor.submit {
            try {
                val manipulated = pipeline.processIncoming(responsePacket)
                if (manipulated.isNotEmpty()) {
                    totalBytesReceived.addAndGet(length.toLong())
                    totalPacketsReceived.incrementAndGet()
                }
                applyDelay()
                for (p in manipulated) {
                    writeToTunFragmented(p.rawBytes)
                }
            } catch (_: InterruptedException) {}
        }
    }

    // ---- ICMP (ping) 回复 ----
    // 不转发真实 ICMP——Android 上 raw ICMP socket 行为不可靠，改为构造 fake echo reply
    private fun handleIcmp(packet: IpPacket) {
        val ip = packet.ipHeader
        val icmpData = packet.payload ?: return
        if (icmpData.size < 8) return

        if (isDisconnected) return
        if (isPaused) return

        // 只处理 echo request (type=8)
        if (icmpData[0].toInt() != 8) return

        // ICMP 丢包率：ping 测试中最直观的弱网体验指标
        if (pipeline.packetLossPercent > 0 && Random.nextInt(100) < pipeline.packetLossPercent) {
            return
        }

        val dstIp = ip.destinationAddress
        val srcIp = ip.sourceAddress
        val dstAddr = ip.destinationAddressString
        val recvTime = System.currentTimeMillis()

        icmpExecutor.submit {
            try {
                // ICMP 延迟也在后台线程实现，模拟 ping 往返延迟
                val delayMs = pipeline.delayMs
                if (delayMs > 0) {
                    val jitter = pipeline.calculateJitter(pipeline.jitterMs)
                    val totalDelay = (delayMs + jitter).coerceAtLeast(0).toLong()
                    if (totalDelay > 0) Thread.sleep(totalDelay)
                }

                // 直接复用请求数据构造 echo reply，只改 type 字段
                val reply = icmpData.copyOf()
                reply[0] = 0  // type: echo reply
                reply[1] = 0  // code: 0

                // Recompute ICMP checksum
                reply[2] = 0
                reply[3] = 0
                val checksum = ByteUtils.calculateChecksum(reply, 0, reply.size)
                reply[2] = (checksum shr 8).toByte()
                reply[3] = checksum.toByte()

                // 源/目的 IP 互换——告诉 app 这个 reply 来自它 ping 的目标
                val totalLen = 20 + reply.size
                val replyPacket = ByteArray(totalLen)
                writeIpHeader(replyPacket, dstIp, srcIp, 1, totalLen)
                System.arraycopy(reply, 0, replyPacket, 20, reply.size)

                writeToTun(replyPacket)
                val elapsed = System.currentTimeMillis() - recvTime
                Log.i(TAG, "ICMP $dstAddr reply=${elapsed}ms (delay=${delayMs}ms)")
                // ICMP 无需精确统计，写入后计数即可
                totalBytesReceived.addAndGet(totalLen.toLong())
                totalPacketsReceived.incrementAndGet()
            } catch (_: InterruptedException) {}
        }
    }

    // 模拟网络延迟：在调用线程中独立 sleep，避免共享锁导致多连接互相阻塞。
    // UDP 出/入方向各调用一次，使 RTT = 2 × delayMs。TCP 流数据不用——逐包延迟会堆叠。
    private fun applyDelay() {
        val delayMs = pipeline.delayMs
        if (delayMs <= 0) return
        val jitter = pipeline.calculateJitter(pipeline.jitterMs)
        val totalDelay = (delayMs + jitter).coerceAtLeast(0).toLong()
        if (totalDelay > 0) {
            Log.d(TAG, "applyDelay: sleeping ${totalDelay}ms (delay=$delayMs, jitter=$jitter)")
            try { Thread.sleep(totalDelay) } catch (_: InterruptedException) {}
        }
    }

    // ---- TUN 写入（线程安全） ----

    private fun writeToTun(data: ByteArray) {
        // 多个 TCP relay 线程 + ICMP 线程并发写入同一个 FileOutputStream，必须同步
        synchronized(tunLock) {
            try {
                tunOutput.write(data)
                tunOutput.flush()
            } catch (e: Exception) {
                Log.w(TAG, "TUN write failed: ${e.message}")
            }
        }
    }

    /**
     * 写入 TUN 并自动分片：如果 IP 包超过 MTU，按 RFC 791 分片后逐片写入。
     * 用于 UDP 响应（单个数据报可能超过 MTU）。
     * TCP 响应已限制读取粒度为 MTU-40，不会产生超大包。
     */
    private fun writeToTunFragmented(data: ByteArray) {
        if (data.size < 20) return
        val mtu = VpnConfig.MTU
        if (data.size <= mtu) {
            writeToTun(data)
            return
        }

        val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
        val totalPayloadLen = data.size - ipHeaderLen
        // 分片载荷必须是 8 的整数倍（除最后一片），单位：字节
        val maxFragPayload = ((mtu - ipHeaderLen) / 8) * 8
        val originalFlags = data[6].toInt() and 0x80  // 只保留 Reserved 位，清除 DF

        var offset = 0
        while (offset < totalPayloadLen) {
            val remaining = totalPayloadLen - offset
            val isLast = remaining <= maxFragPayload
            val fragPayloadLen = if (isLast) remaining else maxFragPayload

            val fragPacket = ByteArray(ipHeaderLen + fragPayloadLen)

            // 复制原始 IP 头
            System.arraycopy(data, 0, fragPacket, 0, ipHeaderLen)

            // 复制分片载荷
            System.arraycopy(data, ipHeaderLen + offset, fragPacket, ipHeaderLen, fragPayloadLen)

            // 更新 total length
            fragPacket[2] = ((ipHeaderLen + fragPayloadLen) shr 8).toByte()
            fragPacket[3] = (ipHeaderLen + fragPayloadLen).toByte()

            // 设置分片偏移和 MF 标志
            val offsetUnits = offset / 8
            val flagsAndOffset = if (isLast) {
                originalFlags or offsetUnits  // MF=0
            } else {
                originalFlags or 0x2000 or offsetUnits  // MF=1
            }
            fragPacket[6] = ((flagsAndOffset shr 8) and 0xFF).toByte()
            fragPacket[7] = (flagsAndOffset and 0xFF).toByte()

            // 重算 IP 头校验和
            fragPacket[10] = 0
            fragPacket[11] = 0
            ByteUtils.updateChecksum(fragPacket, 10, 0, ipHeaderLen)

            writeToTun(fragPacket)
            offset += fragPayloadLen
        }
    }

    // ---- TCP packet builders ----

    private fun sendSynAck(session: TcpSession, ip: IpHeader, tcp: TcpHeader) {
        // TCP options: MSS(4) + NOP(1) + WSOPT(3) + NOP(1) + EOL(1) = 10 bytes → dataOffset=30/4=7+1=8
        val tcpHeaderLen = 32 // 8 * 4 = 32 (20 base + 12 options)
        val pkt = ByteArray(20 + tcpHeaderLen)
        writeIpHeader(pkt, ip.destinationAddress, ip.sourceAddress, 6, 20 + tcpHeaderLen)
        val off = 20
        ByteUtils.shortToByteArray(tcp.destinationPort).copyInto(pkt, off)     // src port
        ByteUtils.shortToByteArray(tcp.sourcePort).copyInto(pkt, off + 2)      // dst port
        ByteUtils.intToByteArray(session.serverSeq).copyInto(pkt, off + 4)     // seq
        ByteUtils.intToByteArray(session.clientNextSeq).copyInto(pkt, off + 8) // ack
        pkt[off + 12] = ((tcpHeaderLen / 4) shl 4).toByte()                   // data offset
        pkt[off + 13] = (TcpHeader.FLAG_SYN or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(65535).copyInto(pkt, off + 14)              // window
        // TCP Options:
        val optOff = off + 20
        pkt[optOff] = 2; pkt[optOff + 1] = 4     // MSS kind=2, len=4
        pkt[optOff + 2] = 0x05; pkt[optOff + 3] = 0xB4.toByte() // MSS=1460
        pkt[optOff + 4] = 1                        // NOP
        pkt[optOff + 5] = 3; pkt[optOff + 6] = 3  // WSOPT kind=3, len=3
        pkt[optOff + 7] = 4                         // window scale = 4 (65535 << 4 ≈ 1MB)
        pkt[optOff + 8] = 1                         // NOP
        pkt[optOff + 9] = 0                         // EOL
        // SYN 占用 1 个 seq 号（RFC 793），后续数据从 serverSeq+1 开始
        session.addAndGetServerSeq(1)
        updateTcpChecksum(pkt, off, tcpHeaderLen)
        writeToTun(pkt)
    }

    private fun sendAck(session: TcpSession, ackNum: Int, ip: IpHeader, tcp: TcpHeader) {
        val pkt = ByteArray(40)
        writeIpHeader(pkt, ip.destinationAddress, ip.sourceAddress, 6, 40)
        val off = 20
        ByteUtils.shortToByteArray(tcp.destinationPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(tcp.sourcePort).copyInto(pkt, off + 2)
        ByteUtils.intToByteArray(session.serverSeq).copyInto(pkt, off + 4)
        ByteUtils.intToByteArray(ackNum).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = TcpHeader.FLAG_ACK.toByte()
        ByteUtils.shortToByteArray(65535).copyInto(pkt, off + 14)
        updateTcpChecksum(pkt, off, 20)
        writeToTun(pkt)
    }

    private fun sendFinAck(session: TcpSession, ip: IpHeader, tcp: TcpHeader) {
        val pkt = ByteArray(40)
        writeIpHeader(pkt, ip.destinationAddress, ip.sourceAddress, 6, 40)
        val off = 20
        ByteUtils.shortToByteArray(tcp.destinationPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(tcp.sourcePort).copyInto(pkt, off + 2)
        ByteUtils.intToByteArray(session.serverSeq).copyInto(pkt, off + 4)
        ByteUtils.intToByteArray(session.clientNextSeq).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = (TcpHeader.FLAG_FIN or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(65535).copyInto(pkt, off + 14)
        session.addAndGetServerSeq(1)
        updateTcpChecksum(pkt, off, 20)
        writeToTun(pkt)
    }

    private fun sendFinToApp(session: TcpSession) {
        val pkt = ByteArray(40)
        writeIpHeader(pkt, session.destIp, session.sourceIp, 6, 40)
        val off = 20
        ByteUtils.shortToByteArray(session.destPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(session.sourcePort).copyInto(pkt, off + 2)
        ByteUtils.intToByteArray(session.serverSeq).copyInto(pkt, off + 4)
        ByteUtils.intToByteArray(session.clientNextSeq).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = (TcpHeader.FLAG_FIN or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(65535).copyInto(pkt, off + 14)
        session.addAndGetServerSeq(1)
        updateTcpChecksum(pkt, off, 20)
        writeToTun(pkt)
    }

    private fun sendRst(ip: IpHeader, tcp: TcpHeader) {
        val pkt = ByteArray(40)
        writeIpHeader(pkt, ip.destinationAddress, ip.sourceAddress, 6, 40)
        val off = 20
        ByteUtils.shortToByteArray(tcp.destinationPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(tcp.sourcePort).copyInto(pkt, off + 2)
        // seq = app 期望的 server seq（tcp.ackNumber），确保 RST 在 app 接收窗口内
        ByteUtils.intToByteArray(tcp.ackNumber).copyInto(pkt, off + 4)
        // ack = app 的下一个 seq，确认 app 已发的数据
        ByteUtils.intToByteArray(tcp.sequenceNumber).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = (TcpHeader.FLAG_RST or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(0).copyInto(pkt, off + 14)
        updateTcpChecksum(pkt, off, 20)
        writeToTun(pkt)
    }

    // 构造从"服务器"到 app 的 TCP 数据包。serverSeq 在调用方 relayUpstream 中按字节数递增，
    // 保证 seq 号是字节级别的连续偏移量，而非包级别递增
    private fun buildTcpDataPacket(session: TcpSession, data: ByteArray, length: Int): IpPacket {
        val totalLen = 20 + 20 + length
        val pkt = ByteArray(totalLen)

        writeIpHeader(pkt, session.destIp, session.sourceIp, 6, totalLen)

        val off = 20
        ByteUtils.shortToByteArray(session.destPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(session.sourcePort).copyInto(pkt, off + 2)
        ByteUtils.intToByteArray(session.serverSeq).copyInto(pkt, off + 4)
        ByteUtils.intToByteArray(session.clientNextSeq).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = (TcpHeader.FLAG_PSH or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(65535).copyInto(pkt, off + 14)

        System.arraycopy(data, 0, pkt, 40, length)

        updateTcpChecksum(pkt, off, 20 + length)
        // 内部构造的包 parse 不应失败，但防御性处理
        return IpPacket.parse(pkt) ?: IpPacket(pkt, IpHeader(pkt))
    }

    // UDP 校验和不能省略——Android 的网络栈会校验 UDP checksum，填 0 会导致包被丢弃
    private fun buildUdpResponsePacket(
        session: UdpSessionManager.UdpSession, data: ByteArray, length: Int
    ): IpPacket {
        val totalLen = 20 + 8 + length
        val pkt = ByteArray(totalLen)

        writeIpHeader(pkt, session.destIp, session.sourceIp, 17, totalLen)

        val off = 20
        ByteUtils.shortToByteArray(session.destPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(session.sourcePort).copyInto(pkt, off + 2)
        ByteUtils.shortToByteArray(8 + length).copyInto(pkt, off + 4)
        // UDP checksum: compute properly
        ByteUtils.shortToByteArray(0).copyInto(pkt, off + 6)
        System.arraycopy(data, 0, pkt, 28, length)

        // Compute UDP checksum with pseudo-header
        val pseudoHeader = ByteArray(12)
        System.arraycopy(pkt, 12, pseudoHeader, 0, 4)   // src IP
        System.arraycopy(pkt, 16, pseudoHeader, 4, 4)   // dst IP
        pseudoHeader[9] = 17                              // protocol UDP
        val udpLen = 8 + length
        pseudoHeader[10] = (udpLen shr 8).toByte()
        pseudoHeader[11] = udpLen.toByte()

        val combined = ByteArray(12 + udpLen)
        System.arraycopy(pseudoHeader, 0, combined, 0, 12)
        System.arraycopy(pkt, off, combined, 12, udpLen)
        val checksum = ByteUtils.calculateChecksum(combined, 0, combined.size)
        pkt[off + 6] = (checksum shr 8).toByte()
        pkt[off + 7] = checksum.toByte()

        return IpPacket.parse(pkt) ?: run {
            Log.w(TAG, "buildUdpResponsePacket: failed to parse constructed packet")
            return@buildUdpResponsePacket IpPacket(pkt, IpHeader(pkt))
        }
    }

    // ---- Helpers ----

    private fun writeIpHeader(pkt: ByteArray, srcIp: ByteArray, dstIp: ByteArray,
                              protocol: Int, totalLength: Int) {
        pkt[0] = 0x45.toByte()
        pkt[1] = 0
        ByteUtils.shortToByteArray(totalLength).copyInto(pkt, 2)
        ByteUtils.shortToByteArray(Random.nextInt(0xFFFF)).copyInto(pkt, 4) // identification
        ByteUtils.shortToByteArray(0x4000).copyInto(pkt, 6) // DF
        pkt[8] = 64 // TTL
        pkt[9] = protocol.toByte()
        ByteUtils.shortToByteArray(0).copyInto(pkt, 10) // checksum placeholder
        srcIp.copyInto(pkt, 12)
        dstIp.copyInto(pkt, 16)
        ByteUtils.updateChecksum(pkt, 10, 0, 20)
    }

    private fun updateTcpChecksum(pkt: ByteArray, tcpOffset: Int, tcpLength: Int) {
        // Zero the checksum field first
        pkt[tcpOffset + 16] = 0
        pkt[tcpOffset + 17] = 0

        val pseudoHeader = ByteArray(12)
        System.arraycopy(pkt, 12, pseudoHeader, 0, 4)  // src IP
        System.arraycopy(pkt, 16, pseudoHeader, 4, 4)  // dst IP
        pseudoHeader[9] = 6                              // TCP
        pseudoHeader[10] = (tcpLength shr 8).toByte()
        pseudoHeader[11] = tcpLength.toByte()

        val combined = ByteArray(12 + tcpLength)
        System.arraycopy(pseudoHeader, 0, combined, 0, 12)
        System.arraycopy(pkt, tcpOffset, combined, 12, tcpLength)

        val checksum = ByteUtils.calculateChecksum(combined, 0, combined.size)
        pkt[tcpOffset + 16] = (checksum shr 8).toByte()
        pkt[tcpOffset + 17] = checksum.toByte()
    }

    fun shutdown() {
        // 先关闭所有 session（释放 SocketChannel），再关闭线程池
        for (session in tcpSessionManager.allSessions()) {
            session.close()
        }
        outgoingExecutor.shutdownNow()
        connectExecutor.shutdownNow()
        udpExecutor.shutdownNow()
        dnsExecutor.shutdownNow()
        icmpExecutor.shutdownNow()
        try { outgoingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        try { connectExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        try { udpExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        try { dnsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    fun handleDisconnectTick() {
        val scheduler = disconnectScheduler ?: return
        when (scheduler.tick()) {
            DisconnectScheduler.State.JUST_RECONNECTED -> {
                // 先捕获每个 session 的 seq 快照，再 close——避免 relayUpstream 线程竞争 serverSeq
                val sessions = tcpSessionManager.allSessions()
                val seqSnapshots = sessions.associateWith { it.serverSeq }
                for (session in sessions) {
                    if (!session.isClosed) {
                        session.close()
                    }
                }
                for (session in sessions) {
                    try { sendRstToSession(session, seqSnapshots[session] ?: 0) }
                    catch (e: Exception) { Log.w(TAG, "RST on reconnect failed: ${e.message}") }
                    tcpSessionManager.removeSession(session)
                }
            }
            DisconnectScheduler.State.CONNECTED,
            DisconnectScheduler.State.DISCONNECTED -> { /* no action */ }
        }
    }

    private fun sendRstToSession(session: TcpSession, serverSeqSnapshot: Int = session.serverSeq) {
        val pkt = ByteArray(40)
        writeIpHeader(pkt, session.destIp, session.sourceIp, 6, 40)
        val off = 20
        ByteUtils.shortToByteArray(session.destPort).copyInto(pkt, off)
        ByteUtils.shortToByteArray(session.sourcePort).copyInto(pkt, off + 2)
        // seq 必须在 app 的接收窗口内（= server 当前 seq），否则 RST 被 app 丢弃
        ByteUtils.intToByteArray(serverSeqSnapshot).copyInto(pkt, off + 4)
        // ack 确认 app 已发的全部数据
        ByteUtils.intToByteArray(session.clientNextSeq).copyInto(pkt, off + 8)
        pkt[off + 12] = (5 shl 4).toByte()
        pkt[off + 13] = (TcpHeader.FLAG_RST or TcpHeader.FLAG_ACK).toByte()
        ByteUtils.shortToByteArray(0).copyInto(pkt, off + 14)
        updateTcpChecksum(pkt, off, 20)
        writeToTun(pkt)
    }

    data class StatsSnapshot(
        val bytesSent: Long,
        val bytesReceived: Long,
        val packetsSent: Long,
        val packetsReceived: Long,
    )

    fun getStats() = StatsSnapshot(
        bytesSent = totalBytesSent.get(),
        bytesReceived = totalBytesReceived.get(),
        packetsSent = totalPacketsSent.get(),
        packetsReceived = totalPacketsReceived.get(),
    )

    private fun flushPipelineForSession(sessionKey: String? = null) {
        val flushed = if (sessionKey != null) {
            pipeline.flushForSession(sessionKey)
        } else pipeline.flush()
        // incoming 包写入 TUN（发给 app），outgoing 包丢弃（连接即将关闭）
        for (p in flushed.incoming) {
            try { writeToTun(p.rawBytes) } catch (_: Exception) {}
        }
    }

    private fun buildSessionKey(packet: IpPacket): String {
        val ip = packet.ipHeader
        val port = if (ip.isTcp) packet.tcpHeader?.let { "${it.sourcePort}:${it.destinationPort}" }
            else packet.udpHeader?.let { "${it.sourcePort}:${it.destinationPort}" } ?: ""
        return "${ip.sourceAddressString}:${ip.destinationAddressString}:$port:${ip.protocol}"
    }

    companion object {
        private const val TAG = "NL-Pkt"
        private const val MAX_PENDING_PER_SESSION = 1024 * 1024 // 1 MB
    }
}
