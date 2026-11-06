/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_BUFFER_VIEW_H
#define OPENGPU_BUFFER_VIEW_H
#include "opengpu_texture_image_format_abi.h"

/* Derive a count only after checking the complete BO envelope and logical
 * range, including bytes that do not form a texel or exceed the count cap.
 * This is address arithmetic, not a GEM/VM ownership or capability check.
 * No output is modified on failure. */
static inline int opengpu_buffer_view_count(unsigned long long binding_base,
    unsigned long long binding_bytes, unsigned long long offset,
    unsigned long long bytes, unsigned bytes_per_texel, unsigned *count)
{
    unsigned long long texels;
    if (!count || !bytes_per_texel || !binding_bytes || !bytes ||
        binding_base >= (1ULL << 32) || binding_bytes > (1ULL << 32) - binding_base ||
        bytes > (1ULL << 32) || offset >= binding_bytes || bytes > binding_bytes - offset)
        return 0;
    texels = bytes / bytes_per_texel;
    if (!texels) return 0;
    *count = texels > OPENGPU_TEXTURE_BUFFER_MAX_TEXELS ?
        OPENGPU_TEXTURE_BUFFER_MAX_TEXELS : (unsigned)texels;
    return 1;
}
#endif
