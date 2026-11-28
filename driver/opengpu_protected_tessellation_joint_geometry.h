/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_PROTECTED_TESSELLATION_JOINT_GEOMETRY_H
#define OPENGPU_PROTECTED_TESSELLATION_JOINT_GEOMETRY_H
#include "opengpu_protected_tessellation.h"
#include "opengpu_tessellation_vertex_plan.h"
#include "opengpu_geometry_snapshot.h"
#include "opengpu_protected_tessellation_joint_geometry_abi.h"
#include "opengpu_tessellation_joint_host.h"
struct opengpu_device;
struct opengpu_private_snapshot;
struct opengpu_render_context;
struct opengpu_protected_tessellation_joint_geometry_plan {
    u32 sequence_index, state_handle;
    struct drm_opengpu_native_program_view control_program, evaluation_program;
    struct drm_opengpu_native_uniform_stage control_uniforms, evaluation_uniforms;
};
/* All targets are already one-time kernel copies. BUILD makes its own complete
 * owned copy before any callback. Catalog plans preserve original GND ordinal;
 * their FS views must equal the corresponding state plan, including unused
 * catalog entries. No old input buffer or raw address is admitted. */
/* Original source references are held by the enclosing source lease through whole-owner retirement. */
struct opengpu_joint_geometry_plan {
    u32 sequence_index, state_handle, command_id, gs_task_id_base, first_input_primitive_id;
    u64 first_output_sequence, input_sequence;
    struct drm_opengpu_native_program_view program;
    struct drm_opengpu_native_uniform_stage uniforms;
    u32 gng[256];
    u32 evaluation_components[64]; /* bit7 valid; low7 TES export; invalid=0 */
    u32 raster_discard;
};
struct opengpu_protected_tessellation_joint_geometry_source {
    const void *job, *frame, *sequences, *states, *draws;
    size_t sequences_bytes, states_bytes, draws_bytes;
    const struct drm_opengpu_native_draw_plan *draw_plans;
    const struct opengpu_tessellation_vertex_outer_invocation_v2 *invocations;
    const struct opengpu_tessellation_vertex_outer_range_v2 *ranges;
    const struct opengpu_protected_tessellation_joint_geometry_plan *plans;
    const struct opengpu_protected_tessellation_state_plan *state_plans;
    const struct opengpu_native_frame_resource *resources;
    u32 draw_count, draw_plan_count, invocation_count, range_count;
    u32 plan_count, state_plan_count, resource_count, context, pass, generation;
    const struct opengpu_joint_geometry_plan *geometry_plans;
    u32 geometry_plan_count;
    struct opengpu_protected_tessellation_joint_geometry_profile profile;
};
bool opengpu_protected_tessellation_joint_geometry_profile_valid(
    const struct opengpu_protected_tessellation_joint_geometry_profile *profile);
/* Canonical record-only checks do not authorize an owner or executable route. */
bool opengpu_protected_tessellation_joint_geometry_job_canonical(const void *job,size_t bytes,bool bound);
/* Original refs/reservations, source CPU-write exclusion and producer waits are
 * mandatory caller prerequisites. No submit_lock while waiting producers.
 * Full physical64B aliases include VS scratch and frame resources. Exact same physical
 * VS scratch window may be reused only as the same serial source role; the
 * verified provider must retire its task/memory before admitting another.
 * Output unchanged on ANY error; retire the WHOLE partially built owner.
 * Returns a GNQ segment ID, never GVM2 as an execution entry or a guessed VA. */
int opengpu_protected_tessellation_joint_geometry_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_protected_tessellation_joint_geometry_source *source,
    const struct opengpu_native_job_copy *copy,u32 *job_segment);
/* Mandatory new-route prepare gate; does not publish, enqueue or expose a VA.
 * It validates the actual placed graph, not a caller's LDS-reserved promise.
 * On failure retire/quarantine the whole owner. The old prepare API is unchanged. */
int opengpu_protected_tessellation_joint_geometry_prepare(struct opengpu_private_snapshot *owner,u32 job_segment);

struct opengpu_protected_tessellation_joint_geometry_host_command {
    u32 opcode, source, destination, bytes, pattern;
    u32 width, height, source_stride, destination_stride;
};
int opengpu_protected_tessellation_joint_geometry_command_locked(
    const struct opengpu_private_snapshot *owner, struct opengpu_device *gpu,
    const struct opengpu_render_context *context, u32 segment,
    struct opengpu_protected_tessellation_joint_geometry_host_command *command);
#endif
