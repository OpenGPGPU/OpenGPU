/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_TESS_UNIFORM_EMIT_H
#define OPENGPU_NATIVE_TESS_UNIFORM_EMIT_H
#include <stdint.h>
#define OPENGPU_NATIVE_TESS_EMIT_VERSION 1u
#ifdef __cplusplus
extern "C" {
#endif
#include "opengpu_native_uniform_emit.h"

struct opengpu_native_tess_layout {
    unsigned version, stage, capacity; /* GPU stage4 TCS /5 TES; actual lanes*warps */
    unsigned input_vertices, output_vertices, input_components, output_components, patch_components;
    unsigned domain, spacing, clockwise, point_mode;
    /* Linked semantic packing: position occupies0..3 when present; remaining
     * generic slots follow. TES input_position is TCS output_position. */
    unsigned input_position, output_position;
};
struct opengpu_native_tess_selection { unsigned per_patch, tess_level, vertex, component; };
struct opengpu_native_tess_program {
    uint32_t code[512]; unsigned words, constant_bytes[13];
    struct opengpu_native_tess_layout layout;
    /* Static TES transport only: component3+i is selection[i]. Dynamic/full
     * patch reads require P40; unsupported shapes fail, never truncate. */
    unsigned selection_count;
    struct opengpu_native_tess_selection selections[61];
    uint64_t input_mask, output_mask; unsigned barriers;
};
/* Real bounded TGSI->native backend, independent of VS/FS SoA and general GS.
 * No ordinary memory access, sampler/image/storage, raw GPU address authority
 * or public feature enable. Outputs unchanged on failure. tokens_words bounds
 * every optional token before Mesa's actual parser reads it. */
int opengpu_native_tess_uniform_reflect(const void *,unsigned tokens_words,unsigned gpu_stage,unsigned capacity,
    unsigned patch_vertices,struct opengpu_native_tess_layout *);
int opengpu_native_vertex_tess_compile(const void *,unsigned capacity,
    struct opengpu_native_uniform_layout *,struct opengpu_native_uniform_program *);
int opengpu_native_tess_uniform_emit(const void *, unsigned tokens_words,
    const struct opengpu_native_tess_layout *, struct opengpu_native_tess_program *);
#ifdef __cplusplus
}
#endif

#endif
