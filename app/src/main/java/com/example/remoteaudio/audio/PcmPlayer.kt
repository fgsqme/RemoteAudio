package com.example.remoteaudio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.example.remoteaudio.net.AudioFrame
import com.example.remoteaudio.net.StreamInfo
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 解码 + AudioTrack 流式播放线程。
 * 缓冲策略：预滚约 60ms 后起播；欠载时暂停重新缓冲；积压超 120ms 丢最旧帧追赶。
 */
class PcmPlayer(
    private val info: StreamInfo,
    private val frames: BlockingQueue<AudioFrame>,
    private val listener: Listener,
) {

    interface Listener {
        fun onBuffering()
        fun onPlaying()
        fun onStats(bufferMs: Int, framesPlayed: Long)
        fun onFatal(reason: String)
    }

    private val frameSamples = info.sampleRate / 1000 * info.frameMs
    private val prerollFrames = PREROLL_MS / info.frameMs
    private val maxFrames = MAX_BUFFER_MS / info.frameMs

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var volume = 1f

    fun start() {
        running = true
        thread = Thread(::runLoop, "PcmPlayer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun setVolume(v: Float) {
        volume = v
        track?.setVolume(v)
    }

    private fun runLoop() {
        var decoder: OpusDecoder? = null
        try {
            val channelMask = if (info.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuf = AudioTrack.getMinBufferSize(info.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, info.sampleRate * info.channels * 2 * TRACK_BUFFER_MS / 1000)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(info.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 低延迟路径（AAudio MMAP，设备支持时生效）
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val audioTrack = builder.build()
            track = audioTrack
            audioTrack.setVolume(volume)

            decoder = OpusDecoder(info.sampleRate, info.channels)
            val pcm = ShortArray(frameSamples * info.channels)
            var playing = false
            var pending = 0
            var framesPlayed = 0L

            while (running) {
                // 积压过深时丢最旧帧追赶进度
                while (frames.size > maxFrames) {
                    frames.poll()
                }
                val frame = frames.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    if (playing) {
                        // 欠载：暂停播放，回到缓冲状态
                        audioTrack.pause()
                        playing = false
                        pending = 0
                        listener.onBuffering()
                    }
                    continue
                }
                val n = decoder.decode(frame.data, frame.data.size, pcm, frameSamples)
                audioTrack.write(pcm, 0, n * info.channels)
                framesPlayed++
                if (!playing) {
                    pending++
                    if (pending >= prerollFrames) {
                        audioTrack.play()
                        playing = true
                        listener.onPlaying()
                    }
                }
                if (framesPlayed % STATS_EVERY_FRAMES == 0L) {
                    listener.onStats(frames.size * info.frameMs, framesPlayed)
                }
            }
        } catch (e: InterruptedException) {
            // stop() 触发，正常退出
        } catch (e: Exception) {
            if (running) {
                listener.onFatal(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            decoder?.close()
            track?.release()
            track = null
        }
    }

    companion object {
        private const val PREROLL_MS = 60
        private const val MAX_BUFFER_MS = 120
        private const val TRACK_BUFFER_MS = 80
        private const val POLL_TIMEOUT_MS = 100L
        private const val STATS_EVERY_FRAMES = 5L
    }
}