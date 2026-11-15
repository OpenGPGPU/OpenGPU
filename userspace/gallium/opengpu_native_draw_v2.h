/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_DRAW_V2_H
#define OPENGPU_NATIVE_DRAW_V2_H
#include "opengpu_native_graphics_v2.h"
/* Separate request: no caller FS metadata, color types or borrowed sources.
 * Full original view/attribute fields retain their old checked interpretation. */
struct opengpu_native_draw_request_v2 {
    struct pipe_context *context;
    const struct opengpu_native_graphics_source_v2 *source;
    unsigned pass,generation,draw_index,state_handle,state_count,token;
    unsigned width,height,sample_mode,topology;
    unsigned count,instances,first_vertex,base_instance;
    struct opengpu_native_draw_attribute attributes[16];
    struct opengpu_native_draw_binding bindings[16];
    struct pipe_opengpu_uniform_view vertex_scratch;
};
struct opengpu_native_draw_encoding_v2 {
    struct opengpu_native_binned_draw draw;
    struct opengpu_typed_fragment_state state;
    struct drm_opengpu_native_layered_draw_plan plan;
    unsigned fragment_prefix_bytes;
};
/* Direct canonical GND1/GFS1 with fragment ABI8/9. Source VAs/PCs/roots and
 * GS extension stay zero. Does not submit, authorize ABI8/9 or hold input and
 * scratch data; caller retains those views until protected producer capture.
 * Output is unchanged on error. */
int opengpu_native_draw_encode_v2(const struct opengpu_native_draw_request_v2 *,
    struct opengpu_native_draw_encoding_v2 *);
#endif
