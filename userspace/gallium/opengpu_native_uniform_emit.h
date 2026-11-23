/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_UNIFORM_EMIT_H
#define OPENGPU_NATIVE_UNIFORM_EMIT_H
#include <stdint.h>
#include "opengpu_native_fragment_v2.h"
/* Internal native SoA ABI v1. This is separate from the legacy draw sandbox. */
struct opengpu_native_uniform_layout {
    unsigned stage; /* 0 vertex, 1 fragment */
    unsigned capacity, attributes, varyings, colors;
};
struct opengpu_native_uniform_program {
    uint32_t code[512];
    unsigned words, constant_bytes[13]; /* default, then logical UBOs 0..11 */
    uint64_t varying_mask; /* VS written components; FS actually read components */
    unsigned color_mask;
};
/* Straight-line raw MOV and unsigned word ALU. Unsupported shapes fail without
 * changing *out; this entry point neither advertises nor enables an API. */
int opengpu_native_uniform_emit(const void *tokens,
    const struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *out);
/* Infer the native v1 interface from actual TGSI declarations, then emit.
 * Capacity must come from verified native layout discovery. Failure changes
 * neither output; no fixed one-attribute/one-varying layout is assumed. */
int opengpu_native_uniform_compile(const void *tokens, unsigned stage,
    unsigned capacity, struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *program);
/* Explicit VS-to-GS path with FP32 add in the native vertex SoA layout. */
int opengpu_native_vertex_geometry_compile(const void *tokens, unsigned token_words,
    unsigned capacity, struct opengpu_native_uniform_layout *layout,
    struct opengpu_native_uniform_program *program);
/* Independent FS8/9 entry; old VS/FS1 API and word streams are unchanged. */
int opengpu_native_fragment_uniform_compile_v2(const void *tokens,unsigned token_words,
    const struct opengpu_native_fragment_reflection_v2 *,const struct opengpu_native_fragment_config_v2 *,
    struct opengpu_native_fragment_program_v2 *);
#endif
