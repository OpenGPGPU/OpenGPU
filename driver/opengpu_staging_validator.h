/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_STAGING_VALIDATOR_H
#define OPENGPU_STAGING_VALIDATOR_H
#ifdef __KERNEL__
#include <linux/types.h>
typedef u64 opengpu_staging_u64;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint64_t opengpu_staging_u64;
#endif

struct opengpu_staging_range {
    opengpu_staging_u64 address;
    opengpu_staging_u64 bytes;
};

/* Transient scratch cannot alias any resource that can observe the draw.
 * Compare DMA spans, including distinct imports with overlapping DMA addresses.
 * Optional empty ranges are ignored; wrapped endpoints are rejected. */
static inline bool opengpu_staging_disjoint(
    const struct opengpu_staging_range *scratch,
    const struct opengpu_staging_range *others, unsigned count)
{
    opengpu_staging_u64 maximum = ~(opengpu_staging_u64)0;
    opengpu_staging_u64 end;
    unsigned i;

    if (!scratch || !scratch->bytes ||
        scratch->bytes > maximum - scratch->address || (count && !others))
        return false;
    end = scratch->address + scratch->bytes;
    for (i = 0; i < count; i++) {
        opengpu_staging_u64 other_end;

        if (!others[i].bytes)
            continue;
        if (others[i].bytes > maximum - others[i].address)
            return false;
        other_end = others[i].address + others[i].bytes;
        if (scratch->address < other_end && others[i].address < end)
            return false;
    }
    return true;
}

/* All nonempty DMA ranges must be pairwise disjoint and non-wrapping. The
 * caller must separately establish physical provenance when distinct IOVAs
 * may refer to the same imported pages. */
static inline bool opengpu_dma_ranges_pairwise_disjoint(
    const struct opengpu_staging_range *ranges, unsigned count)
{
    unsigned i;

    if (count && !ranges)
        return false;
    for (i = 0; i < count; i++) {
        if (ranges[i].bytes &&
            !opengpu_staging_disjoint(&ranges[i], ranges + i + 1,
                                      count - i - 1))
            return false;
    }
    return true;
}
#endif
