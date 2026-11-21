package com.example.remoteaudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.remoteaudio.MainActivity
import com.example.remoteaudio.audio.PcmPlayer
import com.example.remoteaudio.net.AudioFrame
import com.example.remoteaudio.net.AudioReceiver
import com.example.remoteaudio.net.StreamInfo
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * mediaPlayback 前台服务：编排 Receiver -> PcmPlayer 流水线，后台/息屏持续播放。
 * 后台常驻策略：
 * - 断线自动重连（固定间隔），期间保持前台通知与 WakeLock/WifiLock；
 * - START_STICKY + 会话持久化：进程被系统杀死后自动恢复上次连接；
 * - stopWithTask=false：从最近任务划掉不影响播放。
 * 通过进程级 StateFlow 向 UI 暴露状态，Intent action 承载控制指令。
 */
class PlaybackService : Service() {

    enum class Phase { IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val endpoint: String = "",
        val bufferMs: Int = 0,
        val framesPlayed: Long = 0,
        val error: String? = null,
        val volume: Float = 1f,
    )

    private val frames = ArrayBlockingQueue<AudioFrame>(QUEUE_CAPACITY)
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: AudioReceiver? = null
    private var player: PcmPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 代际号：teardown 后旧线程的回调一律忽略 */
    private var generation = 0

    private var host: String = ""
    private var port: Int = DEFAULT_PORT
    private var reconnectAttempt = 0
    private var reconnectPending = false
    private val reconnectRunnable = Runnable {
        reconnectPending = false
        if (host.isNotBlank()) {
            connect(host, port)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val h = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val p = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                if (h.isNotBlank()) {
                    saveSession(h, p)
                    reconnectAttempt = 0
                    connect(h, p)
                }
            }
            ACTION_DISCONNECT -> {
                clearSession()
                stopSession()
                _state.update { it.copy(phase = Phase.IDLE, bufferMs = 0, error = null) }
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_VOLUME -> {
                val v = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)
                player?.setVolume(v)
                _state.update { it.copy(volume = v) }
                // 空闲时仅记录音量，不让服务空转
                if (receiver == null && !reconnectPending) {
                    stopSelf()
                }
            }
            null -> {
                // START_STICKY 被系统杀死后重启：恢复上次会话
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val h = prefs.getString(KEY_HOST, "").orEmpty()
                val p = prefs.getInt(KEY_PORT, DEFAULT_PORT)
                if (prefs.getBoolean(KEY_SESSION_ACTIVE, false) && h.isNotBlank()) {
                    connect(h, p)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        _state.update {
            if (it.phase == Phase.ERROR) it else it.copy(phase = Phase.IDLE, bufferMs = 0)
        }
        super.onDestroy()
    }

    private fun connect(host: String, port: Int) {
        cancelReconnect()
        teardownPipeline()
        frames.clear()
        this.host = host
        this.port = port
        val gen = generation
        val endpoint = "$host:$port"
        val retrying = reconnectAttempt > 0
        _state.update {
            UiState(
                phase = if (retrying) Phase.RECONNECTING else Phase.CONNECTING,
                endpoint = endpoint,
                volume = it.volume,
                error = if (retrying) it.error else null,
            )
        }
        startForegroundCompat(
            buildNotification(if (retrying) "正在重连 $endpoint…" else "正在连接 $endpoint…")
        )
        acquireLocks()

        receiver = AudioReceiver(host, port, frames, object : AudioReceiver.Listener {
            override fun onConnected(info: StreamInfo) {
                if (gen != generation) return
                reconnectAttempt = 0
                _state.update { it.copy(phase = Phase.BUFFERING, error = null) }
                updateNotification("缓冲中…")
                player = PcmPlayer(info, frames, playerListener(gen)).also {
                    it.setVolume(_state.value.volume)
                    it.start()
                }
            }

            override fun onDisconnected(reason: String?) {
                if (gen != generation) return
                if (reason == null) {
                    // 主动停止路径已由 teardown 处理，这里兜底
                    stopSession()
                    _state.update { it.copy(phase = Phase.IDLE, bufferMs = 0) }
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    scheduleReconnect(reason)
                }
            }
        }).also { it.start() }
    }

    private fun playerListener(gen: Int) = object : PcmPlayer.Listener {
        override fun onBuffering() {
            if (gen != generation) return
            _state.update { it.copy(phase = Phase.BUFFERING) }
            updateNotification("缓冲中…")
        }

        override fun onPlaying() {
            if (gen != generation) return
            _state.update { it.copy(phase = Phase.PLAYING) }
            updateNotification("正在播放 ${_state.value.endpoint}")
        }

        override fun onStats(bufferMs: Int, framesPlayed: Long) {
            if (gen != generation) return
            _state.update { it.copy(bufferMs = bufferMs, framesPlayed = framesPlayed) }
        }

        override fun onFatal(reason: String) {
            if (gen != generation) return
            scheduleReconnect(reason)
        }
    }

    /** 意外断线：保持前台与锁，延迟后自动重连 */
    private fun scheduleReconnect(reason: String) {
        teardownPipeline()
        reconnectAttempt++
        reconnectPending = true
        _state.update { it.copy(phase = Phase.RECONNECTING, error = reason, bufferMs = 0) }
        updateNotification("连接断开，${RECONNECT_DELAY_MS / 1000} 秒后重连（第 $reconnectAttempt 次）")
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectPending = false
    }

    /** 结束整个会话：取消重连、停流水线、释放锁 */
    private fun stopSession() {
        cancelReconnect()
        reconnectAttempt = 0
        teardownPipeline()
        releaseLocks()
    }

    private fun teardownPipeline() {
        generation++
        receiver?.stop()
        receiver = null
        player?.stop()
        player = null
    }

    private fun saveSession(host: String, port: Int) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .apply()
    }

    private fun clearSession() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .apply()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteAudio:playback")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "RemoteAudio:wifi").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程音频播放",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("RemoteAudio")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_CONNECT = "com.example.remoteaudio.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.remoteaudio.action.DISCONNECT"
        const val ACTION_VOLUME = "com.example.remoteaudio.action.VOLUME"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_VOLUME = "volume"
        const val DEFAULT_PORT = 9876

        private const val CHANNEL_ID = "remote_audio_playback"
        private const val NOTIFICATION_ID = 1
        private const val QUEUE_CAPACITY = 64
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val RECONNECT_DELAY_MS = 3000L

        private const val PREFS = "remote_audio"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SESSION_ACTIVE = "session_active"

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        /** 上次连接的主机/端口，供 UI 预填 */
        fun savedHost(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HOST, "").orEmpty()

        fun savedPort(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PORT, DEFAULT_PORT)
    }
}