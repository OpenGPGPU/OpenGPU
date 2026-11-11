/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TEXTURE_LAYOUT_H
#define OPENGPU_TEXTURE_LAYOUT_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* Pack complete RGBA rows from a pitched allocation into a distinct buffer.
 * Padding belongs to the allocation, not to the sampler's texture image. */
static inline int opengpu_texture_pack_rows(void *destination, size_t destination_size,
    const void *source, size_t source_size, uint32_t width, uint32_t height,
    uint32_t pitch)
{
    uint64_t row = (uint64_t)width * 4u, packed, span;
    uint32_t y;
    if (!destination || !source || !width || !height || row > pitch)
        return -1;
    packed = row * height;
    span = (uint64_t)(height - 1u) * pitch + row;
    if (packed > destination_size || span > source_size)
        return -1;
    for (y = 0; y < height; y++)
        memcpy((uint8_t *)destination + (size_t)y * (size_t)row,
               (const uint8_t *)source + (size_t)y * pitch, (size_t)row);
    return 0;
}

/* Apply a sampler view's component selectors to a 4-byte linear image.
 * The output is packed RGBA, regardless of the input's R/B byte order. A
 * source X channel reads as one. Selectors 0..3 name RGBA, 4 is zero and 5
 * is one, matching Gallium's PIPE_SWIZZLE values. The buffers are distinct. */
static inline int opengpu_texture_swizzle_rows(void *destination, size_t destination_size,
    const void *source, size_t source_size, uint32_t width, uint32_t height,
    uint32_t pitch, int reverse_rb, int alpha_one, const uint8_t swizzle[4])
{
    uint64_t row = (uint64_t)width * 4u, packed, span;
    uint32_t x, y;
    unsigned component;

    if (!destination || !source || !swizzle || !width || !height || row > pitch)
        return -1;
    packed = row * height;
    span = (uint64_t)(height - 1u) * pitch + row;
    if (packed > destination_size || span > source_size)
        return -1;
    for (component = 0; component < 4; component++)
        if (swizzle[component] > 5)
            return -1;
    for (y = 0; y < height; y++) {
        for (x = 0; x < width; x++) {
            const uint8_t *src = (const uint8_t *)source + (size_t)y * pitch + (size_t)x * 4u;
            uint8_t *dst = (uint8_t *)destination + (size_t)y * (size_t)row + (size_t)x * 4u;
            uint8_t channel[6] = { src[reverse_rb ? 2 : 0], src[1],
                                   src[reverse_rb ? 0 : 2], alpha_one ? 255 : src[3], 0, 255 };
            for (component = 0; component < 4; component++)
                dst[component] = channel[swizzle[component]];
        }
    }
    return 0;
}

#endif
