/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PROTECTED_TESSELLATION_H
#define OPENGPU_PROTECTED_TESSELLATION_H
#include "opengpu_native_job_snapshot.h"
/* Internal verified probe result. This is not a userspace permission record.
 * No current production probe sets available: the new provider/record must be
 * verified independently; an old native/tessellation capability is insufficient. */
struct opengpu_protected_tessellation_profile {
    u32 version, domains, compute_units, lanes, warps, engines;
    bool available, scalar_fpu, advanced_blend;
    /* Verified task-owned input/output patch RAM and levels initialize before
     * access; current P15 undefined-content implementation cannot set this. */
    bool patch_zero_initialized;
};
bool opengpu_protected_tessellation_profile_valid(const struct opengpu_protected_tessellation_profile *profile);
/* Stable original-GEM input, not CPU-evaluated vertex output. GPU read-only,
 * non-executable; all referenced cache lines and the complete object survive
 * execution and translation retirement. bytes is u64 to preserve 2^32 limits. */
struct opengpu_protected_tessellation_input { u64 offset, bytes; u32 handle, reserved; };
struct opengpu_protected_tessellation_plan {
    u32 sequence_index, state_handle;
    struct drm_opengpu_native_program_view control_program, evaluation_program;
    struct drm_opengpu_native_uniform_stage control_uniforms, evaluation_uniforms;
    struct opengpu_protected_tessellation_input input;
};
struct opengpu_protected_tessellation_state_plan {
    u32 state_handle, reserved;
    struct drm_opengpu_native_program_view fragment_program;
    struct drm_opengpu_native_uniform_stage fragment_uniforms;
};
/* All pointer targets are already one-time kernel copies, never userspace or
 * raw GPU addresses. Build captures this complete graph before any callback.
 * Exact GNQ magic selects the domain; there is no duplicate domain selector.
 * Legacy GNQ/GNT/GNF helpers and UAPIs retain their original contracts. */
struct opengpu_protected_tessellation_source {
    const void *job, *frame, *sequences, *states;
    size_t sequences_bytes, states_bytes;
    const struct opengpu_protected_tessellation_plan *plans;
    const struct opengpu_protected_tessellation_state_plan *state_plans;
    u32 plan_count, state_plan_count;
    const struct opengpu_native_frame_resource *resources;
    u32 resource_count, context, pass, generation;
    struct opengpu_protected_tessellation_profile profile;
};
/* Retain local originals, hold all producer reservations and complete producer
 * fences before callbacks; no CPU source writes may race. callbacks may only
 * resolve/copy those originals. RO/RW physical cache-line alias conflicts are
 * checked before publication, independently of handle equality. All original
 * references/context and sync dependencies remain owned until complete drain.
 *
 * BUILD only: no address escapes, no hardware/discovery/queue operation. Output
 * unchanged on failure; ANY failure requires retirement of the whole owner.
 * An unavailable/invalid verified profile fails before copying or appending.
 * P39 original VS execution and P40 full-patch TES transport remain separate. */
int opengpu_protected_tessellation_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_protected_tessellation_source *source,
    const struct opengpu_native_job_copy *copy,u32 *job_segment);
#endif
