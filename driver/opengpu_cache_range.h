/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_CACHE_RANGE_H
#define OPENGPU_CACHE_RANGE_H
#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_cache_u32;
typedef u64 opengpu_cache_u64;
#else
#include <stdint.h>
typedef uint32_t opengpu_cache_u32;
typedef uint64_t opengpu_cache_u64;
#endif

struct opengpu_cache_range {
    opengpu_cache_u32 address;
    opengpu_cache_u32 bytes;
};

/* Return 1 for a representable nonempty invalidation, 0 for an empty range,
 * and -1 if the 32-bit hardware address or byte count cannot represent it.
 * Check the unrounded endpoint first, so aligning it cannot wrap u64. */
static inline int opengpu_cache_invalidate_range(
    opengpu_cache_u64 address, opengpu_cache_u64 bytes,
    struct opengpu_cache_range *out)
{
    const opengpu_cache_u64 limit = 1ull << 32;
    opengpu_cache_u64 start, end, span;

    if (!out)
        return -1;
    if (!bytes) {
        out->address = 0;
        out->bytes = 0;
        return 0;
    }
    if (address >= limit || bytes > limit - address)
        return -1;
    start = address & ~63ull;
    end = (address + bytes + 63ull) & ~63ull;
    span = end - start;
    if (span >= limit)
        return -1;
    out->address = (opengpu_cache_u32)start;
    out->bytes = (opengpu_cache_u32)span;
    return 1;
}
#endif
