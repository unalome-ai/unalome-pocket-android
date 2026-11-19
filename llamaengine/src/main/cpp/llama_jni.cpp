// JNI bridge between LlamaNative.kt and llama.cpp (text) plus libmtmd (images).
//
// Threading: Kotlin calls every function here from one dedicated thread, except `abort` and
// `resetAbort`, which may be called from any thread and only touch an atomic flag. So an Engine
// is never used by two threads at once and needs no lock of its own.
//
// Strings cross the boundary as UTF-8 byte arrays, never through NewStringUTF/GetStringUTFChars:
// those use "modified UTF-8", which mangles characters outside the BMP (most emoji).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "utf8.h"

namespace {

constexpr const char * kTag = "UnalomeLlama";

enum StepStatus : jint {
    STEP_CONTINUE = 0,
    STEP_DONE = 1,          // end of generation token or a stop string
    STEP_LENGTH_LIMIT = 2,  // maxTokens reached or the context is full
    STEP_ABORTED = 3,
};

enum Role : jint { ROLE_SYSTEM = 0, ROLE_USER = 1, ROLE_ASSISTANT = 2 };

// One entry of the context's memory: a text token, or a whole image (its tokens are kept or
// dropped together, and an image is matched by the SHA-256 that mtmd gives its bitmap).
/** Upper bound on the tokens one image becomes, for readers with a variable image size. */
constexpr int MAX_IMAGE_TOKENS = 320;

struct PromptItem {
    llama_token token = LLAMA_TOKEN_NULL;
    std::string image_id;
    int32_t n_tokens = 1;
    llama_pos n_pos = 1;

    bool operator==(const PromptItem & other) const {
        return token == other.token && image_id == other.image_id && n_tokens == other.n_tokens && n_pos == other.n_pos;
    }
};

struct Generation {
    std::unique_ptr<common_chat_session> chat;
    llama_sampler * sampler = nullptr;
    int max_tokens = 0;
    int generated = 0;
    // Bytes of a character split across tokens (or of a possible stop string), and the token they
    // started in.
    std::string pending;
    llama_token pending_token = LLAMA_TOKEN_NULL;
    // What has been handed to Kotlin so far, to compute the next deltas.
    std::string sent_reasoning;
    std::string sent_content;
    // Literal stop strings from the chat template, and the raw text generated so far to find them.
    std::vector<std::string> stops;
    std::string raw;

    void reset() {
        if (sampler != nullptr) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        chat.reset();
        max_tokens = 0;
        generated = 0;
        pending.clear();
        pending_token = LLAMA_TOKEN_NULL;
        sent_reasoning.clear();
        sent_content.clear();
        stops.clear();
        raw.clear();
    }
};

struct PromptStats {
    int64_t prompt_tokens = 0;
    int64_t reused_tokens = 0;
    int64_t images = 0;
    int64_t image_millis = 0;
    int64_t total_millis = 0;
};

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr templates;
    llama_batch_ext * batch = nullptr;
    // The image reader, created on the first image turn from the model's projector file.
    mtmd_context * mtmd = nullptr;
    // A marker that user text never contains, replaced by the image when the prompt is tokenized.
    std::string media_marker;
    // What is in the context's memory, in order; its prefix is reused by the next reply.
    std::vector<PromptItem> cached;
    llama_pos n_past = 0;   // next position
    size_t n_used = 0;      // memory cells in use
    std::atomic<bool> abort{false};
    uint32_t eval_nodes = 0;
    Generation gen;
    PromptStats stats;

    ~Engine() {
        gen.reset();
        templates.reset();
        if (mtmd != nullptr) mtmd_free(mtmd);
        if (batch != nullptr) llama_batch_ext_free(batch);
        if (ctx != nullptr) llama_free(ctx);
        if (model != nullptr) llama_model_free(model);
    }

    void forget_memory() {
        llama_memory_clear(llama_get_memory(ctx), true);
        cached.clear();
        n_past = 0;
        n_used = 0;
    }

    void remember(const PromptItem & item) {
        cached.push_back(item);
        n_past += item.n_pos;
        n_used += static_cast<size_t>(item.n_tokens);
    }
};

