/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_NATIVE_SAMPLE_GRAPHICS_H
#define OPENGPU_GALLIUM_NATIVE_SAMPLE_GRAPHICS_H
#include "opengpu_native_sample_graphics.h"
#include "opengpu_native_draw.h"
/* Private context bootstrap only. Pass actual OGNP0002 observations in a real
 * bridge; synthetic CPU fixtures are explicitly not discovery evidence. Public
 * screen flags and old native runtime/query methods remain unchanged. */
int opengpu_native_sample_context_bootstrap(struct pipe_context *,const struct opengpu_native_sample_runtime *);
int opengpu_native_sample_context_runtime(struct pipe_context *,struct opengpu_native_sample_runtime *,unsigned *);
struct opengpu_native_graphics_program *opengpu_native_sample_program_create(struct pipe_context *,const void *,const void *);
int opengpu_native_sample_program_sources(struct pipe_context *,const struct opengpu_native_graphics_program *,
    struct opengpu_native_graphics_sources *);
int opengpu_native_sample_draw_encode(const struct opengpu_native_draw_request *,struct opengpu_native_draw_encoding *);
struct opengpu_native_sample_submit_sources {
    const struct opengpu_native_sample_runtime *runtime;
    const struct opengpu_native_sample_job_encoding *job;
    const struct opengpu_native_frame_encoding *frame;
    const struct opengpu_native_binned_draw *draws;
    const struct opengpu_typed_fragment_state *states;
    const struct drm_opengpu_native_layered_draw_plan *plans;
    unsigned draw_count,state_count,in_syncobj,out_syncobj;
};
int opengpu_native_sample_graphics_submit(struct pipe_context *,const struct opengpu_native_sample_submit_sources *,uint64_t *);
#endif
