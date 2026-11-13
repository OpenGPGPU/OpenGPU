/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_DRAW_H
#define OPENGPU_NATIVE_DRAW_H
#include "opengpu_native_graphics.h"
#include "pipe_opengpu.h"
#include "opengpu_typed_fragment_state_abi.h"

struct opengpu_native_draw_attribute {
    unsigned enabled, binding, components, format, offset;
    uint32_t constant[4];
};
struct opengpu_native_draw_binding {
    struct pipe_opengpu_uniform_view view;
    unsigned stride, divisor;
};
struct opengpu_native_draw_request {
    struct pipe_context *context;
    const struct opengpu_native_graphics_sources *sources;
    unsigned pass, generation, draw_index, state_handle, state_count, token;
    unsigned width, height, sample_mode, topology;
    unsigned count, instances, first_vertex, base_instance;
    struct opengpu_native_draw_attribute attributes[16];
    struct opengpu_native_draw_binding bindings[16];
    struct pipe_opengpu_uniform_view vertex_scratch;
    /* Original linked Mesa/NIR output types: 0 float, 1 uint, 2 sint. TGSI raw
     * word operations do not retain GLSL output type, so it must be reflected
     * before NTT, rather than inferred from an opcode or framebuffer format. */
    unsigned color_types[4];
};
struct opengpu_native_draw_encoding {
    struct opengpu_native_binned_draw draw;
    struct opengpu_typed_fragment_state state;
    struct drm_opengpu_native_draw_plan plan;
};

/* Ordinary unindexed native draw, full-frame viewport, no blend/late tests,
 * flat generic varyings. All sixteen wire input slots remain available.
 * Original GEM windows are plans; every device address remains canonical zero
 * for the protected kernel producer to reconstruct. No submit/ownership occurs.
 * Failure leaves output unchanged. */
int opengpu_native_draw_encode(const struct opengpu_native_draw_request *,
    struct opengpu_native_draw_encoding *);
int opengpu_native_job_encode(unsigned context, unsigned pass, unsigned generation,
    unsigned draw_count, struct opengpu_native_binned_job *);
#endif