struct BitmapDeleter {
    void operator()(mtmd_bitmap * bitmap) const { mtmd_bitmap_free(bitmap); }
};
using BitmapPtr = std::unique_ptr<mtmd_bitmap, BitmapDeleter>;

struct ChunksDeleter {
    void operator()(mtmd_input_chunks * chunks) const { mtmd_input_chunks_free(chunks); }
};
using ChunksPtr = std::unique_ptr<mtmd_input_chunks, ChunksDeleter>;

// Cached JNI handles, set in JNI_OnLoad.
jclass g_step_class = nullptr;
jmethodID g_step_ctor = nullptr;
jclass g_exception_class = nullptr;

void throw_java(JNIEnv * env, const std::string & message) {
    if (env->ExceptionCheck()) return;
    env->ThrowNew(g_exception_class, message.c_str());
}

Engine * engine_from(JNIEnv * env, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) throw_java(env, "The model is not loaded.");
    return engine;
}

std::string to_string(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & text) {
    if (text.empty()) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
    if (array == nullptr) return nullptr;
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return array;
}

std::string file_name(const std::string & path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

int64_t millis_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
}

void log_to_android(ggml_log_level level, const char * text, void *) {
    // Warnings and errors only. llama.cpp and mtmd log no prompt text at these levels.
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, kTag, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, kTag, text);
    }
}

std::string random_marker() {
    std::random_device device;
    std::mt19937_64 generator(device());
    char hex[17];
    snprintf(hex, sizeof(hex), "%016llx", static_cast<unsigned long long>(generator()));
    return std::string("<__unalome_image_") + hex + "__>";
}

// The image paths of each turn (`null` entries for turns without images).
std::vector<std::vector<std::string>> read_images(JNIEnv * env, jobjectArray images, jsize turns) {
    std::vector<std::vector<std::string>> out(static_cast<size_t>(turns));
    if (images == nullptr) return out;
    for (jsize i = 0; i < turns && i < env->GetArrayLength(images); ++i) {
        auto paths = static_cast<jobjectArray>(env->GetObjectArrayElement(images, i));
        if (paths == nullptr) continue;
        for (jsize j = 0; j < env->GetArrayLength(paths); ++j) {
            auto path = static_cast<jbyteArray>(env->GetObjectArrayElement(paths, j));
            out[static_cast<size_t>(i)].push_back(to_string(env, path));
            env->DeleteLocalRef(path);
        }
        env->DeleteLocalRef(paths);
    }
    return out;
}

// The width and height of each image of each turn, for counting tokens without decoding them.
std::vector<std::vector<std::pair<uint32_t, uint32_t>>> read_sizes(JNIEnv * env, jobjectArray sizes, jsize turns) {
    std::vector<std::vector<std::pair<uint32_t, uint32_t>>> out(static_cast<size_t>(turns));
    if (sizes == nullptr) return out;
    for (jsize i = 0; i < turns && i < env->GetArrayLength(sizes); ++i) {
        auto values = static_cast<jintArray>(env->GetObjectArrayElement(sizes, i));
        if (values == nullptr) continue;
        const jsize n = env->GetArrayLength(values);
        std::vector<jint> raw(static_cast<size_t>(n));
        env->GetIntArrayRegion(values, 0, n, raw.data());
        for (jsize j = 0; j + 1 < n; j += 2) {
            out[static_cast<size_t>(i)].emplace_back(static_cast<uint32_t>(std::max(1, raw[j])), static_cast<uint32_t>(std::max(1, raw[j + 1])));
        }
        env->DeleteLocalRef(values);
    }
    return out;
}

// Each image of a user turn becomes a marker before its text, as llama.cpp's server does for
// OpenAI-style content parts.
common_chat_templates_inputs build_inputs(
        JNIEnv * env, jintArray roles, jobjectArray texts, const std::vector<size_t> & image_counts,
        const std::string & marker, bool thinking) {
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    // Reasoning goes to reasoning_content, also while streaming.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    inputs.enable_thinking = thinking;

    const jsize count = env->GetArrayLength(roles);
    std::vector<jint> role_values(static_cast<size_t>(count));
    env->GetIntArrayRegion(roles, 0, count, role_values.data());
    for (jsize i = 0; i < count; ++i) {
        auto text = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
        common_chat_msg message;
        switch (role_values[static_cast<size_t>(i)]) {
            case ROLE_SYSTEM: message.role = "system"; break;
            case ROLE_ASSISTANT: message.role = "assistant"; break;
            default: message.role = "user"; break;
        }
        const size_t images = static_cast<size_t>(i) < image_counts.size() ? image_counts[static_cast<size_t>(i)] : 0;
        for (size_t j = 0; j < images; ++j) message.content += marker;
        message.content += to_string(env, text);
        env->DeleteLocalRef(text);
        inputs.messages.push_back(std::move(message));
    }
    return inputs;
}

