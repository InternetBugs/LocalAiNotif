// JNI face of MNN-LLM for LocalAiNotif.
// Links against libMNN.so built with -DMNN_BUILD_LLM -DMNN_SUPPORT_TRANSFORMER_FUSE.
#include <jni.h>
#include <cctype>
#include <limits>
#include <memory>
#include <ostream>
#include <streambuf>
#include <string>
#include <MNN/expr/ExecutorScope.hpp>
#include "llm/llm.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

namespace {

std::string readBytes(JNIEnv* env, jbyteArray bytes) {
    jsize length = env->GetArrayLength(bytes);
    std::string result(length, '\0');
    if (length) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

void trimIncompleteUtf8(std::string& s) {
    if (s.empty()) return;
    size_t n = s.size();
    size_t lead = n;
    while (lead > 0 && (n - lead) < 4 &&
           (static_cast<unsigned char>(s[lead - 1]) & 0xC0u) == 0x80u) {
        --lead;
    }
    if (lead == 0) return;
    unsigned char b = static_cast<unsigned char>(s[lead - 1]);
    size_t expected = 0;
    if (b < 0x80u) {
        expected = 1;
    } else if ((b & 0xE0u) == 0xC0u) {
        expected = 2;
    } else if ((b & 0xF0u) == 0xE0u) {
        expected = 3;
    } else if ((b & 0xF8u) == 0xF0u) {
        expected = 4;
    } else {
        s.resize(lead - 1);
        return;
    }
    size_t actual = n - (lead - 1);
    if (actual < expected) {
        s.resize(lead - 1);
    }
}

/**
 * Captures streamed model output directly into a std::string and, when expectedJsonItems > 0,
 * requests immediate decode termination via LlmStatus::USER_CANCEL as soon as the root JSON
 * object closes or the model attempts to emit item (expectedJsonItems + 1) under greedy decoding.
 */
class BoundedJsonStreamBuf final : public std::streambuf {
public:
    BoundedJsonStreamBuf(LlmContext* ctx, int expectedJsonItems)
        : ctx_(ctx), expectedJsonItems_(expectedJsonItems) {}

    bool stoppedEarly() const { return stoppedEarly_; }

    std::string takeOutput() { return std::move(output_); }

protected:
    int_type overflow(int_type ch) override {
        if (ch != traits_type::eof()) {
            char c = static_cast<char>(ch);
            output_.push_back(c);
            if (expectedJsonItems_ > 0 && !stopRequested_) {
                scan();
            }
        }
        return ch;
    }

    std::streamsize xsputn(const char* s, std::streamsize count) override {
        if (count > 0) {
            output_.append(s, static_cast<size_t>(count));
            if (expectedJsonItems_ > 0 && !stopRequested_) {
                scan();
            }
        }
        return count;
    }

    int sync() override {
        if (stopRequested_ && ctx_ != nullptr && ctx_->status == LlmStatus::RUNNING) {
            ctx_->status = LlmStatus::USER_CANCEL;
            stoppedEarly_ = true;
        }
        return 0;
    }

private:
    enum class Phase {
        BEFORE_JSON,
        IN_THINK,
        SCAN_JSON
    };

    void scan() {
        while (scanPos_ < output_.size() && !stopRequested_) {
            if (phase_ == Phase::BEFORE_JSON) {
                while (scanPos_ < output_.size()) {
                    unsigned char ws = static_cast<unsigned char>(output_[scanPos_]);
                    if (ws != ' ' && ws != '\t' && ws != '\r' && ws != '\n') break;
                    ++scanPos_;
                }
                if (scanPos_ >= output_.size()) return;
                constexpr const char* kThinkPrefix = "<think";
                constexpr size_t kThinkLen = 6;
                size_t rem = output_.size() - scanPos_;
                size_t cmpLen = rem < kThinkLen ? rem : kThinkLen;
                if (output_.compare(scanPos_, cmpLen, kThinkPrefix, cmpLen) == 0) {
                    if (rem < kThinkLen) return;
                    phase_ = Phase::IN_THINK;
                    scanPos_ += kThinkLen;
                    continue;
                }
                phase_ = Phase::SCAN_JSON;
            }
            if (phase_ == Phase::IN_THINK) {
                constexpr const char* kThinkClose = "</think>";
                constexpr size_t kCloseLen = 8;
                size_t searchFrom = scanPos_ > kCloseLen ? scanPos_ - kCloseLen : 0;
                size_t closePos = output_.find(kThinkClose, searchFrom);
                if (closePos == std::string::npos) {
                    scanPos_ = output_.size();
                    return;
                }
                scanPos_ = closePos + kCloseLen;
                phase_ = Phase::SCAN_JSON;
                continue;
            }
            unsigned char ch = static_cast<unsigned char>(output_[scanPos_++]);
            if (!seenRootBrace_) {
                if (ch == '{') {
                    seenRootBrace_ = true;
                    braceDepth_ = 1;
                }
                continue;
            }
            if (inString_) {
                if (escaped_) {
                    escaped_ = false;
                } else if (ch == '\\') {
                    escaped_ = true;
                } else if (ch == '"') {
                    inString_ = false;
                }
                continue;
            }
            if (ch == '"') {
                inString_ = true;
            } else if (ch == '{') {
                if (braceDepth_ == 1 && completedItems_ >= expectedJsonItems_) {
                    stopRequested_ = true;
                    break;
                }
                ++braceDepth_;
            } else if (ch == '}') {
                if (braceDepth_ == 2) {
                    ++completedItems_;
                    braceDepth_ = 1;
                } else if (braceDepth_ == 1) {
                    braceDepth_ = 0;
                    stopRequested_ = true;
                    break;
                } else if (braceDepth_ > 2) {
                    --braceDepth_;
                }
            } else if (ch == ',' && braceDepth_ == 1 && completedItems_ >= expectedJsonItems_) {
                stopRequested_ = true;
                break;
            }
        }
    }

    LlmContext* const ctx_;
    const int expectedJsonItems_;
    std::string output_;
    size_t scanPos_ = 0;
    Phase phase_ = Phase::BEFORE_JSON;
    bool seenRootBrace_ = false;
    bool inString_ = false;
    bool escaped_ = false;
    int braceDepth_ = 0;
    int completedItems_ = 0;
    bool stopRequested_ = false;
    bool stoppedEarly_ = false;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localai_notif_LlmEngine_nativeCreate(JNIEnv* env, jclass, jbyteArray configPath,
                                              jbyteArray backend, jbyteArray optionsJson) {
    try {
        std::string path = readBytes(env, configPath);
        if (env->ExceptionCheck() || path.empty()) return 0;
        std::unique_ptr<Llm> llm(Llm::createLLM(path));
        if (!llm) return 0;
        std::string name = readBytes(env, backend);
        std::string settings = readBytes(env, optionsJson);
        if (env->ExceptionCheck()) return 0;
        MNNForwardType type = MNN_FORWARD_CPU;
        if (name == "hexagon") type = MNN_FORWARD_HEXAGON;
        if (name == "vulkan") type = MNN_FORWARD_VULKAN;
        if (type != MNN_FORWARD_CPU) {
            MNN::Express::ExecutorScope scope(llm->getExecutor());
            MNN::ScheduleConfig schedule;
            MNN::BackendConfig options;
            options.precision = MNN::BackendConfig::Precision_Low;
            schedule.type = type;
            schedule.numThread = 1;
            schedule.backendConfig = &options;
            using RuntimeManager = MNN::Express::Executor::RuntimeManager;
            std::unique_ptr<RuntimeManager> probe(RuntimeManager::createRuntimeManager(schedule));
            auto runtimes = MNN::Express::Executor::getRuntime();
            int selected = -1;
            if (!probe || !probe->getInfo(MNN::Interpreter::BACKENDS, &selected) ||
                selected != type || !runtimes.first.count(type)) return 0;
        }
        llm->set_config(settings);
        // Greedy decoding: MNN defaults to mixed sampling at temperature 1.0, which makes
        // summaries drift from the source text and classification JSON unstable.
        // Must be set before load(), where the sampler is created.
        llm->set_config(R"({"is_visual":false,"sampler_type":"greedy","jinja":{"context":{"enable_thinking":false}}})");
        if (!llm->load()) return 0;
        return reinterpret_cast<jlong>(llm.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_localai_notif_LlmEngine_nativeGenerate(JNIEnv* env, jclass, jlong ptr,
                                                jbyteArray systemPrompt, jbyteArray userPrompt,
                                                jint maxNewTokens, jint expectedJsonItems) {
    Llm* llm = reinterpret_cast<Llm*>(ptr);
    if (llm == nullptr) return nullptr;
    try {
        ChatMessages msgs;
        std::string sys = readBytes(env, systemPrompt);
        if (env->ExceptionCheck()) return nullptr;
        if (!sys.empty()) msgs.emplace_back("system", sys);
        msgs.emplace_back("user", readBytes(env, userPrompt));
        if (env->ExceptionCheck()) return nullptr;
        auto* ctx = const_cast<LlmContext*>(llm->getContext());
        if (ctx != nullptr && (ctx->status == LlmStatus::USER_CANCEL ||
                               ctx->status == LlmStatus::TIMEOUT)) {
            ctx->status = LlmStatus::RUNNING;
        }
        // Every call is a stateless one-shot request: drop KV/history/prompt-cache left by the
        // previous (possibly interrupted) call so notifications never leak into each other.
        llm->reset();
        BoundedJsonStreamBuf buf(ctx, expectedJsonItems > 0 ? static_cast<int>(expectedJsonItems) : 0);
        std::ostream os(&buf);
        llm->response(msgs, &os, nullptr, maxNewTokens > 0 ? maxNewTokens : -1);
        LlmStatus status = ctx != nullptr ? ctx->status : LlmStatus::INTERNAL_ERROR;
        if (buf.stoppedEarly() && status == LlmStatus::USER_CANCEL) {
            ctx->status = LlmStatus::NORMAL_FINISHED;
            status = LlmStatus::NORMAL_FINISHED;
        }
        if (status == LlmStatus::INTERNAL_ERROR || status == LlmStatus::TIMEOUT) return nullptr;
        std::string output = buf.takeOutput();
        trimIncompleteUtf8(output);
        if (output.size() > std::numeric_limits<jsize>::max()) return nullptr;
        jsize length = static_cast<jsize>(output.size());
        jbyteArray bytes = env->NewByteArray(length);
        if (bytes && length) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(output.data()));
        return bytes;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_notif_LlmEngine_nativeDestroy(JNIEnv*, jclass, jlong ptr) {
    Llm* llm = reinterpret_cast<Llm*>(ptr);
    if (llm != nullptr) {
        try {
            delete llm;
        } catch (...) {
        }
    }
}
