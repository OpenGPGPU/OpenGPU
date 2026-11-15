/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GEOMETRY_EMIT_H
#define OPENGPU_NATIVE_GEOMETRY_EMIT_H
#include "opengpu_native_tess_uniform_emit.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Private scalar GS program dialect1. Compiler requirements are not verified
 * provider permission. Counts include position and applicable builtins. */
struct opengpu_native_geometry_layout {
    unsigned version,input_primitive,input_vertices,invocations;
    unsigned output_topology,max_vertices,input_components,output_components;
    unsigned builtin_output_mask,input_varyings,output_varyings;
    unsigned input_sources[64];
    struct {unsigned valid,source;} output_sources[68];
};
struct opengpu_native_geometry_program {
    struct opengpu_native_geometry_layout layout;
    uint32_t code[512];unsigned words,constant_bytes[13];
};
/* Original linked NIR spans preserve array boundaries. No ordinary memory,
 * texture/image/SSBO authority. Outputs remain unchanged on any failure. */
int opengpu_native_geometry_compile(const void *tokens,unsigned token_words,
    const struct opengpu_native_tess_input_map *input,
    const struct opengpu_native_tess_input_map *output,
    struct opengpu_native_geometry_program *);
#ifdef __cplusplus
}
#endif
#endif
