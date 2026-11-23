/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GS_EMIT_H
#define OPENGPU_NATIVE_GS_EMIT_H

#include <stdint.h>
#include "opengpu_native_geometry_abi.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OPENGPU_NATIVE_GS_VERTEX_COMPONENTS (4u + 4u * OPENGPU_NATIVE_GEOMETRY_MAX_VARYING_SLOTS)

struct opengpu_native_gs_program {
    uint32_t code[OPENGPU_NATIVE_GEOMETRY_MAX_CODE_WORDS], words;
    uint32_t input_vertices, invocations, max_vertices, output_topology;
    uint32_t input_components, output_components, builtin_output_mask;
    uint32_t vertex_varying_slots, fragment_varying_slots;
    /* Compact GS input component to position/varying component. */
    uint8_t input_sources[OPENGPU_NATIVE_GEOMETRY_MAX_COMPONENTS];
    /* Position/varying component to compact GS output, or UINT8_MAX. */
    uint8_t output_sources[OPENGPU_NATIVE_GS_VERTEX_COMPONENTS];
};

/* Private scalar GS compiler for direct TGSI moves and stream-zero events.
 * Each emitted vertex must define all declared outputs. A rejected stream
 * leaves *out unchanged. */
int opengpu_native_gs_emit(const void *tokens, unsigned token_words,
    struct opengpu_native_gs_program *out);

/* Encode the emitted program's layout for the inline GNG1 submit field.
 * The protected driver still owns each code and uniform address. */
int opengpu_native_gs_metadata(const struct opengpu_native_gs_program *,
    uint32_t context, uint32_t pass, uint32_t generation, uint64_t flat_mask,
    struct opengpu_native_geometry_program *out);

#ifdef __cplusplus
}
#endif
#endif
