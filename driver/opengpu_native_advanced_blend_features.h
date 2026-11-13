/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_ADVANCED_BLEND_FEATURES_H
#define OPENGPU_NATIVE_ADVANCED_BLEND_FEATURES_H
#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_native_advanced_blend_abi.h"

/* GCR1 is guaranteed only by ABI >= 2. Its presence bit guarantees GAB1;
 * no unadvertised optional MMIO address is probed. Every GAB1 field is exact:
 * unknown versions, equation/format bits, features and reserved values fail
 * device initialization. This is native transport, not GL/KHR advertisement. */
static inline bool opengpu_read_native_advanced_blend_support(
    u32 id, u32 resource_flags, u32 capabilities, unsigned long long mmio_bytes,
    void *context, u32 (*read_reg)(void *, u32), bool *transport)
{
    const u32 expected[] = {
        OPENGPU_NATIVE_ADVANCED_BLEND_MAGIC,
        OPENGPU_NATIVE_ADVANCED_BLEND_WORDS,
        OPENGPU_NATIVE_ADVANCED_BLEND_PAYLOAD_VERSIONS,
        OPENGPU_NATIVE_ADVANCED_BLEND_EQUATIONS,
        OPENGPU_NATIVE_ADVANCED_BLEND_FORMATS_LOW,
        OPENGPU_NATIVE_ADVANCED_BLEND_FORMATS_HIGH,
        OPENGPU_NATIVE_ADVANCED_BLEND_FEATURES,
        0u
    };
    const u32 required = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    unsigned int i;

    if (!transport)
        return false;
    *transport = false;
    if (!(resource_flags & OPENGPU_COMPUTE_RESOURCE_NATIVE_ADVANCED_BLEND_V1))
        return true;
    if ((id & 0xffffu) < GPU_ABI_COMPUTE_RESOURCE_VERSION ||
        (capabilities & required) != required || !read_reg ||
        mmio_bytes < OPENGPU_NATIVE_ADVANCED_BLEND_REG_RESERVED + 4u)
        return false;
    for (i = 0; i < sizeof(expected) / sizeof(expected[0]); ++i)
        if (read_reg(context, OPENGPU_NATIVE_ADVANCED_BLEND_CAPABILITIES + 4u * i) != expected[i])
            return false;
    *transport = true;
    return true;
}
#endif
