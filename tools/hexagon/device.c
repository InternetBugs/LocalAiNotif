#include <jni.h>
#include "dsp_capabilities_utils.h"

JNIEXPORT jint JNICALL Java_io_github_internetbugs_localainotif_LlmEngine_nativeHexagonArch(JNIEnv *env, jclass type) {
    uint32_t arch = 0;
    if (get_hex_arch_ver(CDSP_DOMAIN_ID, &arch) != AEE_SUCCESS) return 0;
    return arch & 0xff;
}
