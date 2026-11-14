/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_VERTEX_SOURCE_H
#define OPENGPU_TESSELLATION_VERTEX_SOURCE_H
#include "opengpu_native_job_snapshot.h"
#include "opengpu_tessellation_program_snapshot.h"
/* Internal verified configuration, not caller metadata or a discovery bit.
 * No production getter currently supplies this profile. */
struct opengpu_tessellation_vertex_source_profile {
    u32 version, compute_units, lanes, warps;
    bool available, scalar_fpu, patch_zero_initialized;
};
struct opengpu_tessellation_vertex_slice {
    u32 version, first_ordinal, patches_per_instance, first_instance, instances;
};
struct opengpu_tessellation_vertex_component { u32 kind, component; };
/* Original local GEM views. Indirect, legacy sampler and texture-root sources
 * retain explicit provenance slots but remain unsupported/nonzero-rejected.
 * They are not an opcode permission or an arbitrary GPU-address escape. */
struct opengpu_tessellation_vertex_plan {
    struct drm_opengpu_native_program_view program;
    struct drm_opengpu_native_uniform_stage uniforms;
    struct drm_opengpu_compute_uniform_view bindings[16], indices, indirect, scratch;
    struct drm_opengpu_compute_uniform_view sampler, texture_root;
};
struct opengpu_tessellation_vertex_source {
    const void *draw;
    size_t draw_bytes;
    u32 context, pass, generation, sequence_index, draw_index, task_id_base, patch_count;
    struct opengpu_tessellation_vertex_slice slice;
    struct opengpu_tessellation_patch_layout layout;
    struct opengpu_tessellation_vertex_component components[64];
    struct opengpu_tessellation_vertex_plan plan;
    struct opengpu_tessellation_vertex_source_profile profile;
};
/* Record-only, wide canonical validation; it grants no resource/profile or
 * enqueue permission. A linked reader/outer BUILD is still required. */
bool opengpu_tessellation_vertex_source_canonical(const void *data,size_t bytes);
bool opengpu_tessellation_vertex_sequence_canonical(const void *data,size_t bytes,u32 domain);
/* Capture the complete kernel plan and original GND bytes before callbacks.
 * Caller MUST retain local GEMs/context, hold reservations, complete producers
 * outside submit_lock and prevent source CPU writes while copying. Callback
 * resolves/copies only those originals. Read allocations are cached once;
 * all source/scratch physical 64B aliases are rejected, even across handles.
 *
 * Output is a private segment ID, never a GPU VA. GVS/GND/GUR1 are R/NX,
 * original VS bytes RX/no-W, original inputs R/NX and scratch RW/NX. Any error
 * requires whole-owner retirement; output stays unchanged. This root-only
 * builder does not check unrelated frame/TCS/TES aliases, append a GNT/GNQ,
 * wait Linux fences, publish, enqueue or enable any hardware/query/getter.
 */
int opengpu_tessellation_vertex_source_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_tessellation_vertex_source *source,
    const struct opengpu_native_job_copy *copy,u32 *root_segment);
#endif
