/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_BUILDER_H
#define OPENGPU_SHADER_BUILDER_H

#include "opengpu_shader_validator.h"

/* Form a bounded x1/warp-relative address using signed 12-bit additions.
 * Chaining aligned positive chunks preserves the validator's provenance;
 * truncating a wide SoA offset into one ADDI would address before the bank. */
static inline opengpu_shader_u32 opengpu_shader_append_address(
    opengpu_shader_u32 *program, opengpu_shader_u32 count,
    opengpu_shader_u32 base, opengpu_shader_u32 rd,
    opengpu_shader_u32 offset)
{
    do {
        opengpu_shader_u32 step = offset > 2044u ? 2044u : offset;

        program[count++] = step << 20 | base << 15 | rd << 7 | 0x13u;
        offset -= step;
        base = rd;
    } while (offset);
    return count;
}

/* Build the boot self-test's per-warp fragment colour copy. At the largest
 * advertised batch (252 complete-quad fragments), at most 11 words suffice.
 * The caller supplies one 64-byte shader buffer. */
static inline opengpu_shader_u32 opengpu_fragment_passthrough_shader(
    opengpu_shader_u32 *program, opengpu_shader_u32 capacity,
    opengpu_shader_u32 batch, opengpu_shader_u32 lanes)
{
    opengpu_shader_u32 count = 0, stride = 4u * batch;

    if (!program || capacity < 16u || !batch || batch > 255u ||
        (batch & 3u) || !lanes || lanes > 31u || batch % lanes)
        return 0;
    program[count++] = 0x00241293u; /* slli x5,x8,2 */
    program[count++] = 0x005082b3u; /* add x5,x1,x5 */
    program[count++] = 0xc1007057u | lanes << 15;
    count = opengpu_shader_append_address(program, count, 5, 6, 3u * stride);
    program[count++] = 0x02036107u; /* vle32.v v2,(x6) */
    count = opengpu_shader_append_address(program, count, 5, 6, 6u * stride);
    program[count++] = 0x02036127u; /* vse32.v v2,(x6) */
    program[count++] = OPENGPU_SHADER_CEASE;
    return count;
}

#endif
