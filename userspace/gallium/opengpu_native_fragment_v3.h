/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_FRAGMENT_V3_H
#define OPENGPU_NATIVE_FRAGMENT_V3_H
#include "opengpu_native_fragment_v2.h"
#ifdef __cplusplus
extern "C" {
#endif
/* A separate GS-linked compiler contract. V2 remains no-GS and rejects this
 * reflection/container. Requirements describe compilation, never permission. */
#define OPENGPU_FRAGMENT_GEOMETRY_LAYER 128u
#define OPENGPU_FRAGMENT_POINT_COORD 32u
struct opengpu_native_fragment_reflection_v3 {
    uint32_t version,essl_version,linked_geometry,static_inputs,color_types[4];
};
struct opengpu_native_fragment_config_v3 {
    uint32_t thread_capacity,varying_slots,color_slots;
    uint32_t geometry_transport,sample_transport;
};
struct opengpu_native_fragment_program_v3 {
    /* Same numerical layout inventory, but interpreted only after strict v3
     * initialization/validation: geometry owner required, primitive owner0,
     * Layer128 is real GS Layer, not the no-GS v2 constant-zero requirement. */
    struct opengpu_native_fragment_metadata_v2 metadata;
    uint32_t code[512],words;
};
int opengpu_native_fragment_metadata_init_v3(const struct opengpu_native_fragment_reflection_v3 *,
    const struct opengpu_native_fragment_config_v3 *,struct opengpu_native_fragment_metadata_v2 *);
int opengpu_native_fragment_metadata_valid_v3(const struct opengpu_native_fragment_metadata_v2 *);
int opengpu_native_fragment_uniform_compile_v3(const void *,unsigned,
    const struct opengpu_native_fragment_reflection_v3 *,const struct opengpu_native_fragment_config_v3 *,
    struct opengpu_native_fragment_program_v3 *);
int opengpu_native_fragment_read_v3(const char *,struct opengpu_native_fragment_program_v3 *);
int opengpu_native_fragment_write_v3(const char *,const struct opengpu_native_fragment_program_v3 *);
#ifdef __cplusplus
}
#endif
#endif
