/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GRAPHICS_INTERNAL_H
#define OPENGPU_NATIVE_GRAPHICS_INTERNAL_H
#include "opengpu_native_draw.h"
/* Checked builders share original compiler/view logic. Versioned callers own
 * discovery and context identity; these are not public runtime admissions. */
struct opengpu_native_graphics_program *opengpu_native_graphics_program_create_checked(
    struct pipe_context *,const void *,const void *,const struct opengpu_native_runtime *);
int opengpu_native_graphics_program_sources_checked(struct pipe_context *,
    const struct opengpu_native_graphics_program *,const struct opengpu_native_runtime *,
    int (*)(struct pipe_context *,const unsigned [2][13],struct drm_opengpu_native_uniform_stage [2]),
    struct opengpu_native_graphics_sources *);
int opengpu_native_draw_encode_checked(const struct opengpu_native_draw_request *,
    const struct opengpu_native_runtime *,unsigned,struct opengpu_native_draw_encoding *);
int opengpu_native_sample_cache_install(struct pipe_context *,const void *,size_t,
    const struct opengpu_native_shader_layout *);
int opengpu_native_sample_cache_read(struct pipe_context *,void *,size_t,unsigned *);
int opengpu_native_sample_uniform_sources(struct pipe_context *,const unsigned [2][13],
    struct drm_opengpu_native_uniform_stage [2]);
#endif
