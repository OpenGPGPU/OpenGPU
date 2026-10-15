/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_RENDER_EXTENT_H
#define OPENGPU_RENDER_EXTENT_H

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_extent_u32;
#else
#include <stdint.h>
typedef uint32_t opengpu_extent_u32;
#endif

struct opengpu_render_extent {
    opengpu_extent_u32 width;
    opengpu_extent_u32 height;
};

/* Resolve legacy submissions before validating attachment ranges. Explicit
 * extents can also be larger than the current mode, within hardware limits.
 * Older hardware supports the current mode only, never an incorrect viewport. */
static inline int opengpu_render_extent_resolve(
    opengpu_extent_u32 width, opengpu_extent_u32 height,
    opengpu_extent_u32 mode_width, opengpu_extent_u32 mode_height,
    opengpu_extent_u32 max_width, opengpu_extent_u32 max_height,
    int supported, struct opengpu_render_extent *out)
{
    if (!out || (!!width != !!height))
        return -22; /* EINVAL */
    if (!width) {
        width = mode_width;
        height = mode_height;
    }
    if (!width || !height || width > max_width || height > max_height)
        return -22;
    if (!supported && (width != mode_width || height != mode_height))
        return -95; /* EOPNOTSUPP */
    out->width = width;
    out->height = height;
    return 0;
}

#endif
