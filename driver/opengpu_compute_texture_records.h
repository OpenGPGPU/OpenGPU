/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_RECORDS_H
#define OPENGPU_COMPUTE_TEXTURE_RECORDS_H
#include "opengpu_compute_features.h"
#include "opengpu_texture_image_format_abi.h"

/* GXR1/GXB1 use the graphics word offsets but decode a two-bit stage in
 * compute. The generated graphics-only ABI describes a one-bit stage. */
#define OPENGPU_COMPUTE_TEXTURE_STAGE 2u
#define OPENGPU_COMPUTE_TEXTURE_STAGE_BITS 2u
#define OPENGPU_COMPUTE_TEXTURE_MAX_BINDINGS 32u
#define OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS 32u
#define OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS 16u
#define OPENGPU_COMPUTE_TEXTURE_PACKET_WORDS 16u
#define OPENGPU_COMPUTE_TEXTURE_SAMPLER_WORDS 16u
#define OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS 32u
#define OPENGPU_COMPUTE_TEXTURE_LEVEL_WORDS 32u

struct opengpu_compute_texture_root_config {
    u32 context, pass, generation, count;
    u32 bindings_base, images_base, samplers_base;
    unsigned long long bindings_limit, images_limit, samplers_limit;
    /* These hints allow an absent table; selected nonbuffer fetches still
     * require their sampler in the snapshot/RTL consumer. Not wire fields. */
    bool size_only;
    bool buffer_only;
};

struct opengpu_compute_texture_binding_config {
    u32 context, pass, generation, unit, image_handle, sampler_handle;
    u32 expected_type, target;
};

struct opengpu_compute_texture_packet_config {
    u32 context, pass, generation, x, y, z, layer, face, level;
};

struct opengpu_compute_texture_sampler_config {
    u32 context, pass, generation, handle, min_filter, mag_filter;
    u32 wrap_s, wrap_t, wrap_r, min_lod_bits, max_lod_bits, compare_function;
    bool compare_enable;
};

struct opengpu_compute_texture_image_config {
    u32 context, pass, generation, handle, target, format, base_level, max_level;
    u32 width, height, depth, layers, allocation_base, levels_base, first_level_handle;
    unsigned long long allocation_limit, levels_limit;
    u32 swizzle_version, swizzle[4];
    u32 buffer_texels;
    unsigned long long buffer_bytes;
};

struct opengpu_compute_texture_level_config {
    u32 context, pass, generation, handle, target, level, allocation_base, base;
    u32 width, height, depth, layers, block_bytes;
    unsigned long long allocation_limit, row_pitch, slice_pitch, layer_pitch;
    bool block_4x4;
    u32 buffer_texels;
    unsigned long long buffer_bytes;
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
        !(((r->size_only || r->buffer_only) && !r->samplers_base && !r->samplers_limit) ||
          opengpu_compute_texture_table_valid(r->samplers_base, r->samplers_limit, 64)) ||
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
        b->expected_type > 2u || (b->target > 3u && b->target != OPENGPU_TEXTURE_BUFFER_TARGET))
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

/* Coordinates remain full-width until image/target admission in hardware. */
static inline bool opengpu_encode_compute_texture_packet(
    u32 words[OPENGPU_COMPUTE_TEXTURE_PACKET_WORDS],
    const struct opengpu_compute_texture_packet_config *p)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_PACKET_WORDS] = {0};
    if (!words || !p || p->face > 7u)
        return false;
    result[0] = 0x47585101u; result[1] = 64; result[2] = 1;
    result[3] = p->context; result[4] = p->pass; result[5] = p->generation;
    result[6] = p->x; result[7] = p->y; result[8] = p->z;
    result[9] = p->layer; result[10] = p->face; result[11] = p->level;
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_PACKET_WORDS; ++i)
        words[i] = result[i];
    return true;
}

static inline bool opengpu_compute_texture_lod_is_nan(u32 bits)
{
    return (bits & 0x7f800000u) == 0x7f800000u && (bits & 0x007fffffu);
}

/* Infinities and reversed clamps are legal; format compatibility is checked
 * when the selected image and sampler are used together. */