// Decodes tokens at positions pos0…, in batches of n_batch, optionally asking for logits of the
// last one. Returns llama_process's code: 0 ok, 1 context full, 2 aborted, <0 error.
int decode(Engine & engine, const llama_token * tokens, size_t count, llama_pos pos0, bool logits_last = true) {
    const size_t n_batch = std::max<uint32_t>(1, llama_n_batch(engine.ctx));
    for (size_t start = 0; start < count; start += n_batch) {
        const size_t n = std::min(n_batch, count - start);
        llama_batch_ext_clear(engine.batch);
        for (size_t i = 0; i < n; ++i) {
            const int32_t index = llama_batch_ext_add_token(engine.batch, 0, tokens[start + i]);
            if (index < 0) return -1;
            const llama_pos pos = pos0 + static_cast<llama_pos>(start + i);
            llama_batch_ext_set_pos(engine.batch, index, &pos);
        }
        if (logits_last && start + n == count) {
            llama_batch_ext_set_output_logits(engine.batch, static_cast<int32_t>(n - 1), true);
        }
        const int code = llama_process(engine.ctx, LLAMA_PROCESS_TYPE_DECODE, engine.batch);
        if (code != 0) return code;
    }
    return 0;
}

// The image encoder has no abort callback of its own. Its scheduler asks, node by node, whether
// we want to look at a result: saying yes every 64 nodes splits the work into short runs, and
// answering "stop" after a run ends the encode early when the reply was cancelled.
bool image_eval_callback(ggml_tensor *, bool ask, void * data) {
    auto * engine = static_cast<Engine *>(data);
    if (ask) return engine->abort.load() || (++engine->eval_nodes % 64 == 0);
    return !engine->abort.load();
}

// The text that `now` adds to `sent`, or nothing when the parser rewrote earlier text (it then
// cannot be taken back, so the stream keeps what it has).
std::string delta(std::string & sent, const std::string & now) {
    if (now.size() > sent.size() && now.compare(0, sent.size(), sent) == 0) {
        std::string added = now.substr(sent.size());
        sent = now;
        return added;
    }
    return {};
}

jobject make_step(JNIEnv * env, jint status, const std::string & reasoning, const std::string & content) {
    jbyteArray reasoning_bytes = to_bytes(env, reasoning);
    jbyteArray content_bytes = to_bytes(env, content);
    jobject step = env->NewObject(g_step_class, g_step_ctor, status, reasoning_bytes, content_bytes);
    if (reasoning_bytes != nullptr) env->DeleteLocalRef(reasoning_bytes);
    if (content_bytes != nullptr) env->DeleteLocalRef(content_bytes);
    return step;
}

// Bytes at the end of `text` that could be the start of a stop string, held back until it is
// clear whether they are one.
size_t possible_stop_suffix(const std::string & text, const std::vector<std::string> & stops) {
    size_t hold = 0;
    for (const auto & stop : stops) {
        if (stop.empty()) continue;
        for (size_t length = std::min(text.size(), stop.size() - 1); length > hold; --length) {
            if (text.compare(text.size() - length, length, stop, 0, length) == 0) {
                hold = length;
                break;
            }
        }
    }
    return hold;
}

