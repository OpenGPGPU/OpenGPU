/* SPDX-License-Identifier: MIT */
#ifndef NATIVE_GRAPHICS_UNIFORM_SOURCE_H
#define NATIVE_GRAPHICS_UNIFORM_SOURCE_H
#include <stdint.h>
#include <stdio.h>

/* Qualification data from original GEMs, never from native slot maps or an
 * owner's private allocation. Initialize windows in original GLSL order before
 * submit. Observe only after producer completion, immediately before the real
 * snapshot copy. Records are independent so rebinding cannot alter an earlier
 * record's observed words. This interface does not create or retire a job. */
struct native_graphics_source_window {
    uint32_t handle, bytes, binding;
    uint64_t allocation_bytes, offset;
    uint32_t first, last, observed;
};
struct native_graphics_source_record {
    struct native_graphics_source_window windows[2][13]; /* default, globals */
    uint32_t width, height, color_handle, row_pitch;
    uint64_t color_offset, color_bytes, color_physical, color_allocation;
    uint32_t color_observed, failed;
};

/* Nonuniform copy callbacks may share the observer: an unrelated checked view
 * is ignored. A view matching an expected source must match its complete size.
 * Repeated exact-source copies must observe identical bytes at the endpoints. */
int native_graphics_source_observe(struct native_graphics_source_record *record,
    uint32_t handle, uint64_t offset, uint64_t bytes, const void *stable_source);
int native_graphics_source_allocation(struct native_graphics_source_record *record,
    uint32_t handle, uint64_t physical, uint64_t allocation_bytes);
/* Writes source-oracle JSON only for a complete, successful observation. The
 * caller owns the temporary stream and removes it when qualification ends. */
int native_graphics_source_write(FILE *stream, const struct native_graphics_source_record *record);
#endif
