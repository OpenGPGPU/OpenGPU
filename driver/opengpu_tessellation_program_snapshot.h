/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_TESSELLATION_PROGRAM_SNAPSHOT_H
#define OPENGPU_TESSELLATION_PROGRAM_SNAPSHOT_H
#include <linux/types.h>
#include "opengpu_native_program_snapshot.h"
/* Internal verified-owner profile, never caller metadata permission. Version 1
 * is the current checked-patch TCS / static-selection one-point TES dialect.
 * TES's 61 selected words are a transport limit, not full dynamic gl_in[]. */
struct opengpu_tessellation_program_profile {
    u32 version, stage, lanes, warps;
    u32 input_vertices, input_components, output_vertices, output_components, patch_components;
    u32 patch_words;
    bool available, owned_uniforms, scalar_fpu;
};
bool opengpu_tessellation_program_profile_valid(const struct opengpu_tessellation_program_profile *profile);
bool opengpu_tessellation_shader_validate_words(const u32 *words,u32 word_count,
    const struct opengpu_tessellation_program_profile *profile);
/* The submitter retains/checks original local GEM views and waits producers
 * under reservation ownership before copy_source. Capture view/profile once,
 * validate the reachable entry suffix, copy the ENTIRE original byte view
 * unchanged through the ordinary private RX/no-W segment API. No raw memory
 * permission follows from x1, a uniform value, an opcode or user stage metadata.
 * Output unchanged on failure; retire the WHOLE unpublished owner on any error.
 * This does not discover hardware, publish/enqueue or replace runtime
 * patch/geometry/finish/cancel drain checks. Future protected submit must own
 * the exact launch/register/layout/scope profile through physical retirement.
 */
int opengpu_tessellation_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_tessellation_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output);
#endif