// Feeds complete characters to the chat parser and returns the step with what changed.
jobject parse_and_step(JNIEnv * env, Engine & engine, jint status, bool final) {
    Generation & gen = engine.gen;
    common_chat_input chunk;
    const size_t hold = final ? 0 : possible_stop_suffix(gen.pending, gen.stops);
    // At the end, bytes of an unfinished character are dropped rather than shown as U+FFFD.
    const size_t complete = unalome::utf8_complete_prefix(gen.pending.substr(0, gen.pending.size() - hold));
    if (complete > 0) {
        chunk.append(gen.pending.substr(0, complete), gen.pending_token);
        gen.pending.erase(0, complete);
        gen.pending_token = LLAMA_TOKEN_NULL;
    }
    if (final) gen.pending.clear();

    const common_chat_msg & message = final ? gen.chat->finish(chunk) : gen.chat->feed(chunk);
    const std::string reasoning = delta(gen.sent_reasoning, message.reasoning_content);
    const std::string content = delta(gen.sent_content, message.content);
    return make_step(env, status, reasoning, content);
}

jobject finish(JNIEnv * env, Engine & engine, jint status) {
    jobject step = parse_and_step(env, engine, status, true);
    engine.gen.reset();
    return step;
}

llama_sampler * make_sampler(const llama_vocab * vocab, float temperature, uint32_t seed) {
    auto params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(params);
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    // A mild repetition penalty over the last 64 tokens keeps small models from looping without
    // bending normal answers. After top-k, as llama.h advises, so it scans 40 candidates only.
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

struct LoadProgress {
    JNIEnv * env;
    jobject listener;
    jmethodID on_progress;
};

bool on_load_progress(float progress, void * user_data) {
    auto * state = static_cast<LoadProgress *>(user_data);
    if (state->listener == nullptr) return true;
    const jboolean keep_going = state->env->CallBooleanMethod(state->listener, state->on_progress, progress);
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
        return false;
    }
    return keep_going == JNI_TRUE;
}

// The prompt as memory items, plus the mtmd chunks when it has images.
struct Prompt {
    std::vector<PromptItem> items;
    ChunksPtr chunks;
};

// Tokenizes the rendered prompt. With images, mtmd splits it at the markers into text and image
// chunks; `bitmaps` are the decoded images or, for counting, placeholders with their size only.
Prompt tokenize(Engine & engine, const std::string & text, const std::vector<BitmapPtr> & bitmaps) {
    Prompt prompt;
    if (bitmaps.empty()) {
        for (llama_token token : common_tokenize(engine.vocab, text, true, true)) prompt.items.push_back({token});
        return prompt;
    }
    if (engine.mtmd == nullptr) throw std::runtime_error("the image reader is not loaded");
    prompt.chunks.reset(mtmd_input_chunks_init());
    mtmd_input_text input{text.c_str(), text.size(), true, true};
    std::vector<const mtmd_bitmap *> pointers;
    for (const auto & bitmap : bitmaps) pointers.push_back(bitmap.get());
    const int32_t code = mtmd_tokenize(engine.mtmd, prompt.chunks.get(), &input, pointers.data(), pointers.size());
    if (code != 0) throw std::runtime_error(code == 2 ? "an image could not be prepared" : "the images did not match the message");
    for (size_t i = 0; i < mtmd_input_chunks_size(prompt.chunks.get()); ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(prompt.chunks.get(), i);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n = 0;
            const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &n);
            for (size_t j = 0; j < n; ++j) prompt.items.push_back({tokens[j]});
        } else {
            const char * id = mtmd_input_chunk_get_id(chunk);
            prompt.items.push_back({
                LLAMA_TOKEN_NULL,
                id != nullptr ? id : "",
                static_cast<int32_t>(mtmd_input_chunk_get_n_tokens(chunk)),
                mtmd_input_chunk_get_n_pos(chunk),
            });
        }
    }
    return prompt;
}

