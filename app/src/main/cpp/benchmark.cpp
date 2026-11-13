#include <jni.h>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include <cstring>
#include <string>

struct Session { std::atomic<bool> cancelled{false}; };
static std::once_flag initialized;
static double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
static bool aborted(void * p) { return static_cast<Session *>(p)->cancelled.load(); }
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_aiclusterbenchmark_LlmEngine_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Session());
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_aiclusterbenchmark_LlmEngine_cancel(JNIEnv *, jobject, jlong h) {
    reinterpret_cast<Session *>(h)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_aiclusterbenchmark_LlmEngine_destroy(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<Session *>(h);
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_aiclusterbenchmark_LlmEngine_run(JNIEnv * env, jobject, jlong h, jstring path, jint threads) {
    try {
        auto * session = reinterpret_cast<Session *>(h);
        auto check = [&] { if (aborted(session)) throw std::runtime_error("Test cancelled"); };
        check();
        std::call_once(initialized, [] { llama_backend_init(); });
        const char * chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        std::string filename(chars);
        env->ReleaseStringUTFChars(path, chars);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0; mp.use_mmap = true;
        mp.progress_callback = [](float, void * p) { return !aborted(p); };
        mp.progress_callback_user_data = session;
        double start = now_ms();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(filename.c_str(), mp), llama_model_free);
        check();
        if (!model) throw std::runtime_error("Cannot load GGUF model. Try SmolLM2-135M Q4_K_M; check available memory.");
        double load = now_ms() - start;
        auto cp = llama_context_default_params();
        cp.n_ctx = 256; cp.n_batch = 128; cp.n_ubatch = 32;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.abort_callback = aborted; cp.abort_callback_data = session;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("Cannot allocate context; close other apps and retry.");
        auto vocab = llama_model_get_vocab(model.get());
        const char * prompt = "The following is a short explanation of why the sky is blue. The sky is blue because";
        std::vector<llama_token> tokens(128);
        int n = llama_tokenize(vocab, prompt, std::strlen(prompt), tokens.data(), tokens.size(), true, false);
        if (n <= 0 || n > 128) throw std::runtime_error("Tokenizer failed");
        check(); start = now_ms();
        if (llama_decode(ctx.get(), llama_batch_get_one(tokens.data(), n)) != 0) throw std::runtime_error("Prompt evaluation failed or cancelled");
        llama_synchronize(ctx.get());
        double prefill = now_ms() - start;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        int count = 0;
        start = now_ms();
        for (int i = 0; i < 32; ++i) {
            check();
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) throw std::runtime_error("Generation failed or cancelled");
            llama_synchronize(ctx.get());
            ++count;
        }
        check();
        double generation = now_ms() - start;
        if (count == 0) throw std::runtime_error("Model immediately ended generation; no speed result available");
        double values[] = {load, prefill, generation, double(count), double(n), double(threads)};
        auto result = env->NewDoubleArray(6);
        if (result) env->SetDoubleArrayRegion(result, 0, 6, values);
        return result;
    } catch (const std::exception & e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
