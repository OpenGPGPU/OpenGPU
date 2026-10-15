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

#endif
