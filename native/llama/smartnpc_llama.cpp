// Smart NPC's JNI bridge. llama.cpp is MIT licensed; see bundled notices.
// No server, socket, subprocess, network access, or prompt logging.
#include <jni.h>
#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "log.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using clock_type = std::chrono::steady_clock;
struct cancelled : std::runtime_error { using std::runtime_error::runtime_error; };
struct deadline_exceeded : std::runtime_error { using std::runtime_error::runtime_error; };

// ggml may query this from a native worker; never share a JNIEnv between threads.
struct cancellation {
    JavaVM * vm;
    jobject thread;
    jmethodID interrupted;
    clock_type::time_point deadline;
    std::atomic<bool> stopped{false};
    cancellation(JNIEnv * env, jobject owner, int seconds) : deadline(clock_type::now() + std::chrono::seconds(seconds)) {
        env->GetJavaVM(&vm);
        thread = env->NewGlobalRef(owner);
        if (!thread) throw std::bad_alloc();
        jclass type = env->GetObjectClass(owner);
        interrupted = env->GetMethodID(type, "isInterrupted", "()Z");
        env->DeleteLocalRef(type);
    }
    ~cancellation() {
        JNIEnv * env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) == JNI_OK) env->DeleteGlobalRef(thread);
    }
    bool abort() {
        JNIEnv * env = nullptr;
        jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8);
        bool attached = status == JNI_EDETACHED;
        if (status != JNI_OK && !attached) { stopped = true; return true; }
        if (attached && vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) {
            stopped = true;
            return true;
        }
        bool result = env->CallBooleanMethod(thread, interrupted);
        if (attached) vm->DetachCurrentThread();
        if (result) stopped = true;
        return stopped || clock_type::now() >= deadline;
    }
    void check() {
        abort();
        if (stopped) throw cancelled("Native inference cancelled");
        if (clock_type::now() >= deadline) throw deadline_exceeded("Native inference timed out");
    }
    static bool callback(void * data) { return static_cast<cancellation *>(data)->abort(); }
};

