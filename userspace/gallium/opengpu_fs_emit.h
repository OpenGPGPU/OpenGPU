/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FS_EMIT_H
#define OPENGPU_FS_EMIT_H

#include <stdint.h>

/* A fragment program lowered onto the shader core. Constants occupy
 * kernarg bytes 288 onward, one vec4 at a time; immediates follow them. */
struct opengpu_fs_alu {
    uint32_t code[256];
    unsigned words;
    unsigned nconst;
    float imm[8];
    unsigned nimm;
    unsigned samples;
    int sample_sem;
    int sample_sem_index;
    /* Constants past the eight-float tail are lui/addi/fmv slots. patch_at
     * is the lui word; patch_src is the float index in the constant buffer.
     * The draw fills the slot before the runtime ABI lowerer runs. */
    unsigned patch_n;
    unsigned patch_at[4];
    unsigned patch_src[4];
};

/* Rewrite one three-word immediate slot at code[at]. */
void opengpu_fs_patch_immediate(uint32_t *code, unsigned at, float val);

/* tokens is a TGSI token stream. Returns 0 when the program fits in the
 * 256-instruction sandbox. samples is 1 when the program issues
 * vtex.sample; sample_sem is the texcoord input that supplies U and V.
 * Immediates are folded into the instruction stream, so nimm stays 0 and
 * the uniform block is only the declared constants. */
int opengpu_fs_emit(const void *tokens, struct opengpu_fs_alu *out);

/* The decision create_fs_state makes. Returns 0 when the fragment menu
 * misses and the ALU emitter produced code. Returns 1 when the menu
 * matched. Returns -1 when neither path accepts the program. On 0, code
 * receives the emitter words before the runtime ABI lowerer. */
int opengpu_fragment_lower_kind(const void *tokens, uint32_t *code,
                                unsigned capacity, unsigned *words);

#endif
