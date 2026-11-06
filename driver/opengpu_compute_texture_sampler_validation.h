/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_SAMPLER_VALIDATION_H
#define OPENGPU_COMPUTE_TEXTURE_SAMPLER_VALIDATION_H
#include "opengpu_compute_texture_records.h"
#include "opengpu_compute_texture_format_info.h"

/* Validate kernel-owned snapshots after resource selection. This does not
 * pin memory, check the root's unit range or prove a complete mip chain. */
static inline bool opengpu_validate_compute_texture_sampler(
    const struct opengpu_compute_texture_binding_config *binding,
    const struct opengpu_compute_texture_image_config *image,
    const struct opengpu_compute_texture_sampler_config *sampler,
    bool sampling, bool shadow)
{
    u32 words[OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS];
    const struct opengpu_compute_texture_format_info *format;
    bool nearest;
    if (!binding || !image ||
        !opengpu_encode_compute_texture_binding(words, binding) ||
        !opengpu_encode_compute_texture_image(words, image))
        return false;
    if (binding->context != image->context || binding->pass != image->pass ||
        binding->generation != image->generation || binding->image_handle != image->handle ||
        binding->target != image->target)
        return false;
    format = &opengpu_compute_texture_formats[image->format];
    if (binding->expected_type != format->kind)
        return false;
    if (image->target == OPENGPU_TEXTURE_BUFFER_TARGET)
        return !sampling && !shadow;
    if (!sampler || !opengpu_encode_compute_texture_sampler(words, sampler) ||
        binding->context != sampler->context || binding->pass != sampler->pass ||
        binding->generation != sampler->generation || binding->sampler_handle != sampler->handle)
        return false;
    nearest = sampler->mag_filter == 0u &&
              (sampler->min_filter == 0u || sampler->min_filter == 2u);
    if (!sampling)
        return !sampler->compare_enable && (format->filterable || nearest);
    if (format->depth ? sampler->compare_enable != shadow : shadow)
        return false;
    if (shadow)
        return format->depth && binding->expected_type == 0u;
    return format->filterable || nearest;
}
#endif
