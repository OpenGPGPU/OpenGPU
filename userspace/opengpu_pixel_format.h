/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_PIXEL_FORMAT_H
#define OPENGPU_PIXEL_FORMAT_H

#include <stdint.h>
#include "opengpu_formats.h"

/* Clear patterns are scalar words; texel arrays stay in their declared byte
 * order and are decoded/encoded directly by the GPU. */
static inline uint32_t opengpu_pixel_encode(uint32_t rgba, uint32_t format)
{
    uint32_t r = rgba >> 24, g = (rgba >> 16) & 255u;
    uint32_t b = (rgba >> 8) & 255u, a = rgba & 255u;
    switch (format) {
    case OPENGPU_FORMAT_RGBA8: return r | g << 8 | b << 16 | a << 24;
    case OPENGPU_FORMAT_BGRA8: return b | g << 8 | r << 16 | a << 24;
    case OPENGPU_FORMAT_RGBX8: return r | g << 8 | b << 16 | 0xff000000u;
    case OPENGPU_FORMAT_BGRX8: return b | g << 8 | r << 16 | 0xff000000u;
    default: return rgba;
    }
}
#endif
