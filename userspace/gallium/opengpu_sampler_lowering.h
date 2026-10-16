/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SAMPLER_LOWERING_H
#define OPENGPU_SAMPLER_LOWERING_H
#include <stdbool.h>
#include "opengpu_formats.h"

struct opengpu_sample_plan {
    int texture_format;
    bool shader_swizzle;
};

/* A channel permutation commutes with per-channel bilinear/trilinear
 * filtering and truncation. Paired native formats preserve the same alpha
 * policy; the legacy native word layout keeps the shader implementation. */
static inline struct opengpu_sample_plan opengpu_lower_sample_swap(
    int format, bool swap_rb, bool allow_format_lowering)
{
    struct opengpu_sample_plan plan = { format, swap_rb };
    if (!swap_rb || !allow_format_lowering)
        return plan;
    switch (format) {
    case OPENGPU_FORMAT_RGBA8: plan.texture_format = OPENGPU_FORMAT_BGRA8; break;
    case OPENGPU_FORMAT_BGRA8: plan.texture_format = OPENGPU_FORMAT_RGBA8; break;
    case OPENGPU_FORMAT_RGBX8: plan.texture_format = OPENGPU_FORMAT_BGRX8; break;
    case OPENGPU_FORMAT_BGRX8: plan.texture_format = OPENGPU_FORMAT_RGBX8; break;
    default: return plan;
    }
    plan.shader_swizzle = false;
    return plan;
}
#endif
