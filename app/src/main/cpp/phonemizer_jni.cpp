// Thin JNI shim around espeak-ng's text-to-phoneme API. Kept deliberately dumb: all text
// normalization/sanitization happens in Kotlin before this is called, and all tensor work
// happens later in ONNX Runtime — this file's only job is "text in, IPA string out".
#include <jni.h>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>
#include <cstring>
#include <string>

#define LOG_TAG "PhonemizerJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
bool g_initialized = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinetts_reader_ingestion_Phonemizer_nativeInit(JNIEnv *env, jobject /*thiz*/,
                                                             jstring dataPath) {
    const char *path = env->GetStringUTFChars(dataPath, nullptr);

    // AUDIO_OUTPUT_SYNCHRONOUS: we only want text->phoneme conversion, never espeak-ng's
    // own audio device output — Kokoro is the only thing that produces audio in this app.
    int sampleRate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, /*buflength=*/0, path,
                                        espeakINITIALIZE_DONT_EXIT);
    env->ReleaseStringUTFChars(dataPath, path);

    if (sampleRate <= 0) {
        LOGE("espeak_Initialize failed (rc=%d) for data path", sampleRate);
        return JNI_FALSE;
    }
    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinetts_reader_ingestion_Phonemizer_nativeSetVoice(JNIEnv *env, jobject /*thiz*/,
                                                                 jstring voice) {
    if (!g_initialized) return JNI_FALSE;
    const char *voiceName = env->GetStringUTFChars(voice, nullptr);
    espeak_ERROR err = espeak_SetVoiceByName(voiceName);
    env->ReleaseStringUTFChars(voice, voiceName);
    return err == EE_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlinetts_reader_ingestion_Phonemizer_nativeTextToPhonemes(JNIEnv *env, jobject /*thiz*/,
                                                                       jstring text) {
    if (!g_initialized) {
        return env->NewStringUTF("");
    }

    const char *utf8 = env->GetStringUTFChars(text, nullptr);
    std::string input(utf8);
    env->ReleaseStringUTFChars(text, utf8);

    std::string result;
    const char *cursor = input.c_str();

    // espeakPHONEMES_IPA: emit IPA symbols (Kokoro's phoneme vocabulary is IPA-based).
    // espeak_TextToPhonemes consumes the buffer clause-by-clause, returning nullptr when
    // it has no more clauses left to process.
    while (cursor != nullptr) {
        const char *clausePhonemes = espeak_TextToPhonemes(
            reinterpret_cast<const void **>(&cursor),
            espeakCHARS_UTF8,
            espeakPHONEMES_IPA);
        if (clausePhonemes == nullptr) break;
        if (!result.empty()) result += " | ";
        result += clausePhonemes;
    }

    return env->NewStringUTF(result.c_str());
}
