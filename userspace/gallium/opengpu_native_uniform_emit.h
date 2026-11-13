/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_UNIFORM_EMIT_H
#define OPENGPU_NATIVE_UNIFORM_EMIT_H
#include <stdint.h>
/* Internal native SoA ABI v1. This is separate from the legacy draw sandbox. */
struct opengpu_native_uniform_layout {
    unsigned stage; /* 0 vertex, 1 fragment */
    unsigned capacity, attributes, varyings, colors;
};
struct opengpu_native_uniform_program {
    uint32_t code[512];
    unsigned words, constant_bytes[13]; /* default, then logical UBOs 0..11 */
    uint64_t varying_mask;
    unsigned color_mask;
};
/* Straight-line raw MOV and unsigned word ALU. Unsupported shapes fail without
 * changing *out; this entry point neither advertises nor enables an API. */
int opengpu_native_uniform_emit(const void *tokens,
    const struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out);
#endif
