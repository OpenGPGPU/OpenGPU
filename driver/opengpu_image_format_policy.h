/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_IMAGE_FORMAT_POLICY_H
#define OPENGPU_IMAGE_FORMAT_POLICY_H
#include "opengpu_texture_image_format_abi.h"

/* The thirteen native image-unit formats, independent of sampler eligibility.
 * Plain unsigned types keep the policy usable in both public request builders
 * and kernel descriptor preparation. Existing format IDs are unchanged. */
static inline unsigned opengpu_compute_image_format_bytes(unsigned format)
{
    switch (format) {
    case OPENGPU_TEXTURE_FORMAT_RGBA32F: case OPENGPU_TEXTURE_FORMAT_RGBA32UI:
    case OPENGPU_TEXTURE_FORMAT_RGBA32I: return 16;
    case OPENGPU_TEXTURE_FORMAT_RGBA16F: case OPENGPU_TEXTURE_FORMAT_RGBA16UI:
    case OPENGPU_TEXTURE_FORMAT_RGBA16I: return 8;
    case OPENGPU_TEXTURE_FORMAT_R32F: case OPENGPU_TEXTURE_FORMAT_R32UI:
    case OPENGPU_TEXTURE_FORMAT_R32I: case OPENGPU_TEXTURE_FORMAT_RGBA8UI:
    case OPENGPU_TEXTURE_FORMAT_RGBA8I: case OPENGPU_TEXTURE_FORMAT_RGBA8:
    case OPENGPU_TEXTURE_FORMAT_RGBA8_SNORM: return 4;
    default: return 0;
    }
}
#endif