size_t cells(const std::vector<PromptItem> & items) {
    size_t total = 0;
    for (const auto & item : items) total += static_cast<size_t>(item.n_tokens);
    return total;
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass step = env->FindClass("ai/unalome/pocket/llamaengine/NativeStep");
    jclass exception = env->FindClass("ai/unalome/pocket/llamaengine/LlamaException");
    if (step == nullptr || exception == nullptr) return JNI_ERR;
    g_step_class = static_cast<jclass>(env->NewGlobalRef(step));
    g_exception_class = static_cast<jclass>(env->NewGlobalRef(exception));
    g_step_ctor = env->GetMethodID(g_step_class, "<init>", "(I[B[B)V");
    if (g_step_ctor == nullptr) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_init(JNIEnv * env, jclass, jstring native_library_dir) {
    llama_log_set(log_to_android, nullptr);
    mtmd_helper_log_set(log_to_android, nullptr);
    const char * dir = env->GetStringUTFChars(native_library_dir, nullptr);
    // The CPU backend ships as several libggml-cpu-*.so variants; ggml loads the best one for this CPU.
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(native_library_dir, dir);
    llama_backend_init();
    if (ggml_backend_dev_count() == 0) {
        throw_java(env, "This phone's processor is not supported by the on-device engine.");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_load(
        JNIEnv * env, jclass, jbyteArray path_bytes, jint context_tokens, jint threads, jobject listener) {
    const std::string path = to_string(env, path_bytes);
    auto engine = std::make_unique<Engine>();
    try {
        LoadProgress progress{env, listener, nullptr};
        if (listener != nullptr) {
            jclass listener_class = env->GetObjectClass(listener);
            progress.on_progress = env->GetMethodID(listener_class, "onProgress", "(F)Z");
            env->DeleteLocalRef(listener_class);
            if (progress.on_progress == nullptr) return 0;
        }

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;                     // CPU only for now
        // The file is read once, not mapped. The CPU backend repacks most weights into its own
        // faster layout; with a mapping, the mapped pages stay resident next to the repacked copy
        // and the model is held twice (measured with Qwen 3.5 2B on a 12 GB phone: 2.79 GB peak
        // PSS mapped, 1.95 GB read). Without repacking the mapping alone is smaller (1.54 GB), but
        // prompts are read about twice as slowly, which every image turn and every turn of a
        // recurrent model (which re-reads its whole prompt) pays for.
        model_params.load_mode = LLAMA_LOAD_MODE_NONE;
        model_params.use_extra_bufts = true;
        model_params.progress_callback = on_load_progress;
        model_params.progress_callback_user_data = &progress;

        engine->model = llama_model_load_from_file(path.c_str(), model_params);
        if (env->ExceptionCheck()) return 0;
        if (engine->model == nullptr) {
            throw_java(env, "The model file " + file_name(path) + " could not be read. It may be damaged: delete the model and download it again.");
            return 0;
        }
        engine->vocab = llama_model_get_vocab(engine->model);

        llama_context_params ctx_params = llama_context_default_params();
        const int trained = llama_model_n_ctx_train(engine->model);
        ctx_params.n_ctx = static_cast<uint32_t>(trained > 0 ? std::min<int>(context_tokens, trained) : context_tokens);
        ctx_params.n_batch = 512;
        ctx_params.n_ubatch = 512;
        ctx_params.n_seq_max = 1;
        ctx_params.n_threads = threads;
        ctx_params.n_threads_batch = threads;
        ctx_params.no_perf = true;
        ctx_params.abort_callback = [](void * data) { return static_cast<Engine *>(data)->abort.load(); };
        ctx_params.abort_callback_data = engine.get();
        engine->ctx = llama_init_from_model(engine->model, ctx_params);
        if (engine->ctx == nullptr) {
            throw_java(env, "There is not enough free memory to run this model. Close other apps, or choose a smaller model.");
            return 0;
        }
        engine->batch = llama_batch_ext_init(engine->ctx);
        engine->templates = common_chat_templates_init(engine->model, "");
        if (!engine->templates) {
            throw_java(env, "This model has no usable chat template.");
            return 0;
        }
        engine->media_marker = random_marker();
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::bad_alloc &) {
        throw_java(env, "There is not enough free memory to load this model. Close other apps, or choose a smaller model.");
    } catch (const std::exception & e) {
        throw_java(env, std::string("The model could not be loaded: ") + e.what());
    }
    return 0;
}

// Loads the image reader (the model's mmproj file) next to a loaded model. Safe to call again.
extern "C" JNIEXPORT void JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_loadProjector(
        JNIEnv * env, jclass, jlong handle, jbyteArray path_bytes, jint threads) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr || engine->mtmd != nullptr) return;
    const std::string path = to_string(env, path_bytes);
    try {
        mtmd_context_params params = mtmd_context_params_default();
        params.use_gpu = false;
        params.print_timings = false;
        params.n_threads = threads;
        params.media_marker = engine->media_marker.c_str();
        // No warm-up pass: it costs seconds on a phone, and the first image allocates the same buffers.
        params.warmup = false;
        // Models that read an image at its own resolution (Qwen 3.5) would spend ~770 tokens and
        // ~50 s of a phone's CPU on a 1024 × 768 photo; capped, it is a fraction of that and the
        // answers stay good. Fixed-size readers (Gemma 4 uses about 280) are not affected.
        params.image_max_tokens = MAX_IMAGE_TOKENS;
        params.cb_eval = image_eval_callback;
        params.cb_eval_user_data = engine;
        engine->mtmd = mtmd_init_from_file(path.c_str(), engine->model, params);
        if (engine->mtmd == nullptr) {
            throw_java(env, "The model's image reader (" + file_name(path) + ") could not be loaded. Text chat still works; download image support again if this repeats.");
            return;
        }
        if (!mtmd_support_vision(engine->mtmd)) {
            mtmd_free(engine->mtmd);
            engine->mtmd = nullptr;
            throw_java(env, "This model's projector file cannot read images.");
        }
    } catch (const std::bad_alloc &) {
        throw_java(env, "There is not enough free memory to read images with this model. Close other apps and try again, or ask without the image.");
    } catch (const std::exception & e) {
        throw_java(env, std::string("The model's image reader could not be loaded: ") + e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_contextSize(JNIEnv * env, jclass, jlong handle) {
    Engine * engine = engine_from(env, handle);
    return engine == nullptr ? 0 : static_cast<jint>(llama_n_ctx(engine->ctx));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_supportsThinking(JNIEnv * env, jclass, jlong handle) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr) return JNI_FALSE;
    try {
        return common_chat_templates_support_enable_thinking(engine->templates.get()) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_abort(JNIEnv *, jclass, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->abort.store(true);
}

// Called by Kotlin before a reply is dispatched, never by `start` itself: a Stop that arrives
// between the dispatch and `start` must still stop the prompt from being read.
extern "C" JNIEXPORT void JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_resetAbort(JNIEnv *, jclass, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->abort.store(false);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_countTokens(
        JNIEnv * env, jclass, jlong handle, jintArray roles, jobjectArray texts, jobjectArray image_sizes, jboolean thinking) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr) return 0;
    try {
        const jsize turns = env->GetArrayLength(roles);
        const auto sizes = read_sizes(env, image_sizes, turns);
        std::vector<size_t> counts;
        std::vector<BitmapPtr> placeholders;
        for (const auto & turn : sizes) {
            counts.push_back(turn.size());
            for (const auto & [width, height] : turn) placeholders.emplace_back(mtmd_bitmap_init(width, height, nullptr));
        }
        const auto inputs = build_inputs(env, roles, texts, counts, engine->media_marker, thinking == JNI_TRUE);
        const auto params = common_chat_templates_apply(engine->templates.get(), inputs);
        return static_cast<jint>(cells(tokenize(*engine, params.prompt.str(), placeholders).items));
    } catch (const std::exception & e) {
        throw_java(env, std::string("The conversation could not be prepared for the model: ") + e.what());
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_start(
        JNIEnv * env, jclass, jlong handle, jintArray roles, jobjectArray texts, jobjectArray image_paths,
        jboolean thinking, jfloat temperature, jint max_tokens, jint seed) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr) return 0;
    engine->gen.reset();
    engine->stats = {};
    const auto started = std::chrono::steady_clock::now();
    if (engine->abort.load()) return -1;
    try {
        const jsize turns = env->GetArrayLength(roles);
        const auto paths = read_images(env, image_paths, turns);
        std::vector<size_t> counts;
        std::vector<BitmapPtr> bitmaps;
        for (const auto & turn : paths) {
            counts.push_back(turn.size());
            for (const auto & path : turn) {
                if (engine->mtmd == nullptr) {
                    throw_java(env, "This model's image reader is not loaded.");
                    return 0;
                }
                BitmapPtr bitmap(mtmd_helper_bitmap_init_from_file(engine->mtmd, path.c_str(), false, mtmd_helper_init_opt_default()).bitmap);
                if (!bitmap) {
                    throw_java(env, "An attached image (" + file_name(path) + ") could not be read.");
                    return 0;
                }
                bitmaps.push_back(std::move(bitmap));
            }
        }
        const auto inputs = build_inputs(env, roles, texts, counts, engine->media_marker, thinking == JNI_TRUE);
        auto chat = std::make_unique<common_chat_session>(engine->templates.get(), engine->vocab, inputs);
        Prompt prompt = tokenize(*engine, chat->prompt().str(), bitmaps);
        bitmaps.clear(); // the chunks hold the preprocessed images
        const size_t n_ctx = llama_n_ctx(engine->ctx);
        if (prompt.items.empty()) {
            throw_java(env, "The message is empty.");
            return 0;
        }
        if (cells(prompt.items) >= n_ctx) {
            throw_java(env, "This conversation is too long for the model's memory. Start a new chat.");
            return 0;
        }
        if (prompt.items.back().token == LLAMA_TOKEN_NULL) {
            throw_java(env, "The conversation could not be prepared for the model: it ends with an image.");
            return 0;
        }

        // Reuse the part of the previous prompt that is still in memory, images included (they are
        // matched by content). At least one token is decoded again so there are logits to sample
        // from. Models with recurrent layers cannot drop the tail of their state; for them the
        // memory is cleared and everything decoded.
        llama_memory_t memory = llama_get_memory(engine->ctx);
        size_t keep = 0;
        while (keep < engine->cached.size() && keep < prompt.items.size() && engine->cached[keep] == prompt.items[keep]) ++keep;
        if (keep == prompt.items.size()) --keep;
        llama_pos keep_pos = 0;
        for (size_t i = 0; i < keep; ++i) keep_pos += prompt.items[i].n_pos;
        if (keep == 0 || !llama_memory_seq_rm(memory, 0, keep_pos, -1)) {
            engine->forget_memory();
            keep = 0;
        } else {
            engine->cached.resize(keep);
            engine->n_past = keep_pos;
            engine->n_used = cells(engine->cached);
        }
        engine->stats.prompt_tokens = static_cast<int64_t>(cells(prompt.items));
        engine->stats.reused_tokens = static_cast<int64_t>(engine->n_used);

        auto fail = [&](int code) -> jint {
            engine->forget_memory();
            if (code == 2 || engine->abort.load()) return -1; // aborted: the caller is being cancelled
            throw_java(env, code == 1
                    ? "This conversation is too long for the model's memory. Start a new chat."
                    : "The model failed while reading the conversation (code " + std::to_string(code) + ").");
            return 0;
        };

        if (!prompt.chunks) {
            std::vector<llama_token> tokens;
            for (size_t i = keep; i < prompt.items.size(); ++i) tokens.push_back(prompt.items[i].token);
            const int code = decode(*engine, tokens.data(), tokens.size(), engine->n_past);
            if (code != 0) return fail(code);
            for (size_t i = keep; i < prompt.items.size(); ++i) engine->remember(prompt.items[i]);
        } else {
            const size_t n_chunks = mtmd_input_chunks_size(prompt.chunks.get());
            const int32_t n_batch = static_cast<int32_t>(llama_n_batch(engine->ctx));
            size_t index = 0; // the prompt item where the current chunk starts
            for (size_t c = 0; c < n_chunks; ++c) {
                const mtmd_input_chunk * chunk = mtmd_input_chunks_get(prompt.chunks.get(), c);
                const bool last = c + 1 == n_chunks;
                if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                    size_t n = 0;
                    const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &n);
                    if (index + n > keep) {
                        const size_t from = keep > index ? keep - index : 0;
                        const int code = decode(*engine, tokens + from, n - from, engine->n_past, last);
                        if (code != 0) return fail(code);
                        for (size_t j = index + from; j < index + n; ++j) engine->remember(prompt.items[j]);
                    }
                    index += n;
                } else {
                    if (index >= keep) {
                        if (engine->abort.load()) return fail(2);
                        const auto image_started = std::chrono::steady_clock::now();
                        llama_pos next = engine->n_past;
                        const int32_t code = mtmd_helper_eval_chunk_single(engine->mtmd, engine->ctx, chunk, engine->n_past, 0, n_batch, false, &next);
                        engine->stats.image_millis += millis_since(image_started);
                        engine->stats.images++;
                        // An encode stopped by the abort callback returns early with partial results.
                        if (code != 0 || engine->abort.load()) return fail(code != 0 ? code : 2);
                        engine->remember(prompt.items[index]);
                        engine->n_past = next;
                    }
                    index += 1;
                }
            }
        }
        engine->stats.total_millis = millis_since(started);

        engine->gen.stops = chat->additional_stops();
        engine->gen.chat = std::move(chat);
        engine->gen.sampler = make_sampler(engine->vocab, temperature, static_cast<uint32_t>(seed));
        engine->gen.max_tokens = max_tokens;
        return static_cast<jint>(engine->stats.prompt_tokens);
    } catch (const std::bad_alloc &) {
        engine->gen.reset();
        engine->forget_memory();
        throw_java(env, "There is not enough free memory to answer. Close other apps, or choose a smaller model.");
    } catch (const std::exception & e) {
        engine->gen.reset();
        throw_java(env, std::string("The conversation could not be prepared for the model: ") + e.what());
    }
    return 0;
}

// [prompt tokens, tokens reused from memory, images read, milliseconds reading images,
// milliseconds for the whole prompt] of the latest `start`.
extern "C" JNIEXPORT jlongArray JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_lastPromptStats(JNIEnv * env, jclass, jlong handle) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr) return nullptr;
    const jlong values[] = {
        engine->stats.prompt_tokens, engine->stats.reused_tokens, engine->stats.images,
        engine->stats.image_millis, engine->stats.total_millis,
    };
    jlongArray out = env->NewLongArray(5);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_unalome_pocket_llamaengine_LlamaNative_next(JNIEnv * env, jclass, jlong handle) {
    Engine * engine = engine_from(env, handle);
    if (engine == nullptr) return nullptr;
    Generation & gen = engine->gen;
    if (!gen.chat || gen.sampler == nullptr) {
        throw_java(env, "No reply is being written.");
        return nullptr;
    }
    try {
        if (engine->abort.load()) return finish(env, *engine, STEP_ABORTED);
        if (gen.generated >= gen.max_tokens || engine->n_used >= llama_n_ctx(engine->ctx)) {
            return finish(env, *engine, STEP_LENGTH_LIMIT);
        }

        const llama_token token = llama_sampler_sample(gen.sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, token)) {
            return finish(env, *engine, STEP_DONE);
        }

        // Special tokens are rendered: reasoning delimiters such as Gemma's <|channel> are special
        // tokens, and the chat parser needs to see them.
        const std::string piece = common_token_to_piece(engine->vocab, token, true);

        // A stop string from the template ends the reply like an end-of-generation token; the
        // text before it is kept, the stop string itself is not shown.
        if (!gen.stops.empty()) {
            const size_t pending_starts_at = gen.raw.size() - std::min(gen.raw.size(), gen.pending.size());
            gen.raw += piece;
            for (const auto & stop : gen.stops) {
                if (stop.empty()) continue;
                const size_t search_from = gen.raw.size() > piece.size() + stop.size() ? gen.raw.size() - piece.size() - stop.size() : 0;
                const size_t found = gen.raw.find(stop, search_from);
                if (found == std::string::npos) continue;
                std::string text = gen.pending + piece;
                gen.pending = found > pending_starts_at ? text.substr(0, found - pending_starts_at) : std::string();
                if (gen.pending_token == LLAMA_TOKEN_NULL) gen.pending_token = token;
                return finish(env, *engine, STEP_DONE);
            }
        }

        const int code = decode(*engine, &token, 1, engine->n_past);
        if (code == 2) return finish(env, *engine, STEP_ABORTED);
        if (code == 1) return finish(env, *engine, STEP_LENGTH_LIMIT);
        if (code != 0) {
            gen.reset();
            throw_java(env, "The model failed while writing (code " + std::to_string(code) + ").");
            return nullptr;
        }
        engine->remember({token});
        gen.generated++;

        if (gen.pending.empty()) gen.pending_token = token;
        gen.pending += piece;
        return parse_and_step(env, *engine, STEP_CONTINUE, false);
    } catch (const std::bad_alloc &) {
        gen.reset();
        throw_java(env, "There is not enough free memory to continue the reply.");
    } catch (const std::exception & e) {
        gen.reset();
        throw_java(env, std::string("The reply stopped because of an error: ") + e.what());
    }
    return nullptr;
}