static inline bool opengpu_encode_compute_texture_sampler(
    u32 words[OPENGPU_COMPUTE_TEXTURE_SAMPLER_WORDS],
    const struct opengpu_compute_texture_sampler_config *s)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_SAMPLER_WORDS] = {0};
    if (!words || !s || s->min_filter > 5u || s->mag_filter > 1u ||
        s->wrap_s > 2u || s->wrap_t > 2u || s->wrap_r > 2u ||
        s->compare_function > 7u || opengpu_compute_texture_lod_is_nan(s->min_lod_bits) ||
        opengpu_compute_texture_lod_is_nan(s->max_lod_bits))
        return false;
    result[0] = 0x47585301u; result[1] = 64; result[2] = 1;
    result[3] = s->context; result[4] = s->pass; result[5] = s->generation;
    result[6] = s->handle; result[7] = s->min_filter; result[8] = s->mag_filter;
    result[9] = s->wrap_s; result[10] = s->wrap_t; result[11] = s->wrap_r;
    result[12] = s->min_lod_bits; result[13] = s->max_lod_bits;
    result[14] = s->compare_enable ? 1u : 0u; result[15] = s->compare_function;
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_SAMPLER_WORDS; ++i)
        words[i] = result[i];
    return true;
}

/* This checks the fixed image header and the first selectable level record.
 * Per-level dimensions, spans, mip completeness and sampler compatibility
 * remain subject to their hardware admission checks. */
static inline bool opengpu_encode_compute_texture_image(
    u32 words[OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS],
    const struct opengpu_compute_texture_image_config *image)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS] = {0};
    unsigned long long first, end;
    bool volume, array, cube, buffer;
    if (!words || !image || (image->target > 3u && image->target != OPENGPU_TEXTURE_BUFFER_TARGET) || image->format > 63u ||
        image->base_level > 15u || image->max_level > 15u ||
        image->max_level < image->base_level || image->swizzle_version > 1u)
        return false;
    volume = image->target == 3u; array = image->target == 1u; cube = image->target == 2u;
    buffer = image->target == OPENGPU_TEXTURE_BUFFER_TARGET;
    if (buffer ? (!opengpu_texture_buffer_format_bytes(image->format) ||
            image->width != 1u || image->height != 1u || image->depth != 1u || image->layers != 1u ||
            image->base_level || image->max_level || !image->buffer_texels ||
            image->buffer_texels > OPENGPU_TEXTURE_BUFFER_MAX_TEXELS || !image->buffer_bytes ||
            image->buffer_bytes > (1ULL << 32)) : (image->buffer_texels || image->buffer_bytes))
        return false;
    /* The current image ABI places depth and compressed formats at 49..63;
     * neither class admits a 3D target. */
    if ((volume && image->format >= 49u) || !image->width || !image->height ||
        !image->depth || !image->layers || image->width > 16383u ||
        image->height > 16383u || image->depth > 16383u ||
        (!volume && image->depth != 1u) || (array ? image->layers > 65535u : image->layers != 1u) ||
        (cube && image->width != image->height) ||
        image->allocation_limit <= image->allocation_base || image->allocation_limit > (1ULL << 32) ||
        !opengpu_compute_texture_table_valid(image->levels_base, image->levels_limit, 128))
        return false;
    for (unsigned i = 0; i < 4; ++i)
        if (image->swizzle_version ? image->swizzle[i] > 5u : image->swizzle[i] != 0)
            return false;
    first = (unsigned long long)image->first_level_handle + image->base_level;
    end = (unsigned long long)image->levels_base + first * 128ULL + 128ULL;
    if (first > 0xffffffffULL || end > image->levels_limit)
        return false;
    result[0] = 0x47584901u; result[1] = 128; result[2] = 1;
    result[3] = image->context; result[4] = image->pass; result[5] = image->generation;
    result[6] = image->handle; result[7] = image->target; result[8] = image->format;
    result[9] = image->base_level; result[10] = image->max_level;
    result[11] = image->width; result[12] = image->height; result[13] = image->depth; result[14] = image->layers;
    result[15] = image->allocation_base; result[16] = (u32)image->allocation_limit;
    result[17] = (u32)(image->allocation_limit >> 32); result[18] = 1;
    result[19] = image->levels_base; result[20] = (u32)image->levels_limit;
    result[21] = (u32)(image->levels_limit >> 32); result[22] = image->first_level_handle;
    result[23] = image->swizzle_version;
    for (unsigned i = 0; i < 4; ++i) result[24 + i] = image->swizzle[i];
    result[28] = image->buffer_texels; result[29] = (u32)image->buffer_bytes;
    result[30] = (u32)(image->buffer_bytes >> 32);
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS; ++i) words[i] = result[i];
    return true;
}

/* Prove the full level span using wide arithmetic. Device-specific dimensions,
 * image/level consistency and overlap between levels are checked separately. */
