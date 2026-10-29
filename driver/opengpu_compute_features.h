/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_FEATURES_H
#define OPENGPU_COMPUTE_FEATURES_H
#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"

/* ABI v1 does not guarantee that GCR1 is mapped: do not even probe its magic.
 * ABI v2 retains the legacy registers and guarantees this fixed-length record.
 * A malformed advertised record fails device initialization, not open access. */
static inline bool opengpu_read_compute_features(
    u32 id, unsigned long long mmio_bytes, void *context,
    u32 (*read_reg)(void *, u32), u32 *flags)
{
    *flags = 0;
    if ((id & 0xffffu) < GPU_ABI_COMPUTE_RESOURCE_VERSION)
        return true;
    if (mmio_bytes < OPENGPU_COMPUTE_RESOURCE_REG_L2_BANKS + 4u ||
        read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_MAGIC) !=
            OPENGPU_COMPUTE_RESOURCE_MAGIC ||
        read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_WORDS) !=
            OPENGPU_COMPUTE_RESOURCE_WORDS)
        return false;
    *flags = read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_FLAGS);
    return true;
}
#endif
