/* SPDX-License-Identifier: MIT */
/* Vertex program the Gallium menu does not classify: position x is the
 * Q16.16 input plus the integer at kernarg byte 512, and the other seven
 * slices are copied. create_vs_state lowers it through opengpu_vs_emit.
 * These are the emitter words, before the runtime ABI lowerer. A zero
 * addend leaves the triangle on the vertex-buffer positions. */
#ifndef OPENGPU_VERTEX_ALU_H
#define OPENGPU_VERTEX_ALU_H

#include <stdint.h>

#define OPENGPU_VERTEX_ALU_VADD 0x02864457u
#define OPENGPU_VERTEX_ALU_LW 0x2000a603u

static const uint32_t opengpu_vertex_alu[] = {
    0x00241513u, 0x00150533u, 0x2000a603u, 0xc1027057u,
    0x02056407u, 0x02864457u, 0x10050593u, 0x0205e427u,
    0x02050593u, 0x0205e407u, 0x12050593u, 0x0205e427u,
    0x04050593u, 0x0205e407u, 0x14050593u, 0x0205e427u,
    0x06050593u, 0x0205e407u, 0x16050593u, 0x0205e427u,
    0x08050593u, 0x0205e407u, 0x18050593u, 0x0205e427u,
    0x0a050593u, 0x0205e407u, 0x1a050593u, 0x0205e427u,
    0x0c050593u, 0x0205e407u, 0x1c050593u, 0x0205e427u,
    0x0e050593u, 0x0205e407u, 0x1e050513u, 0x02056427u,
    0x30500073u,
};

#define OPENGPU_VERTEX_ALU_WORDS \
    (sizeof(opengpu_vertex_alu) / sizeof(opengpu_vertex_alu[0]))

#endif
