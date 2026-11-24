// JNI face of MNN-LLM for LocalAiNotif.
// Links against libMNN.so built with -DMNN_BUILD_LLM -DMNN_SUPPORT_TRANSFORMER_FUSE.
#include <jni.h>
#include <limits>
#include <memory>
#include <string>
#include <sstream>
#include <MNN/expr/ExecutorScope.hpp>
#include "llm/llm.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;

namespace {

std::string readBytes(JNIEnv* env, jbyteArray bytes) {
    jsize length = env->GetArrayLength(bytes);
    std::string result(length, '\0');
    if (length) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

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
                                                jint maxNewTokens) {
    Llm* llm = reinterpret_cast<Llm*>(ptr);
    if (llm == nullptr) return nullptr;
    try {
        std::ostringstream oss;
        ChatMessages msgs;
        std::string sys = readBytes(env, systemPrompt);
        if (env->ExceptionCheck()) return nullptr;
        if (!sys.empty()) msgs.emplace_back("system", sys);
        msgs.emplace_back("user", readBytes(env, userPrompt));
        if (env->ExceptionCheck()) return nullptr;
        // Every call is a stateless one-shot request: drop KV/history/prompt-cache left by the
        // previous (possibly interrupted) call so notifications never leak into each other.
        llm->reset();
        llm->response(msgs, &oss, nullptr, maxNewTokens > 0 ? maxNewTokens : -1);
        auto status = llm->getContext()->status;
        if (status == MNN::Transformer::LlmStatus::INTERNAL_ERROR ||
            status == MNN::Transformer::LlmStatus::TIMEOUT) return nullptr;
        std::string output = oss.str();
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
