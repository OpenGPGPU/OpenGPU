/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_INDIRECT_SOURCE_H
#define OPENGPU_COMPUTE_INDIRECT_SOURCE_H
#include "opengpu_kernarg_va.h"

#define OPENGPU_COMPUTE_INDIRECT_SNAPSHOT_SLOT 8u
#define OPENGPU_COMPUTE_INDIRECT_COUNT_BYTES 12u
struct opengpu_indirect_source_plan {
    opengpu_kernarg_u32 offset, bytes;
};

/* Local GEMs cover full pages. Preserve the source page offset in a zeroed
 * one/two-page private allocation, copying only the twelve count bytes. */
static inline int opengpu_compute_indirect_source_plan(
    opengpu_kernarg_u64 dma, opengpu_kernarg_u64 size,
    opengpu_kernarg_u64 offset, struct opengpu_indirect_source_plan *out)
{
    struct opengpu_indirect_source_plan p;
    if (!out || !size || ((dma | size) & 4095u) || (offset & 3u))
        return -22;
    if (!dma || dma >= (1ull << 32) || size > (1ull << 32) - dma)
        return -34;
    if (offset > size || OPENGPU_COMPUTE_INDIRECT_COUNT_BYTES > size - offset)
        return -22;
    p.offset = (opengpu_kernarg_u32)(offset & 4095u);
    p.bytes = (p.offset + OPENGPU_COMPUTE_INDIRECT_COUNT_BYTES + 4095u) & ~4095u;
    *out = p;
    return 0;
}

/* Only call this on the unique private copy, never on the mutable source for
 * a separate admission decision. No sign extension or count truncation. */
static inline int opengpu_compute_indirect_counts(
    const void *copy, const opengpu_kernarg_u32 max_groups[3],
    opengpu_kernarg_u32 groups[3], opengpu_kernarg_u64 *total)
{
    const unsigned char *b = copy;
    opengpu_kernarg_u32 g[3];
    opengpu_kernarg_u64 product = 1;
    unsigned i;
    if (!copy || !max_groups || !groups || !total)
        return -22;
    for (i = 0; i < 3; ++i) {
        if (!max_groups[i]) return -22;
        g[i] = (opengpu_kernarg_u32)b[4*i] |
            (opengpu_kernarg_u32)b[4*i+1] << 8 |
            (opengpu_kernarg_u32)b[4*i+2] << 16 |
            (opengpu_kernarg_u32)b[4*i+3] << 24;
    }
    if (!g[0] || !g[1] || !g[2]) {
        product = 0;
    } else for (i = 0; i < 3; ++i) {
        if (g[i] > max_groups[i] || product > ~(opengpu_kernarg_u64)0 / g[i])
            return -34;
        product *= g[i];
    }
    for (i = 0; i < 3; ++i) groups[i] = g[i];
    *total = product;
    return 0;
}
#endif
