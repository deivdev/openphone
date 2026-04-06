#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>

#include "llama.h"
#include "common.h"

#define TAG "LlmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static llama_sampler *sampler = nullptr;
static std::atomic<bool> stop_flag(false);

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openphone_agent_llm_LlmInference_loadModel(
        JNIEnv *env, jobject /* this */,
        jstring model_path, jint n_threads, jint context_size) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s (threads=%d, ctx=%d)", path, n_threads, context_size);

    // Initialize llama backend
    llama_backend_init();

    // Load model
    auto model_params = llama_model_default_params();
    model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Create context
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_size;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        model = nullptr;
        return JNI_FALSE;
    }

    // Create sampler (greedy / temperature 0)
    auto sampler_params = llama_sampler_chain_default_params();
    sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_openphone_agent_llm_LlmInference_generate(
        JNIEnv *env, jobject /* this */, jstring prompt_jstr) {

    if (!model || !ctx || !sampler) {
        return env->NewStringUTF("[Error: model not loaded]");
    }

    stop_flag = false;
    const char *prompt_cstr = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_cstr);

    // Tokenize the prompt
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const int n_prompt_max = llama_n_ctx(ctx);
    std::vector<llama_token> tokens(n_prompt_max);
    const int n_prompt = llama_tokenize(
            vocab, prompt.c_str(), prompt.length(),
            tokens.data(), tokens.size(), true, true);

    if (n_prompt < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("[Error: tokenization failed]");
    }
    tokens.resize(n_prompt);

    LOGI("Prompt tokens: %d", n_prompt);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(ctx), true);

    // Decode prompt in batch
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        return env->NewStringUTF("[Error: decode failed]");
    }

    // Generate tokens
    std::string result;
    const int max_gen_tokens = 512;

    for (int i = 0; i < max_gen_tokens; i++) {
        if (stop_flag.load()) {
            LOGI("Generation stopped by user");
            break;
        }

        llama_token new_token = llama_sampler_sample(sampler, ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOS reached after %d tokens", i);
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        // Prepare batch for next token
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(ctx, next_batch) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
    }

    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_openphone_agent_llm_LlmInference_stopGeneration(
        JNIEnv * /* env */, jobject /* this */) {
    stop_flag = true;
    LOGI("Stop requested");
}

JNIEXPORT jboolean JNICALL
Java_com_openphone_agent_llm_LlmInference_isModelLoaded(
        JNIEnv * /* env */, jobject /* this */) {
    return (model != nullptr && ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openphone_agent_llm_LlmInference_unloadModel(
        JNIEnv * /* env */, jobject /* this */) {
    if (sampler) {
        llama_sampler_free(sampler);
        sampler = nullptr;
    }
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model) {
        llama_model_free(model);
        model = nullptr;
    }
    llama_backend_free();
    LOGI("Model unloaded");
}

} // extern "C"
