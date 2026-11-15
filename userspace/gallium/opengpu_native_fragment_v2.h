/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAGMENT_V2_H
#define OPENGPU_NATIVE_FRAGMENT_V2_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Private compiler requirements. None of these structures authorize hardware,
 * advertise a screen capability or replace a kernel-verified resource profile. */
enum opengpu_fragment_builtin_v2 {
    OPENGPU_FRAGMENT_SAMPLE_ID=1u, OPENGPU_FRAGMENT_SAMPLE_POSITION=2u,
    OPENGPU_FRAGMENT_SAMPLE_MASK_IN=4u, OPENGPU_FRAGMENT_FRAG_COORD=8u,
    OPENGPU_FRAGMENT_VARYING=16u, OPENGPU_FRAGMENT_PRIMITIVE_ID=64u,
    OPENGPU_FRAGMENT_LAYER_ZERO=128u, OPENGPU_FRAGMENT_HELPER=256u,
    OPENGPU_FRAGMENT_SAMPLE_QUALIFIER=512u
};
struct opengpu_native_fragment_reflection_v2 {
    uint32_t version, essl_version, linked_geometry, static_inputs;
    uint32_t color_types[4]; /* 0 float, 1 uint, 2 int */
};
struct opengpu_native_fragment_config_v2 {
    uint32_t thread_capacity, varying_slots, color_slots;
    uint32_t primitive_transport, sample_transport; /* prerequisites, not grants */
};
struct opengpu_native_fragment_metadata_v2 {
    uint32_t abi_version, input_arrays, array_stride, prefix_bytes;
    uint32_t used_sysvals, output_color_mask, writes_depth, writes_sample_mask;
    uint32_t requires_sample_owner, requires_geometry_owner;
    uint32_t thread_capacity, varying_slots, color_slots;
    uint32_t requires_primitive_owner, requires_uniform_owner, static_inputs;
    uint64_t varying_mask, flat_mask, centroid_mask;
    uint32_t constant_bytes[13], color_types[4];
};
struct opengpu_native_fragment_program_v2 {
    struct opengpu_native_fragment_metadata_v2 metadata;
    uint32_t code[512], words;
};
int opengpu_native_fragment_metadata_init_v2(
    const struct opengpu_native_fragment_reflection_v2 *,
    const struct opengpu_native_fragment_config_v2 *,
    struct opengpu_native_fragment_metadata_v2 *);
int opengpu_native_fragment_metadata_valid_v2(const struct opengpu_native_fragment_metadata_v2 *);
/* OGNSF002: magic + 42 LE u32 words + raw code; exact EOF. See contract doc.
 * Old OGNSF001 readers remain independent and must reject this container. */
int opengpu_native_fragment_read_v2(const char *, struct opengpu_native_fragment_program_v2 *);
int opengpu_native_fragment_write_v2(const char *, const struct opengpu_native_fragment_program_v2 *);
#ifdef __cplusplus
}
#endif
#endif
