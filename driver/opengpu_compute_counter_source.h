/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_COUNTER_SOURCE_H
#define OPENGPU_COMPUTE_COUNTER_SOURCE_H
#include "opengpu_kernarg_va.h"

/* A job-only data window, independent of the four permanent storage windows.
 * This borrows the original local GEM pages; it never owns a copied buffer. */
#define OPENGPU_COMPUTE_COUNTER_SOURCE_SLOT 7u
struct opengpu_counter_source_plan {
    struct opengpu_kernarg_va_plan mapping;
    opengpu_kernarg_u64 data_dma, allocation_base, allocation_limit;
    opengpu_kernarg_u32 bytes;
};

static inline int opengpu_compute_counter_source_plan(
    opengpu_kernarg_u64 dma, opengpu_kernarg_u64 size,
    opengpu_kernarg_u64 offset, opengpu_kernarg_u32 count,
    struct opengpu_counter_source_plan *out)
{
    struct opengpu_counter_source_plan p;
    const opengpu_kernarg_u64 address_limit = 1ull << 32;
    opengpu_kernarg_u64 bytes = (opengpu_kernarg_u64)count * 4;
    int ret;

    if (!out || !count || count > 8 || (offset & 3u) ||
        !size || (dma & 4095u) || (size & 4095u))
        return -22; /* -EINVAL: local GEM allocation must cover whole pages. */
    if (!dma || dma >= address_limit || size > address_limit - dma)
        return -34; /* -ERANGE */
    if (offset > size || bytes > size - offset)
        return -22;
    p.data_dma = dma + offset;
    p.bytes = (opengpu_kernarg_u32)bytes;
    ret = opengpu_command_va_plan(p.data_dma, bytes,
            OPENGPU_COMPUTE_COUNTER_SOURCE_SLOT, 4096, &p.mapping);
    if (ret)
        return ret;
    if (p.mapping.pa_page < dma || p.mapping.pa_page - dma > size ||
        p.mapping.span > size - (p.mapping.pa_page - dma))
        return -34;
    p.allocation_base = p.mapping.va & ~63ull;
    p.allocation_limit = (p.mapping.va + bytes + 63ull) & ~63ull;
    *out = p;
    return 0;
}
#endif
