// JNI face of MNN-LLM for LocalAiNotif.
// Links against libMNN.so built with -DMNN_BUILD_LLM -DMNN_SUPPORT_TRANSFORMER_FUSE.
#include <jni.h>
#include <string>
#include <sstream>
#include "llm/llm.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (p == nullptr) return {};
    std::string r(p);
    env->ReleaseStringUTFChars(s, p);
    return r;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localai_notif_LlmEngine_nativeCreate(JNIEnv* env, jclass, jstring configPath) {
    std::string path = toStd(env, configPath);
    if (path.empty()) return 0;
    Llm* llm = nullptr;
    try {
        llm = Llm::createLLM(path);
        if (llm != nullptr && !llm->load()) {
            delete llm;
            llm = nullptr;
        }
    } catch (...) {
        llm = nullptr;
    }
    return reinterpret_cast<jlong>(llm);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localai_notif_LlmEngine_nativeGenerate(JNIEnv* env, jclass, jlong ptr,
                                                jstring systemPrompt, jstring userPrompt,
                                                jint maxNewTokens) {
    Llm* llm = reinterpret_cast<Llm*>(ptr);
    if (llm == nullptr) return env->NewStringUTF("");
    std::ostringstream oss;
    try {
        ChatMessages msgs;
        std::string sys = toStd(env, systemPrompt);
        if (!sys.empty()) msgs.emplace_back("system", sys);
        msgs.emplace_back("user", toStd(env, userPrompt));
        llm->response(msgs, &oss, nullptr, maxNewTokens > 0 ? maxNewTokens : -1);
    } catch (...) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(oss.str().c_str());
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
