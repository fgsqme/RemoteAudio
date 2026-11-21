package com.example.remoteaudio.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue

/** 握手协商出的流参数 */
data class StreamInfo(val sampleRate: Int, val channels: Int, val frameMs: Int)

/** 一帧 Opus 数据 */
data class AudioFrame(val seq: Long, val data: ByteArray)

/**
 * TCP 客户端：连接 Linux 服务端，读握手 JSON，按协议拆帧入队。
 * 协议：一行 JSON 握手 + [u32 seq][u16 len][opus payload]（大端）。
 */
class AudioReceiver(
    private val host: String,
    private val port: Int,
    private val frames: ArrayBlockingQueue<AudioFrame>,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(info: StreamInfo)

        /** reason 为 null 表示主动停止 */
        fun onDisconnected(reason: String?)
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    fun start() {
        running = true
        thread = Thread(::runLoop, "AudioReceiver").also { it.start() }
    }

    fun stop() {
        running = false
        // 关闭 socket 以打断阻塞中的 read
        runCatching { socket?.close() }
    }

    private fun runLoop() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            // 服务端静音时也持续发帧，超时无数据即视为断线
            s.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(s.getInputStream().buffered())

            val json = JSONObject(readLine(input))
            require(json.optString("magic") == "RAUD") { "协议标识不匹配" }
            require(json.optInt("version") == 1) { "不支持的协议版本" }
            require(json.optString("codec") == "opus") { "不支持的编码格式" }
            val info = StreamInfo(
                sampleRate = json.getInt("sample_rate"),
                channels = json.getInt("channels"),
                frameMs = json.getInt("frame_ms"),
            )
            listener.onConnected(info)

            while (running) {
                val seq = input.readInt().toLong() and 0xFFFFFFFFL
                val len = input.readUnsignedShort()
                require(len in 1..MAX_PACKET_BYTES) { "非法帧长度 $len" }
                val payload = ByteArray(len)
                input.readFully(payload)
                // 队列满时丢最旧帧，避免延迟无限积压
                while (!frames.offer(AudioFrame(seq, payload))) {
                    frames.poll()
                }
            }
            listener.onDisconnected(null)
        } catch (e: Exception) {
            listener.onDisconnected(if (running) (e.message ?: e.javaClass.simpleName) else null)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) throw EOFException("握手阶段连接被关闭")
            if (c == '\n'.code) break
            sb.append(c.toChar())
            if (sb.length > 1024) throw IOException("握手数据过长")
        }
        return sb.toString()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_PACKET_BYTES = 4000
    }
}