/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GRAPHICS_V2_H
#define OPENGPU_NATIVE_GRAPHICS_V2_H
#include "opengpu_native_sample_program.h"
/* Private ESSL320/no-GS compiler and original-GEM source preparation. Exact112
 * observations supply capacity, NOT permission to submit ABI8/9. No public
 * capability, runtime version mask or protected admission is changed here. */
struct opengpu_native_graphics_program_v2;
struct opengpu_native_graphics_source_v2;
struct opengpu_native_graphics_source_description_v2 {
    struct opengpu_native_sample_runtime runtime;
    unsigned context_id;
    struct opengpu_native_uniform_layout vertex_layout;
    struct opengpu_native_uniform_program vertex;
    struct opengpu_native_fragment_program_v2 fragment;
    struct drm_opengpu_native_program_view programs[2];
    struct drm_opengpu_native_uniform_stage uniforms[2];
};
/* Both token streams and reflection are copied/compiled before GEM callbacks.
 * A context is single-threaded as in Gallium. Context/screen outlive objects. */
struct opengpu_native_graphics_program_v2 *opengpu_native_graphics_program_create_v2(
    struct pipe_context *,const void *vertex_tokens,unsigned vertex_words,
    const void *fragment_tokens,unsigned fragment_words,
    const struct opengpu_native_fragment_reflection_v2 *);
void opengpu_native_graphics_program_destroy_v2(struct opengpu_native_graphics_program_v2 *);
/* An opaque lease retains program/code and every required original resource,
 * including independent views of one GEM, across rebind/caller release. It
 * freezes view metadata, not data or fences: protected snapshot still waits
 * producers and copies/validates bytes. NULL on failure; no partial lease. */
struct opengpu_native_graphics_source_v2 *opengpu_native_graphics_source_create_v2(
    struct pipe_context *,struct opengpu_native_graphics_program_v2 *);
void opengpu_native_graphics_source_destroy_v2(struct opengpu_native_graphics_source_v2 *);
/* A value copy for source-plan consumers; changing it cannot change a lease.
 * Draw encoding accepts only the opaque lease, never this description. */
int opengpu_native_graphics_source_describe_v2(const struct opengpu_native_graphics_source_v2 *,
    struct opengpu_native_graphics_source_description_v2 *);
#endif
