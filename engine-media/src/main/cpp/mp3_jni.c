#include <jni.h>
#include <stdlib.h>
#include "lame.h"

/* Encodes interleaved 16-bit PCM to a complete MP3 stream. Returns null on failure. */
JNIEXPORT jbyteArray JNICALL
Java_io_github_timefreak7_fileforge_media_Mp3Encoder_nativeEncode(
        JNIEnv *env, jclass clazz, jshortArray pcm, jint sampleRate, jint channels, jint bitrateKbps) {
    (void) clazz;
    jsize total = (*env)->GetArrayLength(env, pcm);
    int frames = total / channels;

    lame_global_flags *gf = lame_init();
    if (gf == NULL) return NULL;
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_num_channels(gf, channels);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(gf, bitrateKbps);
    lame_set_quality(gf, 2);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return NULL;
    }

    /* LAME's documented worst case: 1.25 * samples + 7200 bytes, plus room for the final flush. */
    int capacity = (int) (1.25 * frames) + 7200 + 7200;
    unsigned char *out = malloc(capacity);
    if (out == NULL) {
        lame_close(gf);
        return NULL;
    }

    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    int written = channels == 1
            ? lame_encode_buffer(gf, samples, NULL, frames, out, capacity)
            : lame_encode_buffer_interleaved(gf, samples, frames, out, capacity);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);

    jbyteArray result = NULL;
    if (written >= 0) {
        int flushed = lame_encode_flush(gf, out + written, capacity - written);
        if (flushed >= 0) {
            result = (*env)->NewByteArray(env, written + flushed);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(env, result, 0, written + flushed, (const jbyte *) out);
            }
        }
    }
    free(out);
    lame_close(gf);
    return result;
}
