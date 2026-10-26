#include "llama.h"
#include <jni.h>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
using Clock = std::chrono::steady_clock;
static bool expired(void * p) { return Clock::now() >= *static_cast<Clock::time_point *>(p); }
static bool loading(float, void * p) { return !expired(p); }
static std::string bytes(JNIEnv * e, jbyteArray a) {
    auto n = e->GetArrayLength(a); std::string s(n, '\0');
    e->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(s.data())); return s;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vlesscardvpn_ai_LocalAiNative_generate(JNIEnv * e, jobject, jstring path, jbyteArray prompt, jboolean planner) {
    try {
        auto deadline = Clock::now() + std::chrono::seconds(35);
        auto p = e->GetStringUTFChars(path, nullptr);
        if (!p) return nullptr;
        std::string filename(p); e->ReleaseStringUTFChars(path, p);
        auto text = bytes(e, prompt);
        if (text.empty() || text.size() > 12000) throw std::runtime_error("prompt_size");
        llama_backend_init();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        mp.progress_callback = loading; mp.progress_callback_user_data = &deadline;
        using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
        Model model(llama_model_load_from_file(filename.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("model_load");
        const auto * vocab = llama_model_get_vocab(model.get());
        int n = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
        if (n <= 0 || n > 768) throw std::runtime_error("context_limit");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, text.data(), text.size(), tokens.data(), n, true, true) != n)
            throw std::runtime_error("tokenize");
        auto cp = llama_context_default_params(); cp.n_ctx = 1024; cp.n_batch = 256; cp.n_ubatch = 128;
        cp.n_threads = 2; cp.n_threads_batch = 2;
        cp.abort_callback = expired; cp.abort_callback_data = &deadline;
        using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
        Context ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("context_init");
        for (int offset = 0; offset < n; offset += 256) {
            if (expired(&deadline) || llama_decode(ctx.get(), llama_batch_get_one(tokens.data()+offset, std::min(256, n-offset))))
                throw std::runtime_error("prefill_timeout");
        }
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (planner) {
            const char * grammar = "root ::= \"PLAN=\" (\"balanced\" | \"latency\" | \"stability\") \"\\nPOWER=\" (\"eco\" | \"balanced\") \"\\nACTION=none\"";
            auto * g = llama_sampler_init_grammar(vocab, grammar, "root");
            if (!g) throw std::runtime_error("planner_grammar");
            llama_sampler_chain_add(sampler.get(), g);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string out;
        for (int i = 0; i < 160; ++i) {
            if (expired(&deadline)) throw std::runtime_error("generation_timeout");
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            char buf[512]; int size = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
            if (size < 0 || size > int(sizeof(buf))) throw std::runtime_error("piece_size");
            out.append(buf, size);
            if (out.size() > 4096) break;
            if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1))) throw std::runtime_error("decode");
        }
        if (out.empty()) throw std::runtime_error("empty_output");
        auto result = e->NewByteArray(out.size());
        if (result) e->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte *>(out.data()));
        return result;
    } catch (const std::exception & ex) {
        e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), ex.what()); return nullptr;
    } catch (...) {
        e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), "native_failure"); return nullptr;
    }
}
