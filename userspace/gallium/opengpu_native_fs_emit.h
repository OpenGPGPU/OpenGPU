/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FS_EMIT_H
#define OPENGPU_NATIVE_FS_EMIT_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Separate from the legacy packed RGBA / nine-array compiler. These flags
 * describe compiler prerequisites, never authorize a device submission. */
struct opengpu_native_fs_config {
    uint32_t abi_version, thread_capacity, varying_slots, color_slots;
    uint32_t sample_transport, geometry_transport;
};
enum opengpu_native_fs_sysval {
    OPENGPU_NATIVE_FS_SAMPLE_ID = 1u,
    OPENGPU_NATIVE_FS_SAMPLE_POSITION = 2u,
    OPENGPU_NATIVE_FS_SAMPLE_MASK_IN = 4u,
    OPENGPU_NATIVE_FS_FRAG_COORD = 8u,
    OPENGPU_NATIVE_FS_VARYING = 16u,
    OPENGPU_NATIVE_FS_GEOMETRY = 32u
};
/* Raw output/control ABI is exactly abi_version (6 or 7): four raw words per
 * MRT, depth, then control (emit/depth/color/maskWritten/sampleMask). No GUR
 * bank is implied by prefix_bytes; resources/uniforms are currently rejected. */
struct opengpu_native_fs_metadata {
    uint32_t abi_version, input_arrays, array_stride, prefix_bytes;
    uint32_t used_sysvals, output_color_mask, writes_depth, writes_sample_mask;
    uint32_t requires_sample_owner, requires_geometry_owner;
    uint32_t thread_capacity, varying_slots, color_slots;
};
struct opengpu_native_fs_program {
    struct opengpu_native_fs_metadata metadata;
    uint32_t code[512], words;
};
/* Bounded original Mesa TGSI stream; unsupported instructions/resources/flow
 * fail without changing *out. Capacity is the full 128/16/4 contract. Generic
 * inputs currently require smooth/center interpolation and native coordinates
 * require lower-left/half-integer convention; no qualifier conversion occurs. */
int opengpu_native_fs_emit(const void *tokens, unsigned token_words,
    const struct opengpu_native_fs_config *, struct opengpu_native_fs_program *out);
/* Portable qualification artifact: OGNSF001, fifteen LE u32 fields, raw code,
 * exact EOF. It conveys compiler output, not hardware qualification. */
int opengpu_native_fs_write(const char *path, const struct opengpu_native_fs_program *);
#ifdef __cplusplus
}
#endif
#endif
