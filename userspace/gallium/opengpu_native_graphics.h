/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GRAPHICS_H
#define OPENGPU_NATIVE_GRAPHICS_H
#include "opengpu_native_runtime.h"
#include "opengpu_native_uniform_emit.h"
#include "opengpu.h"
struct pipe_context;
struct opengpu_native_graphics_program;

struct opengpu_native_graphics_sources {
    struct opengpu_native_runtime runtime;
    struct opengpu_native_uniform_layout layout[2];
    struct drm_opengpu_native_program_view programs[2];
    struct drm_opengpu_native_uniform_stage uniforms[2];
    uint64_t varying_mask[2];
    unsigned color_mask;
};

/* Bounded native frontend, separate from legacy create_vs/fs_state/draw_vbo.
 * Compile actual TGSI immediately and retain private source code GEMs. The
 * context/screen must outlive this object. No GPU VA or native job is published.
 */
struct opengpu_native_graphics_program *opengpu_native_graphics_program_create(
    struct pipe_context *, const void *vertex_tokens, const void *fragment_tokens);
void opengpu_native_graphics_program_destroy(struct opengpu_native_graphics_program *);
/* Capture current real Gallium constant bindings and code source views. Source
 * ownership remains with the program/context until protected submit accepts.
 * The kernel must producer-wait, validate and copy both code and uniform data.
 * Failure leaves output unchanged; this call does not submit or acquire a job.
 */
int opengpu_native_graphics_program_sources(struct pipe_context *,
    const struct opengpu_native_graphics_program *, struct opengpu_native_graphics_sources *);
#endif
