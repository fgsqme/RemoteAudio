package com.example.remoteaudio.audio

/**
 * libopus 解码器的 JNI 封装（NDK 软解，兼容 minSdk 24 的所有设备）。
 * 非线程安全：只能在单一播放线程中使用。
 */
class OpusDecoder(sampleRate: Int, channels: Int) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate, channels)

    init {
        require(handle != 0L) { "创建 Opus 解码器失败" }
    }

    /**
     * 解码一个 Opus 包到 [output]（交错 PCM）。
     * [frameCapacity] 为每声道可容纳的样本数，返回实际解码出的每声道样本数。
     */
    fun decode(input: ByteArray, inputLen: Int, output: ShortArray, frameCapacity: Int): Int {
        check(handle != 0L) { "解码器已关闭" }
        val n = nativeDecode(handle, input, inputLen, output, frameCapacity)
        check(n >= 0) { "Opus 解码失败，错误码 $n" }
        return n
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long

    private external fun nativeDecode(
        handle: Long,
        input: ByteArray,
        inputLen: Int,
        output: ShortArray,
        frameCapacity: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("remoteaudio_jni")
        }
    }
}