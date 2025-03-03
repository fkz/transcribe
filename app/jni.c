#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}


static void whisper_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    (void) level;
    (void) user_data;
#ifndef WHISPER_DEBUG
    if (level == GGML_LOG_LEVEL_DEBUG) {
        return;
    }
#endif
    __android_log_print(ANDROID_LOG_INFO, "WHISPER_CPP", "%s", text);
}


JNIEXPORT void JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_initLogging(JNIEnv *env, jobject thiz) {
    whisper_log_set(whisper_log_callback, NULL);
}

JNIEXPORT jlong JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu;
    context = whisper_init_from_file_with_params(model_path_chars, params);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
UNUSED(env);
UNUSED(thiz);
struct whisper_context *context = (struct whisper_context *) context_ptr;
whisper_free(context);
}

struct progress_segment_user_data {
    JNIEnv * env;
    jmethodID invoke;
    jobject object;
};

void callback_wrapper(struct whisper_context * ctx, struct whisper_state * state, int data, void *v_user_data) {
    struct progress_segment_user_data *user_data = (struct progress_segment_user_data *)v_user_data;
    (*user_data->env)->CallVoidMethod(user_data->env, user_data->object, user_data->invoke, data);
}



JNIEXPORT void JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jobject progress_callback, jobject segment_callback, jstring prompt) {
UNUSED(thiz);
struct whisper_context *context = (struct whisper_context *) context_ptr;
jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);


// The below adapted from the Objective-C iOS sample
struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
params.print_realtime = false;
params.print_progress = false;
params.print_timestamps = true;
params.print_special = false;
params.translate = false;
params.language = "de";
params.n_threads = num_threads;
params.single_segment = false;

if (prompt != NULL) {
    params.initial_prompt = (*env)->GetStringUTFChars(env, prompt, NULL);
}

struct progress_segment_user_data progress_user_data;
struct progress_segment_user_data segment_user_data;
progress_user_data.env = env;
segment_user_data.env = env;
jclass functionClass = (*env)->GetObjectClass(env, progress_callback);
progress_user_data.invoke = (*env)->GetMethodID(env, functionClass, "invoke", "(I)V");
progress_user_data.object = progress_callback;

jclass functionClass2 = (*env)->GetObjectClass(env, segment_callback);
segment_user_data.invoke = (*env)->GetMethodID(env, functionClass2, "invoke", "(I)V");
segment_user_data.object = segment_callback;

params.progress_callback = &callback_wrapper;
params.progress_callback_user_data = &progress_user_data;

params.new_segment_callback = &callback_wrapper;
params.new_segment_callback_user_data = &segment_user_data;

//whisper_reset_timings(context);

LOGI("About to run whisper_full");
if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
LOGI("Failed to run the model");
} else {
whisper_print_timings(context);
}
(*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
if (prompt != NULL) {
    (*env)->ReleaseStringUTFChars(env, prompt, params.initial_prompt);
}
}

JNIEXPORT jint JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                  jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_eu_schmitthenner_transcribe_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}