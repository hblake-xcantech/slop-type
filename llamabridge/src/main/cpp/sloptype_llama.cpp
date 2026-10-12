// JNI bridge between dev.sloptype.llamabridge.LlamaNative and llama.cpp.
//
// One native handle = one llama_model + one llama_context (single sequence, seq_id 0).
// The KV cache always holds a *prefix* of the most recently used context: everything except
// the last context token. Scoring a continuation then decodes [last_ctx_token, cont...] as one
// batch with logits for every position, reads log-softmax of each actual next token, and
// removes those positions again so the next continuation (or the next call with the same
// context) starts from the same cached prefix.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cctype>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"

#define TAG "sloptype_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Lm {
    llama_model *        model = nullptr;
    llama_context *      ctx   = nullptr;
    const llama_vocab *  vocab = nullptr;
    llama_memory_t       mem   = nullptr;
    llama_batch          batch{};
    int                  n_ctx = 0;
    int                  n_vocab = 0;
    llama_token          bos   = LLAMA_TOKEN_NULL;
    bool                 add_bos = false;
    // Tokens currently in the KV cache for seq 0, positions [0, kv.size()).
    std::vector<llama_token> kv;
};

void log_cb(ggml_log_level level, const char * text, void *) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_VERBOSE; break;
    }
    // ggml emits trailing newlines; logcat adds its own.
    size_t n = strlen(text);
    while (n > 0 && (text[n - 1] == '\n' || text[n - 1] == '\r')) n--;
    if (n == 0) return;
    __android_log_print(prio, "llama.cpp", "%.*s", (int) n, text);
}

std::once_flag g_backend_once;
void backend_init() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    });
}

// Strict UTF-8 via String.getBytes("UTF-8"): GetStringUTFChars would hand us modified UTF-8
// (CESU-8 surrogate pairs for emoji), which the tokenizer treats as garbage bytes.
std::string jstring_to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    jclass string_cls = env->GetObjectClass(s);
    jmethodID get_bytes = env->GetMethodID(string_cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, get_bytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_cls);
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out((size_t) n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    return out;
}

// Same in the other direction: build the String from UTF-8 bytes.
jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass string_cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring out = (jstring) env->NewObject(string_cls, ctor, bytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_cls);
    return out;
}

