/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_TEXTURE_BOUND_VIEW_H
#define OPENGPU_COMPUTE_TEXTURE_BOUND_VIEW_H
#include "opengpu_compute_texture_tables.h"

/* Resolve a copied texture view against one already-authorized GPU binding.
 * levels[i].base is an offset from binding_base; allocation and identity fields
 * in the inputs are ignored. Inputs and outputs must be stable and disjoint.
 * This checks address arithmetic and layout, not GEM ownership, cache state,
 * aliases or lifetime. The caller must establish those separately. */
static inline bool opengpu_compute_texture_bound_view(
    struct opengpu_compute_texture_table_entry *out,
    struct opengpu_compute_texture_level_config *out_levels, u32 level_capacity,
    const struct opengpu_compute_texture_image_config *image_in,
    const struct opengpu_compute_texture_sampler_config *sampler_in,
    const struct opengpu_compute_texture_level_config *levels_in, u32 level_count,
    u32 binding_base, unsigned long long binding_size)
{
    struct opengpu_compute_texture_image_config image;
    struct opengpu_compute_texture_sampler_config sampler;
    unsigned long long limit = (unsigned long long)binding_base + binding_size;
    u32 scratch[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS], seen = 0;

    if (!out || !image_in || !sampler_in || !binding_size ||
        binding_size > (1ULL << 32) || limit > (1ULL << 32) ||
        level_count > OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS || level_count > level_capacity ||
        (level_count && (!levels_in || !out_levels)))
        return false;
    image = *image_in;
    image.context=0; image.pass=0; image.generation=0; image.handle=0;
    image.allocation_base=binding_base; image.allocation_limit=limit;
    image.levels_base=0; image.levels_limit=OPENGPU_COMPUTE_TEXTURE_LEVEL_TABLE_BYTES;
    image.first_level_handle=0;
    sampler = *sampler_in;
    sampler.context=0; sampler.pass=0; sampler.generation=0; sampler.handle=0;
    if (!opengpu_encode_compute_texture_image(scratch, &image) ||
        !opengpu_encode_compute_texture_sampler(scratch, &sampler))
        return false;
    for (u32 i = 0; i < level_count; ++i) {
        struct opengpu_compute_texture_level_config level = levels_in[i];
        unsigned long long address = (unsigned long long)binding_base + level.base;
        if (level.level >= OPENGPU_COMPUTE_TEXTURE_LEVEL_SLOTS ||
            (seen & (1u << level.level)) || level.base >= binding_size ||
            address >= (1ULL << 32)) return false;
        seen |= 1u << level.level;
        level.context=0; level.pass=0; level.generation=0; level.handle=level.level;
        level.allocation_base=binding_base; level.allocation_limit=limit; level.base=(u32)address;
        if (!opengpu_validate_compute_texture_levels(&image, &level, 1, level.level, false))
            return false;
    }
    for (u32 i = 0; i < level_count; ++i) {
        struct opengpu_compute_texture_level_config level = levels_in[i];
        level.context=0; level.pass=0; level.generation=0; level.handle=level.level;
        level.allocation_base=binding_base; level.allocation_limit=limit;
        level.base=(u32)((unsigned long long)binding_base + level.base);
        out_levels[i] = level;
    }
    *out = (struct opengpu_compute_texture_table_entry) {
        .present=true, .image=image, .sampler=sampler,
        .levels=out_levels, .level_count=level_count,
    };
    return true;
}
#endif
