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

/* The current shader validator bounds workgroup-wide address ranges with an
 * eight-bit batch capacity. This is a driver limit, not a hardware capacity. */
#define OPENGPU_COMPUTE_VALIDATOR_MAX_LOCAL_ITEMS 255u
#define OPENGPU_COMPUTE_LEGACY_MAX_LOCAL_ITEMS 32u

struct opengpu_compute_local_limits {
    u32 items;
    u32 axis;
    bool discovered;
};

/* Only ABI v2 guarantees GCR1. Check the quantities as a coherent profile
 * before using them; the checked-product flag is needed for safe dispatch. */
static inline bool opengpu_read_compute_local_limits(
    u32 id, unsigned long long mmio_bytes, u32 flags, void *context,
    u32 (*read_reg)(void *, u32), struct opengpu_compute_local_limits *limits)
{
    u32 lanes, warps, items, axis;
    unsigned long long product;

    if (!limits)
        return false;
    if ((id & 0xffffu) < GPU_ABI_COMPUTE_RESOURCE_VERSION) {
        limits->items = OPENGPU_COMPUTE_LEGACY_MAX_LOCAL_ITEMS;
        limits->axis = OPENGPU_COMPUTE_LEGACY_MAX_LOCAL_ITEMS;
        limits->discovered = false;
        return true;
    }
    if (!read_reg || mmio_bytes < OPENGPU_COMPUTE_RESOURCE_REG_L2_BANKS + 4u ||
        !(flags & OPENGPU_COMPUTE_RESOURCE_CHECKED_LOCAL_PRODUCT))
        return false;
    lanes = read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_LANES);
    warps = read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_WARPS);
    items = read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_ITEMS);
    axis = read_reg(context, OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_AXIS);
    product = (unsigned long long)lanes * warps;
    if (!lanes || !warps || !items || product != items ||
        axis != (items < 65535u ? items : 65535u))
        return false;
    limits->items = items < OPENGPU_COMPUTE_VALIDATOR_MAX_LOCAL_ITEMS ?
        items : OPENGPU_COMPUTE_VALIDATOR_MAX_LOCAL_ITEMS;
    limits->axis = axis < limits->items ? axis : limits->items;
    limits->discovered = true;
    return true;
}

/* All three axes are checked before multiplication, keeping the product in
 * 48 bits even for a malformed caller or an internal hardware submission. */
static inline bool opengpu_compute_local_size_valid(
    const struct opengpu_compute_local_limits *limits, const u32 local[3],
    u32 *items)
{
    unsigned long long product;
    unsigned i;

    if (!limits || !local || !limits->items || !limits->axis ||
        limits->items > OPENGPU_COMPUTE_VALIDATOR_MAX_LOCAL_ITEMS ||
        limits->axis > limits->items || limits->axis > 65535u)
        return false;
    for (i = 0; i < 3; i++)
        if (!local[i] || local[i] > limits->axis)
            return false;
    product = (unsigned long long)local[0] * local[1] * local[2];
    if (product > limits->items)
        return false;
    if (items)
        *items = (u32)product;
    return true;
}
#endif