std::vector<llama_token> tokenize(const Lm & lm, const std::string & text, bool add_special, bool parse_special) {
    std::vector<llama_token> out;
    if (text.empty() && !add_special) return out;
    int n = llama_tokenize(lm.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    if (n == INT32_MIN) return out;
    if (n < 0) n = -n;
    out.resize(n);
    int m = llama_tokenize(lm.vocab, text.c_str(), (int32_t) text.size(), out.data(), n, add_special, parse_special);
    if (m < 0) { out.clear(); return out; }
    out.resize(m);
    return out;
}

// Context tokens: BOS (if the model wants one) + text. Special tokens in the text are parsed so
// a context that literally starts with the BOS string is not given a second BOS.
std::vector<llama_token> context_tokens(const Lm & lm, const std::string & text) {
    std::vector<llama_token> toks = tokenize(lm, text, /*add_special*/ false, /*parse_special*/ true);
    if (lm.add_bos && lm.bos != LLAMA_TOKEN_NULL && (toks.empty() || toks[0] != lm.bos)) {
        toks.insert(toks.begin(), lm.bos);
    }
    if (toks.empty() && lm.bos != LLAMA_TOKEN_NULL) toks.push_back(lm.bos);
    return toks;
}

// Keep the context short enough that `reserve` more tokens fit in the KV cache. Drops the oldest
// tokens after the leading BOS.
void fit_context(const Lm & lm, std::vector<llama_token> & toks, int reserve) {
    const int limit = lm.n_ctx - reserve;
    if ((int) toks.size() <= limit) return;
    if (limit <= 0) { toks.clear(); return; }
    const bool has_bos = !toks.empty() && toks[0] == lm.bos && lm.bos != LLAMA_TOKEN_NULL;
    std::vector<llama_token> out;
    out.reserve(limit);
    if (has_bos) out.push_back(lm.bos);
    const int keep = limit - (int) out.size();
    if (keep > 0) out.insert(out.end(), toks.end() - keep, toks.end());
    toks.swap(out);
}

int decode_tokens(Lm & lm, const llama_token * toks, int n, llama_pos pos0, bool logits_all, bool logits_last) {
    if (n <= 0) return 0;
    llama_batch & b = lm.batch;
    b.n_tokens = n;
    for (int i = 0; i < n; i++) {
        b.token[i]    = toks[i];
        b.pos[i]      = pos0 + i;
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = 0;
        b.logits[i]   = logits_all || (logits_last && i == n - 1) ? 1 : 0;
    }
    const int rc = llama_decode(lm.ctx, b);
    if (rc != 0) LOGE("llama_decode failed: %d (n=%d, pos0=%d)", rc, n, (int) pos0);
    return rc;
}

// Make the KV cache hold exactly `prefix` (reusing whatever common prefix is already there).
bool ensure_prefix(Lm & lm, const std::vector<llama_token> & prefix) {
    size_t common = 0;
    const size_t lim = std::min(lm.kv.size(), prefix.size());
    while (common < lim && lm.kv[common] == prefix[common]) common++;
    if (common < lm.kv.size()) {
        if (!llama_memory_seq_rm(lm.mem, 0, (llama_pos) common, -1)) {
            llama_memory_clear(lm.mem, true);
            common = 0;
        }
        lm.kv.resize(common);
    }
    if (common < prefix.size()) {
        const int n = (int) (prefix.size() - common);
        if (decode_tokens(lm, prefix.data() + common, n, (llama_pos) common, false, false) != 0) {
            llama_memory_clear(lm.mem, true);
            lm.kv.clear();
            return false;
        }
        lm.kv.insert(lm.kv.end(), prefix.begin() + common, prefix.end());
    }
    return true;
}

// Drop everything in the KV cache at positions >= pos (keeps lm.kv consistent).
void truncate_kv(Lm & lm, size_t pos) {
    if (lm.kv.size() <= pos) return;
    if (!llama_memory_seq_rm(lm.mem, 0, (llama_pos) pos, -1)) {
        llama_memory_clear(lm.mem, true);
        lm.kv.clear();
        return;
    }
    lm.kv.resize(pos);
}

// log p(tok | logits) with max-subtraction; sum accumulated in double.
float log_softmax_at(const float * logits, int n_vocab, llama_token tok) {
    float mx = -INFINITY;
    for (int i = 0; i < n_vocab; i++) mx = std::max(mx, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < n_vocab; i++) sum += std::exp((double) (logits[i] - mx));
    return (float) ((double) logits[tok] - (double) mx - std::log(sum));
}

// Sum log-prob of `cont` given that the KV cache holds ctx[0..n-2] and ctx[n-1] = last.
float score_one(Lm & lm, llama_token last, const std::vector<llama_token> & cont, size_t prefix_len) {
    if (cont.empty()) return 0.0f;
    std::vector<llama_token> toks;
    toks.reserve(cont.size() + 1);
    toks.push_back(last);
    toks.insert(toks.end(), cont.begin(), cont.end());
    const int n = (int) toks.size();
    if ((int) prefix_len + n > lm.n_ctx) return -INFINITY;
    // The last token's logits are never read, so mark everything but the last as output.
    llama_batch & b = lm.batch;
    b.n_tokens = n;
    for (int i = 0; i < n; i++) {
        b.token[i]     = toks[i];
        b.pos[i]       = (llama_pos) prefix_len + i;
        b.n_seq_id[i]  = 1;
        b.seq_id[i][0] = 0;
        b.logits[i]    = i < n - 1 ? 1 : 0;
    }
    const int rc = llama_decode(lm.ctx, b);
    float total;
    if (rc != 0) {
        LOGE("llama_decode (score) failed: %d", rc);
        total = -INFINITY;
    } else {
        total = 0.0f;
        for (int i = 0; i < n - 1; i++) {
            const float * logits = llama_get_logits_ith(lm.ctx, i);
            if (logits == nullptr) { total = -INFINITY; break; }
            total += log_softmax_at(logits, lm.n_vocab, toks[i + 1]);
        }
    }
    // Remove the continuation (and `last`) again so the cached prefix is intact.
    if (!llama_memory_seq_rm(lm.mem, 0, (llama_pos) prefix_len, -1)) {
        llama_memory_clear(lm.mem, true);
        lm.kv.clear();
    }
    return total;
}

bool is_stop_char(unsigned char c) {
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true;
    if (c < 0x80 && std::ispunct(c)) return true;
    return false;
}

std::string token_piece(const Lm & lm, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(lm.vocab, tok, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(lm.vocab, tok, big.data(), (int32_t) big.size(), 0, false);
        if (n < 0) return {};
        big.resize(n);
        return big;
    }
    return std::string(buf, (size_t) n);
}

Lm * handle(jlong h) { return reinterpret_cast<Lm *>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_threads, jint ctx_size) {
    backend_init();
    const std::string path = jstring_to_utf8(env, jpath);
    if (path.empty()) return 0;
    if (n_threads < 1) n_threads = 1;
    if (ctx_size < 16) ctx_size = 16;

    auto * lm = new Lm();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // page the 145 MB GGUF in on demand, no mlock
    lm->model = llama_model_load_from_file(path.c_str(), mp);
    if (lm->model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        delete lm;
        return 0;
    }
    lm->vocab = llama_model_get_vocab(lm->model);
    lm->n_vocab = llama_vocab_n_tokens(lm->vocab);
    lm->bos = llama_vocab_bos(lm->vocab);
    lm->add_bos = llama_vocab_get_add_bos(lm->vocab);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) ctx_size;
    cp.n_batch = (uint32_t) ctx_size;   // one decode call covers any batch we submit
    cp.n_ubatch = (uint32_t) ctx_size;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings = false;
    cp.no_perf = true;
    lm->ctx = llama_init_from_model(lm->model, cp);
    if (lm->ctx == nullptr) {
        LOGE("failed to create context (n_ctx=%d)", ctx_size);
        llama_model_free(lm->model);
        delete lm;
        return 0;
    }
    lm->mem = llama_get_memory(lm->ctx);
    lm->n_ctx = (int) llama_n_ctx(lm->ctx);
    lm->batch = llama_batch_init(lm->n_ctx, 0, 1);
    LOGI("loaded %s: n_vocab=%d n_ctx=%d threads=%d add_bos=%d", path.c_str(), lm->n_vocab, lm->n_ctx, n_threads, (int) lm->add_bos);
    return reinterpret_cast<jlong>(lm);
}

