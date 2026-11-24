/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_VERTEX_PLAN_H
#define OPENGPU_TESSELLATION_VERTEX_PLAN_H
#include "opengpu_tessellation_vertex_source.h"

#define OPENGPU_VERTEX_OUTER_MAP_MAGIC 0x47564d02u
struct opengpu_tessellation_vertex_outer_source_v2 {
    u32 version, context, pass, generation, reserved[2];
    const void *draws; /* draw_count tightly packed original 2048-byte GND1s */
    size_t draws_bytes;
    const struct drm_opengpu_native_draw_plan *plans;
    u32 draw_count, plan_count;
    const struct opengpu_tessellation_vertex_outer_invocation_v2 *invocations;
    const struct opengpu_tessellation_vertex_outer_range_v2 *ranges;
    u32 invocation_count, range_count;
    struct opengpu_tessellation_vertex_source_profile profile;
};

/* Internal BUILD result. Caller releases roots with the function below. Values
 * are owner segment IDs, never parsed/guessed virtual addresses. */
struct opengpu_tessellation_vertex_outer_roots {
    u32 map_segment, root_count;
    u32 *root_segments; /* sequence-index order */
};
/* Pure complete-catalog/invocation/range preflight, no callbacks/owner writes. */
int opengpu_tessellation_vertex_outer_precheck(const struct opengpu_tessellation_vertex_outer_source_v2 *source);
void opengpu_tessellation_vertex_outer_roots_release(struct opengpu_tessellation_vertex_outer_roots *result);
int opengpu_tessellation_vertex_outer_append_roots(struct opengpu_private_snapshot *owner,
    const struct opengpu_tessellation_vertex_outer_source_v2 *source,
    const struct opengpu_native_job_copy *copy,
    struct opengpu_tessellation_vertex_outer_roots *result);

/* Internal GVM2 only; no GNQ reader, ioctl, factory or address getter accepts
 * this map. Header16 words: magic/bytes/version/entryBytes/draws/invocations/
 * ranges/context/pass/generation/attributes/bindings/varyings/warps/CUs/zero.
 * Each16-word entry: sequenceIndex/drawIndex/invocationLo/invocationHi/flags/
 * rangeOrdinal/firstInstance/instances/firstInputPatch/patchesPerInstance/
 * taskBase/patchCount/GVSbase/GVSlimitLo/GVSlimitHi/zero.
 * Source form has all three GVS address words zero; bound form has an exact
 * aligned512-byte view. Neither syntax check proves original-source authority. */
bool opengpu_tessellation_vertex_outer_map_source_canonical(const void *data,size_t bytes);
bool opengpu_tessellation_vertex_outer_map_canonical(const void *data,size_t bytes);

/* Capture the COMPLETE catalog, native plans, invocations, ranges and callbacks
 * before the first callback. Plans may be permuted; draw_index indexes original
 * GND bytes, not the sequence index. Coverage is exact instance-major original
 * patch order; incomplete trailing vertices do not form an input patch.
 *
 * Caller retains original local GEMs/context/reservations and completes source
 * producers before copying, without racing CPU writes. Real GVS/RX/GUR1/input
 * builders retain their contracts. Any error requires whole-owner retirement;
 * output is unchanged. Only a private R/NX map segment ID is returned.
 *
 * This is NOT whole-graph frame/TCS/TES alias validation, enqueue permission,
 * or a shader PrimitiveID implementation. Full native plans are captured but
 * their FS fields confer no authority here. No production profile is enabled. */
int opengpu_tessellation_vertex_outer_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_tessellation_vertex_outer_source_v2 *source,
    const struct opengpu_native_job_copy *copy,u32 *map_segment);
#endif
