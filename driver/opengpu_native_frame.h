/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAME_H
#define OPENGPU_NATIVE_FRAME_H
#include <stdint.h>
#include "opengpu_native_binned_host_abi.h"
#include "opengpu_native_frame_abi.h"
#include "opengpu_native_binned_abi.h"

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

/* Validate the actual eight-word virtual-image GNC2/GNC3 discovery block.
 * Runtime sizes remain independent of product/profile constants. Callers
 * reading u64 GET_PARAM values must also reject nonzero upper 32 bits before
 * assembling this block, and cross-check shader layout against the same device.
 */
int opengpu_native_frame_caps_validate(const struct opengpu_native_binned_host_caps *);

/* Returns a negative errno on invalid/unsupported input or insufficient
 * resident storage. A failure never changes the caller's output structure.
 * This plans GNF storage; it does not allocate/pin memory or submit a job.
 */
int opengpu_native_tile_plan(const struct opengpu_native_tile_request *,
                            struct opengpu_native_tile_layout *);

/* Exact byte view of a local GEM, not a device address. Image views may start
 * within a cache line; their encompassing lines must also belong to the GEM.
 * Other roles require 64-byte alignment (4096 for pool segments).
 */
struct opengpu_native_frame_gem {
    uint32_t handle;
    uint64_t offset, bytes;
};
struct opengpu_native_frame_image {
    struct opengpu_native_frame_gem gem;
    uint32_t row_pitch, store;
};
struct opengpu_native_frame_attachment {
    uint32_t format, load_op, channel_mask, clear[4];
    struct opengpu_native_frame_image image;
    /* A nonzero GEM handle enables a same-format single-sample resolve.
     * Resolve store must be one; source store/discard is independent.
     */
    struct opengpu_native_frame_image resolve;
};
struct opengpu_native_frame_request {
    const struct opengpu_native_binned_host_caps *caps;
    uint32_t width, height, samples, color_mask, tile_log2;
    uint32_t context, generation, pass, state_count, scratch_stride;
    /* Color slots 0..3, primary depth/stencil 4, separate S8 slot 5.
     * Depth clear[0] is FP32 depth, clear[1] is the stencil byte.
     */
    struct opengpu_native_frame_attachment attachments[6];
    struct opengpu_native_frame_gem primitives, directory, scratch[4];
    /* Initial segment followed by up to eight fallback segments. An entirely
     * zero initial segment is legal when spill_count is nonzero. Private VA
     * placement is ordered by the kernel; no physical contiguity is required.
     */
    struct opengpu_native_frame_gem pool[9];
    uint32_t spill_count;
};
#define OPENGPU_NATIVE_FRAME_MAX_RESOURCES 27u
struct opengpu_native_frame_encoding {
    struct opengpu_native_binned_frame frame;
    struct opengpu_native_tile_layout tile;
    uint32_t resource_count;
    struct opengpu_native_frame_resource resources[OPENGPU_NATIVE_FRAME_MAX_RESOURCES];
};

/* Encode canonical GNF metadata and typed GEM plans atomically. All device
 * addresses, including the private copied GFS state view, remain zero for the
 * protected kernel producer to patch. No GEM allocation, fence or submission
 * occurs here; the kernel must independently validate every plan/object.
 */
int opengpu_native_frame_encode(const struct opengpu_native_frame_request *,
                               struct opengpu_native_frame_encoding *);
#endif
