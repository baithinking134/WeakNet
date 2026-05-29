/**
 * @author awu
 * @date 2026-05-26
 * @desc TCP 会话状态管理：通过 CAS 原子操作管理连接状态（SYN_RECEIVED → ESTABLISHED → CLOSED）
 */
package com.awu.weaknet.vpn.nat

import android.net.VpnService
import android.util.Log
import com.awu.weaknet.util.ByteUtils
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class TcpState {
    SYN_RECEIVED,
    ESTABLISHED,
    CLOSED,
}

class TcpSession(
    val id: Long,
    sourceIp: ByteArray,
    val sourcePort: Int,
    destIp: ByteArray,
    val destPort: Int,
    private val vpnService: VpnService,
) {
    val sourceIp: ByteArray = sourceIp.copyOf()
    val destIp: ByteArray = destIp.copyOf()

    private val _state = AtomicReference(TcpState.SYN_RECEIVED)
    val state: TcpState get() = _state.get()

    @Volatile
    var lastActivityTime = System.currentTimeMillis()

    private val _serverSeq = AtomicInteger(0)
    var serverSeq: Int
        get() = _serverSeq.get()
        set(value) { _serverSeq.set(value) }

    fun addAndGetServerSeq(delta: Int): Int = _serverSeq.addAndGet(delta)

    private val _clientNextSeq = AtomicInteger(0)
    var clientNextSeq: Int
        get() = _clientNextSeq.get()
        set(value) { _clientNextSeq.set(value) }

    // AtomicReference 保证多线程安全：connectExecutor 写入、relay 线程读取、cleanup 线程关闭
    private val _channel = AtomicReference<SocketChannel?>(null)
    val channel: SocketChannel? get() = _channel.get()

    // 写锁：防止 connectExecutor 初次写入和 outgoingExecutor 后续写入并发
    val writeLock = Any()

    fun connectBlocking(): Boolean {
        val dstAddr = ByteUtils.ipAddressToString(destIp)
        val ch: SocketChannel
        try {
            ch = SocketChannel.open()
            ch.configureBlocking(true)
        } catch (e: Exception) {
            Log.w(TAG, "SocketChannel.open failed for $dstAddr:$destPort: ${e.message}")
            return false
        }
        return try {
            if (!vpnService.protect(ch.socket())) {
                Log.e(TAG, "protect() FAILED for $dstAddr:$destPort")
                ch.close()
                return false
            }
            Log.d(TAG, "Connecting to $dstAddr:$destPort ...")
            ch.socket().connect(InetSocketAddress(dstAddr, destPort), CONNECT_TIMEOUT_MS)
            // 先设置 channel，再 CAS 状态——确保 close() 能看到并关闭此 channel
            _channel.set(ch)
            // CAS 防止 close() 已经关闭会话后复活：如果状态不再是 SYN_RECEIVED 说明已被关闭
            if (!_state.compareAndSet(TcpState.SYN_RECEIVED, TcpState.ESTABLISHED)) {
                // close() 可能在 _channel.set(ch) 之前运行而漏关，显式关闭防 fd 泄漏
                try { ch.close() } catch (_: Exception) {}
                return false
            }
            lastActivityTime = System.currentTimeMillis()
            Log.d(TAG, "Connected to $dstAddr:$destPort")
            true
        } catch (e: Exception) {
            try { ch.close() } catch (_: Exception) {}
            Log.w(TAG, "connect failed to $dstAddr:$destPort: ${e.message}")
            false
        }
    }

    fun close() {
        // CAS 保证幂等：只有第一次调用执行关闭
        if (_state.getAndSet(TcpState.CLOSED) == TcpState.CLOSED) return
        try { _channel.getAndSet(null)?.close() } catch (_: Exception) {}
    }

    fun isExpired(timeoutMs: Long = 60000): Boolean =
        System.currentTimeMillis() - lastActivityTime > timeoutMs

    val isClosed: Boolean get() = _state.get() == TcpState.CLOSED

    val key: String by lazy {
        "${ByteUtils.ipAddressToString(sourceIp)}:$sourcePort -> ${ByteUtils.ipAddressToString(destIp)}:$destPort"
    }

    companion object {
        private const val TAG = "NL-Tcp"
        private const val CONNECT_TIMEOUT_MS = 5_000 // 5s，防止连接被墙服务器时无限阻塞
    }
}
