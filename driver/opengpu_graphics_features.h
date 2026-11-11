/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GRAPHICS_FEATURES_H
#define OPENGPU_GRAPHICS_FEATURES_H
#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"

/* ABI v3 gives the legacy vertex renderer an opt-in clip-depth format. */
static inline bool opengpu_graphics_clip_depth_supported(u32 id, u32 caps)
{
    const u32 required = GPU_CAP_VERTEX_CORE | GPU_CAP_UNIFIED_RENDER;
    return (id & 0xffffu) >= GPU_ABI_CLIP_DEPTH_VERSION &&
           (caps & required) == required;
}
#endif
