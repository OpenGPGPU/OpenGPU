#ifndef OPENGPU_DRM_IMAGE_ORACLE_H
#define OPENGPU_DRM_IMAGE_ORACLE_H

#include <stdint.h>

/* Four-lane smoke shader: warp0 discards the first quad in each batch.
 * Top-left coverage includes the diagonal and excludes the coordinate axes.
 * Edge quads have unequal populations, so dividing triangle area by two is
 * not an exact oracle. Enumerate nonempty quads in raster submission order.
 */
static inline uint32_t opengpu_test_fragment_pixels(uint32_t width,
                                                   uint32_t height,
                                                   uint32_t batch_capacity)
{
    uint32_t quad = 0, pixels = 0;
    uint32_t quads_per_batch = batch_capacity / 4;

    if (!quads_per_batch || (batch_capacity & 3u))
        return 0;
    for (uint32_t y = 0; y < height; y += 2) {
        for (uint32_t x = 0; x < width; x += 2) {
            uint32_t covered = 0;

            for (uint32_t dy = 0; dy < 2; dy++) {
                for (uint32_t dx = 0; dx < 2; dx++) {
                    uint32_t px = x + dx, py = y + dy;

                    if (px > 0 && py > 0 && px < width && py < height &&
                        (uint64_t)height * px + (uint64_t)width * py <=
                            (uint64_t)width * height)
                        covered++;
                }
            }
            if (!covered)
                continue;
            if (quad % quads_per_batch)
                pixels += covered;
            quad++;
        }
    }
    return pixels;
}

#endif
