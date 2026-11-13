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
    unsigned counter_reads, counter_writes; /* independent HW counter binding0 */
    unsigned storage_atomics; /* checked RMW bindings0..3; requires discovery */
    unsigned counter_bytes; /* required endpoint within the retained counter view */
};

/* No partial output on failure. The token header owns the TGSI stream length. */
int opengpu_cs_emit(const void *tokens, struct opengpu_cs_program *out);

/* Explicit discovered profile for the bounded, full-warp shared uint32 path.
 * required_bytes comes from Mesa's actual shared allocation, not a device
 * default.  The legacy entry point and opengpu_cs_program layout stay intact. */
struct opengpu_cs_shared {
    uint32_t base, bytes, lanes, required_bytes;
};
int opengpu_cs_emit_shared(const void *tokens,
    const struct opengpu_cs_shared *shared, struct opengpu_cs_program *out);

#endif
