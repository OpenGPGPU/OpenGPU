/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_RECORDS_H
#define OPENGPU_COMPUTE_TEXTURE_RECORDS_H
#include "opengpu_compute_features.h"

/* GXR1/GXB1 use the graphics word offsets but decode a two-bit stage in
 * compute. The generated graphics-only ABI describes a one-bit stage. */
#define OPENGPU_COMPUTE_TEXTURE_STAGE 2u
#define OPENGPU_COMPUTE_TEXTURE_STAGE_BITS 2u
#define OPENGPU_COMPUTE_TEXTURE_MAX_BINDINGS 32u
#define OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS 32u
#define OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS 16u

struct opengpu_compute_texture_root_config {
    u32 context, pass, generation, count;
    u32 bindings_base, images_base, samplers_base;
    unsigned long long bindings_limit, images_limit, samplers_limit;
};

struct opengpu_compute_texture_binding_config {
    u32 context, pass, generation, unit, image_handle, sampler_handle;
    u32 expected_type, target;
};

static inline bool opengpu_compute_texture_table_valid(u32 base,
    unsigned long long limit, u32 stride)
{
    return !(base & 63u) && limit > base && limit <= (1ULL << 32) &&
           !((limit - base) & (stride - 1u));
}

/* Values are words; the caller serializes them little-endian and retains the
 * descriptors, backing allocations and VM until the completion is popped. */
static inline bool opengpu_encode_compute_texture_root(
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS],
    const struct opengpu_compute_texture_root_config *r)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS] = {0};
    if (!words || !r || !r->count || r->count > OPENGPU_COMPUTE_TEXTURE_MAX_BINDINGS ||
        !opengpu_compute_texture_table_valid(r->bindings_base, r->bindings_limit, 64) ||
        !opengpu_compute_texture_table_valid(r->images_base, r->images_limit, 128) ||
        !opengpu_compute_texture_table_valid(r->samplers_base, r->samplers_limit, 64) ||
        (unsigned long long)r->bindings_base + 64ULL * r->count > r->bindings_limit)
        return false;
    result[0] = 0x47585201u; result[1] = 128; result[2] = 1;
    result[3] = r->context; result[4] = r->pass; result[5] = r->generation;
    result[6] = OPENGPU_COMPUTE_TEXTURE_STAGE; result[7] = r->count;
    result[8] = r->bindings_base; result[9] = (u32)r->bindings_limit;
    result[10] = (u32)(r->bindings_limit >> 32);
    result[11] = r->images_base; result[12] = (u32)r->images_limit;
    result[13] = (u32)(r->images_limit >> 32);
    result[14] = r->samplers_base; result[16] = (u32)r->samplers_limit;
    result[17] = (u32)(r->samplers_limit >> 32);
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS; ++i)
        words[i] = result[i];
    return true;
}

static inline bool opengpu_encode_compute_texture_binding(
    u32 words[OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS],
    const struct opengpu_compute_texture_binding_config *b)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS] = {0};
    if (!words || !b || b->unit >= OPENGPU_COMPUTE_TEXTURE_MAX_BINDINGS ||
        b->expected_type > 2u || b->target > 3u)
        return false;
    result[0] = 0x47584201u; result[1] = 64; result[2] = 1;
    result[3] = b->context; result[4] = b->pass; result[5] = b->generation;
    result[6] = OPENGPU_COMPUTE_TEXTURE_STAGE; result[7] = b->unit;
    result[8] = b->image_handle; result[9] = b->sampler_handle;
    result[10] = b->expected_type; result[11] = b->target;
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS; ++i)
        words[i] = result[i];
    return true;
}
#endif
