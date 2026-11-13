/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAME_H
#define OPENGPU_NATIVE_FRAME_H
#include <stdint.h>
#include "opengpu_native_binned_host_abi.h"
#include "opengpu_native_frame_abi.h"

/* Native typed attachment IDs, not scanout formats. The planner retains every
 * enabled attachment/sample and only changes the resident tile decomposition.
 * tile_log2=0 selects the largest advertised tile that fits resident storage;
 * 3/4 require that exact decomposition (including the complete 8x8 API grid).
 */
struct opengpu_native_tile_request {
    const struct opengpu_native_binned_host_caps *caps;
    uint32_t width, height, samples, color_mask;
    uint32_t color_format[4], depth_format, stencil_format, tile_log2;
};
struct opengpu_native_tile_layout {
    uint32_t tile_log2, sample_mode, columns, rows, tile_count;
    uint32_t storage_bytes, used_bytes, reserved_bytes;
    uint32_t depth_offset, stencil_offset, color_offset[4];
};

/* Returns a negative errno on invalid/unsupported input or insufficient
 * resident storage. A failure never changes the caller's output structure.
 * This plans GNF storage; it does not allocate/pin memory or submit a job.
 */
int opengpu_native_tile_plan(const struct opengpu_native_tile_request *,
                            struct opengpu_native_tile_layout *);
#endif
