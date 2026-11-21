#include <jni.h>
#include <opus.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_remoteaudio_audio_OpusDecoder_nativeCreate(JNIEnv *env, jobject thiz,
                                                            jint sample_rate, jint channels) {
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, channels, &error);
    if (error != OPUS_OK || decoder == nullptr) {
        if (decoder != nullptr) {
            opus_decoder_destroy(decoder);
        }
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

// 返回每声道解码出的样本数，负值为 Opus 错误码
JNIEXPORT jint JNICALL
Java_com_example_remoteaudio_audio_OpusDecoder_nativeDecode(JNIEnv *env, jobject thiz, jlong handle,
                                                            jbyteArray input, jint input_len,
                                                            jshortArray output, jint frame_capacity) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(handle);
    if (decoder == nullptr) {
        return OPUS_BAD_ARG;
    }
    jbyte *in = env->GetByteArrayElements(input, nullptr);
    jshort *out = env->GetShortArrayElements(output, nullptr);
    jint decoded = opus_decode(decoder, reinterpret_cast<const unsigned char *>(in), input_len,
                               out, frame_capacity, 0);
    env->ReleaseByteArrayElements(input, in, JNI_ABORT);
    env->ReleaseShortArrayElements(output, out, decoded > 0 ? 0 : JNI_ABORT);
    return decoded;
}

JNIEXPORT void JNICALL
Java_com_example_remoteaudio_audio_OpusDecoder_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(handle);
    if (decoder != nullptr) {
        opus_decoder_destroy(decoder);
    }
}

}  // extern "C"