JNIEXPORT void JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    Lm * lm = handle(h);
    if (lm == nullptr) return;
    llama_batch_free(lm->batch);
    if (lm->ctx) llama_free(lm->ctx);
    if (lm->model) llama_model_free(lm->model);
    delete lm;
}

// Returns one sum-log-prob per continuation, in order. -inf on failure for that entry.
JNIEXPORT jfloatArray JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_score(JNIEnv * env, jclass, jlong h, jstring jcontext, jobjectArray jconts) {
    Lm * lm = handle(h);
    const jsize n_conts = jconts ? env->GetArrayLength(jconts) : 0;
    jfloatArray result = env->NewFloatArray(n_conts);
    if (result == nullptr) return nullptr;
    std::vector<float> scores((size_t) n_conts, -INFINITY);
    if (lm == nullptr || n_conts == 0) {
        env->SetFloatArrayRegion(result, 0, n_conts, scores.data());
        return result;
    }

    std::vector<std::vector<llama_token>> conts((size_t) n_conts);
    int max_cont = 0;
    for (jsize i = 0; i < n_conts; i++) {
        auto js = (jstring) env->GetObjectArrayElement(jconts, i);
        conts[i] = tokenize(*lm, jstring_to_utf8(env, js), false, false);
        env->DeleteLocalRef(js);
        max_cont = std::max(max_cont, (int) conts[i].size());
    }

    std::vector<llama_token> ctx = context_tokens(*lm, jstring_to_utf8(env, jcontext));
    // Room for the longest continuation (the last context token is re-decoded with it).
    fit_context(*lm, ctx, max_cont + 1);
    if (ctx.empty()) {
        env->SetFloatArrayRegion(result, 0, n_conts, scores.data());
        return result;
    }
    const llama_token last = ctx.back();
    ctx.pop_back();
    if (ensure_prefix(*lm, ctx)) {
        for (jsize i = 0; i < n_conts; i++) {
            scores[i] = score_one(*lm, last, conts[i], ctx.size());
            if (lm->kv.size() != ctx.size() && !ensure_prefix(*lm, ctx)) break;
        }
    }
    env->SetFloatArrayRegion(result, 0, n_conts, scores.data());
    return result;
}

// Greedy completion; returns only the generated text, stopping at whitespace/punctuation.
JNIEXPORT jstring JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_complete(JNIEnv * env, jclass, jlong h, jstring jcontext, jint max_tokens) {
    Lm * lm = handle(h);
    if (lm == nullptr || max_tokens <= 0) return env->NewStringUTF("");

    std::vector<llama_token> ctx = context_tokens(*lm, jstring_to_utf8(env, jcontext));
    fit_context(*lm, ctx, max_tokens + 1);
    if (ctx.empty()) return env->NewStringUTF("");
    const llama_token last = ctx.back();
    ctx.pop_back();
    if (!ensure_prefix(*lm, ctx)) return env->NewStringUTF("");

    std::string out;
    llama_token cur = last;
    llama_pos pos = (llama_pos) ctx.size();
    for (int step = 0; step < max_tokens; step++) {
        if (pos >= lm->n_ctx) break;
        if (decode_tokens(*lm, &cur, 1, pos, false, true) != 0) break;
        pos++;
        const float * logits = llama_get_logits_ith(lm->ctx, -1);
        if (logits == nullptr) break;
        llama_token best = 0;
        float best_v = logits[0];
        for (int i = 1; i < lm->n_vocab; i++) {
            if (logits[i] > best_v) { best_v = logits[i]; best = i; }
        }
        if (llama_vocab_is_eog(lm->vocab, best)) break;
        std::string piece = token_piece(*lm, best);
        size_t start = 0;
        if (out.empty()) {
            // A word-initial token usually carries the leading space; keep it, but only once.
            while (start < piece.size() && (piece[start] == ' ')) start++;
            if (start > 0) out.push_back(' ');
        }
        bool stop = false;
        for (size_t i = start; i < piece.size(); i++) {
            if (is_stop_char((unsigned char) piece[i])) { stop = true; break; }
            out.push_back(piece[i]);
        }
        if (stop) break;
        cur = best;
    }
    // Restore the cached prefix: drop `last` and everything generated.
    truncate_kv(*lm, ctx.size());
    return utf8_to_jstring(env, out);
}

JNIEXPORT jint JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_contextSize(JNIEnv *, jclass, jlong h) {
    Lm * lm = handle(h);
    return lm ? lm->n_ctx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_tokenCount(JNIEnv * env, jclass, jlong h, jstring jtext) {
    Lm * lm = handle(h);
    if (lm == nullptr) return 0;
    return (jint) tokenize(*lm, jstring_to_utf8(env, jtext), false, false).size();
}

JNIEXPORT jstring JNICALL
Java_dev_sloptype_llamabridge_LlamaNative_systemInfo(JNIEnv * env, jclass) {
    backend_init();
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
