#pragma once
#include <AEEStdErr.h>
#include <remote.h>

static inline domain *get_domain(int id) {
    static domain cdsp = {CDSP_DOMAIN_ID, CDSP_DOMAIN};
    return id == CDSP_DOMAIN_ID ? &cdsp : NULL;
}

static inline int get_hex_arch_ver(int domain_id, uint32_t *arch) {
    fastrpc_capability info = {.domain = domain_id, .attribute_ID = ARCH_VER};
    int result = remote_handle_control(DSPRPC_GET_DSP_INFO, &info, sizeof(info));
    if (result == AEE_SUCCESS) *arch = info.capability;
    return result;
}
