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
static inline bool opengpu_format_rb_reversed(int format)
{
    return format == OPENGPU_FORMAT_BGRA8 || format == OPENGPU_FORMAT_BGRX8;
}

/* GtkGLArea samples an RGBA view into a BGRA window and also selects c.bgra.
 * The format store already places red and blue for that window, so applying
 * the shader swap as well exchanges them on scanout. A same-order target
 * still honors the swap, which is what the swizzle readback checks. */
static inline bool opengpu_apply_sample_rb_swap(int src_format, int dst_format,
                                                bool swap_rb)
{
    if (!swap_rb)
        return false;
    if (src_format < (int)OPENGPU_FORMAT_RGBA8 ||
        src_format > (int)OPENGPU_FORMAT_BGRX8 ||
        dst_format < (int)OPENGPU_FORMAT_RGBA8 ||
        dst_format > (int)OPENGPU_FORMAT_BGRX8)
        return true;
    return opengpu_format_rb_reversed(src_format) ==
           opengpu_format_rb_reversed(dst_format);
}

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
