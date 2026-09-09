#include <jni.h>
#include <stdint.h>
#include <FLAC/stream_encoder.h>

JNIEXPORT jlong JNICALL
Java_com_bnyro_clock_social_data_FlacStreamEncoder_createEncoder(
    JNIEnv *env, jobject instance, jstring path, jint sample_rate
) {
    (void)instance;
    FLAC__StreamEncoder *encoder = FLAC__stream_encoder_new();
    if (encoder == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"),
            "Cannot allocate the FLAC encoder");
        return 0;
    }
    const char *filename = (*env)->GetStringUTFChars(env, path, NULL);
    if (filename == NULL) {
        FLAC__stream_encoder_delete(encoder);
        return 0;
    }
    FLAC__bool configured = FLAC__stream_encoder_set_channels(encoder, 1)
        && FLAC__stream_encoder_set_bits_per_sample(encoder, 16)
        && FLAC__stream_encoder_set_sample_rate(encoder, (uint32_t)sample_rate)
        && FLAC__stream_encoder_set_compression_level(encoder, 5);
    FLAC__StreamEncoderInitStatus status = configured
        ? FLAC__stream_encoder_init_file(encoder, filename, NULL, NULL)
        : FLAC__STREAM_ENCODER_INIT_STATUS_ENCODER_ERROR;
    (*env)->ReleaseStringUTFChars(env, path, filename);
    if (status != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
        FLAC__stream_encoder_delete(encoder);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
            FLAC__StreamEncoderInitStatusString[status]);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jboolean JNICALL
Java_com_bnyro_clock_social_data_FlacStreamEncoder_encodeSamples(
    JNIEnv *env, jobject instance, jlong handle, jshortArray samples, jint offset, jint count
) {
    (void)instance;
    jshort input[4096];
    FLAC__int32 pcm[4096];
    if (count < 0 || count > 4096) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "Invalid FLAC sample block size");
        return JNI_FALSE;
    }
    (*env)->GetShortArrayRegion(env, samples, offset, count, input);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    for (jint index = 0; index < count; index++) pcm[index] = input[index];
    return FLAC__stream_encoder_process_interleaved(
        (FLAC__StreamEncoder *)(intptr_t)handle, pcm, (uint32_t)count
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bnyro_clock_social_data_FlacStreamEncoder_finishEncoder(
    JNIEnv *env, jobject instance, jlong handle
) {
    (void)env;
    (void)instance;
    return FLAC__stream_encoder_finish((FLAC__StreamEncoder *)(intptr_t)handle)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bnyro_clock_social_data_FlacStreamEncoder_deleteEncoder(
    JNIEnv *env, jobject instance, jlong handle
) {
    (void)env;
    (void)instance;
    FLAC__stream_encoder_delete((FLAC__StreamEncoder *)(intptr_t)handle);
}
