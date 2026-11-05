/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_CS_EMIT_H
#define OPENGPU_CS_EMIT_H

#include <stdint.h>

/* A bounded TGSI uint32 program; this is not a complete compute API profile. */
struct opengpu_cs_program {
    uint32_t code[512];
    unsigned words;
    unsigned reads, writes; /* checked SSBO indices0..3 */
    unsigned fixed_block[3]; /* zero means unspecified */
};

/* No partial output on failure. The token header owns the TGSI stream length. */
int opengpu_cs_emit(const void *tokens, struct opengpu_cs_program *out);

#endif
