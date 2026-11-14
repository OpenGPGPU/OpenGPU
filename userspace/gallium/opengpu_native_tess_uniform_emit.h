/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_TESS_UNIFORM_EMIT_H
#define OPENGPU_NATIVE_TESS_UNIFORM_EMIT_H
#include <stdint.h>
#define OPENGPU_NATIVE_TESS_EMIT_VERSION 1u
#ifdef __cplusplus
extern "C" {
#endif
#include "opengpu_native_uniform_emit.h"

struct opengpu_native_tess_patch_layout {
    unsigned input_vertices,input_components,output_vertices,output_components,patch_components;
};
/* Original linked NIR variable boundaries, before nir_to_tgsi erases ArrayID.
 * Per-vertex's outer vertex array is not part of slots. Matrix columns and
 * user arrays are. This is compiler semantics, never a kernel permission. */
struct opengpu_native_tess_input_span {
    unsigned first,slots,per_vertex,semantic,index,mask,compact;
};
struct opengpu_native_tess_input_map {
    unsigned version,count;
    struct opengpu_native_tess_input_span spans[64];
};

struct opengpu_native_tess_layout {
    unsigned version, stage, capacity; /* GPU stage4 TCS /5 TES; actual lanes*warps */
    unsigned input_vertices, output_vertices, input_components, output_components, patch_components;
    unsigned domain, spacing, clockwise, point_mode;
    /* Linked semantic packing: position occupies0..3 when present; remaining
     * generic slots follow. TES input_position is TCS output_position. */
    unsigned input_position, output_position;
    /* Version1 requires these fields zero. Version2 is TES-only. */
    struct opengpu_native_tess_patch_layout tes_patch_layout;
    unsigned owned_patch_reads;
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
    /* Owned copy of the actual compiler variable map, v2 only. */
    struct opengpu_native_tess_input_map input_map;
    struct opengpu_native_tess_input_map output_map;
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
int opengpu_native_tess_dynamic_reflect(const void *,unsigned tokens_words,unsigned capacity,
    const struct opengpu_native_tess_layout *control,struct opengpu_native_tess_layout *);
int opengpu_native_tess_dynamic_emit(const void *,unsigned tokens_words,
    const struct opengpu_native_tess_layout *,const struct opengpu_native_tess_input_map *,
    struct opengpu_native_tess_program *);
int opengpu_native_tess_control_emit(const void *,unsigned tokens_words,
    const struct opengpu_native_tess_layout *,const struct opengpu_native_tess_input_map *,
    struct opengpu_native_tess_program *);
#ifdef __cplusplus
}
#endif

#endif