struct runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached;
    ~runtime() {
        templates.reset();
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::runtime_error("Missing native input");
    jsize length = env->GetArrayLength(value);
    if (length > 131072) throw std::runtime_error("Native input exceeds limit");
    std::string result(length, '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy JNI input");
    return result;
}
void report(JNIEnv * env, const char * type, const char * message) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}
void report_failure(JNIEnv * env) {
    if (env->ExceptionCheck()) return;
    try { throw; }
    catch (const cancelled & error) { report(env, "java/lang/InterruptedException", error.what()); }
    catch (const deadline_exceeded & error) { report(env, "java/util/concurrent/TimeoutException", error.what()); }
    catch (const std::exception & error) { report(env, "java/io/IOException", error.what()); }
    catch (...) { report(env, "java/io/IOException", "Unknown native inference failure"); }
}
std::once_flag backend;
void initialize() {
#if defined(__GNUC__) && (defined(__x86_64__) || defined(__i386__))
    if (!__builtin_cpu_supports("avx2") || !__builtin_cpu_supports("fma") || !__builtin_cpu_supports("f16c") || !__builtin_cpu_supports("bmi2"))
        throw std::runtime_error("Bundled llama.cpp requires an AVX2/FMA/F16C/BMI2 CPU");
#endif
    std::call_once(backend, [] {
        common_log_set_verbosity_thold(-1);
        common_log_set_stdo(common_log_main(), false);
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
    });
}
struct decode_scope {
    runtime & state;
    bool complete = false;
    decode_scope(runtime & state, cancellation & stop) : state(state) {
        llama_set_abort_callback(state.context, cancellation::callback, &stop);
    }
    ~decode_scope() {
        llama_set_abort_callback(state.context, nullptr, nullptr);
        if (!complete) {
            llama_memory_clear(llama_get_memory(state.context), true);
            state.cached.clear();
        }
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_pla_smart_1npc_ai_EmbeddedLlamaBridge_open(
        JNIEnv * env, jclass, jbyteArray path, jint context_size, jint threads, jint timeout, jobject thread) {
    try {
        initialize();
        cancellation stop(env, thread, timeout);
        stop.check();
        auto state = std::make_unique<runtime>();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void * data) { return !static_cast<cancellation *>(data)->abort(); };
        mp.progress_callback_user_data = &stop;
        state->model = llama_model_load_from_file(bytes(env, path).c_str(), mp);
        stop.check();
        if (!state->model) throw std::runtime_error("Cannot load GGUF with bundled llama.cpp");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size;
        cp.n_batch = 256;
        cp.n_ubatch = 256;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.abort_callback = cancellation::callback;
        cp.abort_callback_data = &stop;
        state->context = llama_init_from_model(state->model, cp);
        stop.check();
        if (!state->context) throw std::runtime_error("Cannot allocate native model context");
        llama_set_abort_callback(state->context, nullptr, nullptr);
        state->templates = common_chat_templates_init(state->model, "");
        stop.check();
        return reinterpret_cast<jlong>(state.release());
    } catch (...) { report_failure(env); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_pla_smart_1npc_ai_EmbeddedLlamaBridge_generate(
        JNIEnv * env, jclass, jlong handle, jbyteArray system, jbyteArray user, jbyteArray schema,
        jint max_tokens, jdouble temperature, jint timeout, jobject thread) {
    try {
        if (!handle) throw std::runtime_error("Native model is closed");
        auto & state = *reinterpret_cast<runtime *>(handle);
        cancellation stop(env, thread, timeout);
        decode_scope scope(state, stop);
        stop.check();
        auto vocab = llama_model_get_vocab(state.model);
        common_chat_templates_inputs input;
        common_chat_msg sys, usr;
        sys.role = "system"; sys.content = bytes(env, system);
        usr.role = "user"; usr.content = bytes(env, user);
        input.messages = {sys, usr};
        input.enable_thinking = false;
        input.chat_template_kwargs["enable_thinking"] = "false";
        input.json_schema = bytes(env, schema);
        common_chat_session chat(state.templates.get(), vocab, input);
        // Tokenize user parts without interpreting user-supplied special tokens.
        std::vector<llama_token> tokens;
        bool first = true;
        for (const auto & part : chat.prompt().parts) {
            auto next = common_tokenize(vocab, part.text, first, !part.is_input);
            tokens.insert(tokens.end(), next.begin(), next.end());
            first = false;
        }
        if (tokens.empty() || tokens.size() + max_tokens > llama_n_ctx(state.context))
            throw std::runtime_error("Prompt and response exceed native context; shorten context or increase contextSize");
        common_params_sampling sampling;
        sampling.temp = static_cast<float>(temperature);
        chat.apply_sampling(sampling);
        common_sampler_ptr sampler(common_sampler_init(state.model, sampling));
        if (!sampler) throw std::runtime_error("Cannot initialize structured reply sampler");
        auto memory = llama_get_memory(state.context);
        size_t reused = 0;
        while (reused < state.cached.size() && reused + 1 < tokens.size() && state.cached[reused] == tokens[reused]) ++reused;
        // Recurrent models may reject partial rollback: restart cleanly in that case.
        if (!reused || !llama_memory_seq_rm(memory, 0, reused, -1)) {
            llama_memory_clear(memory, true);
            reused = 0;
        }
        state.cached.assign(tokens.begin(), tokens.begin() + reused);
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(state.context), llama_batch_ext_free);
        auto decode = [&](const llama_token * data, size_t count, size_t position) {
            stop.check();
            llama_batch_ext_clear(batch.get());
            for (size_t i = 0; i < count; ++i) {
                int idx = llama_batch_ext_add_token(batch.get(), 0, data[i]);
                llama_pos pos = static_cast<llama_pos>(position + i);
                if (idx < 0 || !llama_batch_ext_set_pos(batch.get(), idx, &pos)) throw std::runtime_error("Invalid native batch");
            }
            llama_batch_ext_set_output_logits(batch.get(), count - 1, true);
            int result = llama_process(state.context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
            stop.check();
            if (result) throw std::runtime_error("Native prompt/token evaluation failed");
            state.cached.insert(state.cached.end(), data, data + count);
        };
        for (size_t i = reused; i < tokens.size(); ) {
            size_t count = std::min<size_t>(256, tokens.size() - i);
            decode(tokens.data() + i, count, i);
            i += count;
        }
        common_chat_input output;
        for (int i = 0; i < max_tokens; ++i) {
            stop.check();
            llama_token token = common_sampler_sample(sampler.get(), state.context, -1);
            common_sampler_accept(sampler.get(), token, true);
            if (llama_vocab_is_eog(vocab, token)) {
                const auto & message = chat.finish(output);
                if (!message.reasoning_content.empty() || !message.tool_calls.empty()) throw std::runtime_error("Unexpected reasoning or tool output");
                const std::string & result = message.content;
                if (result.empty() || result.size() > 65536) throw std::runtime_error("Invalid native response length");
                jbyteArray response = env->NewByteArray(static_cast<jsize>(result.size()));
                if (!response) return nullptr;
                env->SetByteArrayRegion(response, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
                scope.complete = true;
                return response;
            }
            output.append(common_token_to_piece(vocab, token, true), token);
            if (output.size() > 65536) throw std::runtime_error("Native response exceeds limit");
            decode(&token, 1, state.cached.size());
        }
        throw std::runtime_error("Model response was truncated at maxOutputTokens");
    } catch (...) { report_failure(env); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_pla_smart_1npc_ai_EmbeddedLlamaBridge_close(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<runtime *>(handle);
}
