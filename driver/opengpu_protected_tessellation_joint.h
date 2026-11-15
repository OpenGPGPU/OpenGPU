/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PROTECTED_TESSELLATION_JOINT_H
#define OPENGPU_PROTECTED_TESSELLATION_JOINT_H
#include "opengpu_protected_tessellation.h"
#include "opengpu_tessellation_vertex_plan.h"

/* Kernel-verified exact joint provider contract, not compiler/GSS permission.
 * No getter, factory, ioctl or queue currently supplies this profile. */
struct opengpu_protected_tessellation_joint_profile {
    u32 version, domains, compute_units, lanes, warps, engines;
    bool available, scalar_fpu, advanced_blend;
    bool owned_vertex_source, patch_zero_initialized, owned_patch_reads;
    bool owned_tessellation_info, primitive_inputs, sample_inputs;
    bool joint_directory_route, whole_owner_retirement;
};
struct opengpu_protected_tessellation_joint_plan {
    u32 sequence_index, state_handle;
    struct drm_opengpu_native_program_view control_program, evaluation_program;
    struct drm_opengpu_native_uniform_stage control_uniforms, evaluation_uniforms;
};
/* All targets are already one-time kernel copies. BUILD makes its own complete
 * owned copy before any callback. Catalog plans preserve original GND ordinal;
 * their FS views must equal the corresponding state plan, including unused
 * catalog entries. No old input buffer or raw address is admitted. */
struct opengpu_protected_tessellation_joint_source {
    const void *job, *frame, *sequences, *states, *draws;
    size_t sequences_bytes, states_bytes, draws_bytes;
    const struct drm_opengpu_native_draw_plan *draw_plans;
    const struct opengpu_tessellation_vertex_outer_invocation_v2 *invocations;
    const struct opengpu_tessellation_vertex_outer_range_v2 *ranges;
    const struct opengpu_protected_tessellation_joint_plan *plans;
    const struct opengpu_protected_tessellation_state_plan *state_plans;
    const struct opengpu_native_frame_resource *resources;
    u32 draw_count, draw_plan_count, invocation_count, range_count;
    u32 plan_count, state_plan_count, resource_count, context, pass, generation;
    struct opengpu_protected_tessellation_joint_profile profile;
};
bool opengpu_protected_tessellation_joint_profile_valid(
    const struct opengpu_protected_tessellation_joint_profile *profile);
/* Canonical record-only checks do not authorize an owner or executable route. */
bool opengpu_protected_tessellation_joint_job_canonical(const void *job,size_t bytes,bool bound);
/* Original refs/reservations, source CPU-write exclusion and producer waits are
 * mandatory caller prerequisites. No submit_lock while waiting producers.
 * Full physical64B aliases include VS scratch as a writer. Exact same physical
 * VS scratch window may be reused only as the same serial source role; the
 * verified provider must retire its task/memory before admitting another.
 * Output unchanged on ANY error; retire the WHOLE partially built owner.
 * Returns a GNQ segment ID, never GVM2 as an execution entry or a guessed VA. */
int opengpu_protected_tessellation_joint_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_protected_tessellation_joint_source *source,
    const struct opengpu_native_job_copy *copy,u32 *job_segment);
/* Mandatory new-route prepare gate; does not publish, enqueue or expose a VA.
 * It validates the actual placed graph, not a caller's LDS-reserved promise.
 * On failure retire/quarantine the whole owner. The old prepare API is unchanged. */
int opengpu_protected_tessellation_joint_prepare(struct opengpu_private_snapshot *owner,u32 job_segment);
#endif
