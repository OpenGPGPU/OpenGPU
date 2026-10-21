/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_VS_EMIT_H
#define OPENGPU_VS_EMIT_H

#include <stdint.h>

/* A vertex program the fixed matchers do not accept. The words copy the
 * eight input slices to outputs 8..15, the same schedule as
 * vertex_passthrough, and vadd.vx CONST[0][0].x onto position x. The
 * fixed vertex format is already Q16.16, so that constant is the integer
 * addend at kernarg byte 512. A zero leaves the triangle where the
 * vertex buffer put it. */
struct opengpu_vs_alu {
    uint32_t code[64];
    unsigned words;
    int pos_src;
    int color_src;
    int color_sem;
    int color_sem_index;
};

/* tokens is a TGSI token stream. Returns 0 when the program is the
 * position-x add described above. */
int opengpu_vs_emit(const void *tokens, struct opengpu_vs_alu *out);

/* The decision create_vs_state makes. Returns 0 when the vertex menu
 * misses and this emitter produced code. Returns 1 when the menu matched
 * vertex_passthrough, vertex_mvp, or vertex_mvp2. Returns 2 when the
 * menu matched a position that is CONST[0][4] rather than an attribute;
 * vertex_mvp still multiplies it. Returns -1 when neither path accepts
 * the program. On 0, code receives the emitter words before the runtime
 * ABI lowerer. */
int opengpu_vertex_lower_kind(const void *tokens, uint32_t *code,
                              unsigned capacity, unsigned *words);

/* Copy CONST[0][4] into the four floats vertex_mvp multiplies. The
 * constant buffer is counted in floats from CONST[0][0]. Returns -1
 * when that fifth vec4 is absent. */
int opengpu_stage_const_position(const float *uniform, unsigned nfloat,
                                 float staged[4]);

#endif
