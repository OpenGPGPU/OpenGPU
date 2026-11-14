/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_JOB_SNAPSHOT_H
#define OPENGPU_NATIVE_JOB_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_native_frame_snapshot.h"
#include "opengpu_drm.h"
struct opengpu_private_snapshot;
/* One-time kernel copies. Original GEMs are retained and all producer fences
 * have completed before build; callbacks resolve/copy only those local refs.
 * Metadata may contain no raw GPU address. Identity comes from the accepted
 * context and must agree across the graph. No buffer/VA escapes this builder. */
struct opengpu_native_job_source {
    const void *job, *frame, *draws, *states;
    size_t draws_bytes, states_bytes;
    const struct drm_opengpu_native_draw_plan *plans;
    u32 plan_count;
    const struct opengpu_native_frame_resource *resources;
    u32 resource_count;
    u32 context, pass, generation, capacity, engines;
    bool scalar_fpu, advanced_blend;
};
struct opengpu_native_job_copy {
    int (*copy)(void *,const struct drm_opengpu_compute_uniform_view *,void *);
    opengpu_native_frame_resolve resolve;
    void *context;
};
/* BUILD only: append code/UBO/metadata copies and typed resource relocations.
 * Output unchanged on failure. Discard the entire unpublished owner on error.
 * Mapping, publication, native enqueue and drain retirement remain explicit. */
int opengpu_native_job_snapshot_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_native_job_source *source,
    const struct opengpu_native_job_copy *copy,u32 *job_segment);
#endif
