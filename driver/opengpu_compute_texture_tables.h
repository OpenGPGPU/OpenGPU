/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_TABLES_H
#define OPENGPU_COMPUTE_TEXTURE_TABLES_H
#include "opengpu_compute_texture_mips.h"
#include "opengpu_compute_texture_format_info.h"

#define OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS 16u
#define OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES (128u * OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS)

/* Kernel-owned, immutable snapshots. Each present entry's levels may be sparse
 * and in any order. The caller must validate and retain the backing allocations,
 * mappings and visibility; these fields are not safe to take from user memory. */
struct opengpu_compute_texture_table_entry {
    bool present;
    struct opengpu_compute_texture_image_config image;
    struct opengpu_compute_texture_sampler_config sampler;
    const struct opengpu_compute_texture_level_config *levels;
    u32 level_count;
};

static inline unsigned long long opengpu_compute_texture_table_bytes(u32 count)
{
    if (!count || count > OPENGPU_COMPUTE_TEXTURE_MAX_BINDINGS)
        return 0;
    return 128ULL + (64ULL + 128ULL + 64ULL +
                     OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES) * count;
}

static inline void opengpu_compute_texture_store_words(unsigned char *dst,
    const u32 *words, u32 count)
{
    for (u32 i = 0; i < count; ++i) {
        dst[4u * i] = (unsigned char)words[i];
        dst[4u * i + 1u] = (unsigned char)(words[i] >> 8);
        dst[4u * i + 2u] = (unsigned char)(words[i] >> 16);
        dst[4u * i + 3u] = (unsigned char)(words[i] >> 24);
    }
}

/* The caller supplies distinct storage and stable entries. All validation is
 * complete before destination writes; table addresses are constructed here,
 * not accepted from the snapshots. Sparse slots stay zero. A valid table does
 * not establish GPU mappings, resource ownership or device-specific limits. */
static inline bool opengpu_build_compute_texture_tables(unsigned char *dst,
    unsigned long long capacity, u32 gpu_base, u32 context, u32 pass,
    u32 generation, const struct opengpu_compute_texture_table_entry *entries,
    u32 count)
{
    unsigned long long bytes = opengpu_compute_texture_table_bytes(count);
    unsigned long long binding_off = 128, image_off, sampler_off, level_off;
    struct opengpu_compute_texture_root_config root;
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS];

    if (!dst || !entries || !bytes || capacity < bytes || (gpu_base & 63u) ||
        (unsigned long long)gpu_base + bytes > (1ULL << 32))
        return false;
    image_off = binding_off + 64ULL * count;
    sampler_off = image_off + 128ULL * count;
    level_off = sampler_off + 64ULL * count;
    root = (struct opengpu_compute_texture_root_config) {
        .context=context, .pass=pass, .generation=generation, .count=count,
        .bindings_base=(u32)(gpu_base + binding_off), .bindings_limit=gpu_base + image_off,
        .images_base=(u32)(gpu_base + image_off), .images_limit=gpu_base + sampler_off,
        .samplers_base=(u32)(gpu_base + sampler_off), .samplers_limit=gpu_base + level_off,
    };
    root.buffer_only=true;
    for (u32 i = 0; i < count; ++i)
        if (entries[i].present && entries[i].image.target != OPENGPU_TEXTURE_BUFFER_TARGET)
            root.buffer_only=false;
    if (root.buffer_only) root.samplers_base=0, root.samplers_limit=0;
    if (!opengpu_encode_compute_texture_root(words, &root)) return false;
    for (u32 i = 0; i < count; ++i) {
        struct opengpu_compute_texture_image_config image;
        struct opengpu_compute_texture_sampler_config sampler;
        u32 seen = 0;
        if (!entries[i].present) continue;
        if (entries[i].level_count > OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS ||
            (entries[i].level_count && !entries[i].levels)) return false;
        image = entries[i].image;
        image.context=context; image.pass=pass; image.generation=generation; image.handle=i;
        image.levels_base=(u32)(gpu_base + level_off +
                         (unsigned long long)i * OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES);
        image.levels_limit=(unsigned long long)image.levels_base + OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES;
        image.first_level_handle=0;
        sampler=entries[i].sampler;
        sampler.context=context; sampler.pass=pass; sampler.generation=generation; sampler.handle=i;
        if (!opengpu_encode_compute_texture_image(words, &image) ||
            (image.target != OPENGPU_TEXTURE_BUFFER_TARGET &&
             !opengpu_encode_compute_texture_sampler(words, &sampler))) return false;
        for (u32 j = 0; j < entries[i].level_count; ++j) {
            struct opengpu_compute_texture_level_config level=entries[i].levels[j];
            if (level.level >= OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS ||
                (seen & (1u << level.level))) return false;
            seen |= 1u << level.level;
            level.context=context; level.pass=pass; level.generation=generation;
            level.handle=level.level;
            if (!opengpu_validate_compute_texture_levels(&image, &level, 1, level.level, false))
                return false;
        }
    }
    for (unsigned long long i = 0; i < bytes; ++i) dst[i] = 0;
    (void)opengpu_encode_compute_texture_root(words, &root);
    opengpu_compute_texture_store_words(dst, words, OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS);
    for (u32 i = 0; i < count; ++i) {
        struct opengpu_compute_texture_image_config image;
        struct opengpu_compute_texture_sampler_config sampler;
        struct opengpu_compute_texture_binding_config binding;
        if (!entries[i].present) continue;
        image=entries[i].image;
        image.context=context; image.pass=pass; image.generation=generation; image.handle=i;
        image.levels_base=(u32)(gpu_base + level_off +
                         (unsigned long long)i * OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES);
        image.levels_limit=(unsigned long long)image.levels_base + OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES;
        image.first_level_handle=0;
        sampler=entries[i].sampler;
        sampler.context=context; sampler.pass=pass; sampler.generation=generation; sampler.handle=i;
        binding=(struct opengpu_compute_texture_binding_config) {
            .context=context, .pass=pass, .generation=generation, .unit=i,
            .image_handle=i, .sampler_handle=i,
            .expected_type=opengpu_compute_texture_formats[image.format].kind, .target=image.target,
        };
        (void)opengpu_encode_compute_texture_binding(words, &binding);
        opengpu_compute_texture_store_words(dst + binding_off + 64ULL*i, words, OPENGPU_COMPUTE_TEXTURE_BINDING_WORDS);
        (void)opengpu_encode_compute_texture_image(words, &image);
        opengpu_compute_texture_store_words(dst + image_off + 128ULL*i, words, OPENGPU_COMPUTE_TEXTURE_IMAGE_WORDS);
        if (image.target != OPENGPU_TEXTURE_BUFFER_TARGET) {
            (void)opengpu_encode_compute_texture_sampler(words, &sampler);
            opengpu_compute_texture_store_words(dst + sampler_off + 64ULL*i, words, OPENGPU_COMPUTE_TEXTURE_SAMPLER_WORDS);
        }
        for (u32 j = 0; j < entries[i].level_count; ++j) {
            struct opengpu_compute_texture_level_config level=entries[i].levels[j];
            level.context=context; level.pass=pass; level.generation=generation; level.handle=level.level;
            (void)opengpu_encode_compute_texture_level(words, &level);
            opengpu_compute_texture_store_words(dst + level_off +
                (unsigned long long)i * OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES +
                128ULL * level.level, words, OPENGPU_COMPUTE_TEXTURE_LEVEL_WORDS);
        }
    }
    return true;
}
#endif