static inline bool opengpu_encode_compute_texture_level(
    u32 words[OPENGPU_COMPUTE_TEXTURE_LEVEL_WORDS],
    const struct opengpu_compute_texture_level_config *level)
{
    u32 result[OPENGPU_COMPUTE_TEXTURE_LEVEL_WORDS] = {0};
    unsigned long long columns, rows, planes, slices, row_bytes, plane_span, end;
    bool array, cube, volume, packed_bytes, buffer;
    if (!words || !level || (level->target > 3u && level->target != OPENGPU_TEXTURE_BUFFER_TARGET) || level->level > 15u ||
        !level->width || !level->height || !level->depth || !level->layers ||
        level->width > 16383u || level->height > 16383u || level->depth > 16383u ||
        level->layers > 65535u || level->allocation_limit > (1ULL << 32) ||
        level->row_pitch >= (1ULL << 33) || level->slice_pitch >= (1ULL << 33) ||
        level->layer_pitch >= (1ULL << 33))
        return false;
    array = level->target == 1u; cube = level->target == 2u; volume = level->target == 3u;
    buffer = level->target == OPENGPU_TEXTURE_BUFFER_TARGET;
    packed_bytes = level->block_bytes == 1u || level->block_bytes == 2u ||
        level->block_bytes == 3u || level->block_bytes == 4u || level->block_bytes == 6u ||
        level->block_bytes == 8u || level->block_bytes == 12u || level->block_bytes == 16u;
    if ((!volume && level->depth != 1u) || (!array && level->layers != 1u) ||
        (cube && level->width != level->height) || (!volume && level->slice_pitch) ||
        (!(array || cube) && level->layer_pitch) ||
        (level->block_4x4 ? (level->block_bytes != 8u && level->block_bytes != 16u) : !packed_bytes) ||
        level->allocation_limit <= level->allocation_base || level->base < level->allocation_base)
        return false;
    if (buffer) {
        unsigned long long texel_bytes = (unsigned long long)level->buffer_texels * level->block_bytes;
        if (level->level || level->width != 1u || level->height != 1u || level->depth != 1u ||
            level->layers != 1u || level->row_pitch || level->slice_pitch || level->layer_pitch || level->block_4x4 ||
            (level->block_bytes != 1u && level->block_bytes != 2u && level->block_bytes != 4u &&
             level->block_bytes != 8u && level->block_bytes != 12u && level->block_bytes != 16u) ||
            !level->buffer_texels || level->buffer_texels > OPENGPU_TEXTURE_BUFFER_MAX_TEXELS ||
            !level->buffer_bytes || level->buffer_bytes > (1ULL << 32) ||
            texel_bytes > level->buffer_bytes ||
            (level->buffer_texels < OPENGPU_TEXTURE_BUFFER_MAX_TEXELS &&
             texel_bytes + level->block_bytes <= level->buffer_bytes))
            return false;
        end = (unsigned long long)level->base + level->buffer_bytes;
        if (end > level->allocation_limit) return false;
    } else {
        if (level->buffer_texels || level->buffer_bytes) return false;
        columns = level->block_4x4 ? (level->width + 3u) / 4u : level->width;
        rows = level->block_4x4 ? (level->height + 3u) / 4u : level->height;
        planes = cube ? 6u : (array ? level->layers : 1u);
        slices = volume ? level->depth : 1u;
        row_bytes = columns * level->block_bytes;
        plane_span = (rows - 1u) * level->row_pitch + row_bytes;
        if (level->row_pitch < row_bytes ||
            ((volume || array || cube) && (volume ? level->slice_pitch : level->layer_pitch) < plane_span))
            return false;
        end = (unsigned long long)level->base + plane_span +
              (slices - 1u) * level->slice_pitch + (planes - 1u) * level->layer_pitch;
        if (end > level->allocation_limit)
            return false;
    }
    result[0] = 0x47584c01u; result[1] = 128; result[2] = 1;
    result[3] = level->context; result[4] = level->pass; result[5] = level->generation;
    result[6] = level->handle; result[7] = level->target; result[8] = level->level;
    result[9] = level->allocation_base; result[10] = (u32)level->allocation_limit;
    result[11] = (u32)(level->allocation_limit >> 32); result[12] = level->base;
    result[13] = level->width; result[14] = level->height; result[15] = level->depth; result[16] = level->layers;
    result[17] = (u32)level->row_pitch; result[18] = (u32)(level->row_pitch >> 32);
    result[19] = (u32)level->slice_pitch; result[20] = (u32)(level->slice_pitch >> 32);
    result[21] = (u32)level->layer_pitch; result[22] = (u32)(level->layer_pitch >> 32);
    result[23] = level->block_4x4 ? 1u : 0u; result[24] = level->block_bytes;
    result[25] = level->buffer_texels; result[26] = (u32)level->buffer_bytes;
    result[27] = (u32)(level->buffer_bytes >> 32);
    for (unsigned i = 0; i < OPENGPU_COMPUTE_TEXTURE_LEVEL_WORDS; ++i) words[i] = result[i];
    return true;
}
#endif
