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
    unsigned constant_bytes[13]; /* static byte endpoints: defaults, then UBOs */
    uint32_t textures; /* statically indexed buffer sampler views, including TXQ */
    unsigned texture_kind[32]; /* native float0/uint1/sint2 return class */
    unsigned image_reads, image_writes, image_queries; /* buffer image indices0..3 */
    unsigned image_format[4]; /* native format qualifier; separate13-format policy */
};

/* No partial output on failure. The token header owns the TGSI stream length. */
int opengpu_cs_emit(const void *tokens, struct opengpu_cs_program *out);

#endif